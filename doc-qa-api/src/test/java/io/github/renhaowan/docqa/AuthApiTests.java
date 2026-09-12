package io.github.renhaowan.docqa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
}
