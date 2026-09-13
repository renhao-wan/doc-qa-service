package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.advisor.CustomChatMemoryAdvisor;
import io.github.renhaowan.docqa.domain.dos.ChatMessageDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMessageMapper;
import io.github.renhaowan.docqa.model.vo.chat.AiChatReqVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 对话记忆的 token 预算裁剪——超长历史必须在撑爆上下文之前被截断
 * <p>
 * 要挡住的问题：{@code LIMIT 50} 只约束**条数**，不约束**长度**。50 条长消息
 * （用户贴了一整篇文档、模型回一大段）拼出来的 prompt 可以远超模型上下文窗口，
 * 表现为模型 API 返回 400、SSE 流转 error——而且这个失败随对话变长才出现，
 * 本地短对话测不出来。
 * <p>
 * <b>纯单元测试，不起 Spring 上下文</b>：{@code ChatMessageMapper} 被 mock，
 * 编排链被 mock，不碰数据库也不碰大模型。整套跑完不到一秒，可以随手跑。
 * <p>
 * <b>为什么断言具体条数而不是「token 总和 ≤ 预算」</b>：后者是同义反复——
 * advisor 内部用 {@link JTokkitTokenCountEstimator} 累加，测试再用同一个估算器求和比较，
 * 等于把实现抄了一遍。这里改从**内容**上断言保留了哪几条（消息内容按 id 各不相同），
 * 测的是裁剪逻辑本身：从哪一端取、什么时候停、配对有没有被切断。
 * <p>
 * 预算用**真实内容算出来的值**（见 {@link #budgetFor(List, int...)}），所以「恰好够几条」
 * 是精确的：同样的字符串交给同样的估算器，两次结果必然一致。
 **/
class ChatMemoryTokenBudgetTests {

    private static final JTokkitTokenCountEstimator ESTIMATOR = new JTokkitTokenCountEstimator();

    private static final String CHAT_UUID = "test-chat-uuid";

    /** 当前这一轮的提问。它不在历史里，任何情况下都不该被裁掉 */
    private static final String CURRENT_QUESTION = "当前这一轮的提问";

    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);

    // ---------- 预算充足 ----------

    @Test
    void shortHistoryShouldBeKeptWhole() {
        List<ChatMessageDO> history = turns(3);
        stubHistoryAsDatabaseWould(history);

        List<Message> messages = advise(200_000);

        // 3 轮 = 6 条历史 + 1 条当前提问
        assertThat(messages).hasSize(999);

        // 预算充足时历史一条不丢、顺序不变，当前提问挂在末尾
        List<String> expected = new ArrayList<>(contentsOf(history));
        expected.add(CURRENT_QUESTION);
        assertThat(textsOf(messages)).containsExactlyElementsOf(expected);
    }

    // ---------- 预算不足 ----------

    @Test
    void historyBeyondBudgetShouldBeTrimmedToTheMostRecentTurns() {
        List<ChatMessageDO> history = turns(5);
        stubHistoryAsDatabaseWould(history);

        // 预算恰好够最后两轮（4 条）
        List<Message> messages = advise(budgetFor(history, 6, 7, 8, 9));

        // 保留的是**最近**的两轮，不是最早的两轮——从最新往旧取是这段逻辑的核心
        assertThat(textsOf(messages)).containsExactly(
                contentOf(history.get(6)),
                contentOf(history.get(7)),
                contentOf(history.get(8)),
                contentOf(history.get(9)),
                CURRENT_QUESTION);
    }

    @Test
    void trimmedHistoryShouldStartWithUserMessage() {
        List<ChatMessageDO> history = turns(5);
        stubHistoryAsDatabaseWould(history);

        // 预算只够最后 3 条，而历史是 user/assistant 交替的——
        // 最后 3 条恰好以 assistant 开头，必须被丢掉，否则模型会看到「有答无问」的历史
        List<Message> messages = advise(budgetFor(history, 7, 8, 9));

        assertThat(messages.get(0).getMessageType())
                .as("裁剪后的第一条历史必须是用户提问，不能是残缺轮次的 AI 回答")
                .isEqualTo(MessageType.USER);
    }

    @Test
    void keptHistoryShouldStayInChronologicalOrder() {
        List<ChatMessageDO> history = turns(4);
        stubHistoryAsDatabaseWould(history);

        List<Message> messages = advise(budgetFor(history, 4, 5, 6, 7));

        // 裁剪是倒着取、最后反转回来的，反转漏了这里就会红
        assertThat(textsOf(messages)).containsExactly(
                contentOf(history.get(4)),
                contentOf(history.get(5)),
                contentOf(history.get(6)),
                contentOf(history.get(7)),
                CURRENT_QUESTION);
    }

    // ---------- 退化情况 ----------

    /**
     * 预算小到装不下任何一条历史。
     * <p>
     * 当前提问**必须**留下：它是这一轮真正要问的东西，把它一起裁掉等于接口直接失效。
     * 历史被清空是可以接受的——退化成无记忆的单轮对话，总比整个请求失败好。
     */
    @Test
    void currentQuestionShouldSurviveEvenWhenBudgetIsTiny() {
        stubHistoryAsDatabaseWould(turns(3));

        List<Message> messages = advise(1);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo(CURRENT_QUESTION);
    }

    @Test
    void emptyHistoryShouldLeaveOnlyCurrentQuestion() {
        stubHistoryAsDatabaseWould(List.of());

        List<Message> messages = advise(8_000);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo(CURRENT_QUESTION);
    }

    // ---------- 辅助方法 ----------

    /**
     * 跑一遍 advisor，抓出它交给下游的消息列表。
     *
     * @param maxTokens 历史消息的 token 预算
     */
    private List<Message> advise(int maxTokens) {
        AiChatReqVO reqVO = new AiChatReqVO();
        reqVO.setChatId(CHAT_UUID);

        CustomChatMemoryAdvisor advisor =
                new CustomChatMemoryAdvisor(chatMessageMapper, reqVO, 50, maxTokens);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage(CURRENT_QUESTION))))
                .build();

        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(chain.nextStream(captor.capture())).thenReturn(Flux.empty());

        advisor.adviseStream(request, chain).subscribe();

        return captor.getValue().prompt().getInstructions();
    }

    /**
     * 按真实内容算出「恰好装得下这几条」的预算。
     * <p>
     * 之所以不写成 {@code 单条 token 数 × 条数}：各条内容长度不同，估算值也不同。
     * 这里直接对同一批字符串跑同一个估算器，结果与 advisor 内部的累加完全一致，
     * 「恰好够」是精确的而不是近似的。
     */
    private static int budgetFor(List<ChatMessageDO> history, int... indices) {
        int budget = 0;
        for (int index : indices) {
            budget += ESTIMATOR.estimate(contentOf(history.get(index)));
        }
        return budget;
    }

    /**
     * 造 {@code turnCount} 轮对话，返回**按 id 升序**的完整历史。
     * <p>
     * 每条内容都不一样（带 id），否则无法断言「保留的是哪几条」。
     */
    private static List<ChatMessageDO> turns(int turnCount) {
        List<ChatMessageDO> history = new ArrayList<>();
        for (int turn = 1; turn <= turnCount; turn++) {
            history.add(message(history.size() + 1L, MessageType.USER.getValue(), turn));
            history.add(message(history.size() + 1L, MessageType.ASSISTANT.getValue(), turn));
        }
        return history;
    }

    private static ChatMessageDO message(long id, String role, int turn) {
        return ChatMessageDO.builder()
                .id(id)
                .chatUuid(CHAT_UUID)
                .role(role)
                .content(contentOf(id, turn))
                .build();
    }

    private static String contentOf(ChatMessageDO message) {
        return message.getContent();
    }

    private static String contentOf(long id, int turn) {
        return "第" + turn + "轮第" + id + "条" + "补充内容用于拉长长度".repeat(6);
    }

    /**
     * 模拟数据库的行为：advisor 用的是
     * {@code ORDER BY id DESC LIMIT n}，所以返回的应当是**倒序**的列表。
     * 给正序会让排序那段代码看起来没问题，实际却在线上一直错着。
     */
    private void stubHistoryAsDatabaseWould(List<ChatMessageDO> ascendingHistory) {
        List<ChatMessageDO> descending = new ArrayList<>(ascendingHistory);
        descending.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        when(chatMessageMapper.selectList(any())).thenReturn(descending);
    }

    private static List<String> textsOf(List<? extends Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    private static List<String> contentsOf(List<ChatMessageDO> history) {
        return history.stream().map(ChatMessageDO::getContent).toList();
    }
}
