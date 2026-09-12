# 多用户会话隔离 + JWT 鉴权 · 设计文档

> 2026-09-12 · 对应 [TODO.md](../../TODO.md) 阶段二「加：多用户会话隔离 + JWT 鉴权」

## 1. 背景与目标

当前后端**没有任何认证与授权**：所有接口裸奔，`t_chat` 没有归属人，任何知道 `chatUuid` 的人都能读、写、删别人的对话；知识库文件的删除与改备注同样不校验操作者。

本次改造要达到：

1. 引入 JWT 认证，未登录无法调用任何业务接口（登录接口除外）
2. 对话按用户隔离——A 登录后看不到、也操作不了 B 的对话
3. 知识库保持「企业共享」语义，但删除与改备注限定上传者本人

## 2. 范围

**做**：登录认证、对话归属隔离、知识库删改权限、前端登录页与路由守卫、SSE 改走 Vite proxy。

**不做**（YAGNI，理由见 §9）：注册、refresh token、登出黑名单、角色与细粒度权限、限流与防爆破。

## 3. 数据模型

### 3.1 新增 `t_user`

```sql
CREATE TABLE IF NOT EXISTS t_user
(
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64),
    create_time   TIMESTAMP    NOT NULL DEFAULT now(),
    update_time   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_user_username ON t_user (username);
```

`password_hash` 用 `VARCHAR(100)`：BCrypt 输出固定 60 字符，留出更换算法的余量。

**预置两个账号**（不是一 个——两个才能在验收时真正验证用户间隔离）：

| username | password | nickname |
|---|---|---|
| `demo` | `demo123` | 演示账号 A |
| `demo2` | `demo123` | 演示账号 B |

哈希值在实现时用 `BCryptPasswordEncoder` 生成后写死进 `01-schema.sql`，明文仅以注释形式保留（开发库限定）。

### 3.2 `t_chat` 加 `user_id`

```sql
ALTER TABLE t_chat ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0;
UPDATE t_chat SET user_id = (SELECT id FROM t_user WHERE username = 'demo');
ALTER TABLE t_chat ALTER COLUMN user_id DROP DEFAULT;
```

存量对话挂到 `demo` 名下（保留数据 + 满足 `NOT NULL` 约定）。

⚠️ **索引必须一并调整**。现有索引是 `idx_t_chat_update_time (update_time DESC)`，而查询将变成 `WHERE user_id = ? ORDER BY update_time DESC`——前缀对不上，索引失效。替换为：

```sql
DROP INDEX IF EXISTS idx_t_chat_update_time;
CREATE INDEX IF NOT EXISTS idx_t_chat_user_update ON t_chat (user_id, update_time DESC);
```

这与阶段一 `idx_t_chat_message_chat_uuid_id` 是同一个道理：过滤列作前缀，排序列跟在后面，`ORDER BY ... DESC LIMIT` 可直接按索引取数、免排序。

### 3.3 文件表加 `uploader_id`

```sql
ALTER TABLE t_ai_customer_service_file_storage
    ADD COLUMN uploader_id BIGINT NOT NULL DEFAULT 0;
UPDATE t_ai_customer_service_file_storage
    SET uploader_id = (SELECT id FROM t_user WHERE username = 'demo');
ALTER TABLE t_ai_customer_service_file_storage ALTER COLUMN uploader_id DROP DEFAULT;
```

**不建 user_id 索引**：文件列表是全局共享的（`ORDER BY create_time DESC`，无用户过滤），删改校验走主键查询后内存比对，都用不上。

### 3.4 迁移的执行方式

⚠️ `db/init/01-schema.sql` **只在数据卷为空时执行**，改它对现有库无效。因此：

1. `01-schema.sql` 更新为**最终表结构**（`t_user` 直接写进去，便于全新部署）
2. 对**运行中的库**手工执行上述等价的 `ALTER` / `UPDATE` 语句（语句记入 commit message 备查）

不新建 `db/migration/` 目录——schema 演进的正式方案是 TODO 阶段四的 Flyway，届时这些语句会以 `V2__auth.sql` 的形式统一接管。

## 4. 后端鉴权层

### 4.1 依赖

- `spring-boot-starter-security`
- `jjwt-api` / `jjwt-impl` / `jjwt-jackson`（0.12.x，`impl` 与 `jackson` 为 runtime scope）

### 4.2 配置

