# GitHub Actions CI/CD · 设计文档

> 2026-09-13 · 对应 [TODO.md](../../TODO.md) 阶段四之后的新增项「自动化部署」

## 1. 背景与目标

项目此前**没有任何 CI/CD**：仓库里没有 `.github/`，构建与部署全靠本地手工 `docker compose up -d --build`。这带来三个问题：

1. 没有任何自动化门禁——测试要人记得跑，忘了就是没有
2. 部署靠手工，改完代码到线上生效之间没有可复现的路径
3. 手工部署时「用的是不是新代码」无法证明；Docker 的镜像缓存与 `latest` tag 的漂移会让**旧代码被当成新的部署上去**，而且从容器状态上看不出来

本次改造要达到：

1. push 到 main 自动跑测试与构建，不过就红
2. 每次成功的构建产出**不可变**的镜像（tag = git sha）
3. 部署是一个可复现的动作：手动触发，拉到指定 sha 的镜像并重建容器
4. 「部署后跑的一定是新代码」这件事由机制保证，不靠人确认
5. **不丢数据**——对话记录、知识库文件、数据库在部署后依然在
6. 不干扰同机上已有的其他项目

## 2. 范围

**做**：CI workflow（测试 + 构建 + 推镜像）、CD workflow（手动触发 + SSH 部署）、服务器侧部署脚本、生产 profile 配置、compose 端口收敛与资源限制、nginx 的 HTTPS。

**不做**（YAGNI，理由见 §11）：自动化回滚、蓝绿/金丝雀发布、多环境（staging）、服务器上自建镜像仓库、监控告警、CD 自动触发（用户明确选择手动）。

## 3. 部署目标环境（已实测）

> ⚠️ 服务器地址与凭据**不进版本库**，一律走 GitHub Secrets。本文档中出现的地址以 `<SERVER_HOST>` 指代。

| 项 | 实测值 | 影响 |
|---|---|---|
| 系统 | CentOS Stream 10 | 用 systemd，无 ufw/firewalld |
| Docker | 29.7.2 / Compose v5.5.0 | **无需安装**，直接用 |
| CPU / 内存 | 2 核 / 1.6G（可用约 1.0G）+ 2G swap | ⚠️ 最大约束，见 §8.4 |
| 磁盘 | 40G，剩 33G | 充足 |
| 已占用端口 | 3001（另一个项目 `orientation`，内存仅 51M） | 不冲突 |
| 80 / 443 | 空闲，**安全组已放行**（实测从超时变为「连接被拒绝」） | 可用 |
| 防火墙 | 无 ufw、无 firewalld | 端口完全由阿里云安全组控制 |
| 宿主机 nginx | **未安装** | 直接用 web 容器监听 80/443，不需要在服务器上再套一层 |
| 域名 | `docqa.wanrenhao.me` → Cloudflare（104.21.x / 172.67.x） | HTTPS 由 CF 终结 |
| CF SSL 模式 | Full / Full (strict)（用户确认） | ⚠️ 回源走 HTTPS，源站**必须**提供证书 |

## 4. 整体架构与数据流

```
开发者 push 到 main
   │
   ├─▶ CI (.github/workflows/ci.yml) —— 自动
   │     ├─ backend  job: postgres service container + mvn test
   │     ├─ frontend job: npm ci + npm run build
   │     └─ images   job: 构建两个镜像 → 推 GHCR
   │                      tag = <git-sha> 与 latest
   │
   └─▶ 失败则停在这里，不产出镜像

维护者在 GitHub 网页 Actions → Deploy → Run workflow —— 手动
   │
   └─▶ CD (.github/workflows/deploy.yml)
         1. checkout（拿到 docker-compose.yml 与部署脚本）
         2. 解析镜像 tag（默认当前 sha，可填旧 sha 做回滚）
         3. 配置 SSH 私钥
         4. scp 编排文件与部署脚本到 /opt/doc-qa-service/
         5. 通过 stdin 写 .env（密钥不进进程列表）
         6. ssh 执行 deploy.sh
```

服务器上的部署目录：

```
/opt/doc-qa-service/
├── docker-compose.yml        ← 由 CD 同步（与仓库里的一致）
├── deploy.sh                 ← 由 CD 同步
├── .env                      ← 由 CD 写入，chmod 600，不进版本库
└── certs/
    ├── origin.crt            ← Cloudflare Origin Certificate，人工放入
    └── origin.key
```

## 5. CI 流水线（`.github/workflows/ci.yml`）

触发：push 到 main、pull request、workflow_dispatch。

### 5.1 backend job

