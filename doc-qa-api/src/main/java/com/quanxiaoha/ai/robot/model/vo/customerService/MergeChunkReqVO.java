package com.quanxiaoha.ai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @author: 犬小哈
 * @url: www.quanxiaoha.com
 * @date: 2023-09-15 14:07
 * @description: 文件分片合并
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MergeChunkReqVO {

    @NotBlank(message = "文件 MD5 不能为空")
    private String fileMd5;

}
