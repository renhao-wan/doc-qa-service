# doc-qa-service

**企业内部知识库问答服务**——把 Markdown 文档解析、切分、向量化后存入 PostgreSQL（pgvector），用户提问时做语义检索，把相关片段交给大模型基于内部资料作答；内部资料不足时可以联网补充。

后端以 REST + SSE 接口对外提供服务，可被内部 OA、知识库平台等系统接入；仓库内的 Vue 前端是演示客户端。

## 能力

- **知识库问答**：文档分片上传（秒传 / 断点续传）→ 异步向量化 → RAG 检索增强回答
- **通用对话**：多轮上下文、模型切换、温度调节、显式联网检索
- **Function Calling**：知识库上下文不足时，由模型自主决定是否联网补充
- **鉴权与数据隔离**：Spring Security + JWT；对话按用户隔离，知识库文件共享可见但删改限上传者
- **检索质量评估**：附一套 RAG 检索评估——五种切分策略与 `topK` 取值的实测对比

## 技术栈

| | |
|---|---|
| **后端** | Spring Boot 3.4.5 · Java 17 · Spring AI 1.1.1 · MyBatis-Plus · PostgreSQL 16 + pgvector · Flyway · Spring Security + JWT |
| **前端** | Vue 3 · Vite 6 · Pinia · Ant Design Vue · Tailwind CSS v4 |
| **部署** | Docker Compose · GitHub Actions（CI + 手动触发的 CD） |

## 快速开始

```bash
cp .env.example .env     # 填入 DASHSCOPE_API_KEY
docker compose up -d --build
```

访问 **http://localhost:8081**。

完整的本地启动步骤（含 IDE 开发模式、演示账号、常见问题）见 **[docs/getting-started.md](docs/getting-started.md)**。

## 文档

全部项目文档在 **[docs/](docs/README.md)**。

| 想了解 | 看哪份 |
|---|---|
| 项目怎么跑起来 | [docs/getting-started.md](docs/getting-started.md) |
| 整体怎么设计的 | [docs/architecture.md](docs/architecture.md) |
| 某个链路的实现细节 | [docs/modules/](docs/modules/) 下的四份 |
| **为什么这么选、为什么不那么选** | [docs/decisions.md](docs/decisions.md) |
| 踩过哪些坑 | [docs/pitfalls.md](docs/pitfalls.md) |
| 怎么部署 | [docs/deployment.md](docs/deployment.md) |

## 仓库结构

```
doc-qa-service/
├── doc-qa-api/          后端：Spring Boot + Spring AI
├── doc-qa-web/          前端：Vue 3 + Vite
├── docs/                项目文档
├── searxng/             SearXNG 聚合搜索配置
├── scripts/deploy.sh    服务器侧部署脚本
├── .github/workflows/   CI 与 CD 流水线
└── docker-compose.yml   四服务编排入口
```