⚠️ **必须起 PostgreSQL service container**。项目里 11 个测试类是 `@SpringBootTest`，会连 `localhost:5432`；GitHub Actions runner 上没有数据库，不起就是一片红。

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16        # 官方 postgres 镜像不含 pgvector
    env:
      POSTGRES_DB: robot
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports: ['5432:5432']
    options: >-
      --health-cmd "pg_isready -U postgres"
      --health-interval 10s --health-timeout 5s --health-retries 5
```

步骤：`actions/checkout@v4` → `actions/setup-java@v4`（temurin 17 + `cache: maven`）→ `mvn -B test`（`working-directory: doc-qa-api`）。

**顺带的好处**：CI 里的库是空库，**Flyway 会在测试启动时从零建表**——每次 push 都等于把三份迁移脚本在干净环境里验一遍。这比在本地那个已经 baseline 过的开发库上跑要有价值。

不需要配 `DASHSCOPE_API_KEY`：`application-dev.yml` 里是 `${DASHSCOPE_API_KEY:xxx}`，缺省值够启动；真正调模型的 2 个 rag 评估测试已被 `@Disabled` 跳过。

### 5.2 frontend job

`actions/setup-node@v4`（node 22 + `cache: npm` + `cache-dependency-path: doc-qa-web/package-lock.json`）→ `npm ci` → `npm run build`。

前端无测试（`package.json` 只有 dev / build / preview），这一步只验证「能构建」。

### 5.3 images job

`needs: [backend, frontend]`，且 `if: github.ref == 'refs/heads/main' && github.event_name == 'push'`——PR 上只跑测试，不推镜像。

`permissions: { contents: read, packages: write }`，用内置 `GITHUB_TOKEN` 登录 `ghcr.io`。

两个 `docker/build-push-action@v6`，分别构建 `./doc-qa-api` 与 `./doc-qa-web`：

```yaml
tags: |
  ghcr.io/renhao-wan/doc-qa-service-api:${{ github.sha }}
  ghcr.io/renhao-wan/doc-qa-service-api:latest
labels: org.opencontainers.image.source=https://github.com/renhao-wan/doc-qa-service
cache-from: type=gha,scope=api
cache-to: type=gha,mode=max,scope=api
```

那个 **label 是清理机制的钥匙**（见 §7.3）——没有它，服务器上按镜像名清理会误删同机其他项目的镜像。

**镜像可见性**：在 GitHub 上把两个 package 设为 public。仓库本来就是公开的（源码公开），镜像里没有密钥；private 的话服务器拉取还要额外配 PAT，不值得。

## 6. CD 流水线（`.github/workflows/deploy.yml`）

触发：**仅 `workflow_dispatch`**（用户明确选择「CI 自动 + 部署手动点按钮」——避免半成品 push 直接上生产）。

### 6.1 可回滚的输入

```yaml
inputs:
  image_tag:
    description: '要部署的镜像 tag（git sha）。留空则部署当前 main 的 HEAD'
    required: false
    default: ''
```

解析：`inputs.image_tag` 非空则用它，否则用 `github.sha`。

这给了**最低成本的回滚能力**：出问题时找到上一个成功的 sha，填进去重跑一次即可。不需要额外的回滚机制。

### 6.2 部署执行

```bash
# 1. scp 编排文件与脚本（它们是部署的一部分，必须与被部署的 sha 一致）
scp docker-compose.yml scripts/deploy.sh \
    "$SERVER_USER@$SERVER_HOST:/opt/doc-qa-service/"

# 2. 写 .env —— 走 stdin，密钥不出现在服务器的进程列表里
ssh "$SERVER_USER@$SERVER_HOST" "cat > /opt/doc-qa-service/.env && chmod 600 /opt/doc-qa-service/.env" <<EOF
IMAGE_TAG=${IMAGE_TAG}
DASHSCOPE_API_KEY=${DASHSCOPE_API_KEY}
JWT_SECRET=${JWT_SECRET}
SEARXNG_SECRET=${SEARXNG_SECRET}
EOF

