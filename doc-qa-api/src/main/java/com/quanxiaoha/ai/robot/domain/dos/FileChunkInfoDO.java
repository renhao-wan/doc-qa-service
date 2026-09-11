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
 * @Description: 分片信息表
 **/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_file_chunk_info")
public class FileChunkInfoDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileMd5;
    private Integer chunkNumber;
    private String chunkPath;
    private Long chunkSize;
    private LocalDateTime createTime;
}
