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
 * @Description: AI 客服问答文件存储
 **/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ai_customer_service_file_storage")
public class AiCustomerServiceFileStorageDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileMd5;
    /** 上传时的原始文件名，仅用于展示 */
    private String fileName;
    /**
     * 合并后实际落盘的文件名（{@code {时间戳}_{原始文件名}}）。
     * <p>
     * 刻意只存文件名、不存绝对路径：所在目录由 {@code customer-service.file-storage-path}
     * 推导，这样换机器或挪目录后历史记录依然有效（t_file_chunk_info.chunk_name 同理）。
     */
    private String storedFileName;
    private Long fileSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
