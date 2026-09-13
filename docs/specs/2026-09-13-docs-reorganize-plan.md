# 项目文档重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把分散在五处（`CLAUDE.md`、`OPTIMIZATION-PLAN.md`、`docs/specs/`、`.superpowers/sdd/`、代码本身）的项目知识，提炼重写成一套按模块组织的 `docs/` 技术文档，达到「读文档就知道项目怎么跑，并能接住面试对实现细节的追问」。

**Architecture:** 按模块而非按文档类型组织——每份 `modules/*.md` 自包含一个链路的架构、设计取舍与坑点。跨模块的选型理由单独进 `decisions.md`，跨模块与环境级的坑单独进 `pitfalls.md`。知识来源向代码收敛：所有技术论断以当前代码为准，旧文档只作为「线索」而非「事实」。

**Tech Stack:** Markdown。无代码改动。唯一的工具依赖是 git 与文件读写。

**Spec:** [docs/specs/2026-09-13-docs-reorganize-design.md](2026-09-13-docs-reorganize-design.md)

## Global Constraints

以下约束适用于**每一个** Task，不再逐条重复：

1. **不改任何代码。** 本次只写文档。盘点中发现的既有缺陷（`mergeChunk` 可改写他人既有记录、前端 `onopen` 兜底缺失、`uploaded_chunks` 是只写不读的死列等）**只记录，不修复**。
2. **`docs/` 随代码公开。** 可以写架构、坑点、选型理由、被否决的方案；**不得**写入简历包装、面试话术、求职规划类内容。
3. **技术论断以当前代码为准。** 旧文档中的说法若与代码不符，以代码为准。已知三处过时内容必须修正：`doc-qa-web/README.md` 的 SSE 说明、`auth-design.md` §3.4 的迁移方案、`cicd-design.md` §8.5 的 SearXNG 引擎。
4. **引用优先写「文件 + 符号名」，行号只用于不常改动的文件。** 行号随代码漂移，是文档腐坏最快的一环。
5. **全中文撰写**，技术术语保留英文原文。
6. **类头注释风格沿用项目既有约定**：`@Author: Renhao-Wan` + `@Description` 风格的 Javadoc 块（仅对 Java 代码；文档无需此格式，此处仅作提醒——本次不改代码）。
7. **不建 `docs/archive/` 目录。** 旧 spec 提炼后直接删除，追溯靠 `decisions.md` 与 git 历史。
8. **`.superpowers/` 原目录只读不动。** 它在主工作目录 `D:/projects/doc-qa-service/`，且被 `*` 完全排除在版本控制之外。

### 关于验证

本文档的每个 Task 用「核对 → 撰写 → 校验 → 提交」代替 TDD 循环：

- **核对**：读来源材料，确认要提炼的内容确实存在、且描述的是当前代码。
- **校验**：文档写完后，逐条对照代码确认断言成立，并检查所有相对链接可达。

`校验` 步骤是硬性的——它承担测试的角色。没有跑过校验就不算完成。

### 关于 CHECKPOINT

Spec §7 要求分三步交付并暂停确认。计划中的 **CHECKPOINT** 标记处**必须停下等用户确认**，不得连续推进。

---

## File Structure

**新建（11 份）：**

| 路径 | 职责 |
|---|---|
| `docs/README.md` | 文档地图、两条阅读路径 |
| `docs/getting-started.md` | 本地跑起来 |
| `docs/architecture.md` | 总览、模块划分、数据流 |
| `docs/modules/chat.md` | 对话链路 |
| `docs/modules/knowledge-base.md` | 知识库链路 |
| `docs/modules/auth.md` | 鉴权与数据隔离 |
| `docs/modules/data.md` | 数据层、Flyway、索引设计 |
| `docs/decisions.md` | 选型理由 + 被否决的方案 |
| `docs/pitfalls.md` | 跨模块与环境级坑点 |
| `docs/deployment.md` | 部署与运维 |
| `docs/rag-evaluation/README.md` | 评估复跑说明 |

**修改（3 份）：**

| 路径 | 改动 |
|---|---|
| `docs/rag-evaluation.md` | 移动为 `docs/rag-evaluation/report.md`（见 Task 11 说明） |
| `docs/rag-evaluation/results/end-to-end-results.md` | 保留汇总段；原文输出段评估是否瘦身（见 Task 11） |
| `doc-qa-web/README.md` | 修正 SSE 说明与页面清单；删除脚手架残留段 |

**删除（5 份）：**

| 路径 | 处置 |
|---|---|
| `docs/specs/2026-09-12-auth-design.md` | 提炼后删除 |
| `docs/specs/2026-09-12-auth-plan.md` | 提炼后删除 |
| `docs/specs/2026-09-13-cicd-design.md` | 提炼后删除 |
| `docs/specs/2026-09-13-cicd-plan.md` | 提炼后删除 |
| `docs/specs/2026-09-13-docs-reorganize-design.md` + 本计划 | 重构收尾时删除，`docs/specs/` 目录整体退役 |

**不在本 worktree 内、需另行处理（1 份）：**

| 路径 | 说明 |
|---|---|
| `D:/projects/doc-qa-service/CLAUDE.md` | 被 `.gitignore` 排除，**不随 worktree 走**，本目录读取不到。瘦身动作见 Task 13，需在主工作目录执行 |

---

## 阶段一：定调（2 份）

这两份决定全篇的文风、信息密度与术语口径，必须先做并确认。

---

### Task 1: docs/README.md

**Files:**
- Create: `docs/README.md`

**来源:**
- Consumes: Spec §2 的目标结构、§3 的职责表；`CLAUDE.md`「仓库结构」节（项目定位与两个应用的划分）
- Produces: 文档地图——后续每份文档完成时都要回来更新它的状态列

**内容要点：**

1. **项目一句话定位。** 取自 `CLAUDE.md` 的表述基础上收敛为一句话：基于 Spring AI 的企业内部私有知识库问答后端服务，提供文档解析入库、语义检索增强问答、联网资料补充能力，以 REST + SSE 对外提供服务。
2. **文档地图表格。** 逐行列出现有全部文档，三列：`文档` / `讲什么` / `什么时候读`。
3. **两条阅读路径**，各自给一个有序的文档列表：
   - **想跑起来**：`getting-started.md` →（可选）`deployment.md`
   - **想搞懂架构**：`architecture.md` → `modules/chat.md` → `modules/knowledge-base.md` → `modules/auth.md` → `modules/data.md` → `decisions.md` → `pitfalls.md`
4. **一句边界声明**：本目录只讲技术与设计，不含项目进展、开发日志与个人求职材料。

**注意**：写到这一步时，`modules/*` 等文档尚未产出。表格中未完成的条目**必须如实标注为「待补」**，不得写成已存在——否则读者点进去是空链接。每份文档完成时回来把状态改掉。这是本 Task 唯一的跨任务耦合点。

- [ ] **Step 1: 核对来源**

确认 `CLAUDE.md`「仓库结构」节对项目定位与两个应用划分的表述（该文件位于主工作目录 `D:/projects/doc-qa-service/CLAUDE.md`，用绝对路径读取）。

- [ ] **Step 2: 撰写 `docs/README.md`**

按上述四个要点撰写。文档地图中尚未产出的条目标注「待补」。

- [ ] **Step 3: 校验**

- 逐条检查地图里的每个链接路径，确认**指向的目标文件存在**；不存在的必须是「待补」状态而非链接。
- 确认两条阅读路径里的每一步都是真实存在或标注待补的文档。

- [ ] **Step 4: 提交**

```bash
git add docs/README.md
git commit -m "docs(readme): 新增文档地图与阅读路径"
```

---

### Task 2: docs/architecture.md

**Files:**
- Create: `docs/architecture.md`

**来源:**
- Consumes: `CLAUDE.md`「仓库结构」；`ChatController`、`KnowledgeBaseController`、`KnowledgeBaseServiceImpl` 的实际代码；Spec §3 对该文档的职责定义
- Produces: 后续 4 份 `modules/*.md` 都从本文的模块划分出发，模块边界一旦在这里定下，Task 3–6 必须与之一致

**内容要点：**

