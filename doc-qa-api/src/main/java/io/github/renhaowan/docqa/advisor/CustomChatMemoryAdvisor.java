package io.github.renhaowan.docqa.advisor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Lists;
import io.github.renhaowan.docqa.domain.dos.ChatMessageDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMessageMapper;
import io.github.renhaowan.docqa.model.vo.chat.AiChatReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/5/26 16:36
 * @Version: v1.0.0
 * @Description: 自定义对话记忆 Advisor
 **/
@Slf4j
public class CustomChatMemoryAdvisor implements StreamAdvisor {

    private final ChatMessageMapper chatMessageMapper;
    private final AiChatReqVO aiChatReqVO;
    private final int limit;

    public CustomChatMemoryAdvisor(ChatMessageMapper chatMessageMapper, AiChatReqVO aiChatReqVO, int limit) {
        this.chatMessageMapper = chatMessageMapper;
        this.aiChatReqVO = aiChatReqVO;
        this.limit = limit;
    }

    @Override
    public int getOrder() {
        return 2; // order 值越小，越先执行
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        log.info("## 自定义聊天记忆 Advisor...");

        // 对话 UUID
        String chatUuid = aiChatReqVO.getChatId();

        // 查询数据库拉取最新的聊天消息。
        // 排序键用 id（BIGSERIAL，单调递增且唯一）而非 create_time：时间可能重复，
        // 排序值相等时 LIMIT 取哪几条是不确定的，会导致消息顺序错乱。
        // 索引 idx_t_chat_message_chat_uuid_id 正是 (chat_uuid, id DESC)，可免排序直接取数。
        List<ChatMessageDO> messages = chatMessageMapper.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getChatUuid, chatUuid) // 查询指定对话 UUID 下的聊天记录
                .orderByDesc(ChatMessageDO::getId) // 查询最新的消息
                .last(String.format("LIMIT %d", limit))); // 仅查询 LIMIT 条

        // 按自增主键升序排列，还原正常的对话先后顺序
        List<ChatMessageDO> sortedMessages = messages.stream()
                 .sorted(Comparator.comparing(ChatMessageDO::getId)) // 升序排列
                 .toList();

        // 所有消息
        List<Message> messageList = Lists.newArrayList();

        // 将数据库记录转换为对应类型的消息
        for (ChatMessageDO chatMessageDO : sortedMessages) {
            // 消息类型
            String type  = chatMessageDO.getRole();
            if (Objects.equals(type, MessageType.USER.getValue())) { // 用户消息
                Message userMessage = new UserMessage(chatMessageDO.getContent());
                messageList.add(userMessage);
            } else if (Objects.equals(type, MessageType.ASSISTANT.getValue())) { // AI 助手消息
                Message assistantMessage = new AssistantMessage(chatMessageDO.getContent());
                messageList.add(assistantMessage);
            }
        }

        // 除了记忆消息，还需要添加当前用户消息
        messageList.addAll(chatClientRequest.prompt().getInstructions());

        // 构建一个新的 ChatClientRequest 请求对象
        ChatClientRequest processedChatClientRequest = chatClientRequest
                .mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(messageList).build())
                .build();

        return streamAdvisorChain.nextStream(processedChatClientRequest);
    }
}
