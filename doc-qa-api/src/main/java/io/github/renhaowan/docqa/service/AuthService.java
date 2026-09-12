package io.github.renhaowan.docqa.service;

import io.github.renhaowan.docqa.model.vo.auth.LoginReqVO;
import io.github.renhaowan.docqa.model.vo.auth.LoginRspVO;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 认证
 **/
public interface AuthService {

    /**
     * 登录：校验账号密码并签发 token
     *
     * @param loginReqVO 用户名与密码
     * @return token 与用户基本信息
     */
    LoginRspVO login(LoginReqVO loginReqVO);
}
