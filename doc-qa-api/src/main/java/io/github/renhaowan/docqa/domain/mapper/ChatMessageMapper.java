package io.github.renhaowan.docqa.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.renhaowan.docqa.domain.dos.ChatMessageDO;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/8/11 11:36
 * @Version: v1.0.0
 * @Description: TODO
 **/
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {

    /**
     * 分页查询
     * @param current
     * @param size
     * @param chatId
     * @return
     */
    default Page<ChatMessageDO> selectPageList(Long current, Long size, String chatId) {
        // 分页对象(查询第几页、每页多少数据)
        Page<ChatMessageDO> page = new Page<>(current, size);

        // 构建查询条件
        LambdaQueryWrapper<ChatMessageDO> wrapper = Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getChatUuid, chatId) // 对话 ID
                // 按自增主键倒序：语义上等价于时间倒序，但 id 单调递增且唯一，
                // 不受时间精度、重复值影响，分页时不会因排序值相等而出现重复或遗漏。
                // 对应索引 idx_t_chat_message_chat_uuid_id (chat_uuid, id DESC)。
                .orderByDesc(ChatMessageDO::getId);

        return selectPage(page, wrapper);
    }
}
