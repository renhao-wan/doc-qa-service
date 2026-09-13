# 项目文档重构设计

> 日期：2026-09-13
> 状态：已评审，待执行
> 生命周期：本文档是本次重构的设计记录，**重构收尾时与其他四份 spec 一并删除**（见 §7 第 3 步）。重构完成后，`docs/specs/` 目录整体退役。

---

## 1. 背景与目标

### 1.1 现状

项目知识分散在五处，形态与可访问性各不相同：

| 位置 | 内容 | 体量 | 在 git 里 |
|---|---|---|---|
| 根目录 `CLAUDE.md` | 架构说明、代码约定、约 40 条改代码时的硬约束 | 长篇 | ❌ `.gitignore` 排除 |
| 根目录 `OPTIMIZATION-PLAN.md` | 优化清单、坑点分析、选型理由 | 368 行 | ❌ `.gitignore` 排除 |
| `docs/specs/` | auth 与 cicd 各一份 design + 一份 plan | 约 190 KB | ✅ |
| `docs/rag-evaluation/` | RAG 检索质量评估：报告、语料、原始数据 | 约 100 KB | ✅ |
| `.superpowers/sdd/` | SDD 执行的决策台账与过程记录 | 39 文件 / 898 KB | ❌ 子目录 `.gitignore` 为 `*` |

四类问题：

**① 知识分散，且最关键的部分最易失。** 架构说明在 `CLAUDE.md`（本机私有），设计决策在 `docs/specs/`（公开），而**最能体现判断力的部分——被否决的方案及理由、实现过程中的取舍——大量只存在于 `.superpowers/sdd/progress.md` 的评审记录里**。该目录被 `*` 完全排除在版本控制之外，换机器或重装系统即永久丢失。

**② 大量逐字重复。** `auth-design.md`（262 行）与 `auth-plan.md`（2876 行 / 129 KB）成段重复：建表 DDL、索引设计说明、错误码表、越权论证各出现两到三次。cicd 两份是同一模式，`deploy.sh` 全文在 design 与 plan 中各出现一次。

**③ 部分内容已过时，且会实际误导读者：**

- `doc-qa-web/README.md` 称 SSE「直连 `http://localhost:8080`，不经过 Vite 代理」——实际在鉴权改造中已改为 `/api` 相对路径；页面清单里还列着已不存在的 `CustomerServiceChatPage`。
- `auth-design.md` §3.4 的数据库迁移方案（改 `db/init/01-schema.sql`、新建 `db/upgrade/2026-09-12-auth.sql`）——两个目录均已不存在，Flyway 已接管 schema 演进。**照该文档执行会直接失败。**
- `cicd-design.md` §8.5 称 SearXNG 引擎列表硬编码在 `SearXNGServiceImpl.java` 中、需改 Java 代码——实际已改为从配置读取，且线上使用的引擎集合与该文档推荐的不同。

**④ 没有入口。** `docs/` 下没有 `README.md`，读者无从知道有哪些文档、按什么顺序读。

### 1.2 目标

产出一套按模块组织的技术文档，验收标准是一句话：

> **读文档就知道项目怎么跑，并能接住面试对实现细节的追问。**

具体拆成三条：

1. 一个新人（或三个月后的作者自己）能照着 `getting-started.md` 把项目跑起来。
2. 任意模块的实现细节、设计理由、已知坑点，能在**一份文档内**读全。
3. 每一个技术论断都能对照当前代码验证，不存在「文档这么说、代码那么写」的情况。

### 1.3 范围外

- **简历包装、面试话术、求职规划**类内容：不整理、不迁入 `docs/`，继续留在 `OPTIMIZATION-PLAN.md` 原文中。
- **代码修改**：本次不改任何代码。盘点中发现的既有缺陷（`mergeChunk` 可改写他人既有记录、前端 `onopen` 兜底缺失、`uploaded_chunks` 是只写不读的死列等）**只记录进文档**，修复另开任务。
- **开发日志**：task brief / task report / progress / review diff 这类过程记录不进入 `docs/`。

---

## 2. 目标结构

```
docs/
├── README.md                文档地图 + 两条阅读路径
├── getting-started.md       本地跑起来
├── architecture.md          总览：模块划分、数据流
├── modules/
│   ├── chat.md              对话链路
│   ├── knowledge-base.md    知识库链路
│   ├── auth.md              鉴权与数据隔离
│   └── data.md              数据层、Flyway、索引设计
├── decisions.md             选型理由 + 被否决的方案
├── pitfalls.md              跨模块与环境级坑点
├── deployment.md            部署与运维
└── rag-evaluation/          实验报告（保留）
    ├── README.md            复跑说明（新增）
    ├── rag-evaluation.md
    ├── questions.tsv
    ├── corpus/
    └── results/
```

**取消 `docs/specs/` 目录。** 四份 spec 的内容全部提炼进上述文档后删除原文，不设 `archive/` 目录——归档等于把 2876 行的重复和过时内容换个目录名继续堆着，与"整理"相悖。追溯设计演进的角色由两处承担：`decisions.md` 记录「原本选什么、因为什么改成什么」，以及 git 历史本身（四份 spec 均已入库，`git log` 随时可挖）。

