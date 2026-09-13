# 知识库链路

对应入口：`POST /api/knowledge-base/completion`（对话）与 `/knowledge-base/file/*`、`/knowledge-base/md/*`（文件管理）。

这是本项目最复杂的一条链路——它同时包含纯工程问题（分片上传、并发幂等、文件状态机）和 AI 问题（切分策略、向量检索、工具调用）。本文按这两条线分开讲。

---

## 1. 完整链路

```
① checkFile       按 fileMd5 查记录
                    ├─ 无记录            → 需上传
                    ├─ 状态非 UPLOADING  → 秒传
                    └─ 状态为 UPLOADING  → 返回已上传分片号，供断点续传
      ↓
② uploadChunk     分片落盘 + 写分片表（并发幂等）
                   首次上传时创建文件主记录，状态 UPLOADING
      ↓
③ mergeChunk      按序流式合并 → 状态改 PENDING → 删分片目录与记录
                   → 发布 KnowledgeBaseFileUploadedEvent
      ↓
④ 监听器          AFTER_COMMIT + @Async
                   状态改 VECTORIZING → MarkdownReader 解析
                   → 按归属删旧向量 + 批量写入 → 状态改 COMPLETED / FAILED
```

每个环节都只做一件事，靠**状态机**串起来——这样任何一步失败都能从状态看出卡在哪。

---

## 2. 文件状态机

| 状态 | code | 含义 |
|---|---|---|
| `UPLOADING` | 0 | 上传中，分片尚未齐 |
| `PENDING` | 1 | 合并完成，等待向量化 |
| `VECTORIZING` | 2 | 向量化中 |
| `COMPLETED` | 3 | 已完成，可被检索 |
| `FAILED` | 4 | 向量化失败 |

**删除在各状态下的放行规则不同**（`deleteMarkdownFile`）：

- `PENDING` / `VECTORIZING` → **拒绝删除**（`MARKDOWN_FILE_CANT_DELETE`）。文件正在被处理，删了会产生"记录没了但向量还在写入"的竞态。
- `UPLOADING` → **放行**。但此时磁盘上还有分片文件、库里有分片记录，**必须一并回收**（见 §3.4）。

---

## 3. 分片上传

### 3.1 秒传

`checkFile` 按 `fileMd5` 查 `t_knowledge_base_file`：记录存在且状态**不是** `UPLOADING`，说明这个文件早就传完过，直接返回 `needUpload: false`——前端跳过上传。

### 3.2 断点续传

记录存在但状态是 `UPLOADING`，说明上次传到一半。返回 `uploadedChunks`（已上传的分片序号列表），前端只补传缺的那些。

### 3.3 并发幂等：`ON CONFLICT DO NOTHING`

前端是**3 路并发上传**的（`MAX_CONCURRENT = 3`）。`uploadChunk` 里"先查再插"存在竞态窗口，靠两层 `ON CONFLICT DO NOTHING` 兜底：

```java
int chunkInserted = knowledgeBaseChunkMapper.insertChunkIgnoreDuplicate(...);
if (chunkInserted == 0) {  // 并发请求抢先写入，已计过数，幂等返回
    return Response.success();
}

int fileInserted = aiKnowledgeBaseFileStorageMapper.insertFileIgnoreDuplicate(...);
```

以**影响行数**判断是否首次写入，而不是"先查再插"——后者两个线程可能同时通过检查。

⚠️ **不要改成「捕获 `DuplicateKeyException`」**。PostgreSQL 中约束冲突会让整个事务进入 **aborted 状态**，后续语句全部失败；而本方法是 `@Transactional` 的，catch 也救不回来。

### 3.4 删除时必须回收分片

`UPLOADING` 状态放行删除，所以删除时要同时做三件事：删主记录、`deleteByMd5` 清分片表、删 `chunks/{fileMd5}/` 目录。

⚠️ **不回收的话该 MD5 会永久卡死**：`uploadChunk` 的快速路径只认分片表、直接返回成功，主记录再也重建不出来，于是 `mergeChunk` 恒报 `MERGE_CHUNK_NOT_FOUND(20006)`——前端 `checkFile` 明明返回"需上传"，却怎么传都合并不了。

**为此快速路径额外做了一层校验**：分片存在时还要确认主记录也还在，缺失则调 `restoreFileStorage()` 就地重建。重建时 `uploadedChunks` 取分片表的**实际条数**而非固定 1——否则多分片场景会被 `mergeChunk` 误判为分片不完整。

