package io.github.renhaowan.docqa.model.vo.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 登录返参
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LoginRspVO {

    /** JWT，前端存 localStorage 并作为 Authorization: Bearer 发送 */
    private String token;
    private String username;
    private String nickname;
}
