# 鉴权与数据隔离

本文回答三个问题：**身份怎么来的**、**鉴权失败长什么样**、**凭什么说 A 看不到 B 的数据**。

第三点比前两点重要——「加了 JWT」只是入门，「越权该在哪一层拦、拦到什么程度」才是这个模块真正的设计内容。

---

## 1. 身份从哪来

**无状态 JWT**。没有会话、没有 cookie，每个请求靠 `Authorization: Bearer <token>` 自证身份。

### 1.1 过滤器链

```java
http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
    .csrf(AbstractHttpConfigurer::disable)        // 没有 cookie 会话可被伪造
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
            .requestMatchers("/auth/login").permitAll()   // ← 放行清单只有这一条
            .anyRequest().authenticated())
    .httpBasic(AbstractHttpConfigurer::disable)
    .formLogin(AbstractHttpConfigurer::disable)
    .logout(AbstractHttpConfigurer::disable);
```

**放行清单只有 `/auth/login`**，其余接口全部需要认证，**包括两个 SSE 接口**。

把 JWT 过滤器插在 `UsernamePasswordAuthenticationFilter` 之前——本项目不用表单登录，这个位置实际就是「所有授权判断之前」，正是解析 token 的时机。

### 1.2 解析与身份形态

`JwtAuthenticationFilter` 继承 `OncePerRequestFilter`，从请求头取 token：

```java
if (StringUtils.isNotBlank(header)
        && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
    String token = header.substring(BEARER_PREFIX.length());
    Long userId = jwtTokenProvider.parseUserId(token);
    UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);
}
```

**principal 直接放 `userId`（Long）**，`AuthContext` 取出来即用；本项目没有角色体系，权限列表给空集合。

⚠️ **`Bearer ` 前缀必须大小写不敏感**（RFC 6750 规定 scheme 大小写不敏感）。客户端 SDK、网关、代理都可能把 scheme 规范成小写（`bearer ...`），按字面比较会把**有效的** token 拒掉，而日志里只留一条「未认证」——**排查方向会被带偏到「token 过期/伪造」上去**。所以用 `regionMatches(true, 0, ...)`，而后面的 `substring` 仍按常量长度切。

**解析失败不在这里写响应**，只是 `clearContext()` 后继续走链：没解析出身份时，后续 `authorizeHttpRequests` 会因为「未认证」而拒绝，由 `AuthenticationEntryPoint` 统一写出 JSON。在这里直接写响应会让错误格式散落两处。

### 1.3 签名算法按键长自动选档

`JwtTokenProvider` 用 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` 构造密钥。

⚠️ **这个方法的算法不是固定的 HS256**——它按密钥**字节数**挑：

| 密钥长度 | 算法 |
|---|---|
| ≥ 64 字节 | HS512 |
| ≥ 48 字节 | HS384 |
| ≥ 32 字节 | HS256 |
| < 32 字节 | 抛 `WeakKeyException`，**启动即失败** |

即：**换 `JWT_SECRET` 就等于换算法**。dev 默认密钥 41 字节 → 实际是 **HS256**。

短于 32 字节时抛异常是刻意设计——与其用一个弱密钥签发 token，不如启动时就报错。

### 1.4 开发默认密钥的自检

```java
private static final String INSECURE_DEV_SECRET = "dev-only-insecure-secret-override-in-prod";

if (INSECURE_DEV_SECRET.equals(secret)) {
    log.warn("⚠️ 当前使用内置的开发默认 JWT 密钥，任何人都可据此伪造 token！...");
}
```

这个密钥随源码公开在版本库里。命中即说明当前实例**没有**通过环境变量配置 `JWT_SECRET`——启动日志里会有这条 WARN。

---

## 2. 鉴权失败长什么样

### 2.1 项目统一约定：HTTP 200 + `success:false`

⚠️ **鉴权失败返回的是 HTTP 200，不是 401。**

这是本项目最反直觉的一条约定，也是最容易被误判的地方：**HTTP 状态码这一层全是 200**，错误信息在响应体里。

Spring Security 的鉴权失败**不经过 `GlobalExceptionHandler`**（它发生在过滤器链里，还没进 DispatcherServlet），所以必须自己写出 JSON：

```java
@Bean
public AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, authException) -> {
        log.warn("## 未认证访问被拒: {} {}", request.getMethod(), request.getRequestURI());
        writeJson(response, Response.fail(ResponseCodeEnum.AUTH_TOKEN_INVALID));
    };
}

