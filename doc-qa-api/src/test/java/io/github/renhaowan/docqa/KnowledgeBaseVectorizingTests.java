package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseFileDO;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseFileMapper;
import io.github.renhaowan.docqa.enums.KnowledgeBaseFileStatusEnum;
import io.github.renhaowan.docqa.event.KnowledgeBaseFileUploadedEvent;
import io.github.renhaowan.docqa.event.listener.KnowledgeBaseFileUploadedListener;
import io.github.renhaowan.docqa.reader.MarkdownReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Author: Renhao-Wan
 * @Date: 2026/9/13
 * @Version: v1.0.0
 * @Description: 文件向量化的写入契约——按归属整体重建，而不是逐条追加
 * <p>
 * 这个类管着 {@code VECTORIZING → COMPLETED / FAILED} 状态机与失败回滚，此前**没有任何测试**。
 * 本类守的是改造后的两条契约：
 * <ol>
 *     <li><b>覆盖式重建</b>：写入前先按 {@code mdStorageId} 删掉该文件已有的向量。
 *         顺序反了会变成「删掉刚写入的向量」，文件向量化后反而检索不到。</li>
 *     <li><b>整批写入</b>：{@code vectorStore.add(documents)} 一次传完全部分片。
 *         {@code PgVectorStore.doAdd} 内部是 {@code EmbeddingModel.embed(List, ..., BatchingStrategy)}
 *         + {@code JdbcTemplate.batchUpdate}，一次调用即可；退回逐条循环会让 embedding 调用
 *         随分片数线性增长（走网络，是这条链路上最贵的一环）。</li>
 * </ol>
 * <p>
 * <b>纯单元测试，不起 Spring 上下文</b>：四个依赖全部 mock，不碰数据库、不碰向量模型、
 * 也不真读文件（{@link MarkdownReader} 被 mock）。整套跑完不到一秒。
 * <p>
 * ⚠️ <b>本类覆盖不到 delete 过滤式与写入元数据的键名耦合</b>：删除用的是硬编码的
 * {@code mdStorageId}，而写入时该元数据的键由
 * {@code KnowledgeBaseServiceImpl#mergeChunk} 构造（{@code metadatas.put("mdStorageId", id)}）。
 * 两处跨类，改了一处而没改另一处，删除会静默失效、旧向量永久累积——本类断言的是字面量，
 * 抓不到这种跨类不一致。改动元数据键名时请一并检查两处。
 **/
class KnowledgeBaseVectorizingTests {

    private static final Long FILE_ID = 42L;

    private static final String FILE_PATH = "D:/tmp/chunking-test.md";

    private final MarkdownReader markdownReader = mock(MarkdownReader.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final KnowledgeBaseFileMapper fileMapper = mock(KnowledgeBaseFileMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private KnowledgeBaseFileUploadedListener listener;

    @BeforeEach
    void setUp() {
        // 事务模板直接执行回调：事务边界本身（AFTER_COMMIT + 回滚）由 Spring 保证，
        // 这里只验证「回调里发生了什么」以及「失败时有没有要求回滚」
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0))
                        .doInTransaction(transactionStatus));

        listener = new KnowledgeBaseFileUploadedListener();
        ReflectionTestUtils.setField(listener, "markdownReader", markdownReader);
        ReflectionTestUtils.setField(listener, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(listener, "aiKnowledgeBaseFileStorageMapper", fileMapper);
        ReflectionTestUtils.setField(listener, "transactionTemplate", transactionTemplate);
    }

    // ---------- 覆盖式重建 ----------

    @Test
    void shouldDeleteThisFilesVectorsBeforeWriting() {
        stubChunking(chunks(3));

        listener.vectorizing(event());

        InOrder inOrder = inOrder(vectorStore);
        // 先按归属删干净，再整体写入。顺序反了等于把刚写好的向量删掉
        inOrder.verify(vectorStore).delete("mdStorageId == " + FILE_ID);
        inOrder.verify(vectorStore).add(anyList());
    }

    @Test
    void shouldDeleteEvenWhenTheFileHasNoChunks() {
        stubChunking(List.of());

        listener.vectorizing(event());

        // 上一轮向量化中途失败可能留下部分向量，这一轮即使解析不出内容也要清掉，
        // 否则那些残片会永远留在库里、并出现在检索结果中
        verify(vectorStore).delete("mdStorageId == " + FILE_ID);
    }

    // ---------- 整批写入 ----------

    @Test
    @SuppressWarnings("unchecked")
    void shouldWriteAllChunksInASingleCall() {
        List<Document> documents = chunks(5);
        stubChunking(documents);

        listener.vectorizing(event());

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());
        assertThat(captor.getValue())
                .as("一次调用必须带上全部分片，退回逐条循环会让 embedding 调用数随分片数线性增长")
                .containsExactlyElementsOf(documents);
    }

    @Test
    void shouldNotSearchForDuplicatesPerChunk() {
        stubChunking(chunks(5));

        listener.vectorizing(event());

        // 逐条相似检索是旧实现的去重手段，它一次只接受一个 query，
        // 因而每片都要付一次 embedding 调用。已由「按 mdStorageId 覆盖式重建」取代
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
        verify(vectorStore, never()).similaritySearch(anyString());
    }

    @Test
    void shouldNotCallAddWhenTheFileHasNoChunks() {
        stubChunking(List.of());

        listener.vectorizing(event());

        // 空集合不递给向量库：add(emptyList) 会走到 embed(空) + batchUpdate(空批)，
        // 这条路径的行为没有保证，不值得依赖
        verify(vectorStore, never()).add(anyList());
    }

    // ---------- 状态机 ----------

    @Test
    void shouldMarkFileCompletedWhenVectorizingSucceeds() {
        stubChunking(chunks(2));

        listener.vectorizing(event());

        // 首次是进入 VECTORIZING，末次是 COMPLETED
        assertThat(statusesWritten()).containsExactly(
                KnowledgeBaseFileStatusEnum.VECTORIZING.getCode(),
                KnowledgeBaseFileStatusEnum.COMPLETED.getCode());
    }

    @Test
    void shouldMarkFileFailedAndRollbackWhenWritingFails() {
        stubChunking(chunks(3));
        doThrow(new RuntimeException("向量模型不可用")).when(vectorStore).add(anyList());

        listener.vectorizing(event());

        assertThat(statusesWritten()).containsExactly(
                KnowledgeBaseFileStatusEnum.VECTORIZING.getCode(),
                KnowledgeBaseFileStatusEnum.FAILED.getCode());
        verify(transactionStatus).setRollbackOnly();
    }

    // ---------- 辅助方法 ----------

    /** 让 {@link MarkdownReader} 按给定分片返回，忽略传入的 resource 与 metadatas */
    private void stubChunking(List<Document> documents) {
        when(markdownReader.loadMarkdown(any(), any())).thenReturn(documents);
    }

    /** 造 {@code count} 个分片，内容各不相同（否则无法断言「传的是哪几条」） */
    private static List<Document> chunks(int count) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            documents.add(new Document("第" + i + "个分片的正文",
                    Map.of("mdStorageId", FILE_ID, "originalFileName", "测试文档.md")));
        }
        return documents;
    }

    private static KnowledgeBaseFileUploadedEvent event() {
        return KnowledgeBaseFileUploadedEvent.builder()
                .id(FILE_ID)
                .filePath(FILE_PATH)
                .metadatas(Map.of("mdStorageId", FILE_ID, "originalFileName", "测试文档.md"))
                .build();
    }

    /**
     * 按顺序取出写入过的所有状态码。
     * <p>
     * 断言完整序列而不是「最后一次是 COMPLETED」：中间那次 {@code VECTORIZING} 是前端
     * 轮询进度的依据，漏掉它文件会一直显示「待处理」直到向量化结束。
     */
    private List<Integer> statusesWritten() {
        ArgumentCaptor<KnowledgeBaseFileDO> captor = ArgumentCaptor.forClass(KnowledgeBaseFileDO.class);
        verify(fileMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        return captor.getAllValues().stream().map(KnowledgeBaseFileDO::getStatus).toList();
    }
}
