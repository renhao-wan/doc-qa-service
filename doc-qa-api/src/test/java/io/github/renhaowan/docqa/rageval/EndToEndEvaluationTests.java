package io.github.renhaowan.docqa.rageval;

import io.github.renhaowan.docqa.advisor.KnowledgeBaseAdvisor;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 端到端评估——开关 RAG 各跑一遍全部问题，比对最终答案对不对
 * <p>
 * 检索层的 Recall/MRR 只是中间指标：检索到了不等于答对了，模型完全可能在一堆正确材料里
 * 挑错一条。所以最终还得看答案本身。
 * <p>
 * 三组对照：
 * <ul>
 *   <li><b>裸模型</b>——不带任何检索，看模型仅凭自身知识能答成什么样。这是 RAG 的收益基准。</li>
 *   <li><b>RAG · topK=3</b>——线上现状配置。</li>
 *   <li><b>RAG · topK=10</b>——检索层数据显示 topK=10 时 Recall 达 100%，验证「多给几条能否救回
 *       那些排在第 4~10 位的题」。</li>
 * </ul>
 * 走的是生产同一条链路：同一套提示词模板（{@link KnowledgeBaseAdvisor} 的默认模板）、
 * 同一个模型与 temperature，只是检索条数不同。
 * <p>
 * 判分靠规则匹配而非人工逐条读，规则见 {@link #isCorrect}；结果文件里同时打印原始回答，
 * 便于人工复核自动判分有没有误判。
 * <p>
 * 与检索层评估一样默认跳过，需要时显式开启：
 * <pre>{@code
 * RAG_EVAL=true JAVA_HOME="D:/IDEAjava/JDK/jdk17" \
 *   DASHSCOPE_API_KEY=xxx mvn test -Dtest=EndToEndEvaluationTests
 * }</pre>
 **/
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true",
        disabledReason = "端到端评估要联网调大模型且耗时较长，默认跳过；设 RAG_EVAL=true 开启")
class EndToEndEvaluationTests {

    /** 对照组的检索条数；0 表示不挂 RAG（裸模型） */
    private static final int[] TOP_KS = {0, 3, 10};

    private static final Path OUT_DIR = Path.of("..", "docs", "rag-evaluation", "results");

    @Resource
    private VectorStore vectorStore;
    @Resource
    private DataSource dataSource;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;
    @Value("${knowledge-base.model}")
    private String model;
    @Value("${knowledge-base.temperature}")
    private Double temperature;

    /**
     * 一组问答的结果。
     *
     * @param correct 规则判定的对错；irrelevant 类判的是「有没有拒答」
     */
    private record Answer(String group, String id, String category, String question,
                          String expected, String response, boolean correct) {
    }

    @Test
    void compareWithAndWithoutRag() throws IOException {
        // RAG 组要有料可检索，语料现导——不依赖检索层评估跑没跑过（它跑完会清表）
        truncateVectorTable();
        int imported = Corpus.importInto(vectorStore, Corpus.ChunkingStrategy.PRODUCTION).size();
        System.out.printf("已导入语料 %d 块（生产切分策略）%n", imported);

        List<QuestionSet.Row> rows = QuestionSet.load();
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .build();

        List<Answer> answers = new ArrayList<>();
        for (int topK : TOP_KS) {
            String group = topK == 0 ? "裸模型" : "RAG · topK=" + topK;
            System.out.printf("%n########## %s ##########%n", group);
            for (QuestionSet.Row row : rows) {
                String response = ask(chatModel, row.question(), topK);
                boolean correct = isRefusalQuestion(row)
                        ? isRefusal(response)
                        : isCorrect(row, response);
                answers.add(new Answer(group, row.id(), row.category(), row.question(),
                        row.answer(), response, correct));
                System.out.printf("  %s %s %s%n", row.id(), correct ? "✓" : "✗",
                        response.replace("\n", " ").substring(0, Math.min(70, response.length())));
            }
        }

        truncateVectorTable();   // 别把评估语料留在开发库里

        Path out = writeReport(answers);
        System.out.printf("%n结果已写入 %s%n", out.toAbsolutePath().normalize());
    }