```yaml
auth:
  jwt:
    secret: ${JWT_SECRET:dev-only-insecure-secret-override-in-prod}
    expire-days: 7
```

⚠️ **密钥不得硬编码**——`application-dev.yml` 被 git 跟踪，硬编码等于把签名密钥提交进版本库（与 `DASHSCOPE_API_KEY` 是同一类问题）。默认值带明显的"生产必须覆盖"措辞，仅服务于本地开发开箱即用。

### 4.3 组件

| 类 | 职责 |
|---|---|
| `config/SecurityConfig` | `SecurityFilterChain`（无状态 / CSRF 关闭 / 放行 `/auth/login`）、`BCryptPasswordEncoder` Bean、`AuthenticationEntryPoint`、`AccessDeniedHandler` |
| `utils/JwtTokenProvider` | 签发与解析 token，payload 含 `userId` + `username`；签名算法由密钥长度决定（`Keys.hmacShaKeyFor` 按键长自动选档，dev 默认密钥 40 字节 → 实际 HS256） |
| `filter/JwtAuthenticationFilter` | `OncePerRequestFilter`，解析 `Authorization: Bearer` → 构造 `Authentication` 填入 `SecurityContextHolder` |
| `controller/AuthController` | `POST /auth/login` |
| `service/AuthService` + `impl/AuthServiceImpl` | 查 `t_user` → `passwordEncoder.matches()` → 签发 token |
| `model/vo/auth/LoginReqVO` / `LoginRspVO` | 入参校验（用户名、密码非空） |
| `utils/AuthContext` | 静态方法 `getCurrentUserId()`，从 `SecurityContextHolder` 取当前用户 |
| `domain/dos/UserDO` | `t_user` 实体 |
| `domain/mapper/UserMapper` | 按 `username` 查询（沿用 `default` 方法 + `Wrappers.lambdaQuery()` 的项目惯例） |

放行清单**仅** `/auth/login`，其余接口全部需要认证——**包括两个 SSE 接口**。

### 4.4 两个必须处理的摩擦点

**① 鉴权失败的响应格式**。Spring Security 的鉴权失败走 `AuthenticationEntryPoint` / `AccessDeniedHandler`，**不经过 `GlobalExceptionHandler`**，默认返回空体或 HTML，与项目「一律返回 `Response`」的约定冲突。需在 `SecurityConfig` 中自定义这两个处理器，直接写出 `Response.fail(...)` 的 JSON。

**② CORS 预检**。前端 SSE 原为跨域直连 `localhost:8080`，加上 `Authorization` 头后会触发 `OPTIONS` 预检，而预检请求本身不携带 token。本设计**通过前端改走 Vite proxy 规避**（见 §6.3），后端无需配置 CORS。若将来有独立域名部署，需补 CORS 配置并放行 `OPTIONS`。

### 4.5 登录接口的日志脱敏

⚠️ **登录接口不得直接挂 `@ApiOperationLog`**。[ApiOperationLogAspect](../../doc-qa-api/src/main/java/io/github/renhaowan/docqa/aspect/ApiOperationLogAspect.java) 会在 `proceed()` 前后分别序列化入参和出参并打日志，而登录接口的入参含**明文密码**、出参含 **token**——挂上就等于把两者写进 `logs/` 目录。

处理方式：`AuthController.login` **不加** `@ApiOperationLog`，改在方法体内手工打印一行不含敏感字段的日志（如 `## 用户登录: username=demo, success=true`）。这与切面中已有的 `maskMultipartFile` 脱敏是同类考虑，但登录是唯一的敏感场景，不值得为此改造切面引入通用脱敏机制。

### 4.6 当前用户的获取方式

`JwtAuthenticationFilter` 将 `userId` 作为 principal 放入 `Authentication`，`AuthContext.getCurrentUserId()` 从 `SecurityContextHolder` 读取。

⚠️ **只在 Controller 方法体内同步获取**，不要在 Reactor 的 `map` / `flatMap` 等回调里取——`SecurityContextHolder` 默认是 `MODE_THREADLOCAL`，流式回调可能运行在其他线程上。本设计中所有归属校验都发生在进入流式返回之前，不受影响。

## 5. 越权防护

### 5.1 对话侧：把归属条件融进查询

```java
int count = chatMapper.delete(Wrappers.<ChatDO>lambdaQuery()
        .eq(ChatDO::getUuid, uuid)
        .eq(ChatDO::getUserId, currentUserId));
if (count == 0) {
    throw new BizException(ResponseCodeEnum.CHAT_NOT_EXISTED);
}
```

