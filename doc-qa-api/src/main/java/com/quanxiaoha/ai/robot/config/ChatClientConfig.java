package com.quanxiaoha.ai.robot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: 犬小哈
 * @Date: 2025/5/24 17:30
 * @Version: v1.0.0
 * @Description: ChatClient 配置类
 **/
@Configuration
public class ChatClientConfig {

    /**
     * 初始化 ChatClient 客户端
     * @param chatModel
     * @return
     */
    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }
}