1. **项目定位与边界。** 明确写出「是什么」与「不是什么」——可被多个客户端调用的企业内部后端服务；**不是**开放平台（无 API Key 签发、计费、多租户隔离、网关限流）。这一条来自 `OPTIMIZATION-PLAN.md` §1.3，是刻意保留的边界声明。
2. **仓库结构。** 单仓库两个独立应用，各自构建，无根级构建文件：
   - `doc-qa-api/` — Spring Boot 3.4.5 + Java 17 + Spring AI 1.1.1 + PostgreSQL/pgvector
   - `doc-qa-web/` — Vue 3 + Vite 6 + Pinia + Ant Design Vue + Tailwind v4
   - 根目录 `docker-compose.yml` 提供四服务编排
3. **模块划分图。** 四条链路：对话链路、知识库链路、鉴权、数据层。用文字或 ASCII 图表示，与 Task 3–6 的四份文档**一一对应**。
4. **一次请求的完整数据流**，分三条走一遍（这是本文档的核心，决定读者能否「看懂项目怎么跑」）：
   - **对话入口** `/api/chat/completion`：JWT 过滤器 → `ChatController` → 现场构造 `OpenAiChatModel` → `ChatClient.create()` → Advisor 链（记忆或联网二选一）→ 模型 → SSE 流 → `doFinally` 落库
   - **知识库入口** `/api/knowledge-base/completion`：JWT 过滤器 → `KnowledgeBaseController` → `KnowledgeBaseAdvisor` 向量检索 →（可选）挂 `web_search` 工具 → 模型 → SSE 流
   - **文件入库链路**：`checkFile` → `uploadChunk` → `mergeChunk` → 发布事件 → `@Async` 监听器 → 向量化
5. **技术栈表**：技术 / 用途 / 详细理由指向 `decisions.md`（此处只列，不展开）。

**注意**：数据流必须以**实际代码**为准。两个 Controller 都在每个请求内现场构造 `OpenAiChatModel` 而非注入共享 Bean，这一点在图上要如实体现——它是本项目一个容易被误读的设计。

- [ ] **Step 1: 核对来源**

读取并确认：
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/controller/ChatController.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/controller/KnowledgeBaseController.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/service/impl/KnowledgeBaseServiceImpl.java`
- 主目录的 `CLAUDE.md`「仓库结构」节与「请求级 ChatClient」节

确认两个 SSE 接口的实际路径、`ChatClient` 的构造方式、Advisor 挂载条件（`if (networkSearch)`）与工具挂载条件（`if (webFallback)`）。

- [ ] **Step 2: 撰写 `docs/architecture.md`**

按上述五个要点撰写。

- [ ] **Step 3: 校验**

- 对照 `ChatController` / `KnowledgeBaseController` 源码，确认数据流每一步的**顺序与触发条件**与代码一致。
- 确认模块划分图里的四条链路与 Task 3–6 将要产出的四份文档一一对应。
- 确认文末技术栈表的每一项在本项目中确实被使用（对照 `pom.xml` 与 `doc-qa-web/package.json`）。

- [ ] **Step 4: 提交**

```bash
git add docs/architecture.md
git commit -m "docs(architecture): 新增架构总览与请求数据流"
```

---

### 🔶 CHECKPOINT 1

**停下，等用户确认。** 请用户读过 `docs/README.md` 与 `docs/architecture.md`，确认文风、信息密度、术语口径。这两份定调，后续 9 份按此标准产出。

---

## 阶段二：模块（4 份）

一份一份交付，每份写完请用户过目。

---

### Task 3: docs/modules/chat.md

**Files:**
- Create: `docs/modules/chat.md`

**来源:**
- Consumes: 主目录 `CLAUDE.md`「请求级 ChatClient」「Advisor 链是核心扩展点」「SSE 流式协议」三节；`advisor/` 下四个 Advisor 的源码；`ChatController`；`config/ThreadPoolConfig.java`、`config/OkHttpConfig.java`；`.superpowers/sdd/2026-09-12-auth-plan/` 中 task-4/5/8 的 report
- Produces: Task 4 会引用本文的 Advisor 链概念；Task 7 会从本文提取落库时机的选型理由

**内容要点：**

1. **请求级 ChatClient。** 解释为何两个 Controller 都在请求内现场构造 `OpenAiChatModel` → `ChatClient.create()`，而不注入 `ChatClientConfig` 注册的 `chatClient` Bean（模型名与 temperature 需按请求动态指定）。这条要写成「设计选择」而非「遗留问题」。
2. **Advisor 链。** 四个 Advisor 的表格：order / 作用。补三条关键行为：
   - 记忆与联网搜索**二选一**（`if (networkSearch)`），不会同时挂载
   - 每个请求 `new` 实例（持有请求级依赖）
   - 提示词模板以 `private static final PromptTemplate` 内联在各 Advisor 中
3. **记忆。** `CustomChatMemoryAdvisor`：从 `t_chat_message` 取该 `chatUuid` 下最近 50 条；**已知短板**是「按条数截断而非 token」，长消息会超出上下文窗口——这是 `OPTIMIZATION-PLAN.md` §2.3 主动记录的缺陷，如实收进本文。
4. **联网搜索。** `NetworkSearchAdvisor`：SearXNG 检索 → 并发抓取正文 → 重写 prompt 注入上下文与来源链接。配套展开双线程池：
   - IO 密集型 50/200/1000 队列 + `CallerRunsPolicy`
   - CPU 密集型 核数 / 2×核数 / 200 队列 + `AbortPolicy`
   - 为什么两者的拒绝策略要分开选——这是 `OPTIMIZATION-PLAN.md` §2.5④ 点名的「全项目质量最高的代码」，必须讲透
5. **SSE 流式协议。** `Flux<AIResponse>`，`AIResponse` 的两个互斥字段 `reasoning`（思考过程）与 `v`（正式回答），前端据此分流渲染。补一条防御性缺口说明：`ChatController` 对 `reasoningContent` 直接 `.toString()` 未判 null，实测未触发（非推理模型下该 key 存在但值为 `""`）。
6. **落库时机。** `CustomStreamLoggerAndMessage2DBAdvisor`（order 99）用 `doFinally(signalType)` 统一处理三种终止信号，在 `TransactionTemplate` 中写入用户消息 + AI 回答两条记录，并调 `touchUpdateTime` 刷新对话列表排序。三条必须写清的行为：
   - 用户点「停止生成」触发 `ON_CANCEL` 而非 `ON_COMPLETE`——**这是已修的历史 bug，改动时别退回去**
   - 用户提问**始终**落库；AI 回答仅在内容非空时落库
   - `ON_ERROR` 不落库
7. **本模块坑点**（就近写在此处，不进 `pitfalls.md`）。

**注意**：第 6 点的 `doOnComplete` → `doFinally` 是本项目最重要的一个 bug 修复。文档里要写清**为什么**必须是 `doFinally`（三种终止信号的语义差异），而不只是"用了 doFinally"。

- [ ] **Step 1: 核对来源**

读取并确认：
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/advisor/` 下四个 Advisor 的源码（确认 order 值、类名、模板）
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/controller/ChatController.java`（确认 Advisor 挂载条件与 `reasoningContent` 的取值方式）
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/config/ThreadPoolConfig.java`（确认两套线程池的实际参数）
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/config/OkHttpConfig.java`
- 主目录 `CLAUDE.md` 的「Advisor 链是核心扩展点」节全文

- [ ] **Step 2: 撰写 `docs/modules/chat.md`**

按上述七个要点撰写。

- [ ] **Step 3: 校验**

- 四个 Advisor 的 order 值逐个对照源码确认（`getOrder()` 返回值）。
- 线程池参数逐个对照 `ThreadPoolConfig` 确认（核心数/最大数/队列/拒绝策略）。
- 确认落库行为的三条描述与 `CustomStreamLoggerAndMessage2DBAdvisor` 源码一致。
- 检查文中所有文件引用路径存在。

- [ ] **Step 4: 提交**

```bash
git add docs/modules/chat.md
git commit -m "docs(modules): 新增对话链路说明"
```

---

### Task 4: docs/modules/knowledge-base.md

**Files:**
- Create: `docs/modules/knowledge-base.md`

**来源:**
- Consumes: 主目录 `CLAUDE.md`「Markdown 问答文件：秒传 + 分片 + 异步向量化」全节、「Function Calling（知识库页的联网兜底）」全节；`KnowledgeBaseServiceImpl`、`KnowledgeBaseFileUploadedListener`、`MarkdownReader`、`WebSearchTool`、`KnowledgeBaseAdvisor` 的源码；`enums/KnowledgeBaseFileStatusEnum.java`；`.superpowers/sdd/` 的 `progress.md` Ruling 20–23 与 `final-fix-report.md`
- Produces: Task 7 会从本文提取「向量去重改为覆盖式重建」的选型理由

**内容要点：**

1. **完整链路。** 五个环节串起来：`checkFile` → `uploadChunk` → `mergeChunk` → 发布事件 → 异步向量化。每个环节写清输入、输出与关键决策。
2. **文件状态机。** `UPLOADING → PENDING → VECTORIZING → COMPLETED / FAILED`，并说明 `deleteMarkdownFile` 在各状态下的放行规则（`PENDING`/`VECTORIZING` 拒绝删除；`UPLOADING` 放行且必须一并回收分片）。
3. **分片上传。** 三个机制：
   - **秒传**：按 `fileMd5` 查记录，状态非 `UPLOADING` 即秒传
   - **断点续传**：状态为 `UPLOADING` 时返回 `uploadedChunks`
   - **并发幂等**：`INSERT ... ON CONFLICT DO NOTHING` + 影响行数判断。**必须写清为什么不能改成「捕获 `DuplicateKeyException`」**——PostgreSQL 中约束冲突会让整个事务进入 aborted 状态，而该方法是 `@Transactional` 的，catch 救不回来
4. **分片大小是前后端共同约定。** 前端 `CHUNK_SIZE = 2MB`（`KnowledgeBaseChatPage.vue`）、后端 `spring.servlet.multipart.max-file-size`（3MB）、nginx `client_max_body_size`（5m）**三处必须同步**。写清为什么上限只需容纳**一个分片**（本项目只走分片上传，整文件从不作为单个 part 传输），以及不配时的具体后果（Tomcat 在进入 Controller 前就拒掉，日志里是 `FileSizeLimitExceededException`，被兜底成 `10000`，表现为「checkFile 说需上传、一点上传就报错」）。
5. **MarkdownReader 的切分语义。** 三件事讲清楚：
   - `visit(Heading)` 里是无条件 flush，**标题才是主切分点，且不受任何配置控制**；`---` 只是额外切分点（仅当 `withHorizontalRuleCreateDocument(true)`）
   - `withIncludeCodeBlock(false)` / `withIncludeBlockquote(false)` 决定代码块与块引用是否单独成篇，**与标题切分无关**
   - **标题文本不进正文**（`visit(Text)` 遇到 parent 是 Heading 时只写 `title` 元数据），代价是语料少约 11% 的文本
6. **向量写入：覆盖式重建。** 先 `delete("mdStorageId == " + id)` 清掉该文件已有向量，再 `vectorStore.add(documents)` 一次写入全部分片。三条必须写：
   - **顺序不能反**，反了等于删掉刚写入的向量
   - 不要退回逐条 `add`（会让走网络的 embedding 调用随分片数线性增长）
   - `mdStorageId` 这个过滤键必须与 `mergeChunk` 写入 Document 元数据时保持一致，**只改一处会让删除静默失效、旧向量永久累积**
7. **历史教训：跨文件去重为什么被废。** 这里曾用「逐条 `topK=1` 相似检索、得分 > 0.99 视为重复并跳过」做去重。那是**跨文件**去重，同一段文本只归属于第一个上传它的文件；而删除按 `mdStorageId` 走，于是删掉 A 文件会连带删掉 B 文件里「被判定为重复」的段落。**这段历史必须写进文档**——它是本项目最有价值的「踩坑后重构」案例之一。
8. **RAG 检索。** `KnowledgeBaseAdvisor`：pgvector 相似检索 → 提示词模板重写 → 注入。检索条数取 `knowledge-base.top-k`（默认 3），取 3 的依据指向 `rag-evaluation/report.md`。说明三参构造器的存在意义（「做成可配而不是写死，是为了让 topK 取多少合适能被实测」）。
9. **Function Calling 联网兜底。** 六条要点：
   - `WebSearchTool` 把联网检索封装成 `@Tool`，**只在知识库页**、**只在用户开了「联网兜底」时**才挂给模型
   - tool loop 跑在 `OpenAiChatModel` 内部，**advisor 链只跑一遍**
   - **提示词必须与工具是否挂载保持一致**（双构造器 + 双模板），否则模型会凭空编造联网结果而前端看不出破绽
   - **工具名 `web_search` 是写死在提示词里的调用契约**，改名必须同步改模板
   - **工具方法内部必须吞掉异常并返回说明文本**，抛出去会让整条 SSE 流转为 error
   - **工具调用之前的正文过滤不掉**，只能靠提示词约束
10. **本模块坑点**（就近写在此处）。

**注意**：第 7 点的历史教训与第 9 点的双模板陷阱，是本模块最容易被未来的自己踩回去的两处。写的时候要给出**具体后果**，不能只写"不要这样做"。

- [ ] **Step 1: 核对来源**

读取并确认：
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/service/impl/KnowledgeBaseServiceImpl.java`（确认 checkFile / uploadChunk / mergeChunk 的实际逻辑与状态流转）
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/event/listener/KnowledgeBaseFileUploadedListener.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/reader/MarkdownReader.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/tool/WebSearchTool.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/advisor/KnowledgeBaseAdvisor.java`（确认双构造器与双模板）
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/enums/KnowledgeBaseFileStatusEnum.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/domain/mapper/KnowledgeBaseChunkMapper.java`（确认 `insertChunkIgnoreDuplicate`）
- 主目录 `CLAUDE.md` 的「Markdown 问答文件」与「Function Calling」两节全文
- 主目录 `.superpowers/sdd/2026-09-12-auth-plan/progress.md` 的 Ruling 20–23