### 3.5 分片大小的四方契约

这是最容易出错、且**报错信息完全指错方向**的一处配置。

| 位置 | 值 | 含义 |
|---|---|---|
| `KnowledgeBaseChatPage.vue` 的 `CHUNK_SIZE` | 2MB | 前端切分片的大小 |
| 后端 `spring.servlet.multipart.max-file-size` | 3MB | 单个 part 的上限 |
| 后端 `spring.servlet.multipart.max-request-size` | 5MB | 整个请求的上限 |
| `nginx.conf` 的 `client_max_body_size` | 5m | nginx 请求体上限 |

必须满足 **分片大小 < `max-file-size` < `max-request-size` ≤ nginx 上限**。当前 2 < 3 < 5 = 5，链条成立。

**为什么 `max-file-size` 只需容纳「一个分片」**：本项目只走分片上传，整文件**从不**作为单个 part 传输[^1]。

**不配会怎样**：走 Spring Boot 默认的 1MB，2MB 的分片被 Tomcat 在**进入 Controller 之前**拒掉（`FileSizeLimitExceededException`），再被 `GlobalExceptionHandler` 兜底成 `10000`，前端只看到一句"出错啦，后台小哥正在努力修复中"。现象是「`checkFile` 说需上传、一点上传就报错」——**排查方向会被完全带偏**，因为日志里看不到任何与文件大小相关的线索。

**为什么不要写成恰好相等**：那依赖「相等即通过」的边界语义，前端一改大就会静默失败。

[^1]: 早期的整文件上传接口 `/md/upload` 已移除。

---

## 4. 文档切分

解析由 `MarkdownReader` 完成，它是对 Spring AI `MarkdownDocumentReader` 的薄封装：

```java
MarkdownDocumentReaderConfig.builder()
        .withHorizontalRuleCreateDocument(true)   // 遇 --- 创建新文档
        .withIncludeCodeBlock(false)
        .withIncludeBlockquote(false);
```

### 4.1 标题才是主切分点，且不可配置

`MarkdownDocumentReader` 在 `visit(Heading)` 里是**无条件 flush**——遇到任何标题就切一刀，这个行为**不受任何配置项控制**。上面三个配置项都不影响它。

理解这一点很重要：**`withHorizontalRuleCreateDocument(true)` 只是加了 `---` 这个额外切分点**，即使关掉它，标题照样切。

### 4.2 代码块与块引用的配置语义

`withIncludeCodeBlock(false)` / `withIncludeBlockquote(false)` 决定的是**代码块与块引用要不要单独成篇**，与标题切分无关。

> ⚠️ `MarkdownReader` 里的行内注释写的是「排除代码块（代码块生成单独文档）」，这个表述**容易读反**——`false` 的实际效果是代码块**不**独立成篇，其内容留在所在段落中。

### 4.3 标题文本不进正文

`visit(Text)` 遇到 parent 是 Heading 时，只把文本写进 `title` 元数据，**不加入正文段落**。

后果：`## 第三章 岗位设置` 这类标题只贡献"这是个三级标题"这一层信息，**概括性语义不参与 embedding**。代价是语料少约 11% 的文本——但实测对检索命中率无影响（见 `rag-evaluation/report.md`）。

### 4.4 未启用表格扩展

Markdown 表格会被当普通文本吞进正文，不做结构化解析。**如果知识库里表格密集，这是当前检索质量的一个已知短板。**

---

## 5. 向量写入：覆盖式重建

监听器在 `AFTER_COMMIT` 事务里执行：

```java
vectorStore.delete(String.format("mdStorageId == %s", id));   // 先删旧
if (!documents.isEmpty()) {
    vectorStore.add(documents);                                // 再整体写入
}
```

### 5.1 三条硬规则

1. **顺序不能反。** 反了等于删掉刚写入的向量，文件"向量化完成"却检索不到自己。
2. **不要退回逐条 `add`。** `PgVectorStore.doAdd` 内部是 `embeddingModel.embed(List, ...)` + `JdbcTemplate.batchUpdate`，一次调用完成批量向量化与批量插入。逐条写会让走网络的 embedding 调用**随分片数线性增长**——那是这条链路上最贵的一环。
3. **过滤键 `mdStorageId` 由 `mergeChunk` 写进 Document 元数据**，与这句删除的过滤式必须一致。只改一处会让删除**静默失效**，旧向量永久累积，表现为"重新上传后检索到两版内容"。

