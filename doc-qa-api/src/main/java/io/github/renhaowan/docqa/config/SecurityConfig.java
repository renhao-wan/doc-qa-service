package io.github.renhaowan.docqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.docqa.enums.ResponseCodeEnum;
import io.github.renhaowan.docqa.filter.JwtAuthenticationFilter;
import io.github.renhaowan.docqa.utils.Response;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: Spring Security 配置
 **/
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * JSON 序列化器。Spring Boot 已自动配置好一个（含 JavaTimeModule 等），直接注入即可，
     * 不要自己 new ObjectMapper——那样会丢掉全局的时间格式等配置。
     */
    private final ObjectMapper objectMapper;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(ObjectMapper objectMapper, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.objectMapper = objectMapper;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 把 JWT 过滤器插在用户名密码过滤器之前——本项目不用表单登录，
                // 这个位置实际就是「所有授权判断之前」，正是解析 token 的时机
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 无状态 JWT：不需要 CSRF token（没有 cookie 会话可被伪造），
                // 也不需要 HttpSession（每个请求都靠 token 自证身份）
                .csrf(AbstractHttpConfigurer::disable)
                // ⚠️ 暂时不要加 CORS 配置，但原因不是「前端本来就同源」——那只是 axios 那条路径的现状。
                //    Vite proxy 只覆盖走 axios 的接口；两个 SSE 接口在前端是**硬编码跨源直连**
                //    http://localhost:8080（ChatPage.vue、CustomerServiceChatPage.vue），
                //    改走 proxy 是 Task 8 的事。在那之前给它们加上 Authorization: Bearer，
                //    浏览器就会先发 OPTIONS 预检，而预检请求不携带 Authorization，
                //    于是落到下面的 anyRequest().authenticated() 上被回 30001。
                //    也就是说：终态（Task 8 之后）仍同源，届时前后端都不需要 CORS；
                //    如果将来真出现独立域名部署，再开 CORS，且必须一并放行 OPTIONS 预检。
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 放行清单只有登录。其余接口——包括两个 SSE 接口——全部要求认证。
                        // 业务侧的越权判断（A 能不能操作 B 的资源）在这一层管不了，
                        // 由 Service 层把归属条件融进 SQL 解决，见 ChatServiceImpl。
                        .requestMatchers("/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                // 关掉默认的 httpBasic 与 formLogin：它们会给未认证请求回一个浏览器登录框
                // 和 WWW-Authenticate 头，前端拿到的东西与本项目「一律 Response JSON」的约定冲突
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // 同样关掉默认的 logout：LogoutFilter 会让 POST /logout 走 Spring Security
                // 自己的分支，返回 302 + Location: /login?logout，既不是项目的 Response JSON、
                // 也不是 30001，是「放行清单只有 /auth/login」与「一律返回 Response」的反例。
                // 无状态 JWT 本来就没有会话可失效，前端退出登录只清本地状态（见 authStore.clear()），
                // 关掉它之后 POST /logout 会落到认证链上，返回 30001 的 JSON，与其余接口一致。
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * 密码编码器。
     * <p>
     * BCrypt 自带随机 salt，同一个密码每次编码结果都不同；校验走 {@code matches()}，
     * 不能拿编码结果做字符串相等比较。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 未认证处理器：token 缺失 / 无效 / 过期都走这里。
     * <p>
     * ⚠️ Spring Security 的鉴权失败**不经过 GlobalExceptionHandler**，
     * 默认返回空体或 HTML 登录页，与项目「一律返回 Response」的约定冲突。
     * 所以这里手工写出 JSON，并显式把 HTTP 状态码设成 200——
     * 项目的错误约定是 **HTTP 200 + success:false**，前端拦截器也是按响应体判断的。
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            log.warn("## 未认证访问被拒: {} {}", request.getMethod(), request.getRequestURI());
            writeJson(response, Response.fail(ResponseCodeEnum.AUTH_TOKEN_INVALID));
        };
    }

    /**
     * 权限不足处理器。
     * <p>
     * 当前没有用方法级权限注解（{@code @PreAuthorize}），所以它几乎不会被触发——
     * 业务层的越权是在 Service 里抛 BizException 处理的。
     * 保留只是为了兜底：一旦将来加了方法级权限，至少返回的仍是项目统一的 JSON 结构。
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            log.warn("## 权限不足: {} {}", request.getMethod(), request.getRequestURI());
            writeJson(response, Response.fail(ResponseCodeEnum.SYSTEM_ERROR));
        };
    }

    private void writeJson(HttpServletResponse response, Response<?> body) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
