package io.github.renhaowan.docqa.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: JWT 的签发与解析
 * <p>
 * ⚠️ 签名算法是**按键长自动选档**的，不是写死的 HS256：{@code Keys.hmacShaKeyFor()} 会按密钥
 * 字节数挑 HS256 / HS384 / HS512（阈值见构造器注释），短于 32 字节直接抛 {@code WeakKeyException}。
 * 也就是说换了 {@code JWT_SECRET} 就等于换了算法——dev 默认密钥 41 字节 → 实际 HS256。
 **/
@Component
@Slf4j
public class JwtTokenProvider {

    /** 用户名的 claim 键名；用户 ID 放标准的 subject，便于直接读 */
    private static final String CLAIM_USERNAME = "username";

    /**
     * 开发环境的内置默认密钥（application-dev.yml 中 ${JWT_SECRET:...} 的默认值）。
     * 它随源码公开在版本库里，命中即说明当前实例未通过环境变量配置 JWT_SECRET。
     */
    private static final String INSECURE_DEV_SECRET = "dev-only-insecure-secret-override-in-prod";

    private final SecretKey key;
    private final long expireDays;

    /**
     * 用构造器注入而不是字段注入：这个类除了配置没有别的依赖，
     * 构造器注入让单元测试可以 {@code new JwtTokenProvider(secret, 7)} 直接构造，
     * 不必启动 Spring 上下文。
     *
     * @param secret     签名密钥，长度决定实际签名算法（≥64 字节 → HS512、≥48 → HS384、
     *                   ≥32 → HS256；短于 32 字节启动即失败）
     * @param expireDays token 有效期（天）
     */
    public JwtTokenProvider(@Value("${auth.jwt.secret}") String secret,
                            @Value("${auth.jwt.expire-days}") long expireDays) {
        if (INSECURE_DEV_SECRET.equals(secret)) {
            log.warn("⚠️ 当前使用内置的开发默认 JWT 密钥，任何人都可据此伪造 token！"
                    + "部署到本机之外前，必须通过环境变量 JWT_SECRET 覆盖。");
        }

        // Keys.hmacShaKeyFor 会在密钥短于 256 bit 时抛 WeakKeyException。
        // 这是刻意的失败：与其用一个弱密钥签发 token，不如启动时就报错。
        // 密钥不短于 256 bit 时，它按字节数自动选档：≥64 → HS512、≥48 → HS384、≥32 → HS256。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireDays = expireDays;
    }

    /**
     * 签发 token
     *
     * @param userId   用户 ID，写入标准 subject
     * @param username 用户名，写入自定义 claim
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + Duration.ofDays(expireDays).toMillis());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    /**
     * 解析出用户 ID
     *
     * @param token JWT 字符串
     * @return 用户 ID
     * @throws io.jsonwebtoken.JwtException 签名无效 / 格式错误 / 已过期（过期是子类 ExpiredJwtException）
     * @throws IllegalArgumentException     token 为 null 或空串时 jjwt 在解析前即抛出；
     *                                      sub 缺失或非数字时 {@code Long.valueOf} 抛出的 NumberFormatException 同属此类
     */
    public Long parseUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /**
     * 解析出用户名
     *
     * @param token JWT 字符串
     * @return 用户名；token 不含 {@code username} claim 时返回 {@code null}
     * @throws io.jsonwebtoken.JwtException 签名无效 / 格式错误 / 已过期（过期是子类 ExpiredJwtException）
     * @throws IllegalArgumentException     token 为 null 或空串，jjwt 在解析前即抛出
     */
    public String parseUsername(String token) {
        return parseClaims(token).get(CLAIM_USERNAME, String.class);
    }

    /**
     * 验签并取出 payload。
     * <p>
     * 没有单独校验 exp：jjwt 在 parse 阶段就会比对过期时间并抛 ExpiredJwtException。
     *
     * @param token JWT 字符串
     * @return payload
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