# 3. 执行部署
ssh "$SERVER_USER@$SERVER_HOST" "cd /opt/doc-qa-service && bash deploy.sh"
```

`DASHSCOPE_API_KEY` 等来自 `secrets.*`，GitHub Actions 会自动在日志里打码。

### 6.3 `scripts/deploy.sh`（在服务器上执行，幂等）

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# ── 前置校验：缺什么就明确报错，不要带着半截配置往下跑 ──
[[ -f .env ]]                    || { echo "缺 .env"; exit 1; }
[[ -f certs/origin.crt ]]        || { echo "缺 certs/origin.crt（Cloudflare Origin Certificate）"; exit 1; }
[[ -f certs/origin.key ]]        || { echo "缺 certs/origin.key"; exit 1; }
grep -q '^IMAGE_TAG=..*' .env    || { echo ".env 里没有 IMAGE_TAG"; exit 1; }

# ⚠️ 不要写 source .env：那会把 .env 当脚本执行，密钥里若含 $ 或空格会被
#    二次展开，可能静默改掉值。compose 自己会读 .env 做变量替换，
#    脚本这里只取 IMAGE_TAG 用于日志与后面的清理过滤
IMAGE_TAG=$(grep '^IMAGE_TAG=' .env | cut -d= -f2-)
echo "==> 部署 IMAGE_TAG=${IMAGE_TAG}"

# ── 拉取镜像 ──
docker compose pull

# ── 重建变化的容器 ──
# ⚠️ 绝不加 -v：那会连数据卷一起删掉（对话记录、知识库文件全没）
# --remove-orphans：compose 文件里删掉的服务，其容器一并回收
docker compose up -d --remove-orphans

# ── 清理本项目旧镜像 ──
# ⚠️ 不能直接 docker image prune -a：会删掉同机 orientation 项目的镜像。
#    按 label 精确过滤，只清本项目产出的、且 72 小时内没被用过的
docker image prune -af \
  --filter "label=org.opencontainers.image.source=https://github.com/renhao-wan/doc-qa-service" \
  --filter "until=72h"

# ── 健康检查：容器全 running + web 能应答 ──
for i in $(seq 1 30); do
  if curl -fsS -k https://localhost/ -o /dev/null 2>/dev/null; then
    echo "==> web 健康检查通过"; break
  fi
  [[ $i == 30 ]] && { echo "==> ❌ web 未就绪，部署失败"; docker compose logs --tail=50 web app; exit 1; }
  sleep 5
done

docker compose ps
echo "==> ✅ 部署完成：${IMAGE_TAG}"
```

**失败即 job 失败**：脚本退出码非 0 会让 CD job 标红，不会出现「容器起来了但其实是坏的，却显示部署成功」。

## 7. 「无残留」的四条机制

这是本设计的核心诉求，拆成四件独立的事，每件对应一个具体的失效场景：

### 7.1 镜像 tag 用 git sha，不用 latest

**防的是**：服务器上留着旧的 `latest`，`docker compose pull` 因网络问题静默失败或拉到旧镜像，容器照常启动——**从 `docker ps` 上看不出任何异常，但跑的是旧代码**。

sha tag 是不可变的：compose 文件的 `image: ...:${IMAGE_TAG}` 里 tag 变了，镜像 ID 必然不同，`docker compose up -d` 就**必须**重建容器。没有「以为更新了其实没有」的中间状态。

`latest` 仍然推，但**只作为「最新是哪个」的标记**，部署路径不依赖它。

### 7.2 `up -d --remove-orphans`，绝不 `down -v`

- `--remove-orphans`：compose 文件里已删除的服务（比如以后砍掉 searxng），其容器会被一并回收，不留僵尸容器
- **绝不加 `-v`**：`docker compose down -v` 会删除 `doc-qa-pg-data`（对话记录、用户）与 `doc-qa-app-data`（已上传的知识库文件）。用户明确要求保留数据卷

### 7.3 按 label 清理，不按名字

**防的是**：旧镜像无限堆积占满 33G 磁盘。

⚠️ 这里有个**会伤及无辜的坑**：服务器上还有一个 `orientation` 项目。`docker image prune -a`（不带过滤）会删掉它未被使用的镜像；按镜像名 `grep doc-qa` 过滤也不够严谨。

正解是**构建时打 OCI label，清理时按 label 过滤**（§5.3 里那行 `labels:`）。只清本项目产出的镜像，同机其他项目一个不碰。

保留 72 小时是有意的：既清得掉堆积，又留出「发现坏了想回滚到上上个版本」的窗口。

### 7.4 健康检查 + 失败即红

**防的是**：部署流程「成功」了但服务其实是坏的。脚本尾部轮询 web 的 HTTPS 端点，30 次（约 2.5 分钟）内不应答就打印日志并以非 0 退出。

## 8. 生产配置改造

> 这一节不是「为了 CD 顺手做的」，其中 §8.1 与 §8.2 是**部署到公网的前提**，不做就是安全事故。

### 8.1 新增 `application-prod.yml`（必须）