- [ ] **Step 2: 撰写 `docs/modules/knowledge-base.md`**

按上述十个要点撰写。

- [ ] **Step 3: 校验**

- 状态机的五个状态与 `KnowledgeBaseFileStatusEnum` 逐个对照。
- 三处分片大小限制的**实际数值**逐个对照：`KnowledgeBaseChatPage.vue` 的 `CHUNK_SIZE`、`application.yml` 的 `max-file-size`、`nginx.conf` 的 `client_max_body_size`。三者一旦发现不一致，**先报告再继续**——那本身就是个待修的 bug。
- 确认 `@Tool(name = "web_search")` 的实际 name 值与 `WEB_FALLBACK_PROMPT_TEMPLATE` 里引用的名字一致。
- 确认 `delete("mdStorageId == ...")` 的过滤键与 `mergeChunk` 写入的元数据键一致。
- 检查文中所有文件引用路径存在。

- [ ] **Step 4: 提交**

```bash
git add docs/modules/knowledge-base.md
git commit -m "docs(modules): 新增知识库链路说明"
```

---

### Task 5: docs/modules/auth.md

**Files:**
- Create: `docs/modules/auth.md`

**来源:**
- Consumes: `docs/specs/2026-09-12-auth-design.md` §3–§5、§7、§9；`.superpowers/sdd/2026-09-12-auth-plan/progress.md` 的 Ruling 1、2、7、14；同目录 `task-9-brief.md` Step 4 的 CLAUDE.md 架构节草稿；`config/SecurityConfig.java`、`filter/JwtAuthenticationFilter.java`、`utils/AuthContext.java`、`utils/JwtTokenProvider.java` 源码；`doc-qa-web/src/axios.js`、`stores/authStore.js`
- Produces: Task 7 会从本文提取多条鉴权相关的选型理由与 YAGNI

**内容要点：**

