package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.advisor.CustomChatMemoryAdvisor;
import io.github.renhaowan.docqa.advisor.CustomStreamLoggerAndMessage2DBAdvisor;
import io.github.renhaowan.docqa.advisor.KnowledgeBaseAdvisor;
import io.github.renhaowan.docqa.advisor.NetworkSearchAdvisor;
import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import io.github.renhaowan.docqa.domain.mapper.ChatMessageMapper;
import io.github.renhaowan.docqa.model.vo.chat.AiChatReqVO;
import io.github.renhaowan.docqa.service.SearchResultContentFetcherService;
import io.github.renhaowan.docqa.service.SearXNGService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: Advisor 链的执行顺序契约——落库的 logger 必须跑在链尾
 * <p>
 * 链上各 advisor 的 {@code getOrder()} 决定执行顺序（值小的在前）。这里守住的是唯一一条
 * 真正会坏的契约：<b>{@link CustomStreamLoggerAndMessage2DBAdvisor} 必须是链上最后一个</b>。
 * 它在 {@code doFinally} 里聚合流式 chunk 落库，任何排在它之后、还对流做转换的 advisor，
 * 其效果都不会被它看到——表现是「库里存的内容和前端看到的不一致」，而且不报任何错。
 * <p>
 * <b>为什么断言相对关系而不是具体数值</b>：直接断言 {@code getOrder() == 99} 是同义反复
 * ——测试与实现写同一个常量，改的时候一起改，抓不到任何东西。断言「最大的是 logger」
 * 才能挡住「把 logger 的 order 改小」和「给某个重写 prompt 的 advisor 调了个更大的 order」。
 * <p>
 * ⚠️ <b>这个测试覆盖不到「新增 advisor 却忘了排进链里」</b>。链是在
 * {@code ChatController} / {@code KnowledgeBaseController} 的方法体内手工 {@code new} 出来的，
 * 没有集中的装配点可枚举；要自动发现新增的 advisor，只能扫描包下所有类并反射构造，
 * 脆弱且收益不抵成本。所以新增 advisor 时，<b>需要手工回来把这个类加进 {@link #buildChain()}</b>。
 * <p>
 * ⚠️ <b>「记忆与联网搜索二选一」这条互斥规则也不在本类的覆盖范围内</b>：它是
 * {@code ChatController} 里的 {@code if/else} 控制流，而不是 advisor 自身的属性。
 * 想测它就得让 Controller 用上可注入的 ChatModel，而当前是每个请求现场
 * {@code new OpenAiChatModel(...)}（模型名与 temperature 按请求动态指定，是刻意设计）。
 * 为一个测试去改这条链路不划算，它与 {@code SseAsyncDispatchSecurityTests} 一样属于手工验收。
 **/
class AdvisorChainOrderTests {

    @Test
    void streamLoggerMustRunAfterEveryPromptRewritingAdvisor() {
        List<StreamAdvisor> chain = buildChain();

        StreamAdvisor logger = chain.stream()
                .filter(CustomStreamLoggerAndMessage2DBAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("链里没有落库用的 logger advisor"));

        assertThat(chain)
                .as("logger 的 order 必须严格大于其他所有 advisor，否则它收不到后续 advisor 对流做的转换")
                .allSatisfy(advisor -> {
                    if (advisor != logger) {
                        assertThat(logger.getOrder()).isGreaterThan(advisor.getOrder());
                    }
                });

        // 再按 order 实际排一遍，确认排完之后 logger 确实落在链尾——
        // 上面的断言只看数值，这一条看排序结果，两者一起才说明链的装配是自洽的
        List<StreamAdvisor> sorted = new ArrayList<>(chain);
        sorted.sort(Comparator.comparingInt(StreamAdvisor::getOrder));

        assertThat(sorted.get(sorted.size() - 1)).isSameAs(logger);
    }

    /**
     * 四个 advisor 的依赖全部 mock：本类只关心 {@code getOrder()}，
     * 不触发任何一次真实调用（构造器都只做字段赋值）。
     * <p>
     * 联网检索与对话记忆在线上是互斥的（{@code ChatController} 的 if/else），这里一并列出，
     * 是因为它们各自都要满足「排在 logger 前面」这条契约。
     */
    private List<StreamAdvisor> buildChain() {
        AiChatReqVO reqVO = new AiChatReqVO();

        return List.of(
                new NetworkSearchAdvisor(mock(SearXNGService.class),
                        mock(SearchResultContentFetcherService.class)),
                new KnowledgeBaseAdvisor(mock(VectorStore.class), false),
                new CustomChatMemoryAdvisor(mock(ChatMessageMapper.class), reqVO, 50, 8000),
                new CustomStreamLoggerAndMessage2DBAdvisor(mock(ChatMessageMapper.class),
                        mock(ChatMapper.class), reqVO, mock(TransactionTemplate.class), 1L));
    }
}
