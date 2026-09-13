package io.github.renhaowan.docqa.controller;

import com.google.common.collect.Lists;
import io.github.renhaowan.docqa.advisor.KnowledgeBaseAdvisor;
import io.github.renhaowan.docqa.aspect.ApiOperationLog;
import io.github.renhaowan.docqa.model.vo.chat.AIResponse;
import io.github.renhaowan.docqa.model.vo.knowledgeBase.*;
import io.github.renhaowan.docqa.service.KnowledgeBaseService;
import io.github.renhaowan.docqa.tool.WebSearchTool;
import io.github.renhaowan.docqa.utils.PageResponse;
import io.github.renhaowan.docqa.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/5/22 12:25
 * @Version: v1.0.0
 * @Description: 企业知识库
 **/
@RestController
@RequestMapping("/knowledge-base")
@Slf4j
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBase;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private WebSearchTool webSearchTool;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;
    @Value("${knowledge-base.model}")
    private String model;
    @Value("${knowledge-base.temperature}")
    private Double temperature;

    @PostMapping("/file/check")
    @ApiOperationLog(description = "检查文件是否存在")
    public Response<CheckFileRspVO> checkFile(@RequestBody @Validated CheckFileReqVO checkFileReqVO) {
        return knowledgeBase.checkFile(checkFileReqVO);
    }

    @PostMapping("/file/upload-chunk")
    @ApiOperationLog(description = "文件分片上传")
    public Response<?> uploadChunk(@ModelAttribute UploadChunkReqVO uploadChunkReqVO) {
        return knowledgeBase.uploadChunk(uploadChunkReqVO);
    }

    @PostMapping("/file/merge-chunk")
    @ApiOperationLog(description = "文件分片合并")
    public Response<?> mergeChunk(@RequestBody @Validated MergeChunkReqVO mergeChunkReqVO) {
        return knowledgeBase.mergeChunk(mergeChunkReqVO);
    }

    @PostMapping("/md/delete")
    @ApiOperationLog(description = "删除 Markdown 问答文件")
    public Response<?> deleteMarkdownFile(@RequestBody @Validated DeleteMarkdownFileReqVO deleteMarkdownFileReqVO) {
        return knowledgeBase.deleteMarkdownFile(deleteMarkdownFileReqVO);
    }

    @PostMapping("/md/list")
    @ApiOperationLog(description = "Markdown 问答文件分页查询")
    public PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(@RequestBody @Validated FindMarkdownFilePageListReqVO findMarkdownFilePageListReqVO) {
        return knowledgeBase.findMarkdownFilePageList(findMarkdownFilePageListReqVO);
    }

    @PostMapping("/md/update")
    @ApiOperationLog(description = "删除 Markdown 问答文件")
    public Response<?> updateMarkdownFile(@RequestBody @Validated UpdateMarkdownFileReqVO updateMarkdownFileReqVO) {
        return knowledgeBase.updateMarkdownFile(updateMarkdownFileReqVO);
    }

    /**
     * 流式对话
     * @return
     */
    @PostMapping(value = "/completion", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiOperationLog(description = "企业知识库对话")
    public Flux<AIResponse> chat(@RequestBody @Validated KnowledgeBaseChatReqVO chatReqVO) {
        String userMessage = chatReqVO.getMessage();

        // 构建 ChatModel
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build())
                .build();

        // 动态设置调用的模型名称、温度值
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = ChatClient.create(chatModel)
                .prompt()
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .user(userMessage); // 用户提示词

        // 是否开启联网兜底：开启后才把联网搜索工具挂给模型，由模型自主决定是否调用
        boolean webFallback = Boolean.TRUE.equals(chatReqVO.getNetworkFallback());

        if (webFallback) {
            // .tools() 与上方 .options(OpenAiChatOptions) 不冲突：前者只往 chatClientRequestSpec
            // 自己的 toolCallbacks 列表里追加，由 DefaultChatClientUtils 在构建请求时合并进 options；
            // 而 OpenAiChatOptions 直接实现 ToolCallingChatOptions、不继承 DefaultChatOptions，
            // 因此 model 与 temperature 不会被复制丢失
            chatClientRequestSpec.tools(webSearchTool);
        }

        // Advisor 集合
        List<Advisor> advisors = Lists.newArrayList();
        // 检索向量库，组合增强提示词；提示词必须与工具是否挂载保持一致，
        // 否则模型会在没有工具可用时凭空编造联网结果
        advisors.add(new KnowledgeBaseAdvisor(vectorStore, webFallback));

        // 应用 Advisor 集合
        chatClientRequestSpec.advisors(advisors);

        // 流式输出
        return chatClientRequestSpec
                .stream()
                .content()
                .mapNotNull(text -> AIResponse.builder().v(text).build()); // 构建返参 AIResponse
    }

}