---

## 3. 各文档职责与来源映射

| 文档 | 讲什么 | 主要知识来源 |
|---|---|---|
| `README.md` | 项目一句话定位；文档地图（每份讲什么、什么时候该读）；两条阅读路径：**想跑起来** 与 **想搞懂架构** | 新写 |
| `getting-started.md` | 前置依赖（JDK 17、Docker、`DASHSCOPE_API_KEY`）→ 起依赖 → 起后端 → 起前端 → 验证（登录 + 发一次问答）；常见起不来：端口占用、密钥未配、Flyway 校验失败 | `CLAUDE.md`「常用命令」「外部依赖」 |
| `architecture.md` | 项目定位与边界；仓库结构（两个应用 + 编排）；模块划分图；**一次请求的完整数据流**（对话入口与知识库入口各走一遍）；技术栈一览（详细理由指向 `decisions.md`） | `CLAUDE.md`「仓库结构」+ 两个 Controller |
| `modules/chat.md` | 请求级 ChatClient（为什么不注入共享 Bean）；Advisor 链：四个 advisor、order、装配规则、记忆与联网二选一；`CustomChatMemoryAdvisor` 与 50 条限制；联网搜索链路与双线程池参数；SSE 流式协议（`AIResponse` 双字段）；**落库时机：`doFinally` 三信号与 `touchUpdateTime`** | `CLAUDE.md` 后端架构 + `progress.md` Ruling + task-4/5/8 report |
| `modules/knowledge-base.md` | 完整链路（`checkFile` → `uploadChunk` → `mergeChunk` → 事件 → 向量化）；文件状态机；秒传与断点续传；`ON CONFLICT DO NOTHING` 的幂等语义；`MarkdownReader` 的切分语义（标题才是主切分点、标题文本不进正文）；向量覆盖式重建（含跨文件去重的历史教训）；RAG 检索与 topK=3 的依据；Function Calling 联网兜底与双模板陷阱 | `CLAUDE.md` 上传链路 + `progress.md` Ruling 20–23 + `final-fix-report` |
| `modules/auth.md` | 无状态 JWT 与过滤器链；**HTTP 200 + `success:false`** 的业务约定及两个 handler；SSE ASYNC dispatch 导致身份丢失的完整成因；两条防越权路径的**刻意对照**（对话走「查不到」、知识库走「无权操作」）；前端 `authStore`、axios 响应拦截器查响应体、SSE 走 Vite proxy | `auth-design.md` §4–§5 + `progress.md` Ruling 1/2/14 + `task-9-brief` 的架构节 |
| `modules/data.md` | 七张表与职责；索引为什么用 `id DESC` 而非 `create_time`；时间列为何必须是 `TIMESTAMP` 而非 `TIMESTAMPTZ`；Flyway 的 locations / baseline-on-migrate / initialize-schema 三项配置各自的理由；唯一索引不约束 NULL 与 `NOT NULL` 的配合 | `CLAUDE.md`「数据层」「数据库迁移」 |
| `decisions.md` | 两块：**为什么这么做**（如为什么手写 ChatMemory 而不用内置、为什么 topK 固定为 3、为什么用 `RequestAttributeSecurityContextRepository`）；**为什么不那么做**（被否决的方案及代价，如不加 `t_chat_message.user_id`、不做自动回滚、不做 refresh token） | `.superpowers/sdd/progress.md` 的 Ruling 1–24 + 四份 spec 的 YAGNI 节 + `OPTIMIZATION-PLAN.md` 的取舍分析 |
| `pitfalls.md` | **只收跨模块与环境级**的坑：JDK 17 与 Lombok、multipart 大小三处必须同步、端口占用误判、Lombok 假诊断与编译失败级联、Maven 仓库位置非默认、Flyway checksum。每条按「现象 → 原因 → 怎么避免」写。**模块内的坑就近写在对应模块文档里**，不在此重复 | `.superpowers` 各 report 的「顾虑」节 + `OPTIMIZATION-PLAN.md` §2.5 |
| `deployment.md` | 容器与宿主机的三处 host 差异（为何用环境变量覆盖而非另开 profile）；密钥管理与「`JWT_SECRET` 为何不在 compose 里」；CI 三个 job；CD 手动触发、sha tag 与回滚方式；`deploy.sh` 的四条防呆（不删数据卷、按 OCI label 清镜像、健康检查、前置校验）；prod profile 的安全理由 | `cicd-design.md` + `ci.yml` + `deploy.yml` + `deploy.sh` |
| `rag-evaluation/` | 现有报告、语料、原始数据整体保留；**新增 `README.md`** 说明评估目的与复跑方式（`RAG_EVAL` 环境变量） | 现成内容，仅补索引 |

---

## 4. 内容准确性规则