现状：`application.yml` 的 `spring.profiles.active` 硬编码为 `dev`，而容器里跑的一直是 dev profile。部署到公网会踩两个坑：

| 坑 | 后果 |
|---|---|
| dev 的 `spring.flyway.locations` 含 `classpath:db/dev-migration` | **把 `demo`/`demo2` 两个密码为 `demo123` 的账号插进生产库**——等于公开发布登录凭证 |
| dev 的 `auth.jwt.secret` 默认值是公开的 `dev-only-insecure-secret-override-in-prod` | 任何人都能伪造 token |

新文件内容：数据源（容器内服务名）、`spring.flyway.locations: classpath:db/migration`（**不含** dev-migration）、`baseline-on-migrate: true`、`initialize-schema: false`、AI 与向量配置、`searxng.url`、`knowledge-base` 路径、`chat.memory.max-tokens`、`auth.jwt.secret: ${JWT_SECRET}`（**无缺省值**，缺失即启动失败）。

compose 里通过 `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}` 切换：本地默认 dev，服务器 `.env` 里写 prod。

> ⚠️ 这与 CLAUDE.md 里「不要单开 profile 配置文件」的约定**不冲突**：那条约定针对的是「为了容器环境而拆 profile」（差异只有 host，用环境变量覆盖即可）。这里是安全边界——dev 与 prod 加载的**迁移脚本集合**不同，必须真的分成两份配置。

### 8.2 端口收敛（必须）

现状 compose 把这些端口映射到 `0.0.0.0`：

| 端口 | 服务 | 暴露到公网的后果 |
|---|---|---|
| 5432 | postgres | ⚠️ **数据库直接暴露，密码是 `postgres/postgres`** |
| 8889 | searxng | 可被当成开放代理滥用 |
| 8080 | app | 绕过 nginx 直连后端 |

改法：全部改为绑定回环地址，例如 `"127.0.0.1:5432:5432"`。

- 本地开发**不受影响**：IDE 连 `localhost:5432` 照常
- 服务器上公网访问不到，只有 Docker 网络内部可达

web 服务是唯一对外暴露的：`"80:80"` 与 `"443:443"`。

### 8.3 nginx 加 HTTPS

`doc-qa-web/nginx.conf` 改为：

- 443：`ssl_certificate /etc/nginx/certs/origin.crt`（CF Origin Certificate），承载全部流量
- 80：`return 301 https://$host$request_uri`（防止 CF 模式回退到 Flexible 时出现明文入口）

compose 里 web 挂载 `./certs:/etc/nginx/certs:ro`。

**证书来源**：
- 服务器：Cloudflare 后台生成 Origin Certificate（免费，15 年有效），人工放入 `certs/`
- 本地：`certs/` 进 `.gitignore`，本地要跑 web 容器时用一条 openssl 命令生成自签证书（会写进 README，浏览器会警告但本地无妨）

### 8.4 资源限制（内存 1.6G 是硬约束）

| 服务 | 限制 | 说明 |
|---|---|---|
| app | `mem_limit: 512m` + `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70` | 不限制的话 JVM 默认按物理内存取堆，会跟其他容器抢 |
| searxng | `mem_limit: 384m` | |
| postgres | `mem_limit: 384m` | 可通过 `shared_buffers` 进一步压低 |
| web | `mem_limit: 64m` | |

合计上限约 1.34G，加上系统与 `orientation`（约 650M）**会顶到 2G**——超出物理内存的部分靠 swap 兜底。这是本设计最大的不确定性，部署后必须实测 `docker stats`；顶不住时第一个该砍的是 searxng（它会同时让前端的「联网搜索」开关置灰）。

### 8.5 SearXNG 引擎改为国内可达

