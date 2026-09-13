# 架构总览

> ⏳ 标记表示该文档尚未产出——本文档目录正在重构中，完成后此标记会全部移除。

先读本文建立整体认识，再按需进入 `modules/` 下对应的链路文档。

---

## 1. 项目定位与边界

**是什么：** 一套企业内部知识库问答服务。把 Markdown 文档解析、切分、向量化后存入 PostgreSQL（pgvector），用户提问时做语义检索，把相关片段交给大模型基于内部资料作答；内部资料不足时可以联网补充。后端以 REST + SSE 接口对外提供服务，仓库内的 Vue 前端是演示客户端。

服务提供两种问答模式：

| 模式 | 面向 | 能力 |
|---|---|---|
| 通用对话 | 开放问题 | 多轮上下文、模型切换、温度调节、显式联网检索 |
| 知识库问答 | 内部文档 | RAG 检索增强、联网兜底（模型自主决定是否调用） |

两者共用同一套流式对话与持久化链路，通过 **Advisor 链**装配不同能力——这是本项目最核心的扩展点。

**不是什么：** 不是开放平台。没有 API Key 签发、计费、多租户隔离、网关限流。它面向的是企业内网这一层——可被内部 OA、知识库平台等系统接入，但不直接对公网提供多租户服务。

---

## 2. 仓库结构

单仓库，两个**独立构建**的应用并列存放，互不共享代码，没有根级 `package.json` / `pom.xml`：

```
doc-qa-service/
├── doc-qa-api/              后端：Spring Boot 3.4.5 + Java 17 + Spring AI 1.1.1
├── doc-qa-web/              前端：Vue 3 + Vite 6 + Pinia + Ant Design Vue
├── docs/                    本文档目录
├── searxng/settings.yml     SearXNG 聚合搜索配置
├── scripts/deploy.sh        服务器侧部署脚本（由 CD 通过 SSH 调用）
├── .github/workflows/       ci.yml / deploy.yml
└── docker-compose.yml       四个服务的编排入口
```

两个子目录各自构建、各自有 Dockerfile。根目录的 `docker-compose.yml` 把它们与 `postgres`、`searxng` 一起编排起来，一条命令起全套（见 `deployment.md` ⏳）。

---

## 3. 模块划分

四条链路，与 `modules/` 下的四份文档一一对应：

| 模块 | 文档 | 职责 |
|---|---|---|
| 对话链路 | `modules/chat.md` ⏳ | 请求级 ChatClient、Advisor 链、对话记忆、联网搜索、SSE 流式输出、消息落库 |
| 知识库链路 | `modules/knowledge-base.md` ⏳ | 文件分片上传与状态机、异步向量化、RAG 检索、Function Calling 联网兜底 |
| 鉴权 | `modules/auth.md` ⏳ | 无状态 JWT、用户维度数据隔离、两条防越权路径 |
| 数据层 | `modules/data.md` ⏳ | 表与索引设计、时间列类型约定、Flyway 迁移 |

---

## 4. 一次请求的完整数据流

### 4.1 对话入口 `/api/chat/completion`

```
HTTP POST
  └─ JwtAuthenticationFilter         解析 Authorization: Bearer，userId 放入 SecurityContext
      └─ ChatController.chat
          ├─ 归属校验                  chatMapper.existsByUuidAndUserId(chatId, userId)
          │                           ⚠️ 必须在返回 Flux 之前同步完成
          ├─ 构造 ChatModel           OpenAiChatModel.builder()…   每次请求现场构造
          ├─ 构造 ChatClient          ChatClient.create(chatModel).prompt()
          │                             .options(model / temperature)
          │                             .user(userMessage)
          ├─ 装配 Advisor
          │     ├─ networkSearch ? NetworkSearchAdvisor
          │     │                : CustomChatMemoryAdvisor    ← 二选一，不同时挂载
          │     └─ CustomStreamLoggerAndMessage2DBAdvisor     ← 固定挂在链尾
          ├─ .stream().chatResponse()
          │     └─ 按 reasoningContent 是否为空分流：
          │          非空 → AIResponse.reasoning（思考过程）
          │          空   → AIResponse.v（正式回答）
          └─ 流终止（doFinally 捕获三种信号）
                └─ 事务内写两条 t_chat_message + 刷新 t_chat.update_time
```

**两个容易误读的点：**

