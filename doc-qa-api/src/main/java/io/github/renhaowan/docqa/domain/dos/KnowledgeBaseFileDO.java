package io.github.renhaowan.docqa.domain.dos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/8/11 11:32
 * @Version: v1.0.0
 * @Description: 企业知识库问答文件存储
 **/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_knowledge_base_file")
public class KnowledgeBaseFileDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileMd5;
    /** 上传时的原始文件名，仅用于展示 */
    private String fileName;
    /**
     * 合并后实际落盘的文件名（{@code {时间戳}_{原始文件名}}）。
     * <p>
     * 刻意只存文件名、不存绝对路径：所在目录由 {@code knowledge-base.file-storage-path}
     * 推导，这样换机器或挪目录后历史记录依然有效（t_knowledge_base_chunk.chunk_name 同理）。
     */
    private String storedFileName;
    private Long fileSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Integer status;
    /**
     * 上传者用户 ID。
     * <p>
     * 文件全局共享可见（列表查询不带用户过滤），但只有上传者本人能删除与改备注。
     * 秒传场景下保持先传者：B 上传 A 已有的文件不会新建记录，这个字段仍是 A。
     */
    private Long uploaderId;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
