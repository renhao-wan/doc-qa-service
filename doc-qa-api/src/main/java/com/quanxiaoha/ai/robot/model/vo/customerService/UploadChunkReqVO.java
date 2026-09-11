package com.quanxiaoha.ai.robot.model.vo.customerService;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author: 犬小哈
 * @url: www.quanxiaoha.com
 * @date: 2023-09-15 14:07
 * @description: 文件分片上传
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadChunkReqVO {
    /**
     * 文件 MD5
     */
    private String fileMd5;

    /**
     * 原始文件名称
     */
    private String fileName;

    /**
     * 原始文件大小
     */
    private Long fileSize;

    /**
     * 当前分片序号
     */
    private Integer chunkNumber;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 分片文件
     */
    private MultipartFile chunk;
}
