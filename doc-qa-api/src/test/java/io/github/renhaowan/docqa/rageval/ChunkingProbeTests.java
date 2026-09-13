package io.github.renhaowan.docqa.rageval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 探查各切分策略在评估语料上的实际产出
 * <p>
 * 不启动 Spring、不调外部接口，只用本地语料与 commonmark 解析器，可留在 {@code mvn test} 里。
 * <p>
 * 它存在的意义：切分粒度决定检索实验的金标准怎么标注、块大小分布是否合理。
 * 不先看清切成什么样，后面测出来的数字无从解释。
 **/
class ChunkingProbeTests {

    @Test
    void probeAllChunkingStrategies() throws IOException {
        for (Corpus.ChunkingStrategy strategy : Corpus.ChunkingStrategy.values()) {
            System.out.printf("%n########## %s（%s）##########%n", strategy.name(), strategy.label());
            int total = 0;
            for (Path file : Corpus.files()) {
                List<Document> chunks = Corpus.chunks(file, strategy);
                total += chunks.size();
                System.out.printf("%n--- %s → %d 块 ---%n", file.getFileName(), chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    String text = chunks.get(i).getText();
                    String head = text.substring(0, Math.min(46, text.length())).replace("\n", " ");
                    System.out.printf("[%2d] len=%4d | %s%n", i + 1, text.length(), head);
                }
            }
            System.out.printf("%n策略合计 %d 块%n", total);
        }
    }
}
