# 对话链路

对应入口：`POST /api/chat/completion`（以及 `/chat/models`、`/chat/new`、`/chat/list`、`/chat/message/list`、`/chat/summary/rename`、`/chat/delete` 等会话管理接口）。

本文讲清楚一件事：**一条用户消息从进来到落库，中间经过了什么，以及每一处为什么这么设计。**

---

## 1. 请求级 ChatClient

`ChatClientConfig` 里确实注册了一个 `chatClient` Bean，但 **`ChatController` 没有用它**——每个请求内现场构造：

```java
ChatModel chatModel = OpenAiChatModel.builder()
        .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
        .build();

ChatClient.ChatClientRequestSpec spec = ChatClient.create(chatModel)
        .prompt()
        .options(OpenAiChatOptions.builder().model(modelName).temperature(temperature).build())
        .user(userMessage);
```

**原因**：模型名和 temperature 需要**按请求动态指定**——对话页由前端传 `modelName` / `temperature`，知识库页取配置里的 `knowledge-base.model` / `temperature`。共享一个单例 ChatClient 就得在里面维护可变状态，反而更糟。

新增 AI 能力时沿用这个模式：不要往字段上注入共享的 ChatClient。

**代价**：每个请求新建一次 `OpenAiChatModel`，牺牲了一点对象复用的收益。相对它带来的灵活性，这个代价可以接受（真正的开销在网络调用上）。

### 1.1 模型名在 options 里，不在 ChatModel 上

⚠️ 这是最容易误解的一处：**`OpenAiChatModel` 对象里不装模型名**。

它只持有 `baseUrl` 与 `apiKey`；模型名（以及 temperature）走**每次请求**的 `OpenAiChatOptions`：

```java
// .model(...) 挂在 options 上，不是挂在 OpenAiChatModel.builder() 上
.options(OpenAiChatOptions.builder().model(modelName).temperature(temperature).build())
```

API 层面也印证这一点：`OpenAiApi` 的构造器内部是 `restClientBuilder.clone().baseUrl(baseUrl)…build()`——它决定「往哪个地址发」，「用哪个模型」则是请求体里的字段。

**直接影响**：为不同请求构造出来的 `OpenAiChatModel` 之间**没有任何按模型区分的状态**。所以「换成另一个模型」不需要重建一个有状态的对象，也不会出现「后端把某个模型的对象缓存起来复用」这种事——模型名每一轮都随请求重新传。

### 1.2 模型名白名单

`modelName` 由前端传入，而服务端只有一把 `DASHSCOPE_API_KEY`。**不校验的话，任何登录用户都能指定一个未列出的模型来消耗同一个 key 的额度。**

`chat.allowed-models`（逗号分隔的配置项）是权威清单，同时服务两处：

| 用途 | 位置 |
|---|---|
| 下发给前端做下拉框 | `GET /chat/models` |
| 校验 `POST /chat/completion` 的入参 | `ChatController.chat` |

不在两处各维护一份的理由：这份清单同时管着「前端能选什么」和「后端收什么」，两边各写一份迟早会对不上——表现为前端列着一个后端已经拒掉的模型。

几处细节：

- **校验必须在返回 `Flux` 之前同步完成**，与归属校验同理——HTTP 头一旦发出，异常就变不成 `Response` JSON 了。
- **后端只返回模型名**。图标与描述是前端资源（icon 是本地 svg symbol 名），由前端按名字补，见 `chatStore` 的 `MODEL_META`。后端新增模型而前端没补映射时，下拉框退化成默认图标 + 空描述，**但功能正常**。
- **前端的模型列表不持久化**（`persist.pick` 刻意不含 `models`）。它是后端白名单的投影，缓存下来会让「后端下架了某模型，前端还列着它」跨刷新一直存在。持久化的只有「上次选中了哪个」——而它若已被移出白名单，启动时会回退到第一个，不会留着一个后端已经拒掉的选中项。

**真正的风险是枚举，不是瞎猜**：名字猜错只会拿到 `model_not_found`，猜对就直接用掉了。实测 `qwen-max`——一个前端下拉框里根本没有的模型——一次就调通了。

---

## 2. Advisor 链

Advisor 是本项目最核心的扩展点：所有增强逻辑都实现 `StreamAdvisor`，按 `getOrder()` 从小到大执行。**每个请求 `new` 出实例**——因为它们持有请求级依赖（当前 `AiChatReqVO`、Mapper 等），做成单例就得把这些依赖变成方法参数或 ThreadLocal。

### 2.1 对话入口挂载的两个

