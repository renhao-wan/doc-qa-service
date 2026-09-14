# 部署与运维

本文覆盖三件事：**本地怎么用容器跑**、**怎么部署到服务器**、**部署流程里那些刻意的防呆设计**。

第三部分是重点——这个部署流程的复杂度几乎全部来自「怎样让错误无法静默发生」。

---

## 1. 编排总览

根目录的 `docker-compose.yml` 编排四个服务：

| 服务 | 镜像 | 作用 | 内存上限 |
|---|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | 数据库 + pgvector 扩展 | 384m |
| `searxng` | `searxng/searxng:2026.9.11-...` | 联网搜索 | 384m |
| `app` | 本仓库构建 / GHCR 拉取 | 后端 | 512m |
| `web` | 本仓库构建 / GHCR 拉取 | nginx 托管前端 + 反代 `/api` | 64m |

```bash
docker compose up -d --build     # 起全套
docker compose up -d postgres searxng   # 只起依赖，应用在 IDE 里跑
```

⚠️ **不加服务名会把 app / web 一起拉起**。只想跑依赖时务必带上服务名。

**访问地址**：`http://localhost:8081`（Windows 宿主机 80 常被 IIS 占用，故默认用 8081）。后端接口经 nginx 反代，无需单独访问。

**数据卷**：`doc-qa-pg-data`（对话、用户）、`doc-qa-app-data`（已上传的知识库文件）。

⚠️ `pgvector/pgvector:pg16` **不能换成** `postgres:16-alpine`——官方镜像**不含** pgvector 扩展。

---

## 2. 容器里没有第二份 profile 配置

`spring.profiles.active` 仍是 `dev`，**容器里跑的就是 dev profile**，没有 `application-docker.yml`。

容器与宿主机的差异只有两处 host，用环境变量覆盖（Spring Boot 的 relaxed binding）：

| 配置 | 宿主机 | 容器内 |
|---|---|---|
| 数据源 | `localhost:5432` | `SPRING_DATASOURCE_URL=jdbc:p6spy:postgresql://postgres:5432/robot` |
| SearXNG | `localhost:8889` | `SEARXNG_URL=http://searxng:8080/search` |

⚠️ **不要改成单开一份 `application-docker.yml`**：数据源、searxng、存储路径会拆成两份，改一处漏一处是必然的。差异集中写在 compose 的 `app.environment` 段，读的人一眼看全「容器跟本机差在哪」。

**这与「dev / prod 要分成两份配置」不矛盾**——那条针对的是**安全边界**（见 §3.2），不是 host。分工是：

> 配置文件的 profile 管「生产与开发的**语义差异**」，compose 的环境变量管「容器与宿主机的**地址差异**」。

---

## 3. 密钥管理

### 3.1 变量从哪来

全部走环境变量，模板见 `.env.example`（`.env` 已在 `.gitignore` 中）：

```bash
cp .env.example .env    # 然后填入真实值
```

| 变量 | 用途 | 本地默认 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 模型调用（对话与向量共用这一把） | **无默认，必填** |
| `JWT_SECRET` | JWT 签名 | 有开发默认值（公开，仅供本地） |
| `SEARXNG_SECRET` | SearXNG 会话签名 | 有开发默认值 |
| `IMAGE_TAG` | 要部署的镜像 tag（git sha） | 空 → `:local`，走本地构建 |
| `SPRING_PROFILES_ACTIVE` | 激活的 profile | 空 → `dev` |
| `WEB_HTTP_PORT` / `WEB_HTTPS_PORT` | web 映射到宿主机的端口 | 8081 / 8443 |
| `SEARXNG_ENGINES` | 覆盖搜索引擎列表 | 通常不设 |

⚠️ `DASHSCOPE_API_KEY` 在 compose 里写的是 `${DASHSCOPE_API_KEY:?...}`——**没设置就直接报错退出**，不会静默用 yml 里的 `xxx` 占位符启动出一个每次问答都失败的容器。

⚠️ `SEARXNG_ENGINES` **不要设成空值**：空字符串会被当成有效值，把 yml 里的默认集合整个覆盖掉。要用它就得给**完整**列表。

