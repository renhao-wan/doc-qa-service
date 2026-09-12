package io.github.renhaowan.docqa.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.renhaowan.docqa.domain.dos.UserDO;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/12
 * @Version: v1.0.0
 * @Description: 用户 Mapper
 **/
public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 按用户名查询
     *
     * @param username 用户名
     * @return 用户记录，不存在返回 null
     */
    default UserDO selectByUsername(String username) {
        return selectOne(Wrappers.<UserDO>lambdaQuery()
                .eq(UserDO::getUsername, username));
    }
}
