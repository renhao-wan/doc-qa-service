package io.github.renhaowan.docqa.rageval;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 检索层评估——四种切分策略各自导入 pgvector，同一批问题问过去，算 Recall@K 与 MRR
 * <p>
 * <b>为什么不自己算余弦相似度</b>：那样测的是「理想检索器」，而线上跑的是 PgVectorStore +
 * HNSW 索引 + 云端 embedding 模型，两者会差出一截。评估要能解释线上的表现，就必须跑在同一条链路上——
 * 这里的检索调用与 {@code KnowledgeBaseAdvisor} 逐字相同（同样不带 metadata filter）。
 * <p>
 * <b>为什么每轮要清空整表</b>：正因为检索不带 filter，表里混进任何非本轮语料都会直接污染结果。
 * 所以本类要求独占 t_vector_store，跑前断言表为空。
 * <p>
 * <b>为什么默认不跑</b>：它要联网调 embedding、会真金白银花钱、单次约 3~5 分钟，
 * 不适合挂在 {@code mvn test} 里。需要时显式开启：
 * <pre>{@code
 * RAG_EVAL=true JAVA_HOME="D:/IDEAjava/JDK/jdk17" \
 *   DASHSCOPE_API_KEY=xxx mvn test -Dtest=RagRetrievalEvaluationTests
 * }</pre>
 * 结果写入 {@code docs/rag-evaluation/results/}，结论见 {@code docs/rag-evaluation.md}。
 **/
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true",
        disabledReason = "检索评估要联网调 embedding 且耗时数分钟，默认跳过；设 RAG_EVAL=true 开启")
class RagRetrievalEvaluationTests {

    @Resource
    private VectorStore vectorStore;
    @Resource
    private DataSource dataSource;

    /**
     * 每题一次性取回的最大条数。
     * <p>
     * K=1/3/5/10 的指标都由这一次检索的结果截取得出，而不是每个 K 各查一次——
     * 同一 query 的 embedding 相同，topK 只影响返回条数、不影响排序，截取与重查等价，
     * 但能把 embedding 调用从 4 次降到 1 次。这是 BEIR 一类评测框架的通行做法。
     * <p>
     * 代价：它假定 PgVectorStore 的返回顺序对同一次查询是确定的。HNSW 是近似索引，
     * 不同 topK 的图遍历路径理论上可能给出略有差异的候选集；若后续需要更严格的数字，
     * 把下面改成「每个 K 各查一次」即可，指标口径不变。
     */
    private static final int RETRIEVE_K = 10;

    /** 对外汇报的 K 值 */
    private static final int[] REPORTED_KS = {1, 3, 5, 10};

    private static final Path OUT_DIR = Path.of("..", "docs", "rag-evaluation", "results");

    /**
     * 一道题在一个策略下的一次检索结果。
     *
     * @param firstHitRank   第一个包含金标准 key phrase 的块排在第几位（1 起）；0 表示前 {@link #RETRIEVE_K} 块全没命中
     * @param top1Score      排名第一的块的相似度得分——用它观察「答得出的题」与「答不出的题」是否可分
     * @param retrievedChars 各名次返回块的字符数，按名次排列——用来算「这一枪打出去，
     *                       实际往提示词里塞了多少字」。只看命中率会把「整篇不切分」误判成最优：
     *                       它整篇就是一块，一命中就是文档级命中，代价是上下文被塞满
     */
    private record Hit(String strategy, String id, String category, String source,
                       int firstHitRank, double top1Score, List<Integer> retrievedChars) {
    }