文档重构最容易翻车的地方是「照着旧文档抄，把旧文档的错误一并继承」。三条规则：

1. **技术论断一律以当前代码为准。** §1.1 中列出的三处过时内容为必改项；每份文档落笔前核对其涉及的文件与符号确实存在。
2. **无法从代码验证的内容**（「当时为什么这么决定」）标注依据来自评审记录，不冒充客观事实。
3. **引用的文件路径与行号必须有效。** 优先写「文件 + 符号名」，行号只在不常改动的文件上使用——行号随代码漂移，是文档腐坏最快的一环。

---

## 5. CLAUDE.md 瘦身规则

`CLAUDE.md` 必须留在仓库根目录（Claude Code 靠它建立每次会话的工作上下文），因此不搬家，只瘦身。

- **保留**：构建命令的坑（`JAVA_HOME` 必须指 JDK 17、`revision` 属性不能为空）、代码约定（`Response` 包装、VO 分包、中文注释、`@ApiOperationLog`）、约 40 条「改代码时的硬约束」⚠️（每条保留简短理由），以及新增的 docs 索引表。
- **移出**：Advisor 链的完整叙述、SSE 协议说明、文件上传链路详解、Docker 化整节——这些进 `docs/modules/` 与 `docs/deployment.md`。
- **判断标准**：这条信息**会不会影响「现在正在写的这行代码」**。会 → 留下；只是「读起来更懂」→ 移走。
- **`.gitignore` 不动**：`CLAUDE.md` 继续不进公开仓库。这是此前的明确决定，改它不可逆。

---

## 6. 各类材料的处置决策

### 6.1 旧 spec（`docs/specs/` 四份）

提炼进新文档后**删除原文**，不建归档目录。理由见 §2。

### 6.2 `.superpowers/sdd/`

**只读提炼，原目录不动。** 该目录 898 KB 中真正有价值的知识约 2.5–4 万字符（占 20–25%），其余是过程记录与 492 KB 纯 git diff（`review-*.diff` 里不含任何评审意见文字）。提炼完成后原目录保留原样——它不属于仓库内容，且删除不可逆。

> 与旧 spec 的处置差异，根源在于**是否已入库**：旧 spec 在 git 历史里，删了能挖回来；`.superpowers/` 只在磁盘上，删了就真没了。因此对后者的提炼是**抢救**而非搬家。

### 6.3 代码

**一行不改。** 盘点发现的既有缺陷只写进文档。范围外。

### 6.4 `OPTIMIZATION-PLAN.md`

从「现状盘点」「藏着的坑」「优化清单」三节提炼技术内容；「重新定位」「简历文案」「面试话术」「时间安排」四节属范围外，原文件保留不动。

---

## 7. 交付节奏

一次铺完 11 份文档会导致中途无法纠偏。分三步，每步交付后暂停确认：

| 步骤 | 产出 | 确认点 |
|---|---|---|
| 1 | `README.md` + `architecture.md` | 文风、信息密度、术语口径是否合适——这两份定调 |
| 2 | `modules/` 四份 | 逐份交付，每份过一眼 |
| 3 | `decisions.md`、`pitfalls.md`、`deployment.md`、`getting-started.md`；补 `rag-evaluation/README.md`；删除四份旧 spec；瘦身 `CLAUDE.md`；删除本 spec | 全部完成后统一验收 |

---

## 8. 验收标准

1. **可跑通**：仅照 `getting-started.md` 操作，能起全套服务并完成一次问答。
2. **无过时**：§1.1 列出的三处过时内容已修正；文档中对代码的引用（文件路径、类名、方法名）均经核对存在——本设计 §2 中列出的待创建文档除外。
3. **无丢失**：`decisions.md` 覆盖 `progress.md` 的 Ruling 1–24；`.superpowers/sdd/` 中盘点判定含可保留知识的材料（决策台账、各 report 的「判断与顾虑」节、brief 的 ⚠️ 注释、kb-rename 的偏离说明）其要点均已落位。
4. **无残留**：`docs/specs/` 目录不再存在；`docs/` 下无 task brief / report / progress / diff 类文件。
5. **有入口**：`docs/README.md` 的文档地图与两条阅读路径自洽，所有链接可达。

---

## 9. 已知取舍

1. **源文档删除后追溯依赖 git 与 `decisions.md` 两处**。`decisions.md` 记录的是「决策摘要 + 理由」，比原文的完整推导过程简略。接受这一损耗，换取仓库里只保留一套有效文档。
2. **`.superpowers/` 的知识提炼有损耗**（20–25% 的抽取率是人工判断的结果，可能漏掉当时未标注价值的段落）。通过每一步交付后确认来降低风险。
3. **文档会随代码漂移**。本次重构不引入自动化一致性检查（如链接检查、代码引用校验）。这是已知的长期成本，若漂移成为问题再单独立项。
4. **`pitfalls.md` 与模块文档存在边界判断**。「这个坑算跨模块还是模块内」有时并无客观标准，本次以「复现它是否需要理解多个模块」为准。
