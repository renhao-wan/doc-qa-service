package io.github.renhaowan.docqa.controller;

import io.github.renhaowan.docqa.model.vo.auth.LoginReqVO;
import io.github.renhaowan.docqa.model.vo.auth.LoginRspVO;
import io.github.renhaowan.docqa.service.AuthService;
import io.github.renhaowan.docqa.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 认证
 **/
@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    @Resource
    private AuthService authService;

    /**
     * 登录
     * <p>
     * ⚠️ 刻意**不加** {@code @ApiOperationLog}：该切面会在方法执行前后序列化入参和出参并打进日志，
     * 而登录的入参含明文密码、出参含 token——挂上就等于把两者写进 logs/ 目录。
     * 这与切面里已有的 MultipartFile 脱敏是同类考虑，但登录是唯一的敏感场景，
     * 不值得为此改造切面引入一套通用脱敏机制。改为在方法体内手工打一行不含敏感字段的日志。
     *
     * @param loginReqVO 用户名与密码
     * @return token 与用户基本信息
     */
    @PostMapping("/login")
    public Response<LoginRspVO> login(@RequestBody @Validated LoginReqVO loginReqVO) {
        LoginRspVO loginRspVO = authService.login(loginReqVO);
        log.info("## 用户登录成功: username={}", loginRspVO.getUsername());
        return Response.success(loginRspVO);
    }
}