- **`ChatModel` 是每个请求现场构造的**，不是注入的共享 Bean。`ChatClientConfig` 里确实注册了一个 `chatClient` Bean，但两个 Controller **都没有用它**——因为模型名和 temperature 需要按请求动态指定（对话页由前端传参，知识库页取自配置）。新增 AI 能力时沿用这个模式。
- **归属校验的位置是被响应式语义倒逼的**。一旦开始返回流式响应，HTTP 头已经发出，异常就不可能再变成 `Response` JSON。所以所有需要「拒绝」的判断都必须在这之前同步完成。

### 4.2 知识库入口 `/api/knowledge-base/completion`

```
HTTP POST
  └─ JwtAuthenticationFilter
      └─ KnowledgeBaseController.chat
          ├─ 构造 ChatModel / ChatClient     模型与温度取自 knowledge-base.* 配置
          ├─ webFallback ? .tools(webSearchTool)     挂上联网工具，由模型自主决定调不调
          ├─ 装配 KnowledgeBaseAdvisor(vectorStore, webFallback, topK)
          │     └─ 向量检索 → 用提示词模板重写 prompt
          │        ⚠️ 模板与「工具是否挂载」严格配对，见 modules/knowledge-base.md
          └─ .stream().content()
                └─ 包成 AIResponse.v      ← 注意：此入口没有 reasoning 分流
```

与对话入口的三处差异：模型与温度来自配置而非请求参数；Advisor 链上只有 `KnowledgeBaseAdvisor`（无记忆、无落库 Advisor）；输出走 `.content()` 拿纯文本，不做思维链分流。

工具调用（Function Calling）的循环跑在 `OpenAiChatModel` 内部，**Advisor 链只执行一遍**——所以向量检索不会随工具轮次重复执行。

### 4.3 文件入库链路 `/api/knowledge-base/file/*`

```
checkFile      按 fileMd5 查 t_knowledge_base_file
                 ├─ 无记录            → 需要上传
                 ├─ 状态非 UPLOADING  → 秒传，直接返回完成
                 └─ 状态为 UPLOADING  → 返回 uploadedChunks，供断点续传
     ↓
uploadChunk    分片落盘 {chunk-path}/{fileMd5}/{n}.chunk
                 并写 t_knowledge_base_chunk（INSERT … ON CONFLICT DO NOTHING）
     ↓
mergeChunk     按序流式合并为 {timestamp}_{原文件名}
                 状态改 PENDING → 删分片目录与记录 → 发布 KnowledgeBaseFileUploadedEvent
     ↓
监听器         @TransactionalEventListener(AFTER_COMMIT) + @Async
                 ├─ 状态改 VECTORIZING
                 ├─ MarkdownReader 按标题切分为 Document
                 ├─ 先按 mdStorageId 删旧向量，再一次性批量写入 pgvector（覆盖式重建）
                 └─ 状态改 COMPLETED，失败则 FAILED
```

状态机：`UPLOADING → PENDING → VECTORIZING → COMPLETED / FAILED`。详见 `modules/knowledge-base.md`。

---

## 5. 技术栈

### 后端（`doc-qa-api`）

| 技术 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.4.5 | 基础框架 |
| Java | 17 | 语言版本 |
| Spring AI | 1.1.1 | 模型调用、Advisor 链、Tool Calling、Document Reader |
| MyBatis-Plus | 3.5.12 | 数据访问，分页插件固定 `DbType.POSTGRE_SQL` |
| PostgreSQL + pgvector | 16 / pgvector 镜像 | 业务数据 + 向量存储 |
| Flyway | 由 Boot 管理 | schema 版本管理，唯一的 DDL 来源 |
| Spring Security + jjwt | 6.x / 0.12.6 | 无状态 JWT 鉴权 |
| OkHttp + Jsoup | 4.12.0 / 1.17.2 | 联网搜索的并发抓取与 HTML 清洗 |
| p6spy | 3.9.1 | SQL 打印 |
| Log4j2 | 由 Boot 管理 | 日志（已排除默认 Logback） |

### 前端（`doc-qa-web`）

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5.13 | 框架（Composition API） |
| Vite | 6.2.4 | 构建与开发服务器（`/api` 代理到后端） |
| Pinia | 3.0.3 + persistedstate | 状态管理，持久化 token 与对话偏好 |
| Ant Design Vue | 4.2.6 | 组件库（经 `unplugin-vue-components` 按需引入） |
| Tailwind CSS | 4.1.8 | 样式 |
| @microsoft/fetch-event-source | 2.0.1 | SSE 流式接收 |
| markdown-it + highlight.js | 14.1.0 / 11.11.1 | 流式 Markdown 渲染 |
| spark-md5 | 3.0.2 | 分片上传前计算文件 MD5 |

每一项技术**为什么选它**，见 `decisions.md` ⏳。
