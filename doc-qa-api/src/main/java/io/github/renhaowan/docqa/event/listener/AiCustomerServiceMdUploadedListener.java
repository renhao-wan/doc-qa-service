package io.github.renhaowan.docqa.event.listener;

import io.github.renhaowan.docqa.domain.dos.AiCustomerServiceFileStorageDO;
import io.github.renhaowan.docqa.domain.mapper.AiCustomerServiceFileStorageMapper;
import io.github.renhaowan.docqa.enums.AiCustomerServiceFileStatusEnum;
import io.github.renhaowan.docqa.event.AiCustomerServiceMdUploadedEvent;
import io.github.renhaowan.docqa.reader.MarkdownReader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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
public class AiCustomerServiceMdUploadedListener {

    @Resource
    private MarkdownReader markdownReader;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private AiCustomerServiceFileStorageMapper aiCustomerServiceFileStorageMapper;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * Markdown 文件向量化
     * <p>
     * 用 {@link TransactionalEventListener} 而不是普通的 {@code @EventListener}：
     * 事件是在 {@code CustomerServiceImpl#mergeChunk} 的事务里发布的，而 {@code @EventListener}
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
    public void vectorizing(AiCustomerServiceMdUploadedEvent event) {
        log.info("## AiCustomerServiceMdUploadedEvent: {}", event);

        // 文件存储表主键 ID
        Long id =  event.getId();
        // Markdown 文件存储路径
        String filePath = event.getFilePath();
        // 元数据
        Map<String, Object> metadatas = event.getMetadatas();

        // 更新存储文件的处理状态为 “向量化中”
        aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder()
                .id(id)
                .status(AiCustomerServiceFileStatusEnum.VECTORIZING.getCode())
                .updateTime(LocalDateTime.now())
                .build());

        // 编程式事务
        boolean isSuccess = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                // 读取文件
                org.springframework.core.io.Resource resource = new FileSystemResource(filePath);

                // 解析为 Document 集合
                List<Document> documents = markdownReader.loadMarkdown(resource, metadatas);

                log.info("## documents: {}", documents);

                // 向量化，并存储入库
                for (Document document : documents) {
                    // 防止重复添加相同文档到 PGVector 中
                    // 从向量数据中，查询当前文档
                    List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(document.getText())
                            .topK(1) // 查询一条最高得分的
                            .build());

                    // 如果结果不为空，并且得分大于 0.99，则表示文档较高几率重复，直接跳过
                    if (!results.isEmpty() && results.get(0).getScore() > 0.99)
                        continue;

                    // 通过向量模型，将文档向量化存储到 PGVector 中
                    vectorStore.add(List.of(document));
                }

                // 更新存储文件的处理状态为 “已完成”
                aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder()
                        .id(id)
                        .status(AiCustomerServiceFileStatusEnum.COMPLETED.getCode())
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
            aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder()
                    .id(id)
                    .status(AiCustomerServiceFileStatusEnum.FAILED.getCode())
                    .updateTime(LocalDateTime.now())
                    .build());
        }
    }
}