1. **无状态 JWT。** `SecurityConfig` 的过滤器链 `sessionCreationPolicy(STATELESS)`；`JwtAuthenticationFilter` 解析 `Authorization: Bearer` → 把 `userId`（Long）作为 principal 放进 `SecurityContextHolder`；**放行清单只有 `/auth/login`**，其余接口全部需要认证，**包括两个 SSE 接口**。
2. **业务错误约定在鉴权层同样成立。** 鉴权失败返回 **HTTP 200 + `success:false`**，不是 401。必须写清：Spring Security 的鉴权失败**不经过 `GlobalExceptionHandler`**，需要 `AuthenticationEntryPoint` / `AccessDeniedHandler` 自己写出 JSON，且两者都显式 `setStatus(200)`。这是本项目最反直觉的一条约定。
3. **`AuthContext.getCurrentUserId()` 的调用边界。** 只在同步代码里调用，**不要放进 Reactor 的 `map`/`flatMap` 回调**——`SecurityContextHolder` 默认是 `MODE_THREADLOCAL`，流式回调可能跑在别的线程上。需要跨线程用时把值当构造参数传进去（见 `CustomStreamLoggerAndMessage2DBAdvisor` 的 `ownerUserId`）。补充后果：取到空值会抛 30001 且**整轮对话消息丢失**。
4. **SSE 的 ASYNC dispatch 坑（本文档最硬核的一段）。** 完整成因链条：
   - 两个 SSE 接口返回 `Flux`，流跑完后 Tomcat 会做一次 **ASYNC dispatch**，把整条 Spring Security 过滤链重跑一遍
   - Spring Security 6 是「显式保存」模型——`SecurityContextHolderFilter` 只从仓库读、**不往回写**
   - `JwtAuthenticationFilter` 继承 `OncePerRequestFilter`，其 `shouldNotFilterAsyncDispatch()` **默认返回 `true`**（ASYNC 时被跳过），token 不会重解析
   - 三件事叠加 → 那一遍既没有 ThreadLocal、仓库里也是空的 → 身份退化成匿名 → `AuthorizationFilter` 判定失败抛 `AuthorizationDeniedException` → 响应早已 committed，转不成正常响应 → 每个 SSE 请求稳定产生 3 条 ERROR 堆栈
   - **修法**：`SecurityConfig` 配 `RequestAttributeSecurityContextRepository`，并在 `JwtAuthenticationFilter` 认证成功后 `saveContext(...)`。**该 `@Bean` 方法必须保持 `static`**（`SecurityConfig` 构造器依赖 `JwtAuthenticationFilter`，过滤器又依赖这个 Bean，写成实例方法即循环依赖、启动失败）
   - 回归测试：`SseAsyncDispatchSecurityTests`
5. **两条防越权路径的刻意对照。** 这是本文档最有判断力的一段：
   - **对话侧**：把归属条件**融进查询**（`WHERE user_id = ?` 直接写进 wrapper），越权自然退化成「查不到」。好处是不用写比对分支，且**不泄露资源是否存在**
   - **知识库侧**：文件是共享可见的，加 `WHERE uploader_id = ?` 会让列表只剩自己的。所以 `deleteMarkdownFile` / `updateMarkdownFile` **显式比对** `uploader_id`，返回「无权操作」
   - **结论**：越权该不该泄露资源存在性，**取决于该资源本来就对操作者可见与否**。这两条路径的差异是刻意的，不是疏漏
6. **JWT 密钥与算法。** `auth.jwt.secret` 写成 `${JWT_SECRET:...}` 不得硬编码；**`Keys.hmacShaKeyFor` 会按密钥字节数自动选档**（≥64 字节 → HS512、≥48 → HS384、≥32 → HS256，短于 32 字节抛 `WeakKeyException`），所以**实际使用的算法取决于密钥长度、不是固定 HS256**。这一条要写得明确——本项目的文档里曾长期把它当作 HS256。
7. **前端。** 三条：`authStore` 持久化 token；`axios.js` 的响应拦截器**必须检查响应体**（`res.success === false && res.errorCode === '30001'`）而非 HTTP 状态码；两个 SSE 页面改走 Vite proxy 的相对路径（跨域直连 + `Authorization` 会触发 `OPTIONS` 预检，而预检不带 token，必然被拒）。
8. **本模块坑点**（就近写在此处），至少包含：登录接口不得挂 `@ApiOperationLog`（会序列化明文密码与 token）；30000 与 30001 的分工是「密码错误不死循环」的关键。

**注意**：第 4 点的成因链条有六个环节，写的时候必须把「三件事叠加」讲清楚——只说修法会让读者下次遇到同类问题时无法自行诊断。

- [ ] **Step 1: 核对来源**

