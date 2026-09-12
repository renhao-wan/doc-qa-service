package io.github.renhaowan.docqa.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/11/2 22:31
 * @Version: v1.0.0
 * @Description: 企业知识库 Markdown 问答文件上传事件
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KnowledgeBaseFileUploadedEvent {

    /**
     * t_knowledge_base_file 表记录主键 ID
     */
    private Long id;

    /**
     * 存储路径
     */
    private String filePath;

    /**
     * 元数据
     */
    private Map<String, Object> metadatas;
}
