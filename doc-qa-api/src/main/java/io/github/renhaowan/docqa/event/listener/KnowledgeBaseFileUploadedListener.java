package io.github.renhaowan.docqa.event.listener;

import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseFileDO;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseFileMapper;
import io.github.renhaowan.docqa.enums.KnowledgeBaseFileStatusEnum;
import io.github.renhaowan.docqa.event.KnowledgeBaseFileUploadedEvent;
import io.github.renhaowan.docqa.reader.MarkdownReader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/11/2 22:36
 * @Version: v1.0.0
 * @Description: Markdown 文件上传事件监听
 **/
@Component
@Slf4j
public class KnowledgeBaseFileUploadedListener {

    @Resource
    private MarkdownReader markdownReader;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private KnowledgeBaseFileMapper aiKnowledgeBaseFileStorageMapper;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * Markdown 文件向量化
     * <p>
     * 用 {@link TransactionalEventListener} 而不是普通的 {@code @EventListener}：
     * 事件是在 {@code KnowledgeBaseServiceImpl#mergeChunk} 的事务里发布的，而 {@code @EventListener}
     * 不感知事务边界，{@code @Async} 也只是把它丢到别的线程，事件可能在事务提交前就被处理，
     * 此时监听器读库会读到旧数据（乃至读不到刚写入的记录）。
     * 指定 {@code AFTER_COMMIT} 后，监听器只在事务成功提交后才被触发。
     * <p>
     * {@code fallbackExecution = true}：若事件在事务外发布，则退化为立即执行。
     * 这样既不失去「提交后才处理」的语义（无事务即无未提交数据），又避免调用方一旦漏加事务，
     * 向量化就被静默跳过、文件永远卡在 PENDING 状态。
     *
     * @param event 文件合并完成事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("eventTaskExecutor") // 指定使用我们自定义的线程池
    public void vectorizing(KnowledgeBaseFileUploadedEvent event) {
        log.info("## KnowledgeBaseFileUploadedEvent: {}", event);

        // 文件存储表主键 ID
        Long id =  event.getId();
        // Markdown 文件存储路径
        String filePath = event.getFilePath();
        // 元数据
        Map<String, Object> metadatas = event.getMetadatas();

        // 更新存储文件的处理状态为 “向量化中”
        aiKnowledgeBaseFileStorageMapper.updateById(KnowledgeBaseFileDO.builder()
                .id(id)
                .status(KnowledgeBaseFileStatusEnum.VECTORIZING.getCode())
                .updateTime(LocalDateTime.now())
                .build());

        // 编程式事务
        boolean isSuccess = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                // 读取文件
                org.springframework.core.io.Resource resource = new FileSystemResource(filePath);

                // 解析为 Document 集合
                List<Document> documents = markdownReader.loadMarkdown(resource, metadatas);

                log.info("## 文件 {} 解析出 {} 个分片", id, documents.size());

                // 先按归属清掉这个文件已有的向量，再整体写入——即「覆盖式重建」。
                // ⚠️ 顺序不能反：反了会把刚写入的向量删掉，文件向量化完反而检索不到。
                //
                // 为什么按 mdStorageId 删而不是按内容去重：旧实现是逐条 similaritySearch(topK=1)
                // 比对得分 > 0.99 就跳过，那是**跨文件**去重——同一段文本只归属于第一个上传它的
                // 文件。而删除走的是 `mdStorageId == id`，于是删掉 A 文件会连带删掉 B 文件里
                // 那段「被判定为重复」的内容，B 从此检索不到自己的段落。归属错乱是正确性问题，
                // 不只是性能问题。改成按归属重建后，一个文件的向量就是它当前内容的向量。
                // ⚠️ 过滤键 mdStorageId 由 KnowledgeBaseServiceImpl#mergeChunk 写入 Documents 的
                // 元数据，两处必须一致；只改一处会让这句删除静默失效、旧向量永久累积。
                vectorStore.delete(String.format("mdStorageId == %s", id));

                // 整体写入。PgVectorStore.doAdd 内部是
                // EmbeddingModel.embed(List, ..., BatchingStrategy) + JdbcTemplate.batchUpdate，
                // 一次调用即可完成批量向量化与批量插入；退回逐条 add 会让 embedding 调用
                // 随分片数线性增长——那是走网络的调用，是这条链路上最贵的一环。
                //
                // 空集合不递进去：add(emptyList) 会走到 embed(空) + batchUpdate(空批)，
                // 这条路径的行为没有保证，不值得依赖
                if (!documents.isEmpty()) {
                    vectorStore.add(documents);
                }

                // 更新存储文件的处理状态为 “已完成”
                aiKnowledgeBaseFileStorageMapper.updateById(KnowledgeBaseFileDO.builder()
                        .id(id)
                        .status(KnowledgeBaseFileStatusEnum.COMPLETED.getCode())
                        .updateTime(LocalDateTime.now())
                        .build());

                return true;
            } catch (Exception ex) {
                log.error("## Markdown 文件向量化失败: {}", event, ex);
                status.setRollbackOnly(); // 标记事务为回滚
                return false;
            }
        }));

        // 若事务执行失败，更新存储文件的处理状态为 “失败”
        if (!isSuccess) {
            aiKnowledgeBaseFileStorageMapper.updateById(KnowledgeBaseFileDO.builder()
                    .id(id)
                    .status(KnowledgeBaseFileStatusEnum.FAILED.getCode())
                    .updateTime(LocalDateTime.now())
                    .build());
        }
    }
}