读取并确认：
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/config/SecurityConfig.java`（确认两个 handler、`RequestAttributeSecurityContextRepository` 的注册方式与 `static` 修饰）
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/filter/JwtAuthenticationFilter.java`
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/utils/JwtTokenProvider.java`、`utils/AuthContext.java`
- `doc-qa-api/src/test/java/io/github/renhaowan/docqa/SseAsyncDispatchSecurityTests.java`
- `doc-qa-web/src/axios.js`、`doc-qa-web/src/stores/authStore.js`
- `docs/specs/2026-09-12-auth-design.md` 全文
- 主目录 `.superpowers/sdd/2026-09-12-auth-plan/progress.md` 的 Ruling 1、2、7、14
- 主目录 `.superpowers/sdd/2026-09-12-auth-plan/task-9-brief.md` 的 Step 4

- [ ] **Step 2: 撰写 `docs/modules/auth.md`**

按上述八个要点撰写。

- [ ] **Step 3: 校验**

- 确认 `SecurityConfig` 里两个 handler 都显式 `setStatus(200)`。
- 确认 `RequestAttributeSecurityContextRepository` 的 `@Bean` 方法确实是 `static`——**如果已经不是，说明有回归，先报告**。
- 确认 `JwtAuthenticationFilter` 里确有 `saveContext` 调用。
- 确认 `axios.js` 的拦截器确实检查响应体而非状态码。
- 确认放行清单确实只有 `/auth/login`。
- 检查文中所有文件引用路径存在。

- [ ] **Step 4: 提交**

```bash
git add docs/modules/auth.md
git commit -m "docs(modules): 新增鉴权与数据隔离说明"
```

---

### Task 6: docs/modules/data.md

**Files:**
- Create: `docs/modules/data.md`

**来源:**
- Consumes: 主目录 `CLAUDE.md`「数据层」「数据库迁移（Flyway）」两节；`db/migration/V1__init_schema.sql`、`V2__vector_store.sql`、`db/dev-migration/V3__seed_demo_users.sql`；`domain/dos/` 下五个 DO；`config/MybatisPlusConfig.java`；`resources/spy.properties`
- Produces: Task 7 会从本文提取索引设计与 Flyway 配置的选型理由

**内容要点：**

1. **七张表。** 五张业务表（`t_user`、`t_chat`、`t_chat_message`、`t_knowledge_base_file`、`t_knowledge_base_chunk`）+ 向量表（`t_vector_store`）+ `flyway_schema_history`。每张表一句话职责。
2. **索引设计。** `idx_t_chat_message_chat_uuid_id (chat_uuid, id DESC)`——**排序键用自增主键 `id` 而不是 `create_time`**：`id` 单调递增且唯一，不受时间精度与重复值影响，分页时不会因排序值相等而出现重复或遗漏；索引带 `id DESC` 后 `ORDER BY id DESC LIMIT 50` 能直接按索引顺序取数；该索引前缀就是 `chat_uuid`，可完全替代单列索引（实测 300 条消息时相差约 17 倍）。
3. **时间列一律 `TIMESTAMP`（不带时区），不要改成 `TIMESTAMPTZ`。** 必须写清后果链：DO / VO 的时间字段全是 `java.time.LocalDateTime`，而 pgjdbc **不支持**把 `timestamptz` 转成 `LocalDateTime`，查询会抛 `Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime`。这个坑的麻烦之处在于**报错点是所有 SELECT**，看起来像 Mapper 或驱动问题；而 INSERT / UPDATE 不读回值所以不会报，**容易误判成「已经改好了」**。另：时间列必须 `NOT NULL DEFAULT now()`，因为 PostgreSQL 的 `ORDER BY ... DESC` 默认 **NULLS FIRST**。
4. **唯一索引不约束 NULL。** 可插入任意多行 `file_md5 IS NULL`，所以业务键列必须显式 `NOT NULL`，否则 `INSERT ... ON CONFLICT` 的幂等语义会失效。
5. **CHECK 约束兜底。** 枚举/取值范围用 CHECK 约束在库层兜底（`ck_t_chat_message_role`、`ck_kb_file_status`、`ck_kb_file_chunks`、`ck_kb_chunk_number`），不只依赖应用层校验。
6. **Flyway。** 四件事：
   - **schema 的唯一来源**是迁移脚本，应用启动时自动对账。三个版本各自的内容
   - `db/dev-migration` **只在 dev profile 被加载**，生产不配置它，那两个演示账号就不会跟着迁移进生产库。迁移脚本是跨环境同一份的，把预置账号放进 `db/migration/` 等于给生产库插两个密码可公开推知的登录凭证
   - **已执行过的迁移脚本不能改**（Flyway 按 checksum 校验，改动会让应用在已有库上直接启动失败）
   - `baseline-on-migrate: true` **不能去掉**（已有开发库非空，不开这项会报 `Found non-empty schema(s) "public" but no schema history table` 并拒绝启动）；`initialize-schema: false` **必须保持**（置 true 会与 V2 职责重叠，且需要 `CREATE EXTENSION` 权限、多实例并发启动时并非原子操作）
   - **V2 的 DDL 必须与 `spring.ai.vectorstore.pgvector` 配置严格一致**（`dimensions: 1536` ↔ `embedding vector(1536)`、`index-type: HNSW` ↔ `USING HNSW`、`distance-type: COSINE_DISTANCE` ↔ `vector_cosine_ops`、`table-name` ↔ 表名）。改配置就得新增迁移脚本去 ALTER，对不上时写入/检索会报维度或算子错误。
7. **数据层实现约定。** PostgreSQL + pgvector，驱动用 p6spy（SQL 打印配置在 `spy.properties`）；MyBatis-Plus，`@MapperScan` 在 `MybatisPlusConfig`，分页插件固定 `DbType.POSTGRE_SQL`；Mapper 普遍用 `default` 方法 + `Wrappers.lambdaQuery()`，不走 XML。
8. **本模块坑点**（就近写在此处）。

- [ ] **Step 1: 核对来源**

读取并确认：
- `doc-qa-api/src/main/resources/db/migration/V1__init_schema.sql`、`V2__vector_store.sql`、`db/dev-migration/V3__seed_demo_users.sql`
- `doc-qa-api/src/main/resources/application.yml` 与 `application-dev.yml` 的 `spring.flyway.*` 与 `spring.ai.vectorstore.pgvector.*` 配置
- `doc-qa-api/src/main/java/io/github/renhaowan/docqa/config/MybatisPlusConfig.java`
- 主目录 `CLAUDE.md` 的「数据层」「数据库迁移（Flyway）」两节全文

- [ ] **Step 2: 撰写 `docs/modules/data.md`**

按上述八个要点撰写。

- [ ] **Step 3: 校验**

- 逐张核对表名与实际迁移脚本一致（`git ls-files` 确认表名无遗漏）。
- 核对 `spring.ai.vectorstore.pgvector` 的**四个配置项**与 V2 的 DDL 字字对应（维度、索引类型、距离算子、表名）。
- 核对 `spring.flyway.locations` 在 dev 与默认 profile 下的实际值。
- 确认 `MybatisPlusConfig` 的分页插件确实是 `DbType.POSTGRE_SQL`。
- 检查文中所有文件引用路径存在。

- [ ] **Step 4: 提交**

```bash
git add docs/modules/data.md
git commit -m "docs(modules): 新增数据层与迁移说明"
```

---

### 🔶 CHECKPOINT 2

**停下，等用户确认。** 四份模块文档逐份确认过之后，进入收口阶段。

---

## 阶段三：收口（5 份 + 清理）

---

### Task 7: docs/decisions.md

**Files:**
- Create: `docs/decisions.md`

**来源:**
- Consumes: `.superpowers/sdd/2026-09-12-auth-plan/progress.md` 的 **Ruling 1–24**（核心来源）；`docs/specs/` 四份的 YAGNI 与取舍节；`OPTIMIZATION-PLAN.md` §2.5④ 与 §3 的取舍分析；Task 3–6 产出的四份模块文档
- Produces: 无下游依赖，但它是全篇的「判断力集中地」

**内容要点：**

本文档分两块，**第二块是重点**——多数项目的文档只写「最后选了什么」，而面试与复盘时被追问最多的是「你当时还考虑了什么」。

**第一块：为什么这么做。** 每条按「决策 / 理由 / 代价」三段写。至少覆盖：

- 为什么手写 `CustomChatMemoryAdvisor` 而不用 Spring AI 内置的 `ChatMemory` / `MessageChatMemoryAdvisor`（对话历史本就是业务数据，落在 `t_chat_message`，要支持分页、重命名、级联删除；用内置等于维护两份存储还要处理双写一致性）
- 为什么两个 Controller 现场构造 `ChatClient` 而不注入共享 Bean（模型名与 temperature 需按请求动态指定）
- 为什么两个线程池的拒绝策略要分开选
- 为什么 `topK` 固定在 3（指向 `rag-evaluation/report.md` 的实测依据）
- 为什么用 `RequestAttributeSecurityContextRepository` 而非 `HttpSession`（无状态场景下不建会话）
- 为什么索引排序键用 `id` 而非 `create_time`
- 为什么用 `doFinally` 而非 `doOnComplete`
- 为什么知识库列表不加 `WHERE uploader_id`，而对话列表要加

**第二块：为什么不那么做（被否决的方案及代价）。** 每条按「被否决的方案 / 否决理由 / 留下的代价」三段写。至少覆盖 `progress.md` 的以下 Ruling：

- `AUTH_ACCESS_DENIED("30002")` 被否决（spec §7 是错误码权威清单，擅自扩充会让 spec 与实际不符）
- 排除 `UserDetailsServiceAutoConfiguration` 消除 WARN 被否决（该默认用户不可利用；属 YAGNI）
- `FilterRegistrationBean#setEnabled(false)` 消除 Filter 双注册被否决（超出范围，当前行为正确）
- `mergeChunk` 加状态门控被否决（行为变更、无测试覆盖、可能破坏合法重传；改为只在 Javadoc 里标注缺口）——**代价必须写全**：缺口仍可利用，对已 COMPLETED 文件的覆盖会把别人已被全体用户检索到的正文整体替换
- 给 `t_chat_message` 加 `user_id` 被否决（冗余，会让热路径多一层无意义过滤）——**并明确写出「不建议后续为『看起来更安全』而随手加列」**
- 迁移脚本包 `BEGIN/COMMIT` 被否决（`ON_ERROR_STOP=1` 已保证无残缺态，全部语句幂等可重跑）
- 登录成功后回跳原目标页被否决（属功能增强而非缺陷）
- 用 `await` 或空 `catch` 消除 `onopen` 的 unhandled rejection 被否决——**理由要写清**：加了 `await` 会让异常落进 `catch` 块，把用户消息改写成「抱歉，请求出错了」，**掩盖「已跳转登录页」的事实并给出不准确文案**
- 补 `application-prod.yml` 的 `auth.jwt.*` 配置被否决（非本次引入，该 profile 本就缺数据源配置）
- 用 `git add -f` 强推 `CLAUDE.md` 被否决（用户此前的明确决定，强推不可逆）
- 不做自动回滚（`cicd-design.md` §11）
- 不做注册 / refresh token / 黑名单 / RBAC / 限流（`auth-design.md` §9 的五条 YAGNI）
- 不给 `uploader_id` 建索引（列表全局共享无用户过滤）；不给 `t_chat_message` 加 `user_id`
- 用 `.accept(MediaType.ALL)` 或改接口 `produces` 解决 SSE 406（明确规定不得改 `produces`）

**注意**：`progress.md` 的 Ruling 里有一部分是纯粹的施工裁定（如某条 SQL 要不要加事务包装），与「技术选型」无关。**只收有技术含金量的**，施工细节丢弃。判断标准：这个决策换一个团队来做，会不会做得不一样？会 → 收。

- [ ] **Step 1: 核对来源**

读取主目录 `.superpowers/sdd/2026-09-12-auth-plan/progress.md` 全文（528 行，重点读 Ruling 1–24 与最终评审段），以及 `docs/specs/` 四份的 YAGNI / 取舍节。

- [ ] **Step 2: 撰写 `docs/decisions.md`**

按上述两块、逐条三段式撰写。

- [ ] **Step 3: 校验**

- 逐条对照 `progress.md`，确认没有把「被否决」误写成「已采纳」（这是本文档最容易犯的错，因为否决理由往往写得很像结论）。
- 确认第一块的每条决策都能在 Task 3–6 的模块文档里找到对应的实现描述。
- 确认无 YAGNI 条目遗漏（五条：注册、refresh token、黑名单、RBAC、限流）。

- [ ] **Step 4: 提交**

```bash
git add docs/decisions.md
git commit -m "docs(decisions): 新增选型理由与被否决方案"
```

---

### Task 8: docs/pitfalls.md

**Files:**
- Create: `docs/pitfalls.md`

**来源:**
- Consumes: `.superpowers/sdd/` 各 report 的「顾虑」「范围外观察」节；`final-fix-report.md`；`kb-rename/implementer-report.md` 第五节；`OPTIMIZATION-PLAN.md` §2.5
- Produces: 无下游依赖

**内容要点：**

**只收跨模块与环境级的坑**——判断标准是「复现它是否需要理解多个模块」。模块内的坑已在 Task 3–6 就近写入对应文档，此处**不重复**（Spec §9.4 已记录这条边界的主观性）。