### 3.2 生产必须是 prod profile —— 这是安全事故级的要求

⚠️ 容器默认跑 dev profile，直接部署到公网会踩两个坑：

| 坑 | 后果 |
|---|---|
| dev 的 `spring.flyway.locations` 含 `classpath:db/dev-migration` | **把两个密码为 `demo123` 的演示账号插进生产库**——等于公开发布登录凭证 |
| dev 的 `auth.jwt.secret` 默认值是公开的 | 任何人都能伪造任意用户的 token |

所以服务器上的 `.env` 里**必须**写 `SPRING_PROFILES_ACTIVE=prod`。prod profile 下 `locations` 不含演示账号目录，且 `auth.jwt.secret: ${JWT_SECRET}` **没有缺省值**——缺失即启动失败。

### 3.3 `JWT_SECRET` 为什么刻意不在 compose 里传真实值

compose 里写的是：

```yaml
JWT_SECRET: ${JWT_SECRET:-dev-only-insecure-secret-override-in-prod}
```

⚠️ **不能写成 `${JWT_SECRET:-}`**。那样本地不设时会往容器里传一个**空字符串**，而 Spring 的 `${JWT_SECRET:默认值}` 只在变量**未定义**时才回退到默认值——空字符串会被当成有效密钥，jjwt 抛 `WeakKeyException`（0 bits），启动即失败。

所以这里给出与 `application-dev.yml` **完全相同**的开发默认值，保证本地开箱即用；而「生产必须有真实密钥」这条保证，由 `deploy.sh` 校验 `.env` 里 `JWT_SECRET` 非空来兜底（见 §5.3）。

---

## 4. 服务器侧：端口收敛与资源限制

### 4.1 端口全部绑回环

除 web 外，所有端口映射都绑定 `127.0.0.1`：

| 端口 | 不绑回环的后果 |
|---|---|
| 5432 (postgres) | **数据库直接暴露到公网**，而密码是 `postgres/postgres` |
| 8889 (searxng) | 可被当成开放代理滥用 |
| 8080 (app) | 绕过 nginx 直连后端 |

本地开发不受影响（IDE 连 `localhost:5432` 照常），但公网访问不到。

web 是唯一对外暴露的，且端口用变量而非写死：

```yaml
- "${WEB_HTTP_PORT:-8081}:80"
- "${WEB_HTTPS_PORT:-8443}:443"
```

本地 8081、服务器 80——**写死 80 会导致 Windows 上起不来**（IIS 占用）。

### 4.2 内存限制（1.6G 是硬约束）

服务器物理内存只有 1.6G，且同机还跑着另一个项目。所以四个容器都设了 `mem_limit`，app 另加 `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70`——不限制的话 JVM 按**物理内存**取堆，会跟其他容器抢。

合计上限约 1.34G，加上系统与同机另一个项目**会顶到 2G**，超出物理内存的部分靠 swap 兜底。这是本部署方案最大的不确定性，**部署后必须实测 `docker stats`**。

顶不住时**第一个该砍的是 searxng**——它会同时让前端的「联网搜索」开关失去意义。

### 4.3 `app` 要等 postgres 健康

```yaml
depends_on:
  postgres:
    condition: service_healthy
```

不等的话，应用会在数据库就绪前尝试建连接池并启动失败。postgres 服务本身配了 `pg_isready` 的 healthcheck。

---

## 5. CI / CD

### 5.1 CI：推送即验

触发：push 到 main、pull request、手动。

三个 job：

| job | 内容 |
|---|---|
| `backend` | 起 pgvector service container → `mvn -B test` |
| `frontend` | `npm ci` → `npm run build` |
| `images` | 构建两个镜像推 GHCR（**只在 main 上跑**，PR 只测不推） |

⚠️ **backend 必须起 PostgreSQL service container**。项目里的测试类是 `@SpringBootTest`，会连 `localhost:5432`；runner 上没数据库就是一片红。

**一个顺带的好处**：CI 里的库是空库，**Flyway 会在测试启动时从零建表**——每次 push 都等于把三份迁移脚本在干净环境里验一遍。这比在本地那个已经 baseline 过的开发库上跑更有价值。

