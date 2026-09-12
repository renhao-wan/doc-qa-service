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
     * 分页查询当前用户的对话
     * <p>
     * ⚠️ {@code userId} 是查询条件的一部分，不是事后过滤——把归属条件融进 SQL 是
     * 本项目防越权的核心手法：越权访问会自然地退化成「查不到」，
     * 不需要额外写比对分支，也不会泄露资源是否存在。
     * <p>
     * 对应索引 idx_t_chat_user_update (user_id, update_time DESC)：过滤列作前缀、
     * 排序列跟在后面，ORDER BY ... DESC LIMIT 可直接按索引取数。
     *
     * @param current 当前页
     * @param size    每页条数
     * @param userId  当前登录用户 ID
     * @return 分页结果
     */
    default Page<ChatDO> selectPageList(Long current, Long size, Long userId) {
        // 分页对象(查询第几页、每页多少数据)
        Page<ChatDO> page = new Page<>(current, size);

        // 构建查询条件
        LambdaQueryWrapper<ChatDO> wrapper = Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUserId, userId) // 只看自己的对话
                .orderByDesc(ChatDO::getUpdateTime); // 按更新时间倒序

        return selectPage(page, wrapper);
    }

    /**
     * 刷新对话的最后活跃时间
     * <p>
     * 对话列表按 update_time 倒序分页，但 update_time 原先只在 newChat 时写过一次，
     * 发消息并不更新它——结果是排序退化成按创建时间排，「刚聊完的对话」不会浮到列表顶部。
     * 因此每轮消息落库时都要调用一次（与消息写入放在同一个事务里，保证一起成功或一起失败）。
     * <p>
     * 带上 userId 是纵深防御：调用方（Advisor）在流式开始前已经由 Controller 校验过归属，
     * 这里再过滤一道，保证「刷新别人对话的活跃时间」在 SQL 层就不可能发生。
     *
     * @param uuid   对话 UUID
     * @param userId 当前登录用户 ID
     * @return 影响行数
     */
    default int touchUpdateTime(String uuid, Long userId) {
        return update(null, Wrappers.<ChatDO>lambdaUpdate()
                .eq(ChatDO::getUuid, uuid)
                .eq(ChatDO::getUserId, userId)
                .set(ChatDO::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 判断某个对话是否归属于指定用户
     * <p>
     * 供「按 UUID 定位对话」的场景使用（SSE 入口、历史消息查询），
     * 这类查询拿不到现成的 wrapper，需要先确认归属再继续。
     *
     * @param uuid   对话 UUID
     * @param userId 当前登录用户 ID
     * @return true 表示归属该用户
     */
    default boolean existsByUuidAndUserId(String uuid, Long userId) {
        return selectCount(Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUuid, uuid)
                .eq(ChatDO::getUserId, userId)) > 0;
    }
}