⚠️ **要改的是 Java 代码，不是配置文件**：引擎列表硬编码在 [SearXNGServiceImpl.java:47](../../doc-qa-api/src/main/java/io/github/renhaowan/docqa/service/impl/SearXNGServiceImpl.java#L47) 的 `engines` 查询参数里：

```
wolframalpha,presearch,seznam,mwmbl,encyclosearch,bpb,mojeek,right dao,
wikimini,crowdview,searchmysite,bing,naver,360search
```

其中大部分（presearch / seznam / mojeek / naver / wolframalpha…）在国内网络下访问不通，会导致搜索结果为空或极慢。改为国内可达的集合（候选：`bing`、`360search`、`baidu`、`quark`）。

⚠️ 这行是**全局**的，本地开发与服务器共用——两地都是国内网络，改成国内可达的引擎对本地同样是改善。改完需要在本地实测一次搜索结果，不能只改不验。

## 9. 服务器一次性初始化

**只有一次，之后全部由 CD 自动完成。**

| # | 事项 | 谁做 |
|---|---|---|
| 1 | 阿里云安全组放行 80、443 入方向 | ✅ 已完成（实测从超时变为拒绝连接） |
| 2 | Cloudflare 后台生成 Origin Certificate，scp 到 `/opt/doc-qa-service/certs/` | 用户 |
| 3 | 生成 SSH 密钥对，公钥写入服务器 `~/.ssh/authorized_keys` | Claude 代劳 |
| 4 | 建目录 `/opt/doc-qa-service/{certs}` | Claude 代劳 |
| 5 | 配 GitHub Secrets（§10） | 用户 |
| 6 | 把 GHCR 两个 package 设为 public | 用户 |

⚠️ 现有的 root 密码登录方式**仅用于第 3 步这一次**。之后一律密钥认证，且**建议改掉那个密码**——它已经出现在本次会话记录中。

## 10. 敏感信息与密钥管理

全部走 **GitHub Secrets**，仓库里只有模板。

| Secret | 用途 | 已知泄漏风险 |
|---|---|---|
| `SERVER_HOST` | 服务器 IP | 写进公开仓库等于公开源站 IP（绕过 CF 的攻击面） |
| `SERVER_USER` | `root` | |
| `SSH_PRIVATE_KEY` | 部署用私钥 | |
| `DASHSCOPE_API_KEY` | 模型调用 | |
| `JWT_SECRET` | 生产 JWT 签名 | 至少 64 字节（jjwt 会按键长自动选算法，≥64 走 HS512） |
| `SEARXNG_SECRET` | SearXNG 会话签名 | 替换掉 compose 里那个 `doc-qa-local-dev-secret-please-change` |

**仓库里绝不出现**：服务器地址、任何密码、任何密钥、证书文件。`.env` / `certs/` 加进 `.gitignore`。

## 11. 风险与取舍

| 风险 | 说明 | 应对 |
|---|---|---|
| **内存不足** | 四容器 + 系统 + 另一个项目 ≈ 2G，物理只有 1.6G | 各容器限内存；部署后实测 `docker stats`；顶不住就砍 searxng |
| **CF 回源证书链** | Full (strict) 下 CF 会校验证书有效性 | 用 CF 官方 Origin Certificate（CF 自己签的，必然被认） |
| **首次部署要 pull 约 1.5G 镜像** | 服务器到 GHCR 的带宽未知 | 首次部署耗时可能较长；`deploy.sh` 的健康检查给它留了时间 |
| **searxng 引擎改动影响本地** | 那行是全局的 | 改完本地实测搜索 |
| **不加 `-v` 意味着迁移出错无法靠重建恢复** | 数据卷保留了，schema 也被 Flyway 记录 | 这正是要的；真出问题用 Flyway 的新迁移脚本修，不要 `down -v` |

**为什么不做自动回滚**：本次设计里「回滚 = 重跑 deploy workflow 并填上一个 sha」，一次点击的成本，已经够用。自动回滚需要在服务器上维护「上一个健康版本」的状态与判定逻辑，复杂度不匹配当前阶段。

## 12. 验收标准

1. push 一个提交到 main，CI 三个 job 全绿；GHCR 上出现 `: <该 sha>` 的镜像
2. 故意让一个测试失败 → CI 变红，**且 images job 不执行**（不产出镜像）
3. 手动触发 Deploy，部署成功后：
   - `https://docqa.wanrenhao.me` 能打开前端（证书由 CF 提供，浏览器无警告）
   - 能登录、能提问、能上传知识库文件（端到端真实可用，非仅端口通）
   - `docker ps` 里四个容器都是新镜像（tag = 部署的 sha）
   - 服务器上 `ss -lntp` 看不到 5432 / 8080 / 8889 监听在 `0.0.0.0`
4. **无残留验证**：改一行前端文案 → push → 部署 → 浏览器强刷能看到新文案（这是「旧代码没被缓存住」的直接证据）
5. **数据保留验证**：部署前后 `SELECT count(*) FROM t_chat` 数字不变；已上传的知识库文件仍能检索到
6. **不影响他人**：`orientation` 项目全程正常，其镜像未被清理
7. 内存实测：`docker stats --no-stream` 记录四个容器的实际占用，写进 TODO.md

> 验收第 3 条的「能提问、能上传」是关键——此前 Docker 化那一轮只验证到「端口通、接口 200」，**从未跑通过一次真实问答**（当时用的是占位 API Key）。这次要补上。