**不需要配 `DASHSCOPE_API_KEY`**：`application-dev.yml` 里是 `${DASHSCOPE_API_KEY:xxx}`，缺省值够启动；真正调模型的 RAG 评估测试受 `RAG_EVAL` 环境变量门控（`@EnabledIfEnvironmentVariable`），默认跳过。

### 5.2 CD：手动触发 + 可回滚

⚠️ **仅 `workflow_dispatch`**，刻意不挂 push——避免写到一半的提交直接上生产。

流程：

```
checkout → 解析镜像 tag → 配置 SSH → scp 编排文件与部署脚本
  → 通过 stdin 写 .env → 拉取镜像并直传到服务器 → ssh 执行 deploy.sh
```

**镜像怎么到的服务器**：runner 先从 GHCR 拉好两个镜像，再 `docker save | ssh "docker load"` 直传。

⚠️ **不让服务器自己 `docker compose pull` 是实测决定的**：服务器直连 `ghcr.io` 只有约 **150 KB/s**——400MB 的 app 镜像要 45 分钟以上，而且中途会稳定 `connection reset`。而 runner 到服务器实测有 **5~6 MB/s**，runner ↔ GHCR 又是机房间速度。让镜像换一趟车，服务器从此完全不碰 registry。

⚠️ **不要给这一步加 gzip**：镜像层本身就是压缩过的 tar，二次压缩几乎压不动，白白吃一遍 CPU。实测 500MB 大约 1~2 分钟，没必要优化。

**镜像 tag 用 git sha，不用 `latest`**：

```yaml
tags: |
  ghcr.io/<owner>/<repo>-api:${{ github.sha }}
  ghcr.io/<owner>/<repo>-api:latest
```

`sha` 是不可变的，部署路径只认它；`latest` 只用来标记「最新是哪个」。

**回滚方式：填一个旧的 sha 重跑一次。** 这是成本最低的回滚——找到上一个成功的 sha，填进 workflow 的输入框即可，不需要额外的回滚机制。

⚠️ **scp 编排文件与部署脚本是必须的**：它们与被部署的代码必须同一个版本。

### 5.3 `scripts/deploy.sh` 的四条防呆

脚本在服务器上执行，幂等。

**① 前置校验，缺什么就明确报错**

```bash
[[ -f .env ]]             || { echo "❌ 缺 .env"; exit 1; }
[[ -f certs/origin.crt ]] || { echo "❌ 缺 certs/origin.crt"; exit 1; }
[[ -f certs/origin.key ]] || { echo "❌ 缺 certs/origin.key"; exit 1; }

for k in IMAGE_TAG DASHSCOPE_API_KEY JWT_SECRET SEARXNG_SECRET; do
  grep -q "^${k}=..*" .env || { echo "❌ .env 里 ${k} 未设置或为空"; exit 1; }
done

# 取出目标 tag（用 grep 提取，不要 source .env，原因见本节末尾）
IMAGE_TAG=$(grep '^IMAGE_TAG=' .env | cut -d= -f2-)

# 镜像必须已在本地（由 CD 直传进来，见 §5.2）
for img in "ghcr.io/<owner>/<repo>-api:${IMAGE_TAG}" \
           "ghcr.io/<owner>/<repo>-web:${IMAGE_TAG}"; do
  docker image inspect "$img" >/dev/null 2>&1 \
    || { echo "❌ 本地缺镜像 ${img}（应由 CD 传入，或人工 docker load）"; exit 1; }
done
```

⚠️ 最后那段 `docker image inspect` 校验是**替代 `docker compose pull`** 的：既然镜像不再由服务器拉取（见 §5.2），此刻本地就必须有；没有就明确报错，而不是让 compose 去 registry 上干等。

密钥的非空校验放在脚本里而不是配置文件里，原因见 §3.3。

**② 绝不删数据卷**

```bash
# ⚠️ 绝不加 -v：那会连数据卷一起删
docker compose up -d --remove-orphans
```

`doc-qa-pg-data`（对话记录、用户）与 `doc-qa-app-data`（已上传的知识库文件）在容器重建后必须原样保留。**这就是全程只用 `up -d`、从不出现 `down -v` 的原因。**

