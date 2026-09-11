package com.quanxiaoha.ai.robot.model.vo.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/**
 * @author: 犬小哈
 * @url: www.quanxiaoha.com
 * @date: 2023-09-15 14:07
 * @description: 对话分页
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindChatHistoryPageListRspVO {
    /**
     * 对话 ID
     */
    private Long id;
    /**
     * 对话 UUID
     */
    private String uuid;
    /**
     * 对话摘要
     */
    private String summary;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
