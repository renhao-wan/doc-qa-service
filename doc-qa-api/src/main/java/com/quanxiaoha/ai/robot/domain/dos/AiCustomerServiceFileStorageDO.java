package com.quanxiaoha.ai.robot.domain.dos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @Author: 犬小哈
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
    private String fileName;
    private String filePath;
    private Long fileSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
