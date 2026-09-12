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
    /**
     * 分片文件名（如 0.chunk）
     * <p>
     * 刻意只存文件名而非绝对路径：所在目录可由 chunk-path 配置 + fileMd5 推导，
     * 换机器或挪目录后历史记录依然有效。
     */
    private String chunkName;
    private Long chunkSize;
    private LocalDateTime createTime;
}
