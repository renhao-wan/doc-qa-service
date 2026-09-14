#!/usr/bin/env bash
#
# 服务器侧的部署脚本。由 GitHub Actions 通过 SSH 调用，也可以人工执行。
#
# 幂等：重复执行同一 IMAGE_TAG 不会产生副作用。
#
# ⚠️ 本脚本绝不删除数据卷。doc-qa-pg-data（对话记录、用户）与 doc-qa-app-data
#    （已上传的知识库文件）在容器重建后必须原样保留 ——
#    这就是全程只用 `up -d`、从不出现 `down -v` 的原因。
set -euo pipefail
cd "$(dirname "$0")"

echo "==> 部署开始：$(date '+%F %T')"

# ── 前置校验：缺什么就明确报错，不要带着半截配置往下跑 ──
[[ -f .env ]]             || { echo "❌ 缺 .env"; exit 1; }
[[ -f certs/origin.crt ]] || { echo "❌ 缺 certs/origin.crt（Cloudflare Origin Certificate）"; exit 1; }
[[ -f certs/origin.key ]] || { echo "❌ 缺 certs/origin.key"; exit 1; }

# ⚠️ 密钥的非空校验放在这里，而不是指望 application-prod.yml 里 ${JWT_SECRET} 无默认值来兜底：
#    docker-compose.yml 为了让本地开箱即用，给 JWT_SECRET 提供了开发默认值
#    （不能写成 ${JWT_SECRET:-}，那会传入空串，jjwt 抛 WeakKeyException 启动即失败），
#    于是「生产必须有真实密钥」这条保证只能由本脚本承担。
#    忘了配的话在这里就失败，而不是静默用公开默认密钥把服务跑起来。
for k in IMAGE_TAG DASHSCOPE_API_KEY JWT_SECRET SEARXNG_SECRET; do
  grep -q "^${k}=..*" .env || { echo "❌ .env 里 ${k} 未设置或为空"; exit 1; }
done

# ⚠️ 不要写 source .env：那会把 .env 当脚本执行，密钥里若含 $ 或空格会被二次展开、
#    可能静默改值。compose 自己会读 .env 做变量替换，这里只取出 IMAGE_TAG 用于日志
IMAGE_TAG=$(grep '^IMAGE_TAG=' .env | cut -d= -f2-)
echo "==> 目标镜像 tag：${IMAGE_TAG}"

# ── 校验镜像已就位 ──
# ⚠️ 这里**不做 `docker compose pull`**。服务器直连 ghcr.io 实测只有 ~150 KB/s，
#    400MB 的 app 镜像要 45 分钟以上且中途会 connection reset —— 镜像改由
#    deploy.yml 在 GitHub runner 上拉好、docker save 后直传进来（实测 5~6 MB/s）。
#    所以此刻本地必须有镜像；没有就明确报错，而不是让 compose 去 registry 上干等
for img in \
  "ghcr.io/renhao-wan/doc-qa-service-api:${IMAGE_TAG}" \
  "ghcr.io/renhao-wan/doc-qa-service-web:${IMAGE_TAG}"; do
  docker image inspect "$img" >/dev/null 2>&1 \
    || { echo "❌ 本地缺镜像 ${img}（应由 deploy.yml 传入，或人工 docker load）"; exit 1; }
done

# ── 重建变化的容器 ──
# ⚠️ 绝不加 -v：那会连数据卷一起删
# --remove-orphans：compose 文件里已删除的服务，其容器一并回收，不留僵尸
docker compose up -d --remove-orphans

# ── 重启 searxng ──
# ⚠️ searxng/settings.yml 是 bind mount 的源文件，改它不会让 compose 认为容器需要重建，
#    up -d 对它完全无效；而 SearXNG 只在启动时读一次配置，不热加载。
#    不重启的话，「改了 formats/engines → 部署成功 → 配置其实没生效」会静默发生。
#    这里无条件重启：它无状态、重启约 1~2 秒，比引入文件时间戳比对更简单，也不会漏判
docker compose restart searxng

