package io.github.renhaowan.docqa.model.vo.knowledgeBase;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @author: Renhao-Wan
 * @url: https://github.com/Renhao-Wan
 * @date: 2023-09-15 14:07
 * @description: 企业知识库聊天
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KnowledgeBaseChatReqVO {

    @NotBlank(message = "用户消息不能为空")
    private String message;

    /**
     * 对话 ID
     */
    private String chatId;

    /**
     * 联网兜底开关：开启后才把联网搜索工具挂给模型，由模型自主决定是否调用。
     * 缺省即关闭，不加 @NotNull
     */
    private Boolean networkFallback;

}