`--remove-orphans` 回收 compose 文件里已删除的服务，不留僵尸容器。

**③ 按 OCI label 清理镜像，不按名字**

```bash
docker image prune -af \
  --filter "label=org.opencontainers.image.source=https://github.com/<owner>/<repo>" \
  --filter "until=72h"
```

⚠️ **不能直接 `docker image prune -a`**：这台机器上还有另一个项目，那样会把它的镜像一起删掉。按构建时打的 label 精确过滤，且只清 72 小时内没被用过的——既清得掉堆积，又留出「想回滚到上上个版本」的窗口。

这就是 CI 里那行 `labels:` 存在的意义——它是清理机制的**钥匙**，去掉它就只能按镜像名猜。

**④ 健康检查**

```bash
for i in $(seq 1 30); do
  if curl -fsS -k https://localhost/ -o /dev/null 2>/dev/null; then
    echo "==> ✅ web 健康检查通过"; break
  fi
  [[ $i == 30 ]] && { echo "❌ web 在 150 秒内未就绪，部署失败"; docker compose logs --tail=50 web app; exit 1; }
  sleep 5
done
```

不做这一步的话，「容器起来了但其实是坏的」会显示成部署成功。脚本退出码非 0 会让 CD job 标红。

⚠️ 脚本里**不要写 `source .env`**：那会把 `.env` 当脚本执行，密钥里若含 `$` 或空格会被二次展开、可能静默改值。compose 自己会读 `.env` 做变量替换；脚本只需要取出 `IMAGE_TAG` 用于日志与镜像校验——用 `grep | cut` 提取即可。

### 5.4 写 `.env` 的两个细节

CD 通过 stdin 写 `.env` 而不是命令行参数——**命令行参数会出现在服务器的进程列表里**（`ps` 可见）。

```bash
ssh ... "cat > /opt/doc-qa-service/.env && chmod 600 /opt/doc-qa-service/.env" <<'EOF'
IMAGE_TAG=${{ steps.tag.outputs.value }}
SPRING_PROFILES_ACTIVE=prod
...
EOF
```

⚠️ **结束符必须写成带引号的 `<<'EOF'`**。GitHub 的 `${{ }}` 替换发生在 **shell 解析之前**，引号只关掉 shell 自身的参数展开、不影响 secrets 的替换。不写引号的话，密钥里若含 `$` 或反引号会被本地 shell 二次展开，写进 `.env` 的就不是原值了——**而 `JWT_SECRET` 出错不会让启动失败，只会让所有已签发的 token 静默失效**。

---

## 6. nginx：三处只有真跑起来才会暴露的配置

`doc-qa-web/nginx.conf` 在 443 上托管静态文件并把 `/api/` 反代到后端，反代规则等价于 `vite.config.js` 里 dev 环境的 proxy（同样去掉 `/api` 前缀），所以前端代码里的相对路径在两种环境下都能用。

⚠️ `proxy_pass http://app:8080/;` **末尾的斜杠不能去掉**——它会替换掉 `/api/` 前缀，去掉后变成原样转发，后端全部 404。

三处非平凡配置：

| 配置 | 不配的后果 |
|---|---|
| `client_max_body_size 5m` | nginx 默认 1MB，而前端分片是 2MB。分片被 nginx 直接回 413，**请求根本到不了后端**——后端日志里什么都没有。现象是「`checkFile` 说需上传，一点上传就报错」，排查方向会被完全带偏 |
| `proxy_buffering off` + `proxy_set_header Connection ""` | nginx 默认会把上游响应攒够一批再发，**流式效果完全消失**，表现为「前端一直转圈、最后一次性吐出全部内容」。功能看着正常，极易误判成前端问题。`Connection` 置空是因为 SSE 是长连接，不能被改写成普通 keep-alive 复用 |
| `proxy_read_timeout 3600s` | 默认 60s 会在长回答中途把连接掐断 |

另外 `chunked_transfer_encoding on` 与上述 SSE 配置配套。

⚠️ 这里与后端 `max-request-size（5MB）` 对齐。**前端分片大小 / 这个值 / 后端 `max-request-size` 三个数字必须同步维护**——详见 `modules/knowledge-base.md` §3.5。