private void writeJson(HttpServletResponse response, Response<?> body) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);   // ← 显式设成 200
    ...
}
```

两个处理器（`AuthenticationEntryPoint` 与 `AccessDeniedHandler`）都显式 `setStatus(200)`。

> `AccessDeniedHandler` 返回的是 `SYSTEM_ERROR`（10000）而非鉴权类错误码。它当前**几乎不会被触发**——项目没有用 `@PreAuthorize`，业务层的越权是在 Service 里抛 `BizException` 处理的。保留它只是兜底：将来若加了方法级权限，至少返回的仍是项目统一的 JSON 结构。

### 2.2 错误码与前端联动

| 码 | 含义 | 前端行为 |
|---|---|---|
| `30000` | 用户名或密码错误 | **不触发跳转**（登录页自身） |
| `30001` | 登录状态已失效 | 清空登录态 + 跳登录页 |

前端 `axios.js` 的响应拦截器：

```js
const AUTH_ERROR_CODES = ['30001']

if (res && res.success === false && AUTH_ERROR_CODES.includes(res.errorCode)) {
    const authStore = useAuthStore()
    authStore.clear()
    if (router.currentRoute.value.path !== '/login') {   // ← 防死循环
        router.push('/login')
    }
}
```

⚠️ **必须检查响应体，不能看 HTTP 状态码**——状态码这一层全是 200，只看 `response.status` 永远等不到鉴权失败。

⚠️ **`path !== '/login'` 这个判断不能去掉**。代码注释记录的原因：否则「登录接口返回 30001」会陷入死循环。它与 `AUTH_ERROR_CODES` 只收 `30001`（不收 `30000`）合起来，才让「密码错误不死循环」这个闭环成立。

`30000` 与 `30001` 的分工是这两条合起来才闭环的关键。

### 2.3 登录接口的枚举防护

```java
// ⚠️ 用户不存在与密码错误必须走同一个分支、返回同一个错误码——
//    否则接口就成了用户名枚举器
if (Objects.isNull(user) || !passwordEncoder.matches(password, user.getPasswordHash())) {
    log.warn("## 登录失败: username={}", String.valueOf(username).replaceAll("[\r\n]", "_"));
    throw new BizException(ResponseCodeEnum.AUTH_INVALID_CREDENTIALS);
}
```

⚠️ **已知取舍**：`user == null` 时直接抛出，比走完 BCrypt 少了几十毫秒，理论上存在**时间侧信道**可被用于同样的枚举。开发库的演示账号不构成实际风险，真要堵住需在 null 分支做一次等价的「假比对」，本次不做。

`replaceAll("[\r\n]", "_")` 是**日志注入防护**：`username` 直接来自请求体，含 `\r\n` 时会被原样写进日志、伪造出额外的日志行。外层的 `String.valueOf` 只是不让这条日志语句自身因 null 抛 NPE。

---

## 3. `AuthContext` 的调用边界

```java
public static Long getCurrentUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (Objects.isNull(authentication) || !(authentication.getPrincipal() instanceof Long userId)) {
        throw new BizException(ResponseCodeEnum.AUTH_TOKEN_INVALID);
    }
    return userId;
}
```

⚠️ **只判 null 不够，必须判类型**：未认证时 Spring Security 会塞一个 principal 为字符串 `"anonymousUser"` 的 `AnonymousAuthenticationToken`，它不是 null。

### 3.1 不能放进 Reactor 回调

`SecurityContextHolder` 默认是 `MODE_THREADLOCAL`，**流式回调可能跑在别的线程上**，在那里取到的是空的。

两条具体后果：

1. **归属校验**：所有校验都发生在进入流式返回之前（同步阶段），不受影响。
2. **流式落库**：`CustomStreamLoggerAndMessage2DBAdvisor` 的 `doFinally` 同样跑在 Reactor 回调里，**在那里调用本方法会抛 `BizException(30001)` 且整轮对话消息丢失**。

正确做法是**值传递**：在同步阶段取好 userId，作为构造参数传进 Advisor（见 `modules/chat.md` §6.5）。这也解释了为什么本项目的 Advisor 都是「每个请求 `new` 一个」而非单例。

---

## 4. SSE 的 ASYNC dispatch：身份为什么会丢

这是本模块最硬核的一段，也是**每个 SSE 请求稳定产生 3 条 ERROR 堆栈**的根因。

### 4.1 成因链条

三个事实叠加：

1. **两个 SSE 接口返回 `Flux`**。流跑完后，Tomcat 会做一次 **ASYNC dispatch**，把整条 Spring Security 过滤链**重跑一遍**。
2. **Spring Security 6 是「显式保存」模型**。`SecurityContextHolderFilter` 只从仓库读、**不往回写**。不显式保存的话，身份只活在 `ThreadLocal` 里，那一遍重跑读不到。
3. **`JwtAuthenticationFilter` 在 ASYNC 时被跳过**。它继承 `OncePerRequestFilter`，其 `shouldNotFilterAsyncDispatch()` **默认返回 `true`**——于是那一遍 token 不会重解析。

结果：重跑的那一遍既没有 ThreadLocal、仓库里也是空的，**身份退化成匿名**，`AuthorizationFilter` 对 `anyRequest().authenticated()` 判定失败抛 `AuthorizationDeniedException`。而响应这时**早已 committed**，`ExceptionTranslationFilter` 转不成正常响应，只能抛给 Tomcat。

### 4.2 修法

两处配合：

```java
// @Bean 方法必须保持 static
@Bean
public static SecurityContextRepository securityContextRepository() {
    return new RequestAttributeSecurityContextRepository();
}
```

```java
// 过滤器认证成功后显式保存
securityContextRepository.saveContext(SecurityContextHolder.getContext(), request, response);
```

**为什么用 `RequestAttributeSecurityContextRepository`**：请求属性作用域天然随请求结束而消失，不跨请求、不建会话，但仍能在**同一次请求**的 ASYNC dispatch 中被读到。

⚠️ **不能用默认的 `DelegatingSecurityContextRepository`**（内含 `HttpSessionSecurityContextRepository`）——它会在保存时建出 `HttpSession`，与 `SessionCreationPolicy.STATELESS` 的意图冲突。

⚠️ **`@Bean` 方法必须是 `static`**。`SecurityConfig` 的构造器依赖 `JwtAuthenticationFilter`，而该过滤器又依赖这个 Bean——写成实例方法会构成 `securityConfig → jwtAuthenticationFilter → securityConfig` 的循环依赖，**启动即失败**。静态 `@Bean` 方法无需先实例化配置类即可产出 Bean，循环由此断开。

回归测试：`SseAsyncDispatchSecurityTests`（用测试专用 SSE 接口 `Flux.just` 复现，不碰大模型）。

---

## 5. 两条防越权路径，刻意做成不同

这是本模块最有判断力的一段。项目里对「越权访问」有两种处理，**它们的差异是刻意的**。

| | 对话 | 知识库文件 |
|---|---|---|
| 资源可见性 | **私有**，只有本人可见 | **全局共享**，所有人都能在列表里看到 |
| 处理方式 | 归属条件**融进查询** | **显式比对** `uploader_id` |
| 越权时的返回 | 「此对话不存在」（20000） | 「无权操作该文件」（20010） |

### 5.1 对话侧：融进查询

```java
chatMapper.existsByUuidAndUserId(chatId, currentUserId)
```

`WHERE user_id = ?` 直接写进查询条件，越权自然退化成「查不到」。好处有二：不用写比对分支，且**不泄露资源是否存在**。

对私有资源，这是正确的——告诉攻击者「这个对话存在但不属于你」，等于确认了它的存在。

### 5.2 知识库侧：显式比对

```java
// 只有上传者本人能删除。这里刻意返回「无权操作」而不是「文件不存在」：
// 文件在列表里人人可见，谎称不存在只会让用户困惑——与对话侧的处理正好相反。
if (!Objects.equals(record.getUploaderId(), AuthContext.getCurrentUserId())) {
    throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NO_PERMISSION);
}
```

文件是**共享可见**的：列表里本来就显示着所有文件。此时还谎称"不存在"，用户只会困惑——他明明在列表里看见了。

**结论**：**越权该不该泄露资源存在性，取决于该资源本来就对操作者可见与否。** 不是「统一返回不存在更安全」那么简单。

### 5.3 为什么知识库列表不加 `WHERE uploader_id`

加了会让列表只剩自己的文件——但文件的语义就是共享的。

代价是必须**显式比对**（多写分支、且依赖每个写操作都记得加），这个代价是刻意接受的。

### 5.4 把安全约束做成编译器强制的

评审时发现并纠正的一处：`ChatMapper.selectPageList` / `touchUpdateTime` 是**改签名**而非新增重载。

理由：如果当初选择「保留旧方法 + 加一个带 userId 的重载」，那么旧方法 `selectPageList(current, size)` 就是一条**现成的越权入口**——只要有一处调用点漏改，漏洞就成立。改签名让编译器强制所有调用点更新，从根上消灭了这类遗漏。

---

## 6. 无状态 JWT 的固有代价

**签发后在有效期内始终有效，服务端没有会话可以失效。** 登出只能清本地状态（`authStore.clear()`）。

这是「为什么需要 refresh token / 黑名单机制」的切入——本项目**刻意不做**，理由见 `decisions.md`。

⚠️ **`logout` 必须显式 disable**。Spring Security 默认的 `LogoutFilter` 仍在链上时，`POST /logout` 会走它自己的分支，返回 **302 + `Location: /login?logout`**——既不是项目的 `Response` JSON、也不是 30001，是「放行清单只有 `/auth/login`」与「一律返回 Response」两条约定的反例。关掉之后 `POST /logout` 会落到认证链上，返回 30001 的 JSON，与其余接口一致。

> 顺带一提：`JwtAuthenticationFilter` 用 `@Component` 注册，会**被注册两次**（Security 链 + Servlet 容器）。`OncePerRequestFilter` 靠 `getAlreadyFilteredAttributeName()` 去重，而真正让「过滤器逻辑只执行一次」成立的是**两处注册共用同一个 Bean 实例**。消除双重注册需 `FilterRegistrationBean#setEnabled(false)`，评审时判定超出范围、当前行为正确，未做。