每条按「现象 → 原因 → 怎么避免」三段写。至少覆盖：

1. **JDK 17 与 Lombok。** 本机 `JAVA_HOME` 默认是 JDK 23，Lombok 1.18.30 最高只支持 JDK 21，直接 `mvn` 会以「几十个编译错误」的形式失败，报错全是 Lombok 生成的方法找不到符号（`log`、`builder()`、`setXxx()`），**看起来像包名问题但不是**。（注：镜像构建不受影响，Dockerfile 内用 temurin-17。）
2. **Lombok 假诊断与编译失败的级联。** IDE 报大量「找不到符号」有两类成因：IDEA 未处理注解处理器（假诊断，`mvn` 全程成功），以及接口名与文件名不匹配导致**注解处理整体中断**（真错误）。后者会报出 80 行级联错误，**表象与前者高度相似、极易误导排查方向**。
3. **multipart 大小三处必须同步。** 前端 `CHUNK_SIZE` / 后端 `spring.servlet.multipart.max-file-size` / nginx `client_max_body_size`。不配就走默认 1MB，2MB 分片被 Tomcat 在**进入 Controller 之前**拒掉，日志里什么都没有或只有 `FileSizeLimitExceededException`，排查方向会被完全带偏。
4. **8080 端口被旧实例占用。** 表现为「带了 token 仍返回 30001 / 登录失败」，极易误判成改造没生效。排查时先确认 8080 上跑的是不是当前构建。
5. **Maven 本地仓库位置非默认。** 由 `settings.xml` 指向别处，不是 `~/.m2/repository`（后者只有旧版本 jar，易误判成依赖未解析）。
6. **Flyway checksum。** 改动已执行过的迁移脚本会让应用在已有库上直接启动失败。要改结构就新增版本号。
7. **`ALTER TABLE ADD COLUMN` 只能把新列追加到表尾。** 迁移路径与全新部署路径的列序必然不同，比较两边结构时必须**按集合而非列序**。
8. **schema 收敛性的方法论教训。** 只比 `information_schema.columns` 的四个字段就下 `IDENTICAL` 结论是过度断言——真实差异恰落在没被 diff 的那一维（列注释）。`COMMENT ON COLUMN` 写进 `pg_description`，**注释分叉即 schema 分叉**。
9. **手工建的旧库不跑迁移脚本会抛 `BadSqlGrammarException`。** MyBatis-Plus 生成的列清单含新列，而旧库没有——排查时第一个该看这里。
10. **`ALTER TABLE ... RENAME` 不会重命名序列与主键约束。** 库里仍是旧名，功能无影响（`nextval` 按 OID 绑定），但既有库与全新初始化的库内部对象名会不一致。

**注意**：这些坑的共同特征是「现象指向的方向与真实原因相反」。写的时候**现象描述要足够具体**（具体的报错文本、具体的表现），否则读者无法在遇到时认出它。

- [ ] **Step 1: 核对来源**

读取主目录 `.superpowers/sdd/2026-09-12-auth-plan/` 下的 `task-*-report.md`（重点读「顾虑」「范围外观察」节）、`final-fix-report.md`、`2026-09-13-kb-rename/implementer-report.md` 第五节，以及 `OPTIMIZATION-PLAN.md` §2.5。

- [ ] **Step 2: 撰写 `docs/pitfalls.md`**

按上述十类撰写，每条三段式。

- [ ] **Step 3: 校验**

- 逐条检查是否**与 Task 3–6 的模块文档重复**。重复的删掉，只保留模块内版本。
- 确认每条的现象描述包含**可识别的特征**（具体报错文本或具体表现），而非泛泛的「会出错」。
- 向用户确认：JDK 路径、Maven 仓库路径这类**本机环境事实**是否要写进公开文档。它们是「本机一次性事实」还是「项目约定」，界线由用户定。

- [ ] **Step 4: 提交**

```bash
git add docs/pitfalls.md
git commit -m "docs(pitfalls): 新增跨模块与环境级坑点"
```

---

### Task 9: docs/deployment.md

**Files:**
- Create: `docs/deployment.md`

**来源:**
- Consumes: `docs/specs/2026-09-13-cicd-design.md`；`.github/workflows/ci.yml`、`deploy.yml`；`scripts/deploy.sh`；根目录 `docker-compose.yml`、`.env.example`；`.gitignore`
- Produces: `docs/getting-started.md` 会引用本文的密钥配置部分

**内容要点：**

1. **编排总览。** 四个服务：`postgres`（pgvector 镜像）、`searxng`、`app`、`web`（nginx 托管前端 + 反代 `/api`）。一条命令起全套；只想跑依赖则 `docker compose up -d postgres searxng`。
2. **容器里没有第二份 profile 配置。** `spring.profiles.active` 仍是 `dev`，容器跑的就是 dev profile。容器与宿主机的差异只有两处 host，用环境变量覆盖（relaxed binding）：
   - 数据源：`SPRING_DATASOURCE_URL`
   - SearXNG：`SEARXNG_URL`
   - **不要改成单开一份 `application-docker.yml`**（配置会拆成两份，改一处漏一处是必然的）
3. **密钥管理。** `DASHSCOPE_API_KEY` / `JWT_SECRET` 从根目录 `.env` 注入；compose 里写 `${DASHSCOPE_API_KEY:?...}`——没设置就直接报错退出，不会静默用 yml 里的占位符启动出一个每次问答都失败的容器。`JWT_SECRET` **刻意不在 compose 里传**，让 `application-dev.yml` 的默认值生效（本地开箱即用）——**部署到公网前必须在 `.env` 里覆盖，且不少于 32 字节**。
4. **生产 profile 为什么是安全事故级的必选项。** dev 的 flyway locations 含 `db/dev-migration`，会把两个演示账号（密码可公开推知）插进生产库；dev 的 JWT 默认密钥是公开的，等于公开发布登录凭证。这两条要写足。
5. **三处只有真跑起来才会暴露的 nginx 配置。** 每条写清「不配会怎样」：
   - `client_max_body_size 5m`（默认 1MB，而前端分片是 2MB；不放开则分片被 nginx 直接回 413，**后端日志里什么都没有**）
   - `proxy_buffering off` + `proxy_set_header Connection ""`（不关缓冲则 SSE 响应被攒够一批再发，表现为「前端一直转圈、最后一次性吐出全部内容」——**功能看着正常，极易误判成前端问题**）；`proxy_read_timeout` 也要放大，默认 60s 会在长回答中途掐断
   - 数据卷 `doc-qa-app-data:/app/data`（不挂卷则容器重建后已上传文件全没，而数据库记录还在——文件状态显示已完成，检索却什么都查不到）
6. **CI。** 三个 job：`backend`（带 pgvector service container，跑全量测试）、`frontend`（构建）、`images`（只在 main 上推 GHCR）。补一句副作用收益：CI 的库是空白的，Flyway 会在测试启动时从零建表，**等于每次 push 都把三份迁移脚本在干净环境里验一遍**。
7. **CD。** 手动触发（刻意不挂 push，避免写到一半的提交直接上生产）；用 git sha 而非 latest 做 tag（sha 不可变，latest 只用来标记「最新是哪个」）；**回滚方式就是填一个旧的 sha 重跑**——这是成本最低的回滚，不需要额外机制；`deploy.sh` 的四条防呆：
   - 绝不 `down -v`（会连数据卷一起删）
   - 按 OCI label 精确过滤清理旧镜像（不能 `docker image prune -a`，同机还有别的项目）
   - 健康检查（不做则「容器起来了但其实是坏的」会显示成部署成功）
   - 前置校验缺什么就明确报错，不带半截配置往下跑
8. **密钥非空校验为什么在 deploy.sh 里而不在配置里。** `docker-compose.yml` 为了让本地开箱即用给 `JWT_SECRET` 提供了开发默认值（不能写成 `${JWT_SECRET:-}`，那会传入空串，jjwt 抛 `WeakKeyException` 启动即失败），于是「生产必须有真实密钥」这条保证只能由脚本承担。

**注意**：第 3、4、8 点是一条完整的因果链（本地开箱即用 ↔ 生产必须覆盖 ↔ 校验只能放脚本里）。分开写会让人误以为可以简化其中一环。

- [ ] **Step 1: 核对来源**

读取 `docs/specs/2026-09-13-cicd-design.md`、`.github/workflows/ci.yml`、`.github/workflows/deploy.yml`、`scripts/deploy.sh`、`docker-compose.yml`、`.env.example`、`doc-qa-web/nginx.conf`。