    @Test
    void evaluateRetrievalAcrossChunkingStrategies() throws IOException {
        assertTableIsEmpty();

        List<QuestionSet.Row> rows = QuestionSet.load();
        assertThat(rows).as("问题集为空").isNotEmpty();

        List<Hit> hits = new ArrayList<>();
        List<String> corpusReport = new ArrayList<>();

        for (Corpus.ChunkingStrategy strategy : Corpus.ChunkingStrategy.values()) {
            truncateVectorTable();
            List<Document> imported = Corpus.importInto(vectorStore, strategy);
            corpusReport.add("%s\t%d\t%d".formatted(strategy.label(), imported.size(),
                    imported.stream().mapToInt(d -> d.getText().length()).sum()));
            System.out.printf("%n########## %s：导入 %d 块 ##########%n", strategy.label(), imported.size());

            for (QuestionSet.Row row : rows) {
                List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(row.question())          // 直接用提问原文当 query，不做改写
                        .topK(RETRIEVE_K)
                        .build());

                int rank = 0;
                List<Integer> chars = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) {
                    String text = found.get(i).getText();
                    chars.add(text == null ? 0 : text.length());
                    // irrelevant 类没有金标准，它无从判定「命中」，只记录得分供后续分析
                    if (row.hasGoldKeys() && row.matches(text)) {
                        rank = i + 1;
                        break;
                    }
                }
                double top1 = found.isEmpty() || found.get(0).getScore() == null
                        ? Double.NaN : found.get(0).getScore();
                hits.add(new Hit(strategy.label(), row.id(), row.category(), row.source(), rank, top1, chars));
            }
        }

        truncateVectorTable();   // 评估结束顺手清干净，别把语料留在开发库里

        Path outFile = writeReport(hits, corpusReport, rows.size());
        System.out.printf("%n结果已写入 %s%n", outFile.toAbsolutePath().normalize());
    }

    // ---------- 表操作 ----------

    private void assertTableIsEmpty() {
        long rows = countVectors();
        assertThat(rows)
                .as("""
                        t_vector_store 里有 %d 行既有数据。评估用的检索链路不带 metadata filter，
                        混入任何非本轮语料都会污染结果，所以这里要求独占该表。
                        请先清空再跑评估（前端删掉全部知识库文件，或直接执行 TRUNCATE TABLE t_vector_store）。
                        """.formatted(rows))
                .isZero();
    }

    private long countVectors() {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForObject("SELECT count(*) FROM t_vector_store", Long.class);
    }

    private void truncateVectorTable() {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .execute("TRUNCATE TABLE t_vector_store");
    }

    // ---------- 出报告 ----------

    private Path writeReport(List<Hit> hits, List<String> corpusReport, int questionCount) throws IOException {
        Files.createDirectories(OUT_DIR);

        StringBuilder md = new StringBuilder();
        md.append("# 检索层评估原始结果\n\n");
        md.append("> 由 `RagRetrievalEvaluationTests` 生成，请勿手工编辑。\n");
        md.append("> 复跑：`RAG_EVAL=true mvn test -Dtest=RagRetrievalEvaluationTests`\n\n");
        md.append("问题集 %d 题，每题取回 top%d 后截取。命中判定 = 检索到的块文本包含该题的金标准 key phrase。\n\n"
                .formatted(questionCount, RETRIEVE_K));

        md.append("## 各策略的切分粒度\n\n");
        md.append("| 切分策略 | 块数 | 语料总字符 | 平均块长 |\n|---|---|---|---|\n");
        for (String line : corpusReport) {
            String[] p = line.split("\t");
            int chunks = Integer.parseInt(p[1]);
            int chars = Integer.parseInt(p[2]);
            md.append("| %s | %d | %d | %.0f |%n".formatted(p[0], chunks, chars,
                    chunks == 0 ? 0.0 : (double) chars / chunks));
        }
        md.append('\n');

        List<String> strategies = hits.stream().map(Hit::strategy).distinct().toList();
        List<String> graded = QuestionSet.categories().stream()
                .filter(c -> !"irrelevant".equals(c)).toList();

        md.append("## Recall@K（只计有金标准的题）\n\n");
        md.append("| 切分策略 | K=1 | K=3 | K=5 | K=10 |\n");
        md.append("|---|").append("---|".repeat(4)).append("\n");
        for (String s : strategies) {
            md.append("| ").append(s).append(" |");
            for (int k : REPORTED_KS) {
                long[] c = recallCell(hits, s, null, k);
                md.append(' ').append(pct(c[0], c[1])).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');

        md.append("## Recall@K 按类别拆分\n\n");
        md.append("| 切分策略 | 类别 |").append(" K=1 | K=3 | K=5 | K=10 |").append("\n");
        md.append("|---|---|").append("---|".repeat(4)).append("\n");
        for (String s : strategies) {
            for (String cat : graded) {
                md.append("| ").append(s).append(" | ").append(cat).append(" |");
                for (int k : REPORTED_KS) {
                    long[] c = recallCell(hits, s, cat, k);
                    md.append(' ').append(pct(c[0], c[1])).append(" |");
                }
                md.append('\n');
            }
        }
        md.append('\n');

        md.append("## MRR@%d\n\n".formatted(RETRIEVE_K));
        md.append("第一个命中的块排第几位，就算 1/几；全没命中记 0。\n\n");
        md.append("| 切分策略 | MRR |\n|---|---|\n");
        for (String s : strategies) {
            double sum = 0;
            int n = 0;
            for (Hit h : hits) {
                if (h.strategy().equals(s) && !"irrelevant".equals(h.category())) {
                    sum += h.firstHitRank() == 0 ? 0 : 1.0 / h.firstHitRank();
                    n++;
                }
            }
            md.append("| %s | %.3f |%n".formatted(s, n == 0 ? 0 : sum / n));
        }
        md.append('\n');

        md.append("## 注入上下文的规模（K=3）\n\n");
        md.append("命中率只说对了一半——另一半是「打中之后往提示词里塞了多少字」。");
        md.append("语料总共才约 9000 字符，看这一列才能发现「整篇不切分」是靠文档级命中刷出来的好看数字：");
        md.append("它一块就是整篇，一命中等于整篇进上下文。\n\n");
        md.append("| 切分策略 | K=1 平均字符数 | K=3 平均字符数 | K=3 最大字符数 |\n|---|---|---|---|\n");
        for (String s : strategies) {
            List<Hit> graded1 = hits.stream()
                    .filter(h -> h.strategy().equals(s) && !"irrelevant".equals(h.category())).toList();
            double avg1 = graded1.stream()
                    .mapToInt(h -> h.retrievedChars().isEmpty() ? 0 : h.retrievedChars().get(0))
                    .average().orElse(0);
            List<Integer> sums3 = graded1.stream()
                    .map(h -> h.retrievedChars().stream().limit(3).mapToInt(Integer::intValue).sum())
                    .toList();
            md.append("| %s | %.0f | %.0f | %d |%n".formatted(s, avg1,
                    sums3.stream().mapToInt(Integer::intValue).average().orElse(0),
                    sums3.stream().mapToInt(Integer::intValue).max().orElse(0)));
        }
        md.append('\n');

        md.append("## 未命中明细\n\n");
        for (String s : strategies) {
            List<Hit> miss3 = hits.stream()
                    .filter(h -> h.strategy().equals(s) && !"irrelevant".equals(h.category())
                            && (h.firstHitRank() == 0 || h.firstHitRank() > 3))
                    .toList();
            long miss1 = hits.stream()
                    .filter(h -> h.strategy().equals(s) && !"irrelevant".equals(h.category())
                            && (h.firstHitRank() == 0 || h.firstHitRank() > 1))
                    .count();
            md.append("- **%s**：K=1 未命中 %d 题，K=3 未命中 %d 题".formatted(s, miss1, miss3.size()));
            if (!miss3.isEmpty()) {
                md.append(" → ").append(miss3.stream()
                        .map(h -> "%s（%s）".formatted(h.id(), h.source())).sorted().toList());
            }
            md.append('\n');
        }
        md.append('\n');

        md.append("## 相似度得分分布（考察「答不出」能否被区分）\n\n");
        md.append("irrelevant 类的正确行为是「知识库中没有」——检索层不评它的命中率，");
        md.append("但如果它的 top1 得分与其他类别一样高，就说明单靠相似度阈值无法把「答得出」和「答不出」分开。\n\n");
        md.append("| 切分策略 | 类别 | 平均 top1 得分 | 最低 | 最高 |\n|---|---|---|---|---|\n");
        for (String s : strategies) {
            for (String cat : QuestionSet.categories()) {
                List<Double> scores = hits.stream()
                        .filter(h -> h.strategy().equals(s) && h.category().equals(cat)
                                && !Double.isNaN(h.top1Score()))
                        .map(Hit::top1Score).sorted().toList();
                if (scores.isEmpty()) {
                    continue;
                }
                md.append("| %s | %s | %.4f | %.4f | %.4f |%n".formatted(s, cat,
                        scores.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                        scores.get(0), scores.get(scores.size() - 1)));
            }
        }
        md.append('\n');

        md.append("## 逐题明细\n\n");
        md.append("| 切分策略 | 题号 | 类别 | 首个命中位次 | top1 得分 |\n|---|---|---|---|---|\n");
        for (Hit h : hits) {
            md.append("| %s | %s | %s | %s | %.4f |%n".formatted(h.strategy(), h.id(), h.category(),
                    h.firstHitRank() == 0 ? "未命中" : String.valueOf(h.firstHitRank()), h.top1Score()));
        }

        Path out = OUT_DIR.resolve("retrieval-results.md");
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);

        System.out.println(md);
        return out;
    }

    /** 命中数 / 总数；category 传 null 表示不分类别 */
    private static long[] recallCell(List<Hit> hits, String strategy, String category, int k) {
        long hit = 0;
        long total = 0;
        for (Hit h : hits) {
            if (!h.strategy().equals(strategy)) {
                continue;
            }
            if ("irrelevant".equals(h.category())) {
                continue;
            }
            if (category != null && !h.category().equals(category)) {
                continue;
            }
            total++;
            if (h.firstHitRank() != 0 && h.firstHitRank() <= k) {
                hit++;
            }
        }
        return new long[]{hit, total};
    }

    private static String pct(long hit, long total) {
        if (total == 0) {
            return "-";
        }
        // 注意不能写成 "…".formatted(Locale.ROOT, …)：formatted 只有 (Object...) 一个重载，
        // 传进去的 Locale 会被当成 %.1f 的实参而抛 IllegalFormatConversion。指定 Locale 要用 String.format
        return String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * hit / total, hit, total);
    }
}
