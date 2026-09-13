package io.github.renhaowan.docqa.rageval;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 评估语料的加载与切分
 * <p>
 * 语料放在仓库根的 {@code docs/rag-evaluation/corpus/}，而测试的工作目录是 {@code doc-qa-api/}，
 * 所以路径要回退一级。这样放是为了让语料、问题集、结论文档三份评估资产同处一处，
 * 复核的人不必在 src/test/resources 与 docs 之间来回跳。
 **/
final class Corpus {

    static final Path DIR = Path.of("..", "docs", "rag-evaluation", "corpus");

    private Corpus() {
    }

    /**
     * 生产环境真实使用的 reader 配置。
     * <p>
     * 评估必须跑在它上面——换一套配置测出来的切分行为，结论无法迁移到线上。
     */
    static MarkdownDocumentReaderConfig productionConfig() {
        return MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(false)
                .withIncludeBlockquote(false)
                .build();
    }

    static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    static List<Document> chunks(Path file, ChunkingStrategy strategy) throws IOException {
        return switch (strategy) {
            case PRODUCTION -> productionChunks(file);
            case TITLE_IN_TEXT -> titleInText(file);
            case WHOLE_DOCUMENT -> List.of(document(Files.readString(file, StandardCharsets.UTF_8)));
            case BY_ARTICLE -> byArticle(file);
            case SLIDING_WINDOW -> windowed(file);
        };
    }

    static List<Document> allChunks(ChunkingStrategy strategy) throws IOException {
        List<Document> all = new ArrayList<>();
        for (Path file : files()) {
            all.addAll(chunks(file, strategy));
        }
        return all;
    }

    /**
     * 每批导入的块数。
     * <p>
     * 不是随便取的：阿里云 text-embedding-v4 单次请求的 input 数组上限是 10 条，
     * 而 PgVectorStore 只按自己的 max-document-batch-size（配置里是 10000）分批、
     * 不会替我们降到 10 以下。一次丢 80 块进去，请求会被服务端直接拒掉。
     */
    private static final int EMBED_BATCH_SIZE = 10;

    /**
     * 把某一切分策略下的全部语料导入向量库，返回写入的块。
     * <p>
     * 刻意<b>不走</b>生产那套「逐条 add 前先 topK=1 查重、得分 >0.99 就跳过」的逻辑：
     * 评估语料本身不重复，查重只会让每条多花一次 embedding 调用。检索行为不受导入方式影响。
     * <p>
     * 重建 Document 而不是沿用 reader 产出的那个：metadata 换成评估自己的标记，
     * 这样各策略之间唯一的变量就是「正文怎么切」，不会因为 PRODUCTION 多带了 title 之类的元数据
     * 而在写入路径上产生差异。
     */
    static List<Document> importInto(VectorStore vectorStore,
                                     ChunkingStrategy strategy) throws IOException {
        List<Document> all = new ArrayList<>();
        for (Path file : files()) {
            List<Document> chunks = chunks(file, strategy);
            for (int i = 0; i < chunks.size(); i++) {
                String text = chunks.get(i).getText();
                all.add(document(text == null ? "" : text, Map.of(
                        "evalStrategy", strategy.name(),
                        "evalSource", file.getFileName().toString(),
                        "evalChunkIndex", i)));
            }
        }

        for (int i = 0; i < all.size(); i += EMBED_BATCH_SIZE) {
            vectorStore.add(all.subList(i, Math.min(i + EMBED_BATCH_SIZE, all.size())));
        }
        return all;
    }

    private static List<Document> productionChunks(Path file) {
        return new MarkdownDocumentReader(new FileSystemResource(file), productionConfig()).get();
    }

    /**
     * 现状 + 把标题补进正文。
     * <p>
     * 原始 reader 把标题只写进 metadata、不写进 text，而向量化的是 text——
     * 于是「第一章 总则」这类概括性语义参与不了检索。这一策略用来量化那部分损失。
     */
    private static List<Document> titleInText(Path file) {
        List<Document> out = new ArrayList<>();
        for (Document d : productionChunks(file)) {
            Object title = d.getMetadata().get("title");
            String text = title == null ? d.getText() : title + "\n" + d.getText();
            out.add(document(text, d.getMetadata()));
        }
        return out;
    }