- [ ] **Step 2: 撰写 `docs/deployment.md`**

按上述八个要点撰写。

- [ ] **Step 3: 校验**

- 对照 `docker-compose.yml` 确认四个服务名、环境变量名、端口映射的实际值。
- 对照 `deploy.sh` 确认四条防呆的实际实现（特别是 OCI label 过滤的完整字符串）。
- 对照 `ci.yml` 确认三个 job 名与触发条件。
- 对照 `nginx.conf` 确认三个配置项的实际值。
- 检查文中所有文件引用路径存在。

- [ ] **Step 4: 提交**

```bash
git add docs/deployment.md
git commit -m "docs(deployment): 新增部署与运维说明"
```

---

### Task 10: docs/getting-started.md

**Files:**
- Create: `docs/getting-started.md`

**来源:**
- Consumes: 主目录 `CLAUDE.md` 的「常用命令」「外部依赖」两节；`docker-compose.yml`；`application-dev.yml` 的配置项；Task 9 的 `deployment.md`
- Produces: 无下游依赖，但它是验收标准 §8.1 的承载者

**内容要点：**

1. **前置依赖。** 三样：JDK 17（**注意 `JAVA_HOME` 必须指向它**，理由指向 `pitfalls.md`）、Docker、`DASHSCOPE_API_KEY`。
2. **起依赖。** `docker compose up -d postgres searxng`——并明确说明**不加服务名会把 app / web 一起拉起**（若只想跑依赖、应用在 IDE 里跑，必须加服务名）。
3. **起后端。** `mvn spring-boot:run`，并给出 `JAVA_HOME` 的正确写法（宿主机构建必须指向 JDK 17）。说明建表**不需要任何手工步骤**——Flyway 在启动时自动执行迁移。
4. **起前端。** `npm install` + `npm run dev`，`/api` 代理到 `localhost:8080`。
5. **验证。** 登录（dev 环境的演示账号来自 `db/dev-migration/V3`）→ 发一次问答 → 观察流式输出。若有条件，再验证一次知识库上传。
6. **常见起不来。** 四条：端口占用（8080 / 8889 / 80）、密钥未配（表现为每次问答都失败）、Flyway 校验失败（改动过已执行的迁移脚本）、multipart 大小不一致（表现为「checkFile 说需上传、一点上传就报错」）。

**注意**：本文的验收方式是**真的照着做一遍**，或至少在 mind 中逐步核对每条命令的参数正确。这是唯一一份「照做失败就等于写错」的文档。

- [ ] **Step 1: 核对来源**

读取主目录 `CLAUDE.md` 的「常用命令」「外部依赖」两节、`docker-compose.yml`、`doc-qa-api/src/main/resources/application-dev.yml`、`doc-qa-api/src/main/resources/db/dev-migration/V3__seed_demo_users.sql`（确认演示账号的用户名）。

- [ ] **Step 2: 撰写 `docs/getting-started.md`**

按上述六个要点撰写。

- [ ] **Step 3: 校验**

- 逐条命令核对参数：`mvn` 命令的工作目录、`docker compose` 的服务名、前端命令的工作目录。
- 确认演示账号信息与 `V3__seed_demo_users.sql` 一致。**密码不得在文档里写明**（该脚本在公开仓库里，但文档直接写出明文密码会放大风险）——写「见 `db/dev-migration/V3__seed_demo_users.sql`」即可。
- 确认「常见起不来」四条里的现象描述与 `pitfalls.md` 一致，不打架。
- 确认文中的 `JAVA_HOME` 示例路径是**通用写法**而非本机绝对路径（如用 `D:/IDEAjava/JDK/jdk17` 这类本机路径，会让其他机器上的读者照抄失败）。

- [ ] **Step 4: 提交**

```bash
git add docs/getting-started.md
git commit -m "docs(getting-started): 新增本地启动指南"
```

---

### Task 11: rag-evaluation 目录整理

**Files:**
- Create: `docs/rag-evaluation/README.md`
- Move: `docs/rag-evaluation.md` → `docs/rag-evaluation/report.md`
- Modify: `docs/README.md`（更新链接）
- 评估（可能 Modify）: `docs/rag-evaluation/results/end-to-end-results.md`

**来源:**
- Consumes: `docs/rag-evaluation.md` 全文、`doc-qa-api/src/test/java/io/github/renhaowan/docqa/rageval/` 下的测试类（确认复跑方式）
- Produces: 无下游依赖

**关于 `rag-evaluation.md` 的改名与移动：**

Spec §2 的目录树写的是 `rag-evaluation/rag-evaluation.md`，此处**做一处细节调整**：改名为 `report.md`。理由：目录名与文件名重复（`rag-evaluation/rag-evaluation.md`）读起来冗余，且该文件在目录内的角色就是「报告」。执行时需同步更新 `docs/README.md` 中指向它的链接。

**关于 `end-to-end-results.md` 的瘦身：**

该文件 1109 行 / 62 KB，其中约 1050 行是模型原始回答（可再生成）。**先评估再决定**，不做默认动作：

- 保留：前约 55 行的汇总段（总正确率、分类别、逐题对错表）——它是 `report.md` §5.1 的数据源与复核入口
- 待定：文末约 1050 行原始回答。`report.md` §5.2 明确引用它作为「每一句结论可逐条复核」的依据；但作为长期文档几乎不会被读

**决策权交给用户**，给出两个选项与各自代价，不擅自删除。

**`docs/rag-evaluation/README.md` 内容要点：**

1. 这个目录是什么：RAG 检索质量的评估材料，三部分——报告（`report.md`）、金标准问题集（`questions.tsv`）、原始结果（`results/`）。
2. 语料（`corpus/` 下三份公开中文公文）为什么是这三份：刻意代表「结构良好 / 局部结构失衡 / 整体结构扁平」三种文档形态。
3. **怎么复跑**：两个评估测试类受 `@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true")` 门控，默认跳过（因为会真调模型 API）。给出设置环境变量后运行的具体命令。
4. **引用口径提醒**：`report.md` §5.1 的 63.6%（裸模型正确率）是**人工复核口径**，而 `results/end-to-end-results.md` 里只有自动判分口径（77.3%）。**引用时请用人工复核口径**——63.6% 这个数无法从原始结果文件复现。
5. 一条已知局限的指引：`questions.tsv` 的 `keys` 列用 key phrase 文本匹配而非 chunk ID，是为了跨切分策略可比——这个约定不要改。

- [ ] **Step 1: 核对来源**

读取 `docs/rag-evaluation.md` 全文、`RagRetrievalEvaluationTests.java` 与 `EndToEndEvaluationTests.java`（确认 `@EnabledIfEnvironmentVariable` 的实际写法与运行方式）、`QuestionSetSelfCheckTests.java`。

- [ ] **Step 2: 移动并改名**

```bash
git mv docs/rag-evaluation.md docs/rag-evaluation/report.md
```

- [ ] **Step 3: 撰写 `docs/rag-evaluation/README.md`**

按上述五个要点撰写。

- [ ] **Step 4: 更新 `docs/README.md` 的链接**

把指向 `rag-evaluation.md` 的链接改为 `rag-evaluation/report.md`。

- [ ] **Step 5: 校验**

- 确认 `docs/` 下已无孤立的 `rag-evaluation.md`，且所有指向它的链接都已更新（全仓库搜索该文件名）。
- 确认新增 README 里的复跑命令与实际测试类的门控写法一致。
- 把 `end-to-end-results.md` 的瘦身选项与代价呈现给用户，等待决定后再动手（若决定瘦身，在同一提交内完成）。

- [ ] **Step 6: 提交**

```bash
git add docs/rag-evaluation/ docs/README.md
git commit -m "docs(rag-evaluation): 补目录索引并整理报告位置"
```

---

### Task 12: 修正 doc-qa-web/README.md

**Files:**
- Modify: `doc-qa-web/README.md`

**来源:**
- Consumes: `doc-qa-web/vite.config.js`、`src/views/` 下的实际文件清单、`src/stores/`、`src/router/index.js`
- Produces: 无下游依赖

**内容要点：**

本 Task 对应 Spec §8.2「无过时」中的第一处，也是 Spec §3 职责表之外的一份（它属前端应用而非 `docs/`）。该文件是仓库内**唯一一份面向使用者的入口文档**，且含实打实的错误信息。三处修正：

