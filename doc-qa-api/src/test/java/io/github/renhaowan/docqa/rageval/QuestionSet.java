package io.github.renhaowan.docqa.rageval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 评估问题集的加载
 * <p>
 * 问题集是 TSV（{@code docs/rag-evaluation/questions.tsv}），列序：
 * {@code id / category / question / answer / keys / source}。
 * 金标准用 <b>key phrase 文本匹配</b>而不是 chunk ID——不同切分策略的块边界不同，
 * chunk ID 无法跨策略比较，而 key phrase 可以。
 **/
final class QuestionSet {

    private static final Path TSV = Path.of("..", "docs", "rag-evaluation", "questions.tsv");

    private QuestionSet() {
    }

    /**
     * 一道题。
     *
     * @param id       题号，如 A1 / B3 / E2
     * @param category precise / confusable / integrative / irrelevant
     * @param question 提问原文——检索时直接拿它当 query，不再改写
     * @param answer   参考答案（端到端评估时才用得上，检索层不参与）
     * @param keys     金标准 key phrase；irrelevant 类为空
     * @param source   出处（文档号-条号），仅供人工复核
     */
    record Row(String id, String category, String question, String answer,
               List<String> keys, String source) {

        /** 是否带金标准。irrelevant 类没有——它的正确答案是「答不出」，检索层不评它 */
        boolean hasGoldKeys() {
            return !keys.isEmpty();
        }

        /** 命中判定：检索到的这一块，是否包含本行的任一 key phrase */
        boolean matches(String chunkText) {
            return keys.stream().anyMatch(chunkText::contains);
        }
    }

    static List<Row> load() throws IOException {
        List<String> lines = Files.readAllLines(TSV, StandardCharsets.UTF_8);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {   // 第 0 行是表头
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            // -1 保留行尾空列：irrelevant 行的 keys / source 是 "-"，但若将来被误删成空，
            // 这里会直接抛数组越界而不是静默错位
            String[] c = line.split("\t", -1);
            rows.add(new Row(c[0], c[1], c[2], c[3],
                    "-".equals(c[4]) ? List.of() : List.of(c[4].split("\\|")),
                    c[5]));
        }
        return rows;
    }

    /**
     * 已知类别。写错类别不会报错，只会静默地少统计一类，所以要有一处权威枚举。
     */
    static List<String> categories() {
        return List.of("precise", "confusable", "integrative", "irrelevant");
    }
}
