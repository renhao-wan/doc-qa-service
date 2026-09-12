package io.github.renhaowan.docqa.utils;

import io.github.renhaowan.docqa.enums.ResponseCodeEnum;
import io.github.renhaowan.docqa.exception.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 当前登录用户的上下文
 **/
public class AuthContext {

    private AuthContext() {
    }

    /**
     * 取当前登录用户 ID
     * <p>
     * ⚠️ 只在 Controller / Service 方法体内**同步**调用，不要放进 Reactor 的
     * {@code map} / {@code flatMap} 等回调里：{@code SecurityContextHolder} 默认是
     * {@code MODE_THREADLOCAL}，流式回调可能跑在别的线程上，取到的是空的。
     * 本项目的所有归属校验都发生在进入流式返回之前，不受影响。
     * <p>
     * 另外，流式回答的**落库**（{@code CustomStreamLoggerAndMessage2DBAdvisor} 的
     * {@code doFinally}）同样跑在 Reactor 回调里，在那里调用本方法一样取不到值。
     * 那种场景必须把 userId 在同步阶段取出后**值传递**进去（沿用本项目
     * 「每个请求 new 一个 Advisor 实例」的模式），不要在 advisor 内部调用本方法，
     * 否则会抛 {@code BizException(30001)} 且整轮对话消息丢失。
     *
     * @return 用户 ID
     * @throws BizException 未认证（正常不该发生——SecurityFilterChain 已经拦过一道，
     *                      这里是防御性兜底，也方便 Service 层被非 HTTP 场景调用时给出明确错误）
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 未认证时 Spring Security 会塞一个 principal 为字符串 "anonymousUser" 的
        // AnonymousAuthenticationToken，所以这里必须判类型而不是只判 null
        if (Objects.isNull(authentication) || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BizException(ResponseCodeEnum.AUTH_TOKEN_INVALID);
        }

        return userId;
    }
}