# ── 清理本项目旧镜像 ──
# ⚠️ 不能直接 `docker image prune -a`：这台机器上还有 orientation 项目，那样会把它的
#    镜像一起删掉。按构建时打的 OCI label 精确过滤，且只清 72 小时内没被用过的 ——
#    既清得掉堆积，又留出「想回滚到上上个版本」的窗口
echo "==> 清理 72 小时前的旧镜像（按 label 过滤，不影响同机其他项目）"
docker image prune -af \
  --filter "label=org.opencontainers.image.source=https://github.com/renhao-wan/doc-qa-service" \
  --filter "until=72h"

# ── 健康检查 ──
# ⚠️ 不做这一步的话，「容器起来了但其实是坏的」会显示成部署成功
echo "==> 等待 web 就绪"
for i in $(seq 1 30); do
  if curl -fsS -k https://localhost/ -o /dev/null 2>/dev/null; then
    echo "==> ✅ web 健康检查通过"
    break
  fi
  if [[ $i == 30 ]]; then
    echo "❌ web 在 150 秒内未就绪，部署失败"
    docker compose ps
    docker compose logs --tail=50 web app
    exit 1
  fi
  sleep 5
done

# ── 校验 searxng 真的能出 json ──
# ⚠️ 这一条防的是「容器起来了但配置没生效」：settings.yml 缺失时 SearXNG 会自生成一份
#    默认配置（search.formats 不含 json），此时容器照常 up、端口照常通、日志也没有异常，
#    只有真的请求一次 format=json 才会暴露 403。2026-09-14 线上就是这样静默坏掉的：
#    部署全程显示成功，而联网搜索一直返回 403
#
# ⚠️ 必须重试，理由与上面的 web 健康检查相同：本脚本几步之前刚 `restart searxng`，
#    而 SearXNG 从进程启动到监听端口有一段空窗。只请求一次就会撞上它 ——
#    2026-09-14 线上正是如此：部署被判失败，而日志显示成「searxng 配置没就位」，
#    实际只是问得太早（校验时刻与容器 Started 相差 0.27 秒，容器随后自己打出 Listening at）。
echo "==> 校验 searxng 的 json 输出"
searxng_code=""
for i in $(seq 1 30); do
  # ⚠️ 失败的兜底必须写在命令替换**外面**。写成 `$(curl ... || echo 000)` 的话，
  #    curl 经 -w 输出的那个 000 与 echo 的 000 会一起被捕获，得到 "000000" ——
  #    旧版正是如此，排障时反而看不出发生了什么。
  #    写在外面还有个必要：本脚本开了 `set -e`，命令替换返回非零会让脚本就此退出。
  searxng_code=$(curl -s -o /dev/null -w '%{http_code}' -m 20 \
    "http://127.0.0.1:8889/search?q=ping&format=json" 2>/dev/null) || searxng_code="000"

  # 两种失败要分开对待，处置完全不同：
  #   000 → 连不上，通常只是还没起来，继续等
  #   非 000 → 端口通了、SearXNG 已明确表态，那是配置问题，再等也不会变
  # ⚠️ 这里用 if 而不是 `[[ ... ]] && break`：后者的条件为假时整条语句返回非零，
  #    在 `set -e` 下会直接终止脚本。
  if [[ "$searxng_code" == "200" ]]; then
    echo "==> ✅ searxng format=json 正常"
    break
  fi
  if [[ "$searxng_code" != "000" ]]; then
    break
  fi
  sleep 2
done

if [[ "$searxng_code" != "200" ]]; then
  echo "❌ searxng format=json 返回 ${searxng_code}（期望 200）"
  if [[ "$searxng_code" == "000" ]]; then
    echo "   等待 60 秒仍连不上 127.0.0.1:8889 —— 容器没起来，或没在监听该端口"
  else
    echo "   端口通了但拒绝 format=json —— 多半是 searxng/settings.yml 没就位"
    echo "   检查 search.formats 是否含 json"
  fi
  docker compose logs --tail=30 searxng
  exit 1
fi

echo "==> 当前容器状态："
docker compose ps
echo "==> ✅ 部署完成：${IMAGE_TAG}（$(date '+%F %T')）"
