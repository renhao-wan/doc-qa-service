# 本地启动

照着本文可以把整套服务在本地跑起来。**照做失败即等于本文有错**，遇到问题请反馈。

---

## 1. 前置依赖

| 依赖 | 说明 |
|---|---|
| **JDK 17** | ⚠️ 构建前 `JAVA_HOME` **必须指向它**，否则会报一堆看起来像包名问题的编译错误。原因见 `pitfalls.md` §1 |
| **Docker**（含 Compose） | 用来跑数据库与 SearXNG |
| **阿里云百炼 API Key** | 对话模型与向量模型共用这一把 |
| **Node.js**（跑前端时需要） | 版本 ≥ 20 |

---

## 2. 准备密钥

```bash
cp .env.example .env
```

编辑 `.env`，至少填入：

```
DASHSCOPE_API_KEY=<你的 API Key>
```

⚠️ **不填的话 `docker compose up` 会直接报错退出**——这是刻意的，比静默启动出一个每次问答都失败的容器好定位得多。

`JWT_SECRET` 本地可以留空，会沿用开发默认值（**那串默认值是公开的，只够本地演示用**，部署到公网前必须覆盖）。

---

## 3. 起依赖服务

```bash
docker compose up -d postgres searxng
```

⚠️ **服务名不能省**。不带服务名会把 `app` / `web` 一起拉起（那是 §6 的整套容器路径）；只想起依赖、应用在 IDE 里跑的话，必须像上面这样只写这两个。

首次启动会拉取镜像。等它们就绪：

```bash
docker compose ps          # 两个服务都应是 healthy / running
```

**建表不需要任何手工步骤**——应用启动时由 Flyway 自动执行迁移。空库会依次执行 V1（业务表）、V2（向量表）、V3（演示账号，仅 dev profile）。

---

## 4. 起后端

```bash
cd doc-qa-api
JAVA_HOME="<你的JDK17路径>" mvn spring-boot:run
```

默认端口 **8080**，profile 为 `dev`（在 `application.yml` 里指定）。

首次启动会下载 Maven 依赖。启动成功的标志是日志里出现 `Started DocQaApiApplication`，并且能看到 Flyway 打印的迁移执行记录。

> 在 IDE 里跑更方便：把 `JAVA_HOME` 配到 Run Configuration 里，环境变量加 `DASHSCOPE_API_KEY`。

---

## 5. 起前端

另开一个终端：

```bash
cd doc-qa-web
npm install
npm run dev
```

终端会打印实际的访问地址（Vite 默认 5173，端口被占用时会自动换）。

前端的 `/api` 请求经 **Vite proxy** 转发到 `http://localhost:8080`（配置在 `vite.config.js`），所以后端不需要额外配 CORS。

---

## 6. 或者：一条命令起全套容器

不想分别启动的话：

```bash
docker compose up -d --build
```

访问 **http://localhost:8081**（Windows 上宿主机的 80 端口常被 IIS 占用，所以默认用 8081）。

这套编排会把 `app` 与 `web` 也构建成容器。细节见 `deployment.md`。

---

## 7. 验证

1. **登录**：开发库预置了两个演示账号 `demo` 与 `demo2`（密码见 `db/dev-migration/V3__seed_demo_users.sql` 的注释）。用 `demo` 登录。
2. **发一次问答**：任选一个模型，问一句普通问题，应看到回复**逐段流式出现**（不是一次性刷出）。若用的是推理模型，思考过程会单独展示。
3. **对话列表**：左侧应出现刚才这轮对话，且它排在列表顶部。
4. **（可选）知识库**：上传一个小 Markdown 文件，等到状态变成「已完成」，然后提问一个只有该文件能回答的问题。

---

## 8. 常见起不来

| 现象 | 原因 | 怎么办 |
|---|---|---|
| `mvn` 报几十个「找不到符号」，且都是 `log` / `builder()` / `setXxx()` | `JAVA_HOME` 指向的 JDK 版本过高，Lombok 不工作 | 指向 JDK 17，见 `pitfalls.md` §1 |
| 服务起不来，报 `Found non-empty schema ... but no schema history table` | 数据库是非空的旧库，且 Flyway 基线未开启 | 正常情况下不会——`baseline-on-migrate` 已开启。若手工改过配置，见 `modules/data.md` §5.5 |
| 服务起不来，报 `Migration checksum mismatch` | 有人改动过**已执行**的迁移脚本 | 不要改已执行的脚本，新增一个版本号，见 `modules/data.md` §5.4 |
| 接口能通，但每次问答都失败 | `DASHSCOPE_API_KEY` 没有配好 | 检查 `.env`，以及在 IDE 里跑时的环境变量 |
| 端口起不来（8080 / 8889 / 80） | 端口被占用 | 见下 |
| 上传文件时一直报错，且 `checkFile` 说「需上传」 | 分片大小与 multipart 限制不匹配 | 见 `modules/knowledge-base.md` §3.5 |

**关于端口被占用**：⚠️ 改完代码后手工验证前，先确认**那个端口上跑的是当前构建**——旧实例占着端口而代码是新的，会表现成「改动没生效」，非常容易被误判。排查方法见 `pitfalls.md` §4。

---

## 9. 下一步

- 想搞懂它怎么设计的 → `architecture.md`
- 想按模块深入 → `modules/` 下的四份文档
- 想知道为什么这么选、为什么不那么选 → `decisions.md`
- 想部署到服务器 → `deployment.md`