不新增「先查出来再比对归属」的校验分支，而是把 `user_id` 直接加进查询条件。一个手法同时买到三件事：代码最少、天然防越权、**不泄露资源是否存在**（A 删 B 的对话得到「对话不存在」，而不是「无权操作」——后者等于向攻击者确认该 uuid 真实存在）。

### 5.2 逐点清单

| 位置 | 改动 |
|---|---|
| `ChatServiceImpl.newChat` | insert 时写入 `user_id`（取自 `AuthContext`） |
| `ChatMapper.selectPageList` | 入参增加 `userId`，wrapper 增加 `.eq(ChatDO::getUserId, userId)` |
| `ChatServiceImpl.findChatHistoryMessagePageList` | 先校验 `chat_uuid` 归属当前用户，再查消息 |
| `ChatServiceImpl.renameChatSummary` | `updateById` 改为带 `.eq(userId)` 的 `update`，影响行数为 0 时抛 `CHAT_NOT_EXISTED` |
| `ChatServiceImpl.deleteChat` | 查询条件增加 `.eq(userId)` |
| `ChatController.chat`（SSE） | **入口处校验 `chatUuid` 归属**，不通过则抛 `CHAT_NOT_EXISTED` |
| `ChatMapper.touchUpdateTime` | 查询条件增加 `.eq(userId)` |
| `CustomerServiceImpl.uploadChunk` / `restoreFileStorage` | 创建文件主记录时写入 `uploader_id` |
| `AiCustomerServiceFileStorageMapper.insertFileIgnoreDuplicate` | INSERT 语句增加 `uploader_id` 列 |
| `ChatDO` | 增加 `userId` 字段 |

`t_chat_message` **不加** `user_id`：它通过 `chat_uuid` 关联，归属已在所有入口校验；加列是冗余，且会让「按 chat_uuid 取最近 N 条」这个热路径多一层无意义的过滤条件。

`CustomChatMemoryAdvisor` 与 `CustomStreamLoggerAndMessage2DBAdvisor` **不改**：它们按 `chatUuid` 工作，归属已在 Controller 把关。

### 5.3 知识库：必须显式比对

知识库是共享可见的，**用不了 §5.1 的手法**——加 `WHERE uploader_id = ?` 会让列表只剩自己的文件，与「企业知识共享」的定位矛盾。因此 `deleteMarkdownFile` / `updateMarkdownFile` 显式比对：

```java
if (!Objects.equals(record.getUploaderId(), currentUserId)) {
    throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NO_PERMISSION);
}
```

这里返回「无权操作」而非「不存在」是**刻意的**，正好与 §5.1 形成对照：**越权该不该泄露资源存在性，取决于该资源是否本来就对操作者可见**。文件在列表里人人可见，谎称不存在只会让用户困惑。

`findMarkdownFilePageList` 不加用户过滤（共享可见）。`checkFile` / `uploadChunk` / `mergeChunk` 保持开放（任何登录用户都可上传）。

### 5.4 秒传场景下的归属语义

A 上传文件 X 后，B 上传同一文件会命中秒传（`checkFile` 返回 `exists=true`、`needUpload=false`），**不会**创建新记录，`uploader_id` 保持 A。即 B 可见、可用于 RAG，但不能删改。

这是有意的：先传者即所有者，同时防止 B 借秒传「夺取」删除权。

## 6. 前端改造

### 6.1 认证状态

新增 `stores/authStore.js`，沿用项目已有的 `pinia-plugin-persistedstate`（与 `chatStore` 一致）持久化，token 落 localStorage，刷新页面不掉登录态。暴露 `token` / `userInfo` / `login()` / `logout()` / `isLoggedIn`。

### 6.2 axios 拦截器

[axios.js](../../doc-qa-web/src/axios.js) 目前无任何拦截器，需补：

- **请求拦截**：注入 `Authorization: Bearer <token>`
- **响应拦截**：⚠️ 项目业务错误是 **HTTP 200 + `success:false`** 的形态（不是 HTTP 错误码），因此**必须检查响应体**而非 HTTP 状态码；检测到鉴权类业务码时清 token 并跳转登录页。

需避免死循环：登录接口自身返回的鉴权失败错误**不得**触发跳转。

### 6.3 SSE 改走 Vite proxy

