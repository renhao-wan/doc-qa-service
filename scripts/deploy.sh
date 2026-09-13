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

echo "==> 当前容器状态："
docker compose ps
echo "==> ✅ 部署完成：${IMAGE_TAG}（$(date '+%F %T')）"
