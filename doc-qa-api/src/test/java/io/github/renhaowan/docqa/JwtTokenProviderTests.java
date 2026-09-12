package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.utils.JwtTokenProvider;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtTokenProvider 的单元测试。
 * <p>
 * 刻意不启动 Spring：这个类的依赖只有「密钥」和「有效期」两个值，
 * 用构造器注入后可以 new 出来直接测，跑一次不到一秒。
 * 项目的 {@code @SpringBootTest} 测试要拉起整个上下文（含数据库连接池、pgvector），
 * 能不用就不用。
 */
class JwtTokenProviderTests {

    /**
     * ⚠️ HS256 要求密钥至少 256 bit（32 字节）。短于 32 字节时
     * {@code Keys.hmacShaKeyFor()} 会抛 WeakKeyException，
     * 表现出来是「应用启动就失败」，很容易被误认成配置没读到。
     */
    private static final String SECRET = "test-secret-must-be-at-least-32-bytes-long-0123456789";

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 7);

    @Test
    void testGenerateAndParse() {
        String token = provider.generateToken(42L, "demo");

        assertEquals(42L, provider.parseUserId(token));
        assertEquals("demo", provider.parseUsername(token));
    }

    @Test
    void testTamperedTokenRejected() {
        String token = provider.generateToken(42L, "demo");
        // 篡改位置取「倒数第 5 个字符」而不是最后一个字符，是为健壮性 —— 两种位置在本测试下都稳定。
        // 通用机制：无填充 base64url 的末字符不必然携带完整的 6 bit。当签名比特数不能被 6 整除时，
        // 末字符会有若干低位比特被丢弃，改动它可能解出与原签名逐字节相同的字节串，校验照样通过。
        // 本测试的实际配置：密钥 53 字节 → jjwt 按密钥长度选 HmacSHA384 → 48 字节签名
        // （384 bit 恰能被 6 整除）→ 64 字符编码、无丢弃 bit，因此改末字符也是安全的；
        // 倒数第 5 个字符则与签名长度无关地安全，故取此位置。
        int index = token.length() - 5;
        String tampered = token.substring(0, index)
                + (token.charAt(index) == 'A' ? 'B' : 'A')
                + token.substring(index + 1);

        assertThrows(JwtException.class, () -> provider.parseUserId(tampered));
    }

    @Test
    void testExpiredTokenRejected() {
        // 有效期为负 → 过期时间落在过去
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1);
        String token = expiredProvider.generateToken(42L, "demo");

        assertThrows(ExpiredJwtException.class, () -> provider.parseUserId(token));
    }

    @Test
    void testNullAndEmptyTokenRejected() {
        // token 为 null 或空串时，jjwt 在验签之前就抛 IllegalArgumentException，
        // 而不是 JwtException —— 调用方（如 JwtAuthenticationFilter）两类都必须捕获，
        // 只 catch JwtException 会让这两种输入漏成 500。
        assertThrows(IllegalArgumentException.class, () -> provider.parseUserId(null));
        assertThrows(IllegalArgumentException.class, () -> provider.parseUserId(""));
    }
}