```java
if (networkSearch) {
    advisors.add(new NetworkSearchAdvisor(searXNGService, searchResultContentFetcherService));
} else {
    advisors.add(new CustomChatMemoryAdvisor(chatMessageMapper, aiChatReqVO, 50, memoryMaxTokens));
}
advisors.add(new CustomStreamLoggerAndMessage2DBAdvisor(
        chatMessageMapper, chatMapper, aiChatReqVO, transactionTemplate, currentUserId));
```

| Advisor | order | 挂载条件 | 作用 |
|---|---|---|---|
| `NetworkSearchAdvisor` | 1 | `networkSearch == true` | SearXNG 检索 → 并发抓取正文 → 重写 prompt |
| `CustomChatMemoryAdvisor` | 2 | `networkSearch == false` | 拉取历史消息并注入本轮 prompt |
| `CustomStreamLoggerAndMessage2DBAdvisor` | 99 | 固定挂载 | 聚合流式 chunk、落库两条消息 |

⚠️ **记忆与联网搜索是二选一，不会同时挂载。** 这是刻意的：开了联网搜索时，检索到的实时资料比历史对话更相关，把两者都塞进 prompt 只会互相稀释。如果要同时具备，需要重新论证 prompt 预算的分配，不能简单地两个都 add 进去。

### 2.2 知识库入口的 Advisor

知识库页只挂一个 `KnowledgeBaseAdvisor`（order 也是 1），它做向量检索和提示词重写。它属于 RAG 链路，详见 `modules/knowledge-base.md`。

---

## 3. 对话记忆

`CustomChatMemoryAdvisor` 从 `t_chat_message` 取该 `chatUuid` 下最近的 **50 条**消息，转成 `Message` 后注入本轮 prompt。

### 3.1 取数

```java
chatMessageMapper.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
        .eq(ChatMessageDO::getChatUuid, chatUuid)
        .orderByDesc(ChatMessageDO::getId)
        .last(String.format("LIMIT %d", limit)));
```

**排序键用自增主键 `id` 而不是 `create_time`**：时间可能重复（同一毫秒内落两条），排序值相等时 `LIMIT` 取哪几条是不确定的，会导致消息顺序错乱。索引 `idx_t_chat_message_chat_uuid_id (chat_uuid, id DESC)` 正是为此建的，可直接按索引顺序取数、免排序。

取出来后按 `id` **升序**重排，还原正常对话顺序。

### 3.2 token 预算裁剪

50 条消息如果每条都很长（用户贴了一整篇文档、模型回了一大段），拼出来的 prompt 可以远超模型上下文窗口，表现为**模型 API 返回 400、SSE 流转 error**。

所以这里有一层 `trimByTokenBudget`：

- **从最新往旧累加**，装不下就停。越近的对话越重要，反过来取会丢掉最相关的上下文。
- **单条超预算时至少留一条**（`kept.isEmpty()` 例外）。不加这个例外，用户直接贴一篇长文时一条都留不下，这轮彻底退化成无记忆对话。
- **最后丢掉开头不完整的轮次**。截断点可能落在一轮中间，留下「有答无问」的历史——模型只看到自己说过的一段话，却看不到对应的问题，它会把那段话当成凭空出现的陈述来接。丢掉比留着安全。
- **裁剪在拼接当前用户消息之前完成**。当前消息是这一轮真正的提问，任何情况下都不能被裁掉；裁剪逻辑只从最旧一端丢弃，把它混进来一起算有风险。

**条数上限（50）与 token 预算（`chat.memory.max-tokens`，默认 8000）管的是两件不同的事**：前者是查库边界，后者是上下文边界。50 条长消息同样能把上下文撑爆，两者不能互相替代。8000 token 约合 1.1 万字中文（≈55 条普通长度的消息），本意是兜住极端长消息而非把窗口填满——记忆越长，成本与延迟越高，边际价值递减。

### 3.3 token 估算为什么可以"不准"

估算用的是 `JTokkitTokenCountEstimator`，即 OpenAI 的 BPE 词表，对 Qwen / DeepSeek 的中文切分只是**近似**。

这是有意的取舍：这里不需要精确记账，只需要「足够接近以便在撑爆上下文之前触发截断」。精确方案要么依赖模型自己的 tokenizer（本项目按请求动态指定模型名，拿不到对应词表），要么自建词表（维护成本远高于收益）。

估算器声明为 `static final`：构造它要加载词表，而 advisor 是每请求 new 一个的，写成实例字段等于每个请求加载一遍词表。

---

## 4. 联网搜索

启用后，`NetworkSearchAdvisor` 在模型调用之前完成一次完整的检索-抓取-重写。

### 4.1 流程

