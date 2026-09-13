package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import io.github.renhaowan.docqa.domain.mapper.ChatMessageMapper;
import io.github.renhaowan.docqa.filter.JwtAuthenticationFilter;
import io.github.renhaowan.docqa.service.AuthService;
import io.github.renhaowan.docqa.service.ChatService;
import io.github.renhaowan.docqa.service.KnowledgeBaseService;
import io.github.renhaowan.docqa.service.SearchResultContentFetcherService;
import io.github.renhaowan.docqa.service.SearXNGService;
import io.github.renhaowan.docqa.tool.WebSearchTool;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: Controller 入参校验的契约测试——每个接口的每个必填字段都必须以 10001 被拒
 * <p>
 * 守住的是接口对客户端的**承诺**：漏传字段时返回「参数错误(10001)」并在 message 里点名是哪个字段。
 * 这条承诺最常见的失效方式是<b>忘了给参数加 {@code @Validated}</b>——校验注解就成了一行注释，
 * 请求会一路走到方法体里，在某个 {@code Paths.get(..., null)} 或 {@code .toString()} 上抛 NPE，
 * 再被 {@code GlobalExceptionHandler} 兜底成「系统错误(10000)」。前端只能看到
 * 「后台小哥正在努力修复中」，真正的原因（客户端漏传字段）完全看不出来。
 * {@code uploadChunk} 就漏过一次，本类正是为了它而写。
 * <p>
 * <b>用例是反射发现的，不是手写的表格。</b>接口列表来自 {@link RequestMappingHandlerMapping}，
 * 必填字段来自 VO 上的 {@code @NotBlank}/{@code @NotNull}。因此新增接口、给已有 VO 加必填字段
 * 都会**自动**纳入覆盖，不存在「加了代码忘了加测试」的漂移；反过来，
 * 如果新增的 Controller 引入了本项目还没 mock 的依赖，上下文启动就会失败并报出缺失的 bean——
 * 这是个显式的失败，比静默漏测好得多。
 * <p>
 * ⚠️ <b>本类守的是「已经标了的约束确实生效」，不是「该标的都标了」。</b>下面两类缺口它抓不到，
 * 因为反射只能看见**存在**的注解，看不见**缺失**的注解，而一个字段该不该必填是业务判断：
 * <ul>
 *   <li><b>该必填却没标</b>——字段静默允许为 null，没有任何用例会生成。</li>
 *   <li><b>标了之后又删掉</b>——该字段的用例跟着消失，而接口本身仍在清单里，
 *       {@code discoveredEndpointsShouldMatchTheKnownList} 也拦不住。测试全绿，
 *       覆盖却少了一条。这是本类最大的盲区，也是刻意接受的：
 *       想堵住它就得把「每个接口有几个必填字段」也手工登记，
 *       而增删字段是常规操作，登记表会被改到失去意义。</li>
 * </ul>
 * <p>
 * ⚠️ <b>两个 SSE 接口只测校验失败路径，不测「参数齐全」路径。</b>{@code KnowledgeBaseController.chat}
 * 的方法体里没有任何前置守卫，会直接 {@code new OpenAiChatModel(...)} 并 {@code .stream()}——
 * 参数齐全时请求真的会打到阿里云百炼，既花钱又让测试依赖外网。{@code ChatController.chat} 恰好
 * 因为最先调用 {@code AuthContext.getCurrentUserId()}（无认证时抛 30001）而不会走到联网那一步，
 * 但那是**方法体内语句顺序**带来的巧合，不是可以依赖的性质：哪天有人把归属校验往后挪一行，
 * 这个测试就会开始偷偷联网。所以按返回类型（{@code Flux}）一刀切，两个都不测 happy path。
 * 校验失败则发生在进入方法体之前，两个接口都安全。
 * <p>
 * <b>为什么用 {@code @WebMvcTest} 而不是 {@code @SpringBootTest}</b>：本类只关心
 * 「请求绑定 + 校验 + 异常转错误码」这条 Web 层链路，不起 Service 与数据库。
 * 切片上下文启动约 2 秒，可以放进「改完随手跑一遍」的快速反馈环。
 * {@code addFilters = false} 关掉鉴权过滤器链——测的是绑定与校验，不是身份。
 * 代价是**不带 token 也能调到接口**，这是刻意的：一旦哪天有人把校验注解挪到
 * 只有鉴权后才会生效的位置，这组测试会立刻发现。
 **/