    /**
     * 走生产同一条链路提问。
     * <p>
     * {@code topK == 0} 时不挂 advisor——对照的是「模型完全不知道这份知识库」的情形，
     * 而不是「提示词里给了一段空上下文」，后者不是任何一种真实用法。
     */
    private String ask(ChatModel chatModel, String question, int topK) {
        ChatClient.ChatClientRequestSpec spec = ChatClient.create(chatModel)
                .prompt()
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .user(question);

        if (topK > 0) {
            // webFallback 传 false：走严格基于上下文的那套模板，与线上未开联网兜底时一致
            spec = spec.advisors(new KnowledgeBaseAdvisor(vectorStore, false, topK));
        }

        List<String> chunks = spec.stream().content().collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    // ---------- 判分 ----------

    private static boolean isRefusalQuestion(QuestionSet.Row row) {
        return "irrelevant".equals(row.category());
    }

    /** irrelevant 类的正确答案就是「知识库里没有」，命中任一拒答特征词即算对 */
    private static boolean isRefusal(String response) {
        return List.of("没有找到", "未找到", "无法回答", "没有相关", "没有此", "不包含", "暂无",
                        "没有收录", "未收录", "没有提及")
                .stream().anyMatch(response::contains);
    }

    /**
     * 规则判分。
     * <p>
     * 带数字的题按「数字+单位」比，不带单位地裸比数字会误判——比如 B1 问的是年休假 10 天，
     * 而题干里就有「满 10 年」，光看「10」必然算对。
     * <p>
     * ⚠️ 这套规则有一处<b>已知偏差</b>：模型答不出时不会明说「不知道」，而是罗列各地各种可能性
     * （「因地区而异」「以下为参考标准」），凑巧也包含期望数字，于是被判成答对。因此
     * <b>裸模型组的自动判分偏高</b>，人工复核后的数字见 docs/rag-evaluation.md。
     * 曾试过用「不同数字 token 超过 3 个就判否」来拦这类回答，但 <b>弊大于利</b>——
     * 它会把「逐项列明细、最后给合计」这类正确回答一并拦掉（A9 列了 340+100+130+30=600、
     * B4 列了 30天/15天/80% 之后给出 70%），实测净收益为负，已移除。
     * <p>
     * 另一处是措辞改写导致的漏判：提示词要求「热情、专业」，模型于是把「不得安排高档套房」
     * 写成「严禁安排高档套房」。{@link #normalize} 归一了最常见的几个近义词，但不是穷举。
     */
    private static boolean isCorrect(QuestionSet.Row row, String response) {
        List<String> expected = numericTokens(row.answer());
        if (!expected.isEmpty()) {
            List<String> got = numericTokens(response);
            return expected.stream().anyMatch(got::contains);
        }

        // 无数字的题（多为列举型）：把 key phrase 拆成片段，过半命中即算对。
        // 要求全中过于苛刻——模型答对「嘉奖、记功」，只把「记大功」写成「记大功奖励」就会被判错
        String normalized = normalize(response);
        List<String> parts = Arrays.stream(String.join("|", row.keys()).split("[|、，,]"))
                .map(String::strip)
                .map(EndToEndEvaluationTests::normalize)
                .filter(s -> s.length() >= 2)
                .toList();
        if (parts.isEmpty()) {
            return false;
        }
        long hit = parts.stream().filter(normalized::contains).count();
        return hit * 2 >= parts.size();
    }

    /**
     * 把模型常用的替代措辞归一。原文写「不得提供烟酒」，模型答「禁止提供烟酒」——
     * 意思分毫不差，字符串却对不上。
     */
    private static String normalize(String text) {
        return text.replace("严禁", "不得")
                .replace("禁止", "不得")
                .replace("不可以", "不得")
                .replace("不允许", "不得");
    }

    /**
     * 抽出「数字 + 单位」。
     * <p>
     * 单位取数字后的第一个量词，多于一个字的组合（如「个工作日」）只看首字——够用来区分
     * 「15 天」和「15 个工作日」这类同数不同指的答案，又不至于因为模型的措辞差异而漏判。
     * 「日」与「天」归一，因为原文与回答常各写一种。
     */
    private static List<String> numericTokens(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(\\d+)\\s*([%天日年月元人个周])?").matcher(text);
        while (m.find()) {
            String unit = m.group(2);
            if (unit == null) {
                continue;   // 没有单位的裸数字不参与判分，太容易撞车
            }
            out.add(m.group(1) + ("日".equals(unit) ? "天" : unit));
        }
        return List.copyOf(out);
    }

    // ---------- 表操作 ----------

    private void truncateVectorTable() {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .execute("TRUNCATE TABLE t_vector_store");
    }

    // ---------- 出报告 ----------

    private Path writeReport(List<Answer> answers) throws IOException {
        Files.createDirectories(OUT_DIR);

        List<String> groups = answers.stream().map(Answer::group).distinct().toList();
        List<String> categories = QuestionSet.categories();

        StringBuilder md = new StringBuilder();
        md.append("# 端到端评估原始结果\n\n");
        md.append("> 由 `EndToEndEvaluationTests` 生成，请勿手工编辑。\n");
        md.append("> 复跑：`RAG_EVAL=true mvn test -Dtest=EndToEndEvaluationTests`\n\n");
        md.append("同一批问题分别在三组条件下问同一个模型，temperature=0，答案由规则判定对错。\n");
        md.append("判分规则见测试类的 `isCorrect` / `isRefusal`；每题原始回答附在文末，便于复核误判。\n\n");

        md.append("## 总正确率\n\n");
        md.append("| 条件 | 有金标准的题 | 拒答题（irrelevant） |\n|---|---|---|\n");
        for (String g : groups) {
            long graded = answers.stream().filter(a -> a.group().equals(g) && !"irrelevant".equals(a.category())).count();
            long gradedOk = answers.stream().filter(a -> a.group().equals(g) && !"irrelevant".equals(a.category()) && a.correct()).count();
            long ref = answers.stream().filter(a -> a.group().equals(g) && "irrelevant".equals(a.category())).count();
            long refOk = answers.stream().filter(a -> a.group().equals(g) && "irrelevant".equals(a.category()) && a.correct()).count();
            md.append("| %s | %s | %s |%n".formatted(g, pct(gradedOk, graded), pct(refOk, ref)));
        }
        md.append('\n');

        md.append("## 按类别拆分（有金标准的题）\n\n");
        md.append("| 条件 |").append(" precise | confusable | integrative |").append("\n");
        md.append("|---|").append("---|".repeat(3)).append("\n");
        for (String g : groups) {
            md.append("| ").append(g).append(" |");
            for (String cat : categories) {
                if ("irrelevant".equals(cat)) {
                    continue;
                }
                long n = answers.stream().filter(a -> a.group().equals(g) && a.category().equals(cat)).count();
                long ok = answers.stream().filter(a -> a.group().equals(g) && a.category().equals(cat) && a.correct()).count();
                md.append(' ').append(pct(ok, n)).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');

        md.append("## 逐题对错\n\n");
        md.append("| 题号 | 类别 |").append(groups.stream().map(g -> " " + g + " |").reduce("", String::concat)).append("\n");
        md.append("|---|---|").append("---|".repeat(groups.size())).append("\n");
        for (QuestionSet.Row row : QuestionSet.load()) {
            md.append("| %s | %s |".formatted(row.id(), row.category()));
            for (String g : groups) {
                String mark = answers.stream()
                        .filter(a -> a.group().equals(g) && a.id().equals(row.id()))
                        .map(a -> a.correct() ? "✅" : "❌")
                        .findFirst().orElse("-");
                md.append(' ').append(mark).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');

        md.append("## 参考答案与原始回答\n\n");
        for (QuestionSet.Row row : QuestionSet.load()) {
            md.append("### %s · %s\n\n".formatted(row.id(), row.question()));
            md.append("**参考答案**：%s（出处 %s）\n\n".formatted(row.answer(), row.source()));
            for (String g : groups) {
                answers.stream()
                        .filter(a -> a.group().equals(g) && a.id().equals(row.id()))
                        .findFirst()
                        .ifPresent(a -> md.append("**%s** %s：%s\n\n".formatted(
                                g, a.correct() ? "✅" : "❌",
                                a.response().strip().replace("\n", "\n> "))));
            }
        }

        Path out = OUT_DIR.resolve("end-to-end-results.md");
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);

        // 汇总表也打到 stdout，省得为了看一眼结果去开文件
        int head = md.indexOf("## 按类别拆分");
        System.out.println(md.substring(0, head));
        return out;
    }

    private static String pct(long ok, long total) {
        if (total == 0) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * ok / total, ok, total);
    }
}