```
用户问题
  └─ SearXNG 搜索                       searxng.url，取前 searxng.count（默认 10）条
      └─ 并发抓取每个结果页的 HTML        7 秒超时，每个 URL 独立计时
          └─ Jsoup 提取纯文本            在另一个线程池里做
              └─ 过滤掉 content 为空的
                  └─ 按模板重写 prompt    注入「来源编号 + 相关性 + 链接 + 正文」
                      └─ 交给下游
```

重写后的 prompt 有两条硬要求：**避免说"根据上下文……"这类元表述**，以及**在关键信息后标注来源编号与链接**（模板里给了 `<a href="…">来源N</a>` 的具体格式）。

### 4.2 两个线程池，以及为什么拒绝策略不同

抓取天然是 IO 密集与 CPU 密集混合的，本项目把它拆成两段，各用各的池：

| 线程池 | 参数 | 用途 | 拒绝策略 |
|---|---|---|---|
| `httpRequestExecutor` | core 50 / max 200 / queue 1000 / keepAlive 120s | OkHttp 抓取 HTML（IO 密集） | `CallerRunsPolicy` |
| `resultProcessingExecutor` | core = CPU 核数 / max = 核数×2 / queue 200 | Jsoup 解析 HTML（CPU 密集） | `AbortPolicy` |

在 `SearchResultContentFetcherServiceImpl` 里的用法是：

```java
CompletableFuture.supplyAsync(() -> syncFetchHtmlContent(url), httpExecutor)   // 抓取
        .completeOnTimeout(fallback, timeout, unit)                            // 每个 URL 独立超时
        .exceptionally(e -> fallback);                                         // 失败降级为「空内容」

CompletableFuture.allOf(futures)
        .thenApplyAsync(v -> …Jsoup.parse(html).text()…, processingExecutor);  // 解析
```

**为什么拒绝策略要分开选**——这是本模块最值得讲的一处设计：

- **IO 密集型用 `CallerRunsPolicy`**：任务是网络等待，压垮线程池也不会压垮 CPU；队列满了让调用线程自己跑，相当于**给上游一个自然的减速带**，任务不会丢。如果这里用 `AbortPolicy`，搜索高峰会直接抛异常，用户看到的是问答失败。
- **CPU 密集型用 `AbortPolicy`**：解析任务是实打实吃 CPU 的，队列堆积意味着响应时间雪崩。此时**快速失败比慢慢排队更有价值**——排队的任务即使最终执行完，用户也早就不等了。

两个池的参数方向也相反：IO 池给大核心数（50）和大队列（1000）容忍等待；CPU 池核心数贴着核数、队列只给 200，避免堆积。

### 4.3 降级设计

`completeOnTimeout` 是**每个 URL 独立计时**的，一个慢站点不会拖垮整批。抓取失败（网络异常、响应非 2xx、响应体为空）统一降级为空字符串，之后被 `filter(StringUtils::isNotBlank)` 过滤掉。

结果是：**部分来源抓不到时，回答质量下降但不会失败**。这是刻意的——联网搜索是增强手段，不该成为单点故障。

### 4.4 搜索引擎的可用性依赖网络环境

`searxng.engines` 是配置项（可用环境变量 `SEARXNG_ENGINES` 覆盖，不必改代码重新构建镜像），**本地与服务器的取值不必相同**——本地开发机可访问境外，服务器（阿里云机房、无代理）几乎必然不通 Google 系。

排查时要注意：「引擎不可用」有**好几种不同的原因**，现象完全不同：

| 引擎 | 现象 |
|---|---|
| `360search` `naver` `presearch` `mwmbl` `yandex` `marginalia` `startpage` | 可用 |
| `baidu` | 被反爬拦下，日志 `SearxEngineCaptchaException`，随后被挂起 1 小时 |
| `quark` / `sogou` | SearXNG 引擎代码自身报 `AttributeError`（页面结构变了，待上游修） |
| `bing` | **请求成功但解析出 0 条，无任何报错**——属静默失效，最难查 |
| `wolframalpha` / `seznam` / `mojeek` | 网络超时 |

⚠️ 引擎名必须与 SearXNG 实例的 `/config` 端点完全一致，写错会被**静默忽略**。

---

## 5. SSE 流式协议

接口 `produces = TEXT_EVENT_STREAM_VALUE`，返回 `Flux<AIResponse>`。`AIResponse` 只有两个互斥字段：

| 字段 | 含义 |
|---|---|
| `reasoning` | 模型的思考过程（仅推理模型返回） |
| `v` | 正式回答 |

前端据此把内容分流到不同的渲染区域：思考过程折叠展示，正文走 Markdown 渲染。

