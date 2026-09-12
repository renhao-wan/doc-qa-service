package io.github.renhaowan.docqa.service.impl;

import io.github.renhaowan.docqa.domain.dos.UserDO;
import io.github.renhaowan.docqa.domain.mapper.UserMapper;
import io.github.renhaowan.docqa.enums.ResponseCodeEnum;
import io.github.renhaowan.docqa.exception.BizException;
import io.github.renhaowan.docqa.model.vo.auth.LoginReqVO;
import io.github.renhaowan.docqa.model.vo.auth.LoginRspVO;
import io.github.renhaowan.docqa.service.AuthService;
import io.github.renhaowan.docqa.utils.JwtTokenProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 认证
 **/
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Override
    public LoginRspVO login(LoginReqVO loginReqVO) {
        String username = loginReqVO.getUsername();
        String password = loginReqVO.getPassword();

        UserDO user = userMapper.selectByUsername(username);

        // ⚠️ 用户不存在与密码错误必须走同一个分支、返回同一个错误码——
        //    否则接口就成了用户名枚举器（攻击者可据此判断哪些账号真实存在）。
        //    已知取舍：user 为 null 时直接抛出，比走完 BCrypt 少了几十毫秒，
        //    理论上存在时间侧信道可被用于同样的枚举。开发库的演示账号不构成实际风险，
        //    真要堵住需在 null 分支做一次等价的「假比对」，本次不做（见 spec §10）。
        if (Objects.isNull(user) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // username 直接来自请求体，含 \r\n 时会被原样写进日志、伪造出额外的日志行，写前先剥离。
            // String.valueOf 只是不让这条日志语句自身因 null 抛 NPE（Controller 路径有 @NotBlank 兜底）
            log.warn("## 登录失败: username={}", String.valueOf(username).replaceAll("[\r\n]", "_"));
            throw new BizException(ResponseCodeEnum.AUTH_INVALID_CREDENTIALS);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        return LoginRspVO.builder()
                .token(token)
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
    }
}
