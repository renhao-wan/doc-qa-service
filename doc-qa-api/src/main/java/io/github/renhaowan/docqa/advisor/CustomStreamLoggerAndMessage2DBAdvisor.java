package io.github.renhaowan.docqa.advisor;

import io.github.renhaowan.docqa.domain.dos.ChatMessageDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import io.github.renhaowan.docqa.domain.mapper.ChatMessageMapper;
import io.github.renhaowan.docqa.model.vo.chat.AiChatReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/5/26 16:36
 * @Version: v1.0.0
 * @Description: 自定义打印流式日志 Advisor
 **/
@Slf4j
public class CustomStreamLoggerAndMessage2DBAdvisor implements StreamAdvisor {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMapper chatMapper;
    private final AiChatReqVO aiChatReqVO;
    private final TransactionTemplate transactionTemplate;

    public CustomStreamLoggerAndMessage2DBAdvisor(ChatMessageMapper chatMessageMapper,
                                                  ChatMapper chatMapper,
                                                  AiChatReqVO aiChatReqVO,
                                                  TransactionTemplate transactionTemplate) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatMapper = chatMapper;
        this.aiChatReqVO = aiChatReqVO;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int getOrder() {
        return 99; // order 值越小，越先执行
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 对话 UUID
        String chatUuid = aiChatReqVO.getChatId();
        // 用户消息
        String userMessage = aiChatReqVO.getMessage();

        // 流式调用
        Flux<ChatClientResponse> chatClientResponseFlux = streamAdvisorChain.nextStream(chatClientRequest);

        // 创建 AI 流式推理过程聚合容器（线程安全）
        AtomicReference<StringBuilder> fullReasoning = new AtomicReference<>(new StringBuilder());
        // 创建 AI 流式回答聚合容器（线程安全）
        AtomicReference<StringBuilder> fullContent = new AtomicReference<>(new StringBuilder());

        // 返回处理后的流
        return chatClientResponseFlux
                .doOnNext(response -> {
                    // 获取响应
                    ChatResponse chatResponse = response.chatResponse();

                    // 判空
                    if (Objects.nonNull(chatResponse) && Objects.nonNull(chatResponse.getResult())) {
                        // 获取 AI 回复的消息
                        AssistantMessage message = chatResponse.getResult().getOutput();

                        // 获取推理内容（如果存在）。
                        // 注意：非推理模型（如 deepseek-v3）不返回该字段，取到的可能是缺失的 key，也可能是 null，
                        // 必须先判空再 toString，否则直接 NPE
                        Object reasoningContent = message.getMetadata().get("reasoningContent");
                        String reasoningChunk = Objects.nonNull(reasoningContent) ? reasoningContent.toString() : null;

                        // 逐块收集正式回答
                        String chunk = message.getText();

                        if (reasoningChunk != null) {
                            log.info("## reasoning chunk: {}", reasoningChunk);
                            fullReasoning.get().append(reasoningChunk);
                        }

                        // 若 chunk 块不为空，则追加到 fullContent 中
                        if (chunk != null) {
                            log.info("## chunk: {}", chunk);
                            fullContent.get().append(chunk);
                        }
                    }
                })
                .doOnError(error -> {
                    // 出错时打印已收集的部分
                    String partialResponse = fullContent.get().toString();
                    log.error("## Stream 流出现错误，已收集回答如下: {}", partialResponse, error);
                })
                // 用 doFinally 统一处理三种终止信号，而不是只看 doOnComplete：
                //   ON_COMPLETE —— 模型正常输出完毕
                //   ON_CANCEL   —— 用户点击「停止生成」，前端 abort 连接，Reactor 流被 cancel
                //   ON_ERROR    —— 流异常中断
                // 用户点「停止」触发的是 cancel 而非 complete，原实现只在 doOnComplete 里落库，
                // 会导致这一轮的用户提问和已生成的部分回答全部丢失。
                .doFinally(signalType -> {
                    // 流终止后打印完整推理过程
                    String completeReasoning = fullReasoning.get().toString();
                    log.info("\n==== FULL Reasoning RESPONSE ====\n{}\n========================", completeReasoning);

                    // 流终止后打印完整回答
                    String completeResponse = fullContent.get().toString();
                    log.info("\n==== FULL AI RESPONSE ====\n{}\n========================", completeResponse);

                    // 流异常中断时保持原有行为：本轮不落库（半截回答写进去意义不大，且用户没看到）
                    if (SignalType.ON_ERROR == signalType) {
                        log.warn("## 流异常终止，本轮对话不落库: chatUuid={}, signalType={}", chatUuid, signalType);
                        return;
                    }

                    persistChatMessages(chatUuid, userMessage, completeReasoning, completeResponse, signalType);
                });
    }

    /**
     * 落库本轮对话：用户提问 + AI 回答
     * <p>
     * 用编程式事务保证两条记录要么都写、要么都不写，避免出现「有提问没回答」的半截数据。
     *
     * @param chatUuid          对话 UUID
     * @param userMessage       用户提问
     * @param completeReasoning AI 推理内容（非推理模型下为空串）
     * @param completeResponse  AI 回答正文（用户中途停止时可能只有部分，也可能一字未出）
     * @param signalType        流的终止信号，仅用于日志区分「正常结束」与「用户中止」
     */
    private void persistChatMessages(String chatUuid,
                                     String userMessage,
                                     String completeReasoning,
                                     String completeResponse,
                                     SignalType signalType) {
        // 用户提前中止且模型一字未出：此时没有可保存的回答，但用户提问本身仍要落库，
        // 否则刷新页面后用户会发现自己的问题凭空消失了
        boolean hasAssistantContent = !completeResponse.isBlank();

        // 开启编程式事务
        transactionTemplate.execute(status -> {
            try {
                // 1. 存储用户消息
                chatMessageMapper.insert(ChatMessageDO.builder()
                        .chatUuid(chatUuid)
                        .content(userMessage)
                        .role(MessageType.USER.getValue()) // 用户消息
                        .createTime(LocalDateTime.now())
                        .build());

                // 2. 存储 AI 回答（模型一字未出时跳过，避免落一条空消息污染后续多轮上下文）
                if (hasAssistantContent) {
                    chatMessageMapper.insert(ChatMessageDO.builder()
                            .chatUuid(chatUuid)
                            .reasoningContent(completeReasoning) // 推理内容
                            .content(completeResponse)
                            .role(MessageType.ASSISTANT.getValue()) // AI 回答
                            .createTime(LocalDateTime.now())
                            .build());
                }

                // 3. 刷新对话的最后活跃时间。
                // 对话列表按 update_time 倒序分页，而它原先只在新建对话时写过一次，
                // 不更新的话排序会退化成按创建时间排，「刚聊完的对话」不会浮到列表顶部。
                chatMapper.touchUpdateTime(chatUuid);

                log.info("## 本轮对话落库完成: chatUuid={}, signalType={}, hasAssistantContent={}",
                        chatUuid, signalType, hasAssistantContent);

                return true;
            } catch (Exception ex) {
                status.setRollbackOnly(); // 标记事务为回滚
                log.error("## 本轮对话落库失败: chatUuid={}", chatUuid, ex);
            }
            return false;
        });
    }
}
