package com.quanxiaoha.ai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @author: 犬小哈
 * @url: www.quanxiaoha.com
 * @date: 2023-09-15 14:07
 * @description: 删除 Markdown 问答文件
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DeleteMarkdownFileReqVO {

    @NotNull(message = "问答文件 ID 不能为空")
    private Long id;

}