空集合不递进 `add`：`add(emptyList)` 会走到 `embed(空) + batchUpdate(空批)`，这条路径的行为没有保证。

### 5.2 历史教训：跨文件去重为什么被废除

这里曾经用「逐条 `topK=1` 相似检索、得分 > 0.99 视为重复并跳过」做去重。**那个方案是错的**，原因值得记住：

它是**跨文件**去重——同一段文本只归属于**第一个**上传它的文件。而删除走 `mdStorageId == id`，于是：

> 删掉 A 文件，会连带删掉 B 文件里那段「被判定为重复」的段落。**B 从此检索不到自己的内容。**

表现是"重新上传内容相近的文件不会更新向量数据"。这是**归属错乱，是正确性问题**，不只是性能问题。

改成按归属重建后，一个文件的向量就是它当前内容的向量，与别的文件无关。

---

## 6. RAG 检索

### 6.1 检索

`KnowledgeBaseAdvisor`（order 1）在模型调用前做一次相似检索：

```java
vectorStore.similaritySearch(SearchRequest.builder()
        .query(userMessage.getText())
        .topK(topK)
        .build());
```

检索条数取自 `knowledge-base.top-k`（默认 3）。**做成可配而不是写死，是为了让「取多少合适」能被实测决定，而不是拍一个数字**——取值依据与对照数据见 `rag-evaluation/report.md`。

双构造器 `(vectorStore, webFallback)` 与 `(vectorStore, webFallback, topK)` 就是为此存在：前者用默认值，后者让评估测试显式指定。

### 6.2 上下文组装

检索到的 Document 用 `---` 分隔拼成上下文文本，再填进提示词模板的 `{context}` 占位符。

### 6.3 options 必须透传

```java
Prompt newPrompt = promptTemplate.create(
        Map.of("question", ..., "context", ...),
        chatClientRequest.prompt().getOptions());   // ← 不能换成别的 options
```

因为这组 options 里带着 Controller 通过 `.tools()` 挂上的工具回调。换成新建的 options 会让模型**失去联网搜索能力**——而且提示词里还在要求它调用 `web_search`，结果是模型凭空编造联网结果。

---

## 7. Function Calling：联网兜底

知识库页有一个「联网兜底」开关。开启后，`WebSearchTool` 被挂给模型，**由模型自主决定是否调用**。

### 7.1 挂载条件

```java
boolean webFallback = Boolean.TRUE.equals(chatReqVO.getNetworkFallback());
if (webFallback) {
    chatClientRequestSpec.tools(webSearchTool);
}
advisors.add(new KnowledgeBaseAdvisor(vectorStore, webFallback, topK));
```

**只在知识库页、只在开关打开时**才挂。对话页不用它——那里已有 `NetworkSearchAdvisor` 做显式联网检索，再叠一个自动联网工具是功能重复。

### 7.2 `.tools()` 与 `.options()` 可以安全叠加

不会丢 model / temperature：`DefaultChatClient.tools()` 只往 `toolCallbacks` 里追加，不碰 chatOptions；而那个会「把 options 复制进 DefaultChatOptions」的分支只在 options 是 `DefaultChatOptions` 时走，`OpenAiChatOptions` 直接 `implements ToolCallingChatOptions`、不继承它。

### 7.3 tool loop 只跑一遍 advisor 链

工具调用循环由 `OpenAiChatModel` 内部驱动（判定 `isToolExecutionRequired` → 执行工具 → 递归 `internalStream`），**advisor 链只执行一次**。所以向量检索不会随工具轮次重复执行——这是个容易误解的点。

### 7.4 双模板：提示词必须与工具是否挂载保持一致

`KnowledgeBaseAdvisor` 因此是双构造器 + 双模板：

| `webFallback` | 模板 | 行为 |
|---|---|---|
| `false` | `DEFAULT_PROMPT_TEMPLATE` | 严格基于上下文作答 |
| `true` | `WEB_FALLBACK_PROMPT_TEMPLATE` | 上下文不足时**必须先调 `web_search`** |

⚠️ **两者必须成对出现。** 提示词让模型联网而工具没挂，模型会**凭空编造联网结果**，前端看不出破绽。为便于排查，`webFallback` 为真时 advisor 会打一行日志报告实际挂载的工具回调数。

### 7.5 工具名是写死在提示词里的调用契约

```java
@Tool(name = "web_search", description = "...")
public String webSearch(@ToolParam(...) String query) {
```

