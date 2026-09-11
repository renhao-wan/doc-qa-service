package com.quanxiaoha.ai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quanxiaoha.ai.robot.domain.dos.ChatDO;

/**
 * @Author: 犬小哈
 * @Date: 2025/8/11 11:36
 * @Version: v1.0.0
 * @Description: TODO
 **/
public interface ChatMapper extends BaseMapper<ChatDO> {

    /**
     * 分页查询
     * @param current
     * @param size
     * @return
     */
    default Page<ChatDO> selectPageList(Long current, Long size) {
        // 分页对象(查询第几页、每页多少数据)
        Page<ChatDO> page = new Page<>(current, size);

        // 构建查询条件
        LambdaQueryWrapper<ChatDO> wrapper = Wrappers.<ChatDO>lambdaQuery()
                .orderByDesc(ChatDO::getUpdateTime); // 按更新时间倒序

        return selectPage(page, wrapper);
    }
}