---

## 7. 前端

### 7.1 登录态持久化

`authStore`（Pinia）用 `pinia-plugin-persistedstate` 持久化，默认落 `localStorage`，刷新页面不掉登录态。存 `token` / `username` / `nickname`。

### 7.2 请求带上 token

```js
instance.interceptors.request.use((config) => {
    const authStore = useAuthStore()   // ⚠️ 必须在函数体内调用
    if (authStore.token) {
        config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
})
```

⚠️ `useAuthStore()` **不能提到模块顶层**——模块加载时 Pinia 还没被 `app.use()` 安装，顶层调用会直接抛错。

### 7.3 SSE 走 Vite proxy，不跨域直连

两个 SSE 页面用的是相对路径 `/api/chat/completion` 与 `/api/knowledge-base/completion`，**不硬编码 `localhost:8080`**（该地址只存在于 `vite.config.js` 的 proxy 配置里，换端口只改一处）。

**为什么不跨域直连**：加 `Authorization` 头后跨域直连会触发 `OPTIONS` 预检，而**预检请求不携带 Authorization**，必然被 `anyRequest().authenticated()` 拒掉。改走 proxy 后同源，后端无需配 CORS。

这也是 `SecurityConfig` 里 `.cors(disable)` 的原因。

⚠️ **`fetchEventSource` 的 `onopen` 必须检查 `content-type`**：token 失效时后端返回的是 HTTP 200 + JSON（项目的错误约定），而 `fetchEventSource` 只认 `text/event-stream`，不检查会报出与真实原因无关的解析错误。

---

## 8. 本模块坑点

1. **登录接口不得挂 `@ApiOperationLog`**。该切面会序列化入参和出参并打进日志，而登录的入参含明文密码、出参含 token——挂上就等于把两者写进 `logs/` 目录。改为在方法体内手工打一行不含敏感字段的日志。

2. **`SecurityConfig` 里 `cors` 的那段注释已经过时**。它写着「两个 SSE 接口在前端是硬编码跨源直连……改走 proxy 是 Task 8 的事」，而 Task 8 早已完成、SSE 现在走的是 proxy。注释的**结论**（当前不需要 CORS）仍然成立，但**理由**描述的是改造前的状态，读的时候别被带偏。

3. **`p6spy` 的 SQL 回显仍会原样打印未净化的参数值**。登录失败的日志做了 `\r\n` 剥离，但 `spy.properties` 打出的 SQL 不受这层保护——属于框架侧，未闭合。

4. **前端 `ChatPage.vue` 的 `closeSSE()` 目前是空操作**。它关闭的 `eventSource` 变量在 `sendMessage` 里只声明为 `null`、从未被赋值（`fetchEventSource` 的返回值没接），**切页时流不会被取消**。既有状态，未修。