**证书**：443 用挂载进来的 `./certs`（服务器上是 Cloudflare Origin Certificate，本地是自签证书）。⚠️ 证书文件不存在时 **nginx 会启动失败**（`cannot load certificate`），这是刻意的——静默退回内置默认证书会让「CF 回源被拒」极难排查。

---

## 7. 一次性初始化

只有一次，之后全部由 CD 自动完成。

| # | 事项 |
|---|---|
| 1 | 云服务器安全组放行 80、443 入方向 |
| 2 | Cloudflare 后台生成 Origin Certificate，放到 `/opt/doc-qa-service/certs/` |
| 3 | 生成 SSH 密钥对，公钥写入服务器 `~/.ssh/authorized_keys` |
| 4 | 建目录 `/opt/doc-qa-service/certs`（`searxng/` 由 CD 首次部署时自动创建并同步） |
| 5 | 配 GitHub Secrets（见下） |
| 6 | 把 GHCR 的两个 package 设为 public |

### 7.1 为什么只有 `certs/` 要手动准备

`docker-compose.yml` 里有两个 bind mount 的源目录，但归宿不同：

| 挂载 | 谁来准备 | 依据 |
|---|---|---|
| `./certs` → `/etc/nginx/certs` | **手动** | 含私钥，绝不能进仓库 |
| `./searxng` → `/etc/searxng` | **CD 自动 scp** | 不含密钥（`secret_key` 由 `SEARXNG_SECRET` 环境变量提供），可随仓库同步 |

⚠️ 漏掉 `certs/` 会**当场暴露**：nginx 启动失败（`cannot load certificate`），
`deploy.sh` 的前置校验直接 `exit 1`。

⚠️ `searxng/settings.yml` 在 2026-09-14 之前不在 `scp` 清单里，后果是**静默降级**：
容器自己生成一份默认配置（`search.formats` 不含 `json`），`format=json` 返回 **403**，
联网搜索整条链路失效，而部署脚本仍然报「✅ 部署完成」。现已由 CD 同步，
并由 `deploy.sh` 末尾的 json 校验兜底——**再漏就变成部署失败，而不是静默坏掉**。

**GitHub Secrets**：`SERVER_HOST`、`SERVER_USER`、`SSH_PRIVATE_KEY`、`DASHSCOPE_API_KEY`、`JWT_SECRET`、`SEARXNG_SECRET`。

⚠️ **仓库里绝不出现**服务器地址、任何密码、任何密钥、证书文件。`.env` / `certs/` 已在 `.gitignore` 中。

⚠️ 服务器地址写进公开仓库等于公开源站 IP（绕过 CDN 的攻击面）。

⚠️ `JWT_SECRET` 生产环境**建议不少于 64 字节**（jjwt 会按键长自动选算法，≥64 走 HS512）。

---

## 8. 部署后怎么验证

1. **无残留**：改一行前端文案 → push → 部署 → 浏览器强刷能看到新文案。这是「旧代码没被缓存住」的直接证据。
2. **数据保留**：部署前后 `SELECT count(*) FROM t_chat` 数字不变；已上传的知识库文件仍能检索到。
3. **不影响同机其他项目**：另一个项目的容器正常、镜像未被清理。
4. **端口收敛**：服务器上 `ss -lntp` 看不到 5432 / 8080 / 8889 监听在 `0.0.0.0`。
5. **内存实测**：`docker stats --no-stream` 记录四个容器的实际占用。
6. **端到端真实可用**：能登录、能提问、能上传知识库文件——**不只是端口通、接口返 200**。
7. **联网搜索真的搜得到东西**：在服务器上执行

   ```bash
   curl "http://127.0.0.1:8889/search?q=spring+boot&format=json&engines=360search,naver,presearch,mwmbl"
   ```

   看 `results` 是否**非空**（2026-09-14 实测 53 条）。⚠️ 只看 HTTP 状态码不够：
   `search.formats` 没开 `json` 时返回 **403**，引擎全部超时时返回 **200 但 `results` 为空**——
   两种都不是「能用」，而后者尤其容易被误判成「搜索就是这个样子」。
