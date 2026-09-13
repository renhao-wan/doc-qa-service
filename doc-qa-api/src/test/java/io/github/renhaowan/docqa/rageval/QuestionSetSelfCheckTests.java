package io.github.renhaowan.docqa.rageval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 金标准自检——问题集里每条 key phrase 必须落在**单个**切分块内
 * <p>
 * 为什么必须是「单个块」而不是「全文能找到」：检索的返回单位是块，命中判断也是按块做的。
 * 若某条 key phrase 跨了两块（例如正好被标题切开），那么无论检索质量多好，判断逻辑都匹配不上，
 * 该题会永远计为未命中——污染出来的是一份假数据，且从结果表上完全看不出异常。
 * <p>
 * 不联网、不启动 Spring，可留在 {@code mvn test} 里默认执行。
 **/
class QuestionSetSelfCheckTests {

    @Test
    void everyKeyPhraseFallsWithinASingleChunk() throws IOException {
        List<QuestionSet.Row> rows = QuestionSet.load();
        assertThat(rows).as("问题集为空").isNotEmpty();

        List<String> broken = new ArrayList<>();
        int checked = 0;
        // 每个策略都要各自自检：它们的切点集合并不相同（PRODUCTION 切标题、BY_ARTICLE 切「第X条」），
        // 某条 key phrase 在 A 策略下完整、在 B 策略下正好被切断，完全可能。只验一个策略，
        // 就等于默认其余策略的数据可信——而那正是要拿来对比的。
        for (Corpus.ChunkingStrategy strategy : Corpus.ChunkingStrategy.values()) {
            List<Document> chunks = Corpus.allChunks(strategy);
            assertThat(chunks)
                    .as("策略 %s 没加载到任何块，检查 docs/rag-evaluation/corpus/ 是否存在", strategy.name())
                    .isNotEmpty();

            for (QuestionSet.Row row : rows) {
                if (!row.hasGoldKeys()) {
                    continue;   // irrelevant 类本就没有金标准 key
                }
                for (String key : row.keys()) {
                    checked++;
                    if (chunks.stream().noneMatch(c -> c.getText().contains(key))) {
                        broken.add("[%s] %s: 未落在任何单一块内 -> %s".formatted(strategy.name(), row.id(), key));
                    }
                }
            }
        }

        System.out.printf("共校验 %d 条 key phrase（%d 个策略），其中 %d 条跨块或找不到%n",
                checked, Corpus.ChunkingStrategy.values().length, broken.size());
        broken.forEach(System.out::println);

        assertThat(broken)
                .as("以下 key phrase 跨块或不存在，会让对应题目永远无法命中，必须修问题集而不是改判断逻辑")
                .isEmpty();
    }

    /**
     * 问题集里每道题的 category 必须来自已知集合——写错类别不会报错，只会静默地少统计一类。
     */
    @Test
    void categoriesAreKnown() throws IOException {
        List<String> unknown = new ArrayList<>();
        for (QuestionSet.Row row : QuestionSet.load()) {
            if (!QuestionSet.categories().contains(row.category())) {
                unknown.add("%s: 未知类别 %s".formatted(row.id(), row.category()));
            }
        }
        assertThat(unknown).isEmpty();
    }
}
