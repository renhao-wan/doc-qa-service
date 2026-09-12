package io.github.renhaowan.docqa.filter;

import io.github.renhaowan.docqa.utils.JwtTokenProvider;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 解析 Authorization: Bearer 中的 JWT，写入 SecurityContext
 **/
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    /**
     * 前缀比较必须忽略大小写：RFC 6750 规定 scheme 大小写不敏感。
     * 客户端 SDK、网关、代理都可能把 scheme 规范成小写（{@code bearer ...}），
     * 按字面比较会把**有效**的 token 拒掉，而日志里只留下一条「未认证」，
     * 排查方向会被带偏到「token 过期/伪造」上去。
     */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);

        if (StringUtils.isNotBlank(header)
                && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length());

            try {
                Long userId = jwtTokenProvider.parseUserId(token);

                // principal 直接放 userId（Long），AuthContext 取出来即用。
                // 第三个参数是权限列表——本项目没有角色体系，给空集合。
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (ExpiredJwtException e) {
                // 过期与签名无效对外返回同一个错误码，但日志里必须分开：
                // 「用户的 token 到期了」和「有人在伪造 token」是性质完全不同的两件事
                log.info("## token 已过期: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            } catch (JwtException | IllegalArgumentException e) {
                log.warn("## token 无效: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        // 无论解析成功与否都继续走链：
        // 没解析出身份时，后续的 authorizeHttpRequests 会因为「未认证」而拒绝，
        // 由 SecurityConfig 里的 AuthenticationEntryPoint 统一写出 JSON。
        // 在这里直接写响应会让错误格式散落两处，且拿不到 RequestMatcher 的放行清单。
        filterChain.doFilter(request, response);
    }
}