⚠️ `name` **不能省**。省了就按方法名绑定成 `webSearch`，跟提示词里的 `web_search` 对不上（实测模型能猜对，但不该依赖）。改名必须同步改 `WEB_FALLBACK_PROMPT_TEMPLATE`，`ToolCallbackSmokeTests` 断言了这个名字。

### 7.6 工具内部必须吞掉异常

```java
} catch (Exception e) {
    log.error("## 联网搜索失败，降级为仅依据知识库回答", e);
    return "联网搜索失败，暂时无法获取互联网信息，请仅依据知识库上下文回答用户问题。";
}
```

⚠️ 抛出去会让**整条 SSE 流转为 error**，前端只看到"请求出错"，而这本该降级成"仅凭知识库回答"。检索结果为空时同理，返回说明文本而不是空字符串。

### 7.7 「统一回复」是个陷阱

只要给模型一条「答不出时就说这句」的现成出口，它就会**直接抄，根本不去调工具**。

所以兜底模板把「上下文不足必须先调 `web_search`」放在判断链最前，并把统一回复**绑死在「已经调用过 web_search 之后」**。这是提示词设计上一个具体而深刻的教训。

### 7.8 工具调用之前的正文过滤不掉

含 tool call 的那条 chunk 被框架吞掉，但模型在调工具**之前**若输出了正文（"让我查一下……"），那段文本会直接流到前端。

**chunk 层没有拦截点**，只能靠提示词约束——兜底模板的第 5 条「禁止输出过程说明」就是干这个的。

---

## 8. 本模块坑点

1. **`mergeChunk` 是知识库侧唯一绕过权限模型的路径，且缺口未修复。** 它**不校验 `uploader_id`**：任何登录用户只要把某个 `fileMd5` 的分片补齐（分片接口本身也不校验归属），就能合并出"别人的"文件记录——`updateById` 会覆盖该记录的 `stored_file_name` 并把状态改回 `PENDING`，随后重新触发向量化。

   合法续传只发生在记录处于 `UPLOADING` 时（此时 `stored_file_name` 还是空串，覆盖它没有副作用）。**当目标记录已经 `COMPLETED` 时，这次覆盖会把别人已经向量化、正在被所有人检索到的正文整体替换掉**——影响面是全体用户的检索结果。

   已知的缓解方向（"状态不是 `UPLOADING` 时拒绝合并"）属于行为变更，会波及合法重传场景，评审时未采纳，改为在方法 Javadoc 里显式记录该缺口。

2. **`deleteMarkdownFile` 删本地文件前必须判 `stored_file_name` 非空。** `UPLOADING` 状态下该列为空串，而 `new File(dir, "")` 指向的是存储目录**本身**，`FileUtils.forceDelete` 对目录是**递归删除**——不拦住会把整个 `data/files/` 连同所有知识库文件一起删掉。

3. **合并文件的路径必须绝对化 + 规范化**（`Paths.get(x).toAbsolutePath().normalize()`）。`MultipartFile.transferTo()` 走 Servlet 容器的 `Part.write()`，它把**相对路径**解析为相对于 multipart 临时目录；而 `FileUtils.forceMkdir()` 按 JVM 工作目录解析。两者规则不同，直接传相对路径会"目录建好了、文件却写不进去"，报的 `FileNotFoundException` 路径看着莫名其妙。注意 `getAbsoluteFile()` 只拼工作目录、**不解析** `.`，必须用 `normalize()`。

4. **`t_knowledge_base_chunk.chunk_name` 只存文件名**（`0.chunk`），不存绝对路径——目录由 `chunk-path` + `fileMd5` 推导，换机器或挪目录后历史记录依然有效。同理，落盘文件名只写进 `stored_file_name`（纯文件名）。例外是事件里的 `filePath`，它给同一 JVM 内的 `FileSystemResource` 用，必须是绝对路径。

5. **`updateMarkdownFile` 的 `@ApiOperationLog` 描述写错了**——挂的是「删除 Markdown 问答文件」，应为「修改」。复制粘贴笔误，不影响功能，但会误导看日志的人。

6. **`uploaded_chunks` 实际是只写不读的**。库层注释说它是「刻意冗余，用一次原子自增换掉高频 `count(*)`」，但 `src/main` 里没有任何地方读它——续传走的是分片表查询（`checkFile` 里那一段），`mergeChunk` 用的也是分片表的实际条数。它本意服务于断点续传，实际没承担这个职责，且可被并发上传或他人抬高。

7. **`mergeChunk` 的 `updateById` 未刷新 `update_time`**。