分流逻辑在 `ChatController.chat` 里：

```java
String reasoningContent = message.getMetadata().get("reasoningContent").toString();
if (StringUtils.isNotBlank(reasoningContent)) {
    return AIResponse.builder().reasoning(reasoningContent).build();
}
return AIResponse.builder().v(text).build();
```

⚠️ **这里的 `.toString()` 是裸调用，没有判 null。** 非推理模型（如知识库页用的 deepseek-v3）下该 key 可能存在但值为空字符串，也可能缺失——缺失时会 NPE。**实测未触发**（值为 `""` 而非 `null`），但这是防御性缺口。Advisor 里的写法是正确示范：先判 null 再 `toString()`（见 `CustomStreamLoggerAndMessage2DBAdvisor`）。

**知识库入口没有这层分流**——它用 `.content()` 只取纯文本。这是两个入口在输出处理上的实质差异。

---

## 6. 消息落库

落库由 `CustomStreamLoggerAndMessage2DBAdvisor`（order 99，链尾）负责。它包在整条链最外层，因此能看到最终流向用户的全部 chunk。

### 6.1 聚合

`doOnNext` 把每个 chunk 追加进两个 `AtomicReference<StringBuilder>`（线程安全），分别收集 `reasoningContent` 与正文。

### 6.2 用 `doFinally`，不是 `doOnComplete`

这是本项目**最重要的一个历史 bug 修复**，改动时不要退回去。

Reactor 流有三种终止信号：

| 信号 | 触发场景 |
|---|---|
| `ON_COMPLETE` | 模型正常输出完毕 |
| `ON_CANCEL` | **用户点击「停止生成」**，前端 abort 连接，流被 cancel |
| `ON_ERROR` | 流异常中断 |

两个前端页面都有 `controller.abort()`。用户点「停止」触发的是 **cancel 而非 complete**——原实现只在 `doOnComplete` 里落库，导致这一轮的用户提问和已生成的部分回答**全部丢失**：问一句、答一半、点停止、刷新页面，什么都没了。

`doFinally` 能同时覆盖三种信号，是这里唯一正确的选择。

### 6.3 落库规则

三条规则都来自实际场景：

| 规则 | 原因 |
|---|---|
| 用户提问**始终**落库 | 用户中止且模型一字未出时，提问本身仍要保存，否则刷新后用户会发现自己的问题凭空消失了 |
| AI 回答**仅当内容非空**时落库 | 模型一字未出时写一条空消息，会污染后续多轮上下文 |
| `ON_ERROR` 时**整轮不落库** | 半截回答写进去意义不大，且用户根本没看到 |

两条 `insert` 包在 `TransactionTemplate` 里（编程式事务），保证「有提问没回答」的半截数据不会出现。

### 6.4 刷新对话活跃时间

```java
chatMapper.touchUpdateTime(chatUuid, ownerUserId);
```

对话列表按 `t_chat.update_time` 倒序分页，而它原先只在新建对话时写过一次。不更新的话排序会退化成按创建时间排，**「刚聊完的对话」不会浮到列表顶部**。

### 6.5 `ownerUserId` 为什么是构造参数

落库发生在 `doFinally` 回调里，而该回调**可能运行在 Reactor 的其他线程上**。`SecurityContextHolder` 默认是 `MODE_THREADLOCAL`，在那里取到的是空值。

所以归属用户 ID 在进入流式返回之前就取好，当作构造参数传进来（见 `modules/auth.md` 对此的进一步说明）。

---

## 7. 本模块坑点

1. **`.tools()` 与 `.options()` 可以安全叠加**，不会丢 model / temperature——`DefaultChatClient.tools()` 只往 `toolCallbacks` 里加，而 `OpenAiChatOptions` 直接实现 `ToolCallingChatOptions`、不继承 `DefaultChatOptions`，所以那个「把 options 复制进 DefaultChatOptions」的分支不会走到。（这条在知识库入口用得到。）

2. **改提示词要改对应 Advisor 里的常量**。提示词模板以 `private static final PromptTemplate` 内联在各 Advisor 中，没有外置配置文件。

3. **`reasoningContent` 的取值**：Advisor 里先判 null 再 `toString()` 是正确写法；`ChatController` 里的裸 `.toString()` 是待修缺口（见 §5）。

4. **不要给 `CustomChatMemoryAdvisor` 单独加 userId 过滤**——当前不可达（实例化点在 Controller 的归属校验之后）。但**将来若有人把它挂到别的入口，会静默变成「可读他人对话消息」**。这是评审时记录在案的、有意留下的缺口。
