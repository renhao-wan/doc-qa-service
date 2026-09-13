package io.github.renhaowan.docqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE（异步 Servlet）请求在 ASYNC dispatch 上的鉴权测试。
 * <p>
 * 背景：两个 SSE 接口返回 {@code Flux}，Spring MVC 因此走异步处理。流跑完后容器会再做一次
 * <b>ASYNC dispatch</b>，把整条 Spring Security 过滤链重跑一遍。此时若 {@code SecurityContext}
 * 没有被持久化，身份会退化成匿名，{@code AuthorizationFilter} 就会抛
 * {@code AuthorizationDeniedException}——而响应这时早已 committed，异常无法被转成正常响应，
 * 最终以 ERROR 堆栈的形式进日志。
 * <p>
 * 用测试专用的 SSE 接口（{@code Flux.just}）而不是真实业务接口，是为了把「异步 dispatch 上的鉴权」
 * 与「调用大模型」解耦：后者需要外网与 API Key，会把一个纯粹的过滤器链问题变成不稳定测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SseAsyncDispatchSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class SseTestConfig {

        @Bean
        SseTestController sseTestController() {
            return new SseTestController();
        }
    }

    @RestController
    static class SseTestController {

        @GetMapping(value = "/test/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<String> sse() {
            return Flux.just("a", "b");
        }
    }

    @Test
    void asyncDispatchKeepsAuthenticated() throws Exception {
        String token = login();

        // 第一次 dispatch：正常的 REQUEST，此时 JwtAuthenticationFilter 会解析 token
        MvcResult mvcResult = mockMvc.perform(get("/test/sse")
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 第二次 dispatch：ASYNC。这一步若身份丢失，就会被 AuthorizationFilter 拒掉
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
    }

    /**
     * 登录并取出 token
     */
    private String login() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
