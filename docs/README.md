# 项目文档

doc-qa-service 是一套**企业内部知识库问答服务**，用 Spring AI 构建。它把 Markdown 文档解析、切分、向量化后存入 PostgreSQL（pgvector），用户提问时做语义检索，把相关片段交给大模型基于内部资料作答；内部资料不足时可以联网补充。后端以 REST + SSE 接口对外提供服务，仓库内的 Vue 前端是一个演示客户端。

服务同时提供两种问答模式：**通用对话**面向开放问题，支持模型切换与联网检索；**知识库问答**面向内部文档，走 RAG。两者共用同一套流式对话与持久化链路，通过 Advisor 链装配不同能力。

> ⏳ 标记表示该文档尚未产出——本文档目录正在重构中，完成后此标记会全部移除。

---

## 从哪读起

按你的目的选一条：

### 一、想把项目跑起来

| 顺序 | 文档 | 说明 |
|---|---|---|
| 1 | `getting-started.md` ⏳ | 前置依赖、起服务、验证 |
| 2 | `deployment.md` ⏳ | 部署到服务器、CI/CD、运维（本地跑通之后再看） |

### 二、想搞懂它怎么设计的

| 顺序 | 文档 | 说明 |
|---|---|---|
| 1 | `architecture.md` ⏳ | 先建立整体认识：模块划分、一次请求的完整数据流 |
| 2 | `modules/chat.md` ⏳ | 对话链路：Advisor 链、记忆、联网搜索、SSE、落库时机 |
| 3 | `modules/knowledge-base.md` ⏳ | 知识库链路：分片上传、向量化、RAG 检索、工具调用 |
| 4 | `modules/auth.md` ⏳ | 鉴权与数据隔离：无状态 JWT、两条防越权路径 |
| 5 | `modules/data.md` ⏳ | 数据层：表设计、索引、Flyway 迁移 |
| 6 | `decisions.md` ⏳ | 为什么这么做，以及**为什么不那么做** |
| 7 | `pitfalls.md` ⏳ | 跨模块与环境级的坑：现象、原因、怎么避免 |

第 6、7 两份是**判断力最集中的地方**。前者记录了被否决的方案及其代价，后者记录了那些「现象指向的方向与真实原因相反」的问题——它们比「用了什么技术」更值得读。

---

## 文档地图

| 文档 | 讲什么 | 状态 |
|---|---|---|
| `README.md` | 本文：文档地图与阅读路径 | ✅ |
| `architecture.md` | 项目定位、仓库结构、模块划分、请求数据流、技术栈 | ⏳ |
| `getting-started.md` | 前置依赖、起依赖、起后端、起前端、验证、常见起不来 | ⏳ |
| `modules/chat.md` | 请求级 ChatClient、Advisor 链、记忆、联网搜索与双线程池、SSE 协议、落库时机 | ⏳ |
| `modules/knowledge-base.md` | 完整入库链路、文件状态机、秒传与断点续传、MarkdownReader 切分语义、向量覆盖式重建、RAG 检索、Function Calling | ⏳ |
| `modules/auth.md` | 无状态 JWT、HTTP 200 错误约定、SSE ASYNC dispatch、两条防越权路径的对照 | ⏳ |
| `modules/data.md` | 七张表、索引设计、时间列类型、Flyway 策略 | ⏳ |
| `decisions.md` | 选型理由 + 被否决的方案及代价 | ⏳ |
| `pitfalls.md` | 跨模块与环境级坑点 | ⏳ |
| `deployment.md` | 编排、密钥管理、CI/CD、部署脚本的防呆设计 | ⏳ |
| `rag-evaluation/` | RAG 检索质量评估：报告、金标准问题集、语料、原始结果 | [rag-evaluation.md](rag-evaluation.md) 已存在，目录索引 ⏳ |

---

## 关于这个目录

- 本文档随代码一起公开。内容只讲技术与设计。
- **不含**开发日志、任务派发记录、评审过程、项目进展。
- 历史设计文档（含已作废的迁移方案、被推翻的结论）不单独归档，需要追溯某个决策的完整推导过程时，查 `git log`。
- 前端应用自身的说明在 [doc-qa-web/README.md](../doc-qa-web/README.md)。
