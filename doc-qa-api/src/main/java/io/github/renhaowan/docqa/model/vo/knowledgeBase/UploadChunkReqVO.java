package io.github.renhaowan.docqa.model.vo.knowledgeBase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author: Renhao-Wan
 * @url: https://github.com/Renhao-Wan
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
    @NotBlank(message = "文件 MD5 不能为空")
    private String fileMd5;

    /**
     * 原始文件名称
     */
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    /**
     * 原始文件大小
     */
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    /**
     * 当前分片序号
     */
    @NotNull(message = "分片序号不能为空")
    private Integer chunkNumber;

    /**
     * 总分片数
     */
    @NotNull(message = "总分片数不能为空")
    private Integer totalChunks;

    /**
     * 分片文件
     */
    @NotNull(message = "分片文件不能为空")
    private MultipartFile chunk;
}
