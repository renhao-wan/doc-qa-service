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
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * token 估算器。
     * <p>
     * JTokkit 用的是 OpenAI 的 BPE 词表，对 Qwen / DeepSeek 的中文切分只是**近似**——
     * 但这里不需要精确记账，只需要「足够接近以便在撑爆上下文之前触发截断」。
     * 精确方案要么依赖模型自己的 tokenizer（本项目按请求动态指定模型名，
     * 拿不到对应词表），要么自建词表（维护成本远高于收益）。
     * <p>
     * 做成 {@code static}：构造它要加载词表，而 advisor 是**每个请求 new 一个**的
     * （见 CLAUDE.md「Advisor 链是核心扩展点」），写成实例字段等于每个请求加载一遍词表。
     */
    private static final TokenCountEstimator TOKEN_COUNT_ESTIMATOR = new JTokkitTokenCountEstimator();

    private final ChatMessageMapper chatMessageMapper;
    private final AiChatReqVO aiChatReqVO;
    private final int limit;

    /**
     * 历史消息的 token 预算上限
     *
     * @see #trimByTokenBudget(List)
     */
    private final int maxTokens;

    public CustomChatMemoryAdvisor(ChatMessageMapper chatMessageMapper, AiChatReqVO aiChatReqVO,
                                   int limit, int maxTokens) {
        this.chatMessageMapper = chatMessageMapper;
        this.aiChatReqVO = aiChatReqVO;
        this.limit = limit;
        this.maxTokens = maxTokens;
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

        // 所有历史消息（未经裁剪）
        List<Message> historyMessages = Lists.newArrayList();

        // 将数据库记录转换为对应类型的消息
        for (ChatMessageDO chatMessageDO : sortedMessages) {
            // 消息类型
            String type  = chatMessageDO.getRole();
            if (Objects.equals(type, MessageType.USER.getValue())) { // 用户消息
                Message userMessage = new UserMessage(chatMessageDO.getContent());
                historyMessages.add(userMessage);
            } else if (Objects.equals(type, MessageType.ASSISTANT.getValue())) { // AI 助手消息
                Message assistantMessage = new AssistantMessage(chatMessageDO.getContent());
                historyMessages.add(assistantMessage);
            }
        }

        // 按 token 预算裁剪历史。⚠️ 必须在拼接当前用户消息**之前**做：
        // 当前消息是这一轮真正的提问，任何情况下都不能被裁掉，
        // 而裁剪逻辑只会从「最旧的一端」丢弃，把它混进来一起算是有风险的
        List<Message> trimmedHistory = trimByTokenBudget(historyMessages);

        // 除了记忆消息，还需要添加当前用户消息
        trimmedHistory.addAll(chatClientRequest.prompt().getInstructions());

        // 构建一个新的 ChatClientRequest 请求对象。
        // ⚠️ 这里必须用裁剪后的 trimmedHistory（含当前提问），用 historyMessages 等于裁剪白做
        ChatClientRequest processedChatClientRequest = chatClientRequest
                .mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(trimmedHistory).build())
                .build();

        return streamAdvisorChain.nextStream(processedChatClientRequest);
    }

    /**
     * 按 token 预算裁剪历史消息：从最新往旧累加，装不下就停，再丢掉开头不完整的轮次。
     * <p>
     * 为什么需要它：上面的 {@code LIMIT} 只约束**条数**，不约束长度。50 条消息如果每一条都很长
     * （用户贴了一整篇文档、模型回了一大段），拼出来的 prompt 可以远超模型上下文窗口，
     * 表现为模型 API 返回 400、SSE 流转 error。条数上限是查库边界，token 预算才是上下文边界，
     * 两者管的是不同的事，不能互相替代。
     * <p>
     * <b>为什么从最新往旧取</b>：越近的对话对当前这轮越重要，反过来取会丢掉最相关的上下文。
     * <p>
     * <b>为什么最后还要丢开头</b>：截断点可能落在一轮的中间，留下「有答无问」的历史
     * （模型只看到自己说过的一段话，却看不到对应的问题），它会把那段话当成凭空出现的陈述来接。
     * 丢掉不完整的轮次比留着更安全。
     *
     * @param history 按时间升序排好的历史消息
     * @return 裁剪后的历史消息，仍按时间升序
     */
    private List<Message> trimByTokenBudget(List<Message> history) {
        List<Message> kept = new ArrayList<>();
        int keptTokens = 0;

        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            int messageTokens = TOKEN_COUNT_ESTIMATOR.estimate(message.getText());

            // kept.isEmpty() 这个例外是必要的：单条消息本身就超预算时（用户直接贴了一篇长文），
            // 不加例外会一条都留不下，这一轮彻底退化成无记忆对话
            if (!kept.isEmpty() && keptTokens + messageTokens > maxTokens) {
                break;
            }

            keptTokens += messageTokens;
            kept.add(message);
        }

        // 上面是倒着取的，反转回时间顺序
        Collections.reverse(kept);

        // 丢掉开头不完整的轮次（见方法注释）
        int droppedLeading = 0;
        while (!kept.isEmpty() && !MessageType.USER.equals(kept.get(0).getMessageType())) {
            kept.remove(0);
            droppedLeading++;
        }

        if (kept.isEmpty() && !history.isEmpty()) {
            log.warn("## 历史消息全部超出 token 预算(maxTokens={})，本轮将无记忆上下文，历史 {} 条",
                    maxTokens, history.size());
        } else if (droppedLeading > 0) {
            log.info("## 历史消息超出 token 预算(maxTokens={})，{} 条裁剪至 {} 条（含丢弃 {} 条开头的残缺轮次）",
                    maxTokens, history.size(), kept.size(), droppedLeading);
        }

        return kept;
    }
}
