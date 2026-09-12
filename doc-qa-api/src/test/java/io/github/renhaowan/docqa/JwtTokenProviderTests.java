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
        // ⚠️ 改动位置取「倒数第 5 个字符」，不能取最后一个：
        //    HS256 签名是 32 字节 = 256 bit，base64url 编码出 43 个字符（43 × 6 = 258 bit），
        //    第 43 个字符只有高 4 位参与解码、低 2 位被丢弃 —— 改动它若高 4 位不变，
        //    解出的签名与原签名逐字节相同，校验照样通过，测试会假通过。
        //    实测该假通过概率约 6.2%（4/64），是实打实的 flaky。
        //    倒数第 5 个字符完全落在 256 bit 的有效范围内，改动它必然改变签名。
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
}