    /**
     * 按「第X条」切分。语料里的条文以 {@code **第X条**} 开头，用前瞻断言在原位置切开，
     * 分隔符本身留在后一段的开头。
     * <p>
     * 注意 {@code **} 不是正文——commonmark 会把它解析成 Strong 节点、不进文本，
     * 所以这里按原文切出来的块，其纯文本与 reader 产出的写法保持可比。
     */
    private static List<Document> byArticle(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<Document> out = new ArrayList<>();
        for (String part : content.split("(?=\\*\\*第[一二三四五六七八九十]+条\\*\\*)")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.add(document(trimmed));
            }
        }
        return out;
    }

    /**
     * 超长块的长度上限。评估数据显示 02 文档的「第十五条」单块 1222 字符——
     * 它内部挤进了事假、婚假、产假、探亲假、丧假、年休假全部规定，
     * 于是「基本产假多少天」这种细节被同块的其他假期内容稀释、排到了第 7 位。
     * 500 是让这个巨块能落下 3 块左右而设的经验值，不是推出来的最优解。
     */
    private static final int MAX_CHUNK_CHARS = 500;

    /**
     * 滑窗重叠长度。保证任何跨边界的短片段至少完整出现在某一窗内——
     * 否则 key phrase 一旦正好骑在切点上，就会像跨块那样永远匹配不上。
     * 取值要明显大于问题集里最长的 key phrase。
     */
    private static final int WINDOW_OVERLAP = 50;

    /**
     * 现状 + 超长块滑窗。
     * <p>
     * 主干仍是 reader 的标题切分，只对超过 {@link #MAX_CHUNK_CHARS} 的块再按固定长度、
     * 带 {@link #WINDOW_OVERLAP} 字符重叠地滑窗切开。相比"按条切"，它不动正常的块，
     * 只解决"一条特别长"这一个具体问题。
     */
    private static List<Document> windowed(Path file) {
        List<Document> out = new ArrayList<>();
        for (Document d : productionChunks(file)) {
            String text = d.getText();
            if (text == null || text.length() <= MAX_CHUNK_CHARS) {
                out.add(d);
                continue;
            }
            int step = MAX_CHUNK_CHARS - WINDOW_OVERLAP;
            for (int i = 0; i < text.length(); i += step) {
                out.add(document(text.substring(i, Math.min(i + MAX_CHUNK_CHARS, text.length())),
                        d.getMetadata()));
                if (i + MAX_CHUNK_CHARS >= text.length()) {
                    break;   // 已取到末尾，不必再补一个几乎全是重叠的尾巴
                }
            }
        }
        return out;
    }

    private static Document document(String text) {
        return Document.builder().text(text).build();
    }

    private static Document document(String text, java.util.Map<String, Object> metadata) {
        Document.Builder builder = Document.builder().text(text);
        metadata.forEach(builder::metadata);
        return builder.build();
    }

    /**
     * 切分策略。评估要回答的不只是「检索准不准」，还有「切分方式对检索的影响有多大」。
     */
    enum ChunkingStrategy {

        /** 生产现状：MarkdownDocumentReader 按标题（含 ---）切分 */
        PRODUCTION("现状（按标题切，标题不进正文）"),

        /** 现状 + 标题补进正文 */
        TITLE_IN_TEXT("标题进正文"),

        /** 整篇作一块，不做任何切分 */
        WHOLE_DOCUMENT("整篇不切分"),

        /** 按「第X条」切，粒度最细 */
        BY_ARTICLE("按条切"),

        /** 现状 + 超长块滑窗——针对「一条特别长」这一具体失败模式的改进尝试 */
        SLIDING_WINDOW("现状 + 超长块滑窗");

        private final String label;

        ChunkingStrategy(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
