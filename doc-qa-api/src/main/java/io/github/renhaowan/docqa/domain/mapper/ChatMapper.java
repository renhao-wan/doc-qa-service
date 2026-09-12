package io.github.renhaowan.docqa.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.renhaowan.docqa.domain.dos.ChatDO;

import java.time.LocalDateTime;

/**
 * @Author: Renhao-Wan
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

    /**
     * 刷新对话的最后活跃时间
     * <p>
     * 对话列表按 update_time 倒序分页，但 update_time 原先只在 newChat 时写过一次，
     * 发消息并不更新它——结果是排序退化成按创建时间排，「刚聊完的对话」不会浮到列表顶部。
     * 因此每轮消息落库时都要调用一次（与消息写入放在同一个事务里，保证一起成功或一起失败）。
     *
     * @param uuid 对话 UUID
     * @return 影响行数
     */
    default int touchUpdateTime(String uuid) {
        return update(null, Wrappers.<ChatDO>lambdaUpdate()
                .eq(ChatDO::getUuid, uuid)
                .set(ChatDO::getUpdateTime, LocalDateTime.now()));
    }
}