两个流式页面原以 `fetch-event-source` **硬编码直连 `http://localhost:8080`**，改为相对路径 `/api/...` 走 Vite 的 `server.proxy`，token 通过 `fetchEventSource` 的 `headers` 选项传递。

收益：消除跨域预检（后端无需 CORS 配置）、消除硬编码地址（CLAUDE.md 中「换端口必须同步改两处」的已知问题一并消解）、多一个调试流式失败时的干扰变量被移除。

需确认 `vite.config.js` 的 proxy 不会缓冲 SSE 响应。

### 6.4 路由与页面

- 新增 `views/LoginPage.vue` + 路由 `/login`（hash 模式不变）
- `router.beforeEach` 守卫：无 token 且目标非 `/login` → 跳登录页
- 登录页展示演示账号提示，便于面试现场演示

## 7. 错误码

新增至 `ResponseCodeEnum`：

| 常量 | 码 | 消息 |
|---|---|---|
| `AUTH_INVALID_CREDENTIALS` | `30000` | 用户名或密码错误 |
| `AUTH_TOKEN_INVALID` | `30001` | 登录状态已失效，请重新登录 |
| `MARKDOWN_FILE_NO_PERMISSION` | `20010` | 无权操作该文件 |

`3xxxx` 段预留给鉴权类错误，与 `1xxxx`（通用）、`2xxxx`（业务）区分开。token 过期与签名无效**对外合并为 `AUTH_TOKEN_INVALID`**（前端处置一致，均为跳登录页；攻击者本就可自行解析 JWT 的 `exp`，区分不构成额外泄露），但**日志中区分** `ExpiredJwtException` 与签名异常，便于排查。

## 8. 验收标准

1. **未认证访问被拒**：不带 token 调 `/chat/list` 返回 `AUTH_TOKEN_INVALID`（HTTP 200 + `success:false`，格式与其他业务错误一致，不是 Security 默认页）
2. **登录可用**：`demo` / `demo123` 登录返回 token；错误密码返回 `AUTH_INVALID_CREDENTIALS`
3. **对话隔离**：`demo` 的新建对话以 `user_id=demo` 落库；用 `demo2` 的 token 调 `/chat/list` 看不到 demo 的对话
4. **越权被拦**：用 `demo2` 的 token 删除、重命名、读消息、或向 demo 的 `chatUuid` 发消息（SSE），一律返回 `CHAT_NOT_EXISTED`
5. **知识库权限**：`demo2` 能列出 `demo` 上传的文件、能正常问答检索，但删改返回 `MARKDOWN_FILE_NO_PERMISSION`
6. **前端闭环**：未登录访问任意页面跳登录页；登录后能正常对话（含流式）与上传知识库；token 失效时自动跳回登录页
7. **回归**：`mvn test` 全绿；阶段一验收过的多轮记忆、中止落库、分片上传、向量化全流程仍然通过

## 9. 明确不做的事

| 不做 | 理由 |
|---|---|
| 注册接口 | 定位为「企业内部系统」，账号由管理员分配更贴合真实场景；演示用预置账号已足够 |
| refresh token | 超出 TODO 预估的 0.5–1d，且对简历的边际收益低于把主线链路做扎实 |
| 登出黑名单 | 需要额外存储与清理策略；前端清 token 已满足演示与面试讲解需求 |
| 角色 / 细粒度权限 | 当前只有「文件删改限上传者」一处需要权限判断，引入 RBAC 是过度设计 |
| 登录限流与防爆破 | 真实生产必需，但与本次「会话隔离」目标正交，强行塞入会让改动面失控 |

## 10. 已知取舍与风险

- **JWT 无法主动失效**：签发后在有效期内始终有效，改密码或强制下线都做不到。这是选择无状态 JWT 的固有代价，也是「为什么需要 refresh token / 黑名单」的面试切入点。
- **BCrypt 哈希写死进 SQL**：仅限开发库。生产应由账号开通流程生成。
- **SSE 的 `SecurityContextHolder` 线程**：设计上所有归属校验都在流式返回之前完成（§4.6）。若将来在流式回调内新增需要当前用户信息的逻辑，必须显式传递，不能依赖 `SecurityContextHolder`。
- **秒传的归属固化**（§5.4）：文件被 A 上传后，即使 A 离职或账号删除，该文件的删改权仍属 A。当前无账号删除功能，不构成实际问题。
