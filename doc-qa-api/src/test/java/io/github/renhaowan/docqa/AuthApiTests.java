package io.github.renhaowan.docqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权接口的集成测试。
 * <p>
 * {@code @AutoConfigureMockMvc} 会把 Spring Security 的过滤器链一并装上，
 * 所以「未认证被拦」这类行为能在这里真实复现——这正是它比单元测试有价值的地方。
 * <p>
 * 依赖 t_user 里的预置账号（db/upgrade/2026-09-12-auth.sql）。
 * 登录是只读操作，加 {@code @Transactional} 主要是为了让测试对库零污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testLoginSuccess() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value("demo"));
    }

    @Test
    void testLoginWrongPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30000"));
    }

    @Test
    void testLoginUnknownUser() throws Exception {
        // 用户名不存在与密码错误必须返回同一个错误码，否则接口成了用户名枚举器
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("30000"));
    }

    @Test
    void testUnauthenticatedRequestRejected() throws Exception {
        // 不带 token 访问任意业务接口
        mockMvc.perform(post("/chat/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":10}"))
                .andExpect(status().isOk()) // ⚠️ 是 200，不是 401
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30001"));
    }

    @Test
    void testAuthenticatedRequestPasses() throws Exception {
        String token = login("demo", "demo123");

        mockMvc.perform(post("/chat/list")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * 登录并取出 token
     *
     * @param username 用户名
     * @param password 密码
     * @return JWT 字符串
     */
    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
