package com.quanxiaoha.ai.robot.model.vo.customerService;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


/**
 * @author: 犬小哈
 * @url: www.quanxiaoha.com
 * @date: 2023-09-15 14:07
 * @description: 文件检查
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CheckFileRspVO {

    /**
     * 文件是否存在
     */
    private Boolean exists;

    /**
     * 是否需要上传
     */
    private Boolean needUpload;

    /**
     * 已上传成功的分片序号
     */
    private List<Integer> uploadedChunks;

}
