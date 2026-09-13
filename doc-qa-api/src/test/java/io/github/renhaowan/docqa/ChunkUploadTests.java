package io.github.renhaowan.docqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseChunkDO;
import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseFileDO;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseChunkMapper;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseFileMapper;
import io.github.renhaowan.docqa.domain.mapper.UserMapper;
import io.github.renhaowan.docqa.enums.KnowledgeBaseFileStatusEnum;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 分片上传链路的集成测试——乱序、重复、缺片，以及孤儿分片的就地修复
 * <p>
 * 这是项目里状态最多、分支最密的一段代码（{@code checkFile → uploadChunk → mergeChunk}），
 * 也是唯一出过真实竞态与脏数据问题的地方，所以单独给它一组测试。覆盖的行为：
 * <ul>
 *   <li><b>乱序上传</b>——前端是 3 路并发，分片到达顺序不确定，但合并结果必须与顺序无关。
 *       这条断言实际锁住的是 {@code KnowledgeBaseChunkMapper.selecChunkedtList} 的
 *       {@code orderByAsc(chunkNumber)}：少了这个排序，合并出来的文件内容就是错的，
 *       而接口层面一切正常——没有这组测试根本发现不了。</li>
 *   <li><b>重复上传同一分片</b>——幂等返回，且 {@code uploaded_chunks} 不能重复计数
 *       （多计会让 {@code mergeChunk} 的分片完整性校验永远通不过）。</li>
 *   <li><b>缺片合并</b>——必须报 20007，不能悄悄合并出一个残缺文件。</li>
 *   <li><b>孤儿分片</b>——「有分片、无主记录」的历史脏数据；快速路径要就地重建主记录，
 *       否则该 MD5 会永久卡死（怎么传都合并不了）。</li>
 * </ul>
 * <p>
 * <b>磁盘路径被改到临时目录</b>（见 {@link #registerStoragePaths}）：这个类会真的写分片、
 * 真的合并文件，而 {@code @Transactional} 只回滚数据库、<b>回滚不了磁盘</b>。
 * 不重定向的话，每跑一次都会往 {@code data/} 里留一堆垃圾。
 * <p>
 * <b>不会触发向量化</b>：{@code mergeChunk} 发布的事件由
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 监听，而测试的事务从不提交，
 * 所以监听器不会执行——既不会联网调 embedding，也不会往 {@code t_vector_store} 写数据。
 * 这也是本类能挂 {@code @Transactional} 的前提。
 * <p>
 * ⚠️ <b>没有覆盖真正的并发</b>。{@code insertChunkIgnoreDuplicate} 的 {@code ON CONFLICT}
 * 与「检查-写入」之间的竞态窗口，需要在多线程下让两个请求同时通过检查才能复现，
 * 用单测稳定复现的成本高于收益。这里只覆盖了它的幂等语义（重复上传），
 * 并发本身属于手工验收。
 **/
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChunkUploadTests {

    private static final String FILE_NAME = "分片上传测试.md";

    /** 三片内容各不相同，且刻意让长度不等——合并顺序一旦错乱，拼出来的字节必然对不上 */
    private static final byte[][] CHUNKS = {
            "分片零".getBytes(StandardCharsets.UTF_8),
            "分片一的内容长一些".getBytes(StandardCharsets.UTF_8),
            "片二".getBytes(StandardCharsets.UTF_8),
    };

    private static final long TOTAL_BYTES = totalBytes();

    private static final Path TMP_DIR;

    static {
        try {
            TMP_DIR = Files.createTempDirectory("kb-chunk-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 把分片目录与文件目录都指到临时目录。
     * <p>
     * 用 {@code @DynamicPropertySource} 而不是 {@code @TestPropertySource}：后者的值必须写成
     * 字符串字面量，而这里要等到运行时才拿到临时目录的绝对路径。
     */
    @DynamicPropertySource
    static void registerStoragePaths(DynamicPropertyRegistry registry) {
        registry.add("knowledge-base.chunk-path", () -> TMP_DIR.resolve("chunks").toString());
        registry.add("knowledge-base.file-storage-path", () -> TMP_DIR.resolve("files").toString());
    }

    @AfterAll
    static void cleanUpTempDir() throws IOException {
        FileUtils.deleteDirectory(TMP_DIR.toFile());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private KnowledgeBaseChunkMapper chunkMapper;
    @Autowired
    private KnowledgeBaseFileMapper fileMapper;
    @Autowired
    private UserMapper userMapper;

    private String token;

    @BeforeEach
    void loginAsDemo() throws Exception {
        token = login("demo", "demo123");
    }

    // ---------- 上传与合并 ----------

    @Test
    void testUploadInOrderThenMergeProducesIdenticalBytes() throws Exception {
        String md5 = randomMd5();

        uploadAllChunks(md5, 0, 1, 2);
        merge(md5);

        assertThat(mergedBytes(md5)).isEqualTo(concatenatedChunks());
    }

    @Test
    void testUploadOutOfOrderThenMergeProducesIdenticalBytes() throws Exception {
        String md5 = randomMd5();

        // 前端并发上传时真实的到达顺序：先传第二片，再传最后一片，第一片姗姗来迟
        uploadAllChunks(md5, 1, 2, 0);
        merge(md5);

        // 合并按 chunk_number 升序拼接，与上传顺序无关。
        // 若 selecChunkedtList 丢了 orderByAsc，这里会拿到 1→2→0 的拼接结果而失败
        assertThat(mergedBytes(md5)).isEqualTo(concatenatedChunks());
    }

    /**
     * 单独锁住「分片列表按序号升序返回」这一前提。
     * <p>
     * 上面的乱序合并用例已经间接覆盖了它，但那是个端到端断言，失败时只能看出「文件内容不对」。
     * 这条断言失败时直接指向原因。
     */
    @Test
    void testChunkListIsOrderedByChunkNumber() throws Exception {
        String md5 = randomMd5();
        uploadAllChunks(md5, 2, 0, 1);

        List<Integer> numbers = chunkMapper.selecChunkedtList(md5).stream()
                .map(KnowledgeBaseChunkDO::getChunkNumber)
                .toList();

        assertThat(numbers).containsExactly(0, 1, 2);
    }

    @Test
    void testDuplicateChunkIsIdempotentAndNotCountedTwice() throws Exception {
        String md5 = randomMd5();

        uploadChunk(md5, 0, 3);
        uploadChunk(md5, 0, 3);   // 重传同一片

        assertThat(chunkMapper.selecChunkedtList(md5)).hasSize(1);
        // 多计会让 mergeChunk 的「已传片数 != 总片数」校验永远不成立，文件再也合并不了
        assertThat(fileMapper.selectByMd5(md5).getUploadedChunks()).isEqualTo(1);
    }

    @Test
    void testMergeWithMissingChunkRejected() throws Exception {
        String md5 = randomMd5();

        uploadChunk(md5, 0, 3);
        uploadChunk(md5, 1, 3);
        // 第三片没传

        mockMvc.perform(post("/knowledge-base/file/merge-chunk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMd5\":\"" + md5 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20007"));

        // 拒了就要拒干净：状态还停在上传中，文件不落盘
        assertThat(fileMapper.selectByMd5(md5).getStatus())
                .isEqualTo(KnowledgeBaseFileStatusEnum.UPLOADING.getCode());
        assertThat(fileMapper.selectByMd5(md5).getStoredFileName()).isEmpty();
    }

    @Test
    void testMergeWithoutMainRecordRejected() throws Exception {
        mockMvc.perform(post("/knowledge-base/file/merge-chunk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMd5\":\"" + randomMd5() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20006"));
    }

    @Test
    void testMergeClearsChunksAndMovesToPending() throws Exception {
        String md5 = randomMd5();
        uploadAllChunks(md5, 0, 1, 2);

        merge(md5);

        KnowledgeBaseFileDO record = fileMapper.selectByMd5(md5);
        assertThat(record.getStatus()).isEqualTo(KnowledgeBaseFileStatusEnum.PENDING.getCode());
        assertThat(record.getStoredFileName()).endsWith(FILE_NAME);
        // 分片记录与分片目录都要清掉，否则同一个 MD5 重传时会误判成「分片已齐」
        assertThat(chunkMapper.selecChunkedtList(md5)).isEmpty();
        assertThat(TMP_DIR.resolve("chunks").resolve(md5)).doesNotExist();
    }

    // ---------- 入参校验 ----------

    /**
     * 缺 fileMd5 的上传请求。
     * <p>
     * 必须返回「参数错误」而不是让它在 {@code Paths.get(chunkPath, null)} 上抛 NPE——
     * 后者会被兜底成「系统错误」，前端只能看到「后台小哥正在努力修复中」，
     * 而真正的原因（客户端漏传字段）完全看不出来。
     */
    @Test
    void testUploadChunkWithoutMd5IsRejectedAsParamError() throws Exception {
        mockMvc.perform(multipart("/knowledge-base/file/upload-chunk")
                        .file(new MockMultipartFile("chunk", "0.chunk",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, CHUNKS[0]))
                        .param("fileName", FILE_NAME)
                        .param("fileSize", String.valueOf(TOTAL_BYTES))
                        .param("chunkNumber", "0")
                        .param("totalChunks", String.valueOf(CHUNKS.length))
                        // 刻意不传 fileMd5
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    // ---------- checkFile 的三种返回 ----------

    @Test
    void testCheckFileAsksForUploadWhenRecordAbsent() throws Exception {
        mockMvc.perform(post("/knowledge-base/file/check")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMd5\":\"" + randomMd5() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(false))
                .andExpect(jsonPath("$.data.needUpload").value(true));
    }

    @Test
    void testCheckFileListsUploadedChunksWhileUploading() throws Exception {
        String md5 = randomMd5();
        uploadChunk(md5, 0, 3);
        uploadChunk(md5, 2, 3);

        mockMvc.perform(post("/knowledge-base/file/check")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMd5\":\"" + md5 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.needUpload").value(true))
                // 断点续传的依据：客户端据此跳过这几片
                .andExpect(jsonPath("$.data.uploadedChunks[0]").value(0))
                .andExpect(jsonPath("$.data.uploadedChunks[1]").value(2));
    }

    @Test
    void testCheckFileSkipsUploadWhenAlreadyVectorized() throws Exception {
        String md5 = insertFileRecord(KnowledgeBaseFileStatusEnum.COMPLETED);

        mockMvc.perform(post("/knowledge-base/file/check")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMd5\":\"" + md5 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.needUpload").value(false));
    }

    // ---------- 孤儿分片 ----------

    /**
     * 「有分片、无主记录」的历史脏数据。
     * <p>
     * 正常路径下 {@code deleteMarkdownFile} 会连分片一并清理，但历史上留过这个状态，
     * 而它的后果很重：重传时 {@code checkFile} 说「需上传」，可 {@code uploadChunk} 的快速路径
     * 只认分片表、直接返回成功，主记录永远重建不出来，于是 {@code mergeChunk} 恒报 20006。
     * 修复办法就是在快速路径里就地重建主记录。
     */
    @Test
    void testOrphanChunkRestoresMainRecord() throws Exception {
        String md5 = randomMd5();
        uploadChunk(md5, 0, 3);
        uploadChunk(md5, 1, 3);

        // 模拟主记录被删、分片残留
        fileMapper.deleteById(fileMapper.selectByMd5(md5).getId());
        assertThat(fileMapper.selectByMd5(md5)).isNull();

        // 重传已存在的分片，触发快速路径
        uploadChunk(md5, 1, 3);

        KnowledgeBaseFileDO restored = fileMapper.selectByMd5(md5);
        assertThat(restored).as("主记录应当被就地重建").isNotNull();
        // 取分片表的实际条数而不是固定 1——这个 MD5 已经攒了 2 片，
        // 写成 1 会让 mergeChunk 判为分片不完整
        assertThat(restored.getUploadedChunks()).isEqualTo(2);
        assertThat(restored.getStatus()).isEqualTo(KnowledgeBaseFileStatusEnum.UPLOADING.getCode());
    }

    @Test
    void testOrphanChunkCanStillBeMergedAfterRestore() throws Exception {
        String md5 = randomMd5();
        uploadChunk(md5, 0, 3);
        uploadChunk(md5, 1, 3);
        fileMapper.deleteById(fileMapper.selectByMd5(md5).getId());

        // 重建之后补齐剩下的分片，合并应当能走通——这正是修复前做不到的事
        uploadChunk(md5, 1, 3);
        uploadChunk(md5, 2, 3);
        merge(md5);

        assertThat(mergedBytes(md5)).isEqualTo(concatenatedChunks());
    }

    // ---------- 辅助方法 ----------

    private void uploadAllChunks(String md5, int... order) throws Exception {
        for (int chunkNumber : order) {
            uploadChunk(md5, chunkNumber, CHUNKS.length);
        }
    }

    private void uploadChunk(String md5, int chunkNumber, int totalChunks) throws Exception {
        mockMvc.perform(multipart("/knowledge-base/file/upload-chunk")
                        .file(new MockMultipartFile("chunk", chunkNumber + ".chunk",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, CHUNKS[chunkNumber]))
                        .param("fileMd5", md5)
                        .param("fileName", FILE_NAME)
                        .param("fileSize", String.valueOf(TOTAL_BYTES))
                        .param("chunkNumber", String.valueOf(chunkNumber))
                        .param("totalChunks", String.valueOf(totalChunks))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void merge(String md5) throws Exception {
        mockMvc.perform(post("/knowledge-base/file/merge-chunk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMd5\":\"" + md5 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** 读取合并后的文件。目录由 file-storage-path 推导，记录里只有文件名 */
    private byte[] mergedBytes(String md5) throws IOException {
        String storedFileName = fileMapper.selectByMd5(md5).getStoredFileName();
        assertThat(storedFileName).as("合并后 stored_file_name 应当有值").isNotBlank();
        return Files.readAllBytes(TMP_DIR.resolve("files").resolve(storedFileName));
    }

    private String insertFileRecord(KnowledgeBaseFileStatusEnum status) {
        Long uploaderId = userMapper.selectByUsername("demo").getId();
        LocalDateTime now = LocalDateTime.now();
        String md5 = randomMd5();

        fileMapper.insert(KnowledgeBaseFileDO.builder()
                .fileMd5(md5)
                .fileName(FILE_NAME)
                .storedFileName(System.currentTimeMillis() + "_" + FILE_NAME)
                .fileSize(TOTAL_BYTES)
                .totalChunks(CHUNKS.length)
                .uploadedChunks(CHUNKS.length)
                .status(status.getCode())
                .uploaderId(uploaderId)
                .createTime(now)
                .updateTime(now)
                .build());
        return md5;
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /**
     * 每次都用新的 MD5。测试不清理数据库（靠 {@code @Transactional} 回滚），
     * 但 MD5 一旦重复，上一次留下的分片表记录会让本次的「缺片」用例误判成「已齐」。
     */
    private static String randomMd5() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] concatenatedChunks() {
        byte[] out = new byte[(int) TOTAL_BYTES];
        int pos = 0;
        for (byte[] chunk : CHUNKS) {
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }

    private static long totalBytes() {
        long total = 0;
        for (byte[] chunk : CHUNKS) {
            total += chunk.length;
        }
        return total;
    }
}