@WebMvcTest(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class ControllerParamValidationTests {

    /** 错误码字面量直接写死：本类断言的就是「对外的错误码」，引用常量会让测试跟着实现一起变 */
    private static final String PARAM_NOT_VALID = "10001";

    /** 校验注解所在包。只认这个包下的注解，避免把 Lombok 的注解误当成约束 */
    private static final String CONSTRAINT_PACKAGE = "jakarta.validation.constraints.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    // ---------- 反射发现出来的 Controller 依赖，全部 mock ----------

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;
    @MockitoBean
    private ChatService chatService;
    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private VectorStore vectorStore;
    @MockitoBean
    private WebSearchTool webSearchTool;
    @MockitoBean
    private ChatMessageMapper chatMessageMapper;
    @MockitoBean
    private ChatMapper chatMapper;
    @MockitoBean
    private TransactionTemplate transactionTemplate;
    @MockitoBean
    private SearXNGService searXNGService;
    @MockitoBean
    private SearchResultContentFetcherService searchResultContentFetcherService;

    // ---------- 防空转 ----------

    /**
     * 反射一旦失效（例如 Spring 换了注册 handler 的方式、或注解包被改名），
     * 后面两个工厂会产出 0 个用例——而「没有用例」在 JUnit 里等于「全部通过」，
     * 测试会变成一片沉默的绿。这条断言是专门用来把这个沉默变成失败的。
     */
    @Test
    void shouldDiscoverEndpointsAndConstraints() {
        List<Endpoint> endpoints = discoverEndpoints();

        assertThat(endpoints)
                .as("没有发现任何带校验注解的接口——反射发现逻辑已经失效，后面的用例全是空跑")
                .isNotEmpty();

        long constrainedFields = endpoints.stream()
                .flatMap(endpoint -> endpoint.requiredFields().stream())
                .count();
        assertThat(constrainedFields)
                .as("发现了接口但一个必填字段都没读到，同样是反射失效")
                .isPositive();

        assertThat(endpoints).anySatisfy(endpoint -> assertThat(endpoint.url()).contains("/auth/login"));
    }

    /**
     * 被覆盖的接口必须**恰好**是这一份清单。
     * <p>
     * ⚠️ 这条断言是本类唯一的防线，挡住的是反射发现最大的弱点——
     * <b>注解被摘掉之后用例会静默消失</b>。上面两条工厂都是「发现什么就测什么」：
     * 有人给某个接口去掉了 {@code @Validated}（或删光了 VO 上的必填注解），
     * 该接口就从发现结果里消失，对应的用例跟着消失，测试**依然全绿**——
     * 覆盖少了，灯却是绿的。这正是最初那个缺口（{@code uploadChunk} 漏了
     * {@code @Validated}）的表现形式，靠动态用例是抓不住的。
     * <p>
     * 所以这里保留一份需要**手工维护**的清单。它换来的性质是：新增接口、删除接口、
     * 摘掉某个接口的校验注解，都必须在这一次断言上停下来交代清楚——要么登记，
     * 要么解释为什么这个接口不该带校验。
     * <p>
     * 它挡不住的是**字段级**的静默消失：删掉某个 VO 上某个字段的必填注解，
     * 该字段的用例没了，但接口本身还在清单里，这条断言不会响。为什么不再往下
     * 登记到字段一级，见类头 Javadoc 的说明。
     * <p>
     * 清单里没有 {@code /chat/list} 与 {@code /knowledge-base/md/list}：
     * 它们的 VO 上只有非必填约束（如分页大小的 {@code @Min}），
     * 没有必填字段就产不出「缺字段」用例，按当前设计被跳过。
     */
    @Test
    void discoveredEndpointsShouldMatchTheKnownList() {
        assertThat(discoverEndpoints())
                .extracting(Endpoint::url)
                .containsExactlyInAnyOrder(
                        "/auth/login",
                        "/chat/completion",
                        "/chat/delete",
                        "/chat/message/list",
                        "/chat/new",
                        "/chat/summary/rename",
                        "/knowledge-base/completion",
                        "/knowledge-base/file/check",
                        "/knowledge-base/file/merge-chunk",
                        "/knowledge-base/file/upload-chunk",
                        "/knowledge-base/md/delete",
                        "/knowledge-base/md/update");
    }

    // ---------- 必填字段缺失 → 10001 ----------

    /**
     * 每个接口 × 每个必填字段一条用例。
     * <p>
     * 缺失方式统一为「该字段传 null / 干脆不传」，因为那才是真实的故障形态——
     * 客户端漏传字段，而不是故意传空串。
     */
    @TestFactory
    Stream<DynamicTest> missingRequiredFieldShouldBeRejectedAsParamError() {
        return discoverEndpoints().stream()
                .flatMap(endpoint -> endpoint.requiredFields().stream()
                        .map(field -> dynamicTest(
                                endpoint.displayName() + " 缺少 " + field.getName(),
                                () -> {
                                    String body = performRequest(endpoint, field.getName());

                                    assertThat(body)
                                            .as("%s 缺少必填字段 %s 时没有返回参数错误(10001)，实际响应：%s",
                                                    endpoint.displayName(), field.getName(), body)
                                            .contains("\"errorCode\":\"" + PARAM_NOT_VALID + "\"");

                                    // 只断言「是参数错误」还不够：如果 VO 上有两个必填字段而错误信息
                                    // 报的是另一个，说明这个字段的注解根本没生效。点名才算数。
                                    assertThat(body)
                                            .as("%s 报的错误信息里没有点名 %s，可能报的是别的字段",
                                                    endpoint.displayName(), field.getName())
                                            .contains(field.getName());
                                })));
    }

    // ---------- 字段齐全 → 不得被判成参数错误 ----------

    /**
     * 每个接口一条用例，断言校验没有误伤。
     * <p>
     * 断言的是「不是 10001」而不是「请求成功」——参数齐全之后，请求会进入方法体，
     * 而本类把 Service 全部 mock 掉了、也没有登录态，方法体里可能因为
     * {@code AuthContext.getCurrentUserId()} 拿不到认证而抛 30001。
     * 那与「入参校验」无关，不该让本测试变红。
     * <p>
     * 这条用例能抓住的真实故障是：给必填字段加了过严的约束（例如给可选的
     * {@code fileName} 加了 {@code @NotBlank} 却在某些调用方那里本来就允许不传），
     * 上线后表现为「客户端什么都没改，接口突然开始报参数错误」。
     */
    @TestFactory
    Stream<DynamicTest> completeParamsShouldNotBeRejectedAsParamError() {
        return discoverEndpoints().stream()
                .filter(endpoint -> !endpoint.streaming())
                .map(endpoint -> dynamicTest(
                        endpoint.displayName() + " 字段齐全时不应被判为参数错误",
                        () -> {
                            String body = performRequest(endpoint, null);

                            assertThat(body)
                                    .as("%s 在字段齐全时被判成了参数错误，实际响应：%s",
                                            endpoint.displayName(), body)
                                    .doesNotContain("\"errorCode\":\"" + PARAM_NOT_VALID + "\"");
                        }));
    }

    // ---------- 发请求 ----------

    /**
     * 按接口的绑定方式构造并发送请求。
     *
     * @param omittedField 要故意留空的字段名；传 {@code null} 表示把字段全部填上
     * @return 响应体原文
     */
    private String performRequest(Endpoint endpoint, String omittedField) throws Exception {
        MockHttpServletRequestBuilder request;

        if (endpoint.isRequestBody()) {
            request = post(endpoint.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JsonBodies.build(endpoint.voType(), omittedField));
        } else {
            // @ModelAttribute：每个字段一个 form part。MultipartFile 走 file part，其余走普通 param
            MockMultipartHttpServletRequestBuilder multipartRequest = multipart(endpoint.url());
            for (Field field : fieldsOf(endpoint.voType())) {
                if (field.getName().equals(omittedField)) {
                    continue;
                }
                Object value = sampleValue(field.getType());
                if (value instanceof MockMultipartFile file) {
                    multipartRequest.file(file);
                } else if (Objects.nonNull(value)) {
                    multipartRequest.param(field.getName(), String.valueOf(value));
                }
            }
            request = multipartRequest;
        }

        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    // ---------- 反射发现 ----------

    /**
     * 枚举所有「参数带 {@code @Validated} 的接口」及其 VO 上的必填字段。
     * <p>
     * 只收 {@code @RequestBody} 与 {@code @ModelAttribute} 两种绑定——它们是
     * 本项目 VO 的全部用法，也是 {@code @Validated} 唯一能起作用的地方。
     */
    private List<Endpoint> discoverEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Method method = entry.getValue().getMethod();

            if (info.getPatternValues().isEmpty()) {
                continue;
            }
            String url = info.getPatternValues().iterator().next();

            for (Parameter parameter : method.getParameters()) {
                boolean isBody = parameter.isAnnotationPresent(RequestBody.class);
                boolean isModelAttribute = parameter.isAnnotationPresent(ModelAttribute.class);
                if (!isBody && !isModelAttribute) {
                    continue;
                }

                // 有 @Validated 的接口被纳入覆盖；没有的直接跳过——「忘了加 @Validated」
                // 恰恰是本类要抓的缺口，但它只能通过「这个接口一个用例都没有」间接暴露，
                // 所以另有 shouldDiscoverEndpointsAndConstraints 兜底防止整体空转
                if (!hasValidated(parameter)) {
                    continue;
                }

                Class<?> voType = parameter.getType();
                List<Field> required = requiredFieldsOf(voType);
                if (required.isEmpty()) {
                    continue;
                }

                endpoints.add(new Endpoint(url, voType, isBody, isStreaming(method), required));
            }
        }

        endpoints.sort((a, b) -> a.displayName().compareTo(b.displayName()));
        return endpoints;
    }

    /**
     * 返回类型是不是 {@code Flux}（也就是两个 SSE 接口）。
     * <p>
     * 用类名比较而不是 {@code Flux.class.isAssignableFrom(...)}，省一个 reactor 的 import；
     * 这里只需要认这一个类型，也没有子类要照顾。
     */
    private static boolean isStreaming(Method method) {
        return "reactor.core.publisher.Flux".equals(method.getReturnType().getName());
    }

    private static boolean hasValidated(Parameter parameter) {
        for (Annotation annotation : parameter.getAnnotations()) {
            // @Validated（Spring）与 @Valid（Jakarta）都算；用简单名匹配避免多引一个包
            String name = annotation.annotationType().getSimpleName();
            if ("Validated".equals(name) || "Valid".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 遍历字段与父类字段，收集所有标了 {@code jakarta.validation.constraints.*} 注解的 */
    private static List<Field> requiredFieldsOf(Class<?> voType) {
        List<Field> required = new ArrayList<>();
        for (Field field : fieldsOf(voType)) {
            if (hasConstraint(field)) {
                required.add(field);
            }
        }
        return required;
    }

    private static List<Field> fieldsOf(Class<?> voType) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = voType; current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(List.of(current.getDeclaredFields()));
        }
        return fields;
    }

    private static boolean hasConstraint(Field field) {
        for (Annotation annotation : field.getAnnotations()) {
            if (annotation.annotationType().getName().startsWith(CONSTRAINT_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    /** 按字段类型造一个「合法」的样本值；返回 null 表示这个类型本类填不出来，请求里就不带这个字段 */
    private static Object sampleValue(Class<?> type) {
        if (type == String.class) {
            return "x";
        }
        if (type == Integer.class || type == int.class) {
            return 1;
        }
        if (type == Long.class || type == long.class) {
            return 1L;
        }
        if (type == Double.class || type == double.class) {
            return 1.0D;
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (MultipartFile.class.isAssignableFrom(type)) {
            return new MockMultipartFile("chunk", "chunk.bin",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});
        }
        return null;
    }

    /**
     * @param url           请求路径
     * @param voType        入参 VO
     * @param requestBody   true 为 {@code @RequestBody}（JSON），false 为 {@code @ModelAttribute}（form/multipart）
     * @param requiredFields VO 上标注了必填约束的字段
     */
    private record Endpoint(String url, Class<?> voType, boolean isRequestBody,
                            boolean streaming, List<Field> requiredFields) {

        String displayName() {
            return url;
        }
    }

    /** 按 VO 字段拼 JSON 请求体；{@code omittedField} 对应的字段写成 null */
    private static final class JsonBodies {

        private JsonBodies() {
        }

        static String build(Class<?> voType, String omittedField) {
            Map<String, Object> body = new LinkedHashMap<>();

            for (Field field : fieldsOf(voType)) {
                if (field.getName().equals(omittedField)) {
                    continue;
                }
                Object value = sampleValue(field.getType());
                if (Objects.nonNull(value) && !(value instanceof MultipartFile)) {
                    body.put(field.getName(), value);
                }
            }

            // Jackson 默认的 JsonInclude 是 ALWAYS，缺失的字段本来就不会出现在 map 里，
            // 这里只需保证「该在的都在」，不需要额外处理 null
            return toJson(body);
        }

        /** 不引 ObjectMapper：入参只有字符串与数字，手拼即可，且避免为测试再注入一个 bean */
        private static String toJson(Map<String, Object> body) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String) {
                    sb.append('"').append(value).append('"');
                } else {
                    sb.append(value);
                }
            }
            return sb.append('}').toString();
        }
    }
}