1. **SSE 说明完全反了。** 原文称流式接口「直连 `http://localhost:8080`，不经过 Vite 的 `/api` 代理，换端口需同步修改 `src/views/` 下的调用地址」。实际在鉴权改造中已改为相对路径 `/api/chat/completion` 与 `/api/knowledge-base/completion`，**不再硬编码** `localhost:8080`（该地址只存在于 `vite.config.js` 的 proxy 配置里，换端口只改一处）。
2. **页面清单引用不存在的文件。** 原文列 `Index` / `ChatPage` / `CustomerServiceChatPage`；实际 `src/views/` 下是 `ChatPage.vue` / `Index.vue` / `KnowledgeBaseChatPage.vue` / `LoginPage.vue`。`CustomerServiceChatPage` **已不存在**，且 `LoginPage.vue` 未列入。
3. **stores 说明过期。** 实际有 `authStore.js` + `chatStore.js`，原文完全未提登录态持久化与路由守卫。

另删除末尾的「IDE 建议 / VS Code + Volar（并禁用 Vetur）」段——那是 `npm create vue` 脚手架生成的默认段落，与本项目无关。

**注意**：本文档的定位是「前端应用的开发说明」，不是项目总入口。修正后应在开头补一句指向 `../docs/README.md`，让读者知道项目级文档在哪。

- [ ] **Step 1: 核对来源**

读取 `doc-qa-web/src/views/` 与 `src/stores/` 的实际文件清单、`vite.config.js` 的 proxy 配置、`src/router/index.js` 的路由表。

- [ ] **Step 2: 修正 `doc-qa-web/README.md`**

按上述三处修正 + 删除脚手架残留段 + 补指向 `../docs/README.md` 的链接。

- [ ] **Step 3: 校验**

- 逐个核对页面清单与实际文件一致（用 `git ls-files doc-qa-web/src/views/`）。
- 确认 SSE 段落描述的行为与 `ChatPage.vue` / `KnowledgeBaseChatPage.vue` 里实际的请求地址一致。
- 确认 mock 掉的风险：文中不得再出现「换端口需改两处」这类已失效的警告。

- [ ] **Step 4: 提交**

```bash
git add doc-qa-web/README.md
git commit -m "docs(web): 修正 SSE 说明与页面清单"
```

---

### Task 13: 瘦身 CLAUDE.md

**Files:**
- Modify: `D:/projects/doc-qa-service/CLAUDE.md`（**注意：不在本 worktree 内**）

**⚠️ 本 Task 无法在 worktree 内完成。** `CLAUDE.md` 被 `.gitignore` 排除（见 `.gitignore` 末段「内部工作文档（不进入公开仓库）」），它只存在于主工作目录 `D:/projects/doc-qa-service/`，本 worktree 读取不到，写回也受 worktree 隔离限制。

**执行时机**：等本重构分支合并回 `main` 之后，在主工作目录操作。**不要提前做**——瘦身要去掉的是「已迁入 `docs/` 的内容」，而 `docs/` 尚未合回 main 时提前瘦身，会造成中间态的知识真空。

**来源:**
- Consumes: Task 1–12 产出的全部 `docs/` 文档（作为「已迁出」的清单）；主目录 `CLAUDE.md` 全文
- Produces: 无

**内容要点：**

按 Spec §5 的三条规则执行：

- **保留**：构建命令的坑（`JAVA_HOME` 必须指 JDK 17、`revision` 属性不能为空）、代码约定（`Response` 包装、VO 分包、中文注释、`@ApiOperationLog`）、约 40 条「改代码时的硬约束」⚠️（每条保留简短理由），以及新增的 docs 索引表。
- **移出**：Advisor 链的完整叙述、SSE 协议说明、文件上传链路详解、Docker 化整节。
- **判断标准**：这条信息**会不会影响「现在正在写的这行代码」**。会 → 留下；只是「读起来更懂」→ 移走。
- **`.gitignore` 不动**：`CLAUDE.md` 继续不进公开仓库。

**注意**：这是整个重构里**唯一有回归风险**的一步。移走的内容如果 AI（下一次会话里的我）改代码时看不到，就可能重蹈覆辙——比如把 `doFinally` 改回 `doOnComplete`。所以移出的每条 ⚠️ 约束，在 `docs/modules/*.md` 里必须有对应段落，且 CLAUDE.md 里要留下**指向它的索引**（「落库时机的完整成因见 docs/modules/chat.md」），而不是无声消失。

- [ ] **Step 1: 确认前置条件**

确认本重构分支已合并回 `main`，且 `docs/` 下全部文档已就位。**未满足则停止，不要执行。**

- [ ] **Step 2: 逐条建立映射**

对照 `docs/` 下的文档，为 CLAUDE.md 里每条待移出的内容建立「原文位置 → docs 中的落点」映射表。**任何找不到落点的内容一律不移除**。

- [ ] **Step 3: 改写 `D:/projects/doc-qa-service/CLAUDE.md`**

按三条规则执行，末尾追加 docs 索引表。

- [ ] **Step 4: 校验**

- 确认每一条被移出的 ⚠️ 硬约束在 `docs/` 下有对应段落（对照 Step 2 的映射表）。
- 确认被保留的构建命令、代码约定完整无损。
- 确认新增的索引表链接在 `docs/` 下有效。

- [ ] **Step 5: 不提交**

该文件被 gitignore，`git add` 不适用。改动直接落地在文件系统上。

---

### Task 14: 删除旧 spec 与收尾

**Files:**
- Delete: `docs/specs/2026-09-12-auth-design.md`
- Delete: `docs/specs/2026-09-12-auth-plan.md`
- Delete: `docs/specs/2026-09-13-cicd-design.md`
- Delete: `docs/specs/2026-09-13-cicd-plan.md`
- Delete: `docs/specs/2026-09-13-docs-reorganize-design.md`
- Delete: `docs/specs/2026-09-13-docs-reorganize-plan.md`（本文件）
- Modify: `docs/README.md`（把「待补」状态全部改为已完成）

**⚠️ 删除是不可逆的（在本次分支上下文中）。执行前必须完成 Step 1–3 的确认。**

**来源:**
- Consumes: Task 1–12 的全部产出
- Produces: 无——这是最后一步

- [ ] **Step 1: 确认提炼已完成**

逐份确认四份旧 spec 的内容已落位：

| 旧文档 | 应落位于 |
|---|---|
| `2026-09-12-auth-design.md` | `docs/modules/auth.md`（§4–§5、§7）、`docs/decisions.md`（§9 的五条 YAGNI） |
| `2026-09-12-auth-plan.md` | `docs/modules/auth.md`（散落的坑点）、`docs/pitfalls.md`、`docs/decisions.md` |
| `2026-09-13-cicd-design.md` | `docs/deployment.md`（§4、§6–§8、§11） |
| `2026-09-13-cicd-plan.md` | `docs/deployment.md`（自查记录里的两处有意偏差） |

**任何一份没有明确落点的，先补再删。**

- [ ] **Step 2: 确认无内容丢失**

对照 `progress.md` 的 Ruling 1–24 逐条确认 `decisions.md` 已覆盖（这是验收标准 §8.3）。

- [ ] **Step 3: 删除**

```bash
git rm docs/specs/2026-09-12-auth-design.md docs/specs/2026-09-12-auth-plan.md
git rm docs/specs/2026-09-13-cicd-design.md docs/specs/2026-09-13-cicd-plan.md
git rm docs/specs/2026-09-13-docs-reorganize-design.md docs/specs/2026-09-13-docs-reorganize-plan.md
```

- [ ] **Step 4: 更新 `docs/README.md`**

把所有「待补」状态改为已完成，确认文档地图与实际文件一一对应。

- [ ] **Step 5: 全量验收**

逐条核对 Spec §8 的五条验收标准：

1. **可跑通**：仅照 `getting-started.md` 操作能起全套服务并完成一次问答（至少逐步核对命令参数）。
2. **无过时**：§1.1 的三处过时内容已修正；文档中对代码的引用（文件路径、类名、方法名）均经核对存在。
3. **无丢失**：`decisions.md` 覆盖 Ruling 1–24；`.superpowers/sdd/` 中判定含可保留知识的材料其要点均已落位。
4. **无残留**：`docs/specs/` 目录不再存在；`docs/` 下无 task brief / report / progress / diff 类文件。
5. **有入口**：`docs/README.md` 的地图与阅读路径自洽，所有链接可达。

- [ ] **Step 6: 提交**

```bash
git add -A docs/
git commit -m "docs: 完成文档重构收尾，退役 specs 目录"
```
