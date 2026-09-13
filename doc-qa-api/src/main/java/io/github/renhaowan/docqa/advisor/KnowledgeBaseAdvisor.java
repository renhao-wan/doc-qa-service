package io.github.renhaowan.docqa.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/8/5 13:40
 * @Version: v1.0.0
 * @Description: 知识库 Advisor
 **/
@Slf4j
public class KnowledgeBaseAdvisor implements StreamAdvisor {

    private final VectorStore vectorStore;

    /**
     * 是否开启联网兜底：true 时提示词允许模型自主调用联网搜索工具
     */
    private final boolean webFallback;

    /**
     * 向量检索返回的文档条数。
     * <p>
     * 做成可配而不是写死，是为了让「topK 取多少合适」能被实测，而不是拍一个数字。
     * 取值依据与对照数据见 docs/rag-evaluation/report.md。
     */
    private final int topK;

    /**
     * 配置缺失时的兜底条数，与 application-dev.yml 的 knowledge-base.top-k 保持一致
     */
    private static final int DEFAULT_TOP_K = 3;

    /**
     * 知识库提示词模板（未开启联网兜底：严格基于检索到的上下文作答）
     */
    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
            你是企业内部知识库助手，名为 “Doc QA”。请根据以下上下文信息回答用户问题。
            
            ## 上下文信息
            {context}

            请根据上下文内容来回复用户：
            
            ## 用户问题
            {question}
            
            ## 回答要求
            
            **核心规则**：
            1. **严格基于上下文**：只能使用提供的上下文信息回答问题
            2. **服务风格**：热情、耐心、专业，可以使用适当的 Emoji 表情 😊
            3. **禁止用语**：避免使用"根据上下文"、"所提供的信息"等生硬表述
            
            **回答范围判断**：
            - ✅ 如果用户问题与上下文信息直接相关，请提供详细、准确的回答
            - ✅ 如果用户问题与上下文信息间接相关，可以基于已有信息进行合理推断
            - ❌ 如果用户问题完全超出上下文范围，或者上下文信息不足以回答该问题
        
            **无法回答时的统一回复**：
            当遇到以下情况时，请统一回复：
            "抱歉，当前知识库中没有找到相关信息，我无法回答这个问题。建议补充相关文档，或换个问法再试试。"

            **图片展示**：
            如需要展示图片，请使用 Markdown 格式：![](图片链接)
        
            现在请根据以上要求回答问题。
            """);

    /**
     * 知识库提示词模板（开启联网兜底：上下文不足时模型必须先联网检索，再决定如何作答）
     */
    private static final PromptTemplate WEB_FALLBACK_PROMPT_TEMPLATE = new PromptTemplate("""
            你是企业内部知识库助手，名为 “Doc QA”。

            ## 用户问题
            {question}

            ## 上下文信息（来自企业内部知识库的检索结果）
            {context}

            ## 回答要求

            **第一步：判断上下文是否足以回答用户问题**
            - ✅ 如果用户问题与上下文信息直接相关，或可以基于已有信息合理推断，请直接作答。
              **此时不要调用任何工具。**
            - 🔍 如果上下文信息**不足以回答**该问题，**必须先调用 web_search 工具联网检索**，
              再依据检索结果作答；检索结果中的来源链接要保留在回答里。
            - ❌ 只有在**已经调用过 web_search、且检索结果仍然无法回答**时，才使用下面的统一回复。

            **核心规则**：
            1. **不得跳过检索**：上下文不足时，不允许直接回复"知识库中没有找到相关信息"——必须先联网检索
            2. **不要无谓联网**：上下文已经能回答的问题，直接回答，不要调用工具
            3. **服务风格**：热情、耐心、专业，可以使用适当的 Emoji 表情 😊
            4. **禁止用语**：避免使用"根据上下文"、"所提供的信息"等生硬表述
            5. **禁止输出过程说明**：无论是否调用工具，都只输出最终答案。
               不要写"上下文信息中没有提到……"、"我将为您联网检索"、"让我搜索一下"这类过渡语

            **统一回复（仅在联网检索后仍无法回答时使用）**：
            "抱歉，当前知识库中没有找到相关信息，我无法回答这个问题。建议补充相关文档，或换个问法再试试。"

            **图片展示**：
            如需要展示图片，请使用 Markdown 格式：![](图片链接)

            现在请根据以上要求回答问题。
            """);

    public KnowledgeBaseAdvisor(VectorStore vectorStore, boolean webFallback) {
        this(vectorStore, webFallback, DEFAULT_TOP_K);
    }

    public KnowledgeBaseAdvisor(VectorStore vectorStore, boolean webFallback, int topK) {
        this.vectorStore = vectorStore;
        this.webFallback = webFallback;
        this.topK = topK;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 获取用户输入的提示词
        Prompt prompt = chatClientRequest.prompt();
        UserMessage userMessage = prompt.getUserMessage();

        // 兜底提示词里写了「必须先调用 web_search」，若工具回调没跟着 options 传进来，
        // 模型会因为没有工具可调而直接编造联网结果——那种失败在前端看不出破绽，所以这里留一行日志备查
        if (webFallback && prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
            log.info("## 联网兜底已挂载，工具回调数={}", toolCallingChatOptions.getToolCallbacks().size());
        }

        // 查询向量库
        // 检索与查询相似的文档
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(userMessage.getText()) // 查询的关键词
                .topK(topK) // 查询条数由 knowledge-base.top-k 配置
                .build());

        // 构建向量查询结果上下文信息
        String context = buildContext(documents);

        // 填充提示词占位符，转换为 Prompt 提示词对象
        // 注意：options 必须透传（它里面带着 Controller 通过 .tools() 挂上的工具回调），
        // 换成别的 options 会让模型失去联网搜索能力
        PromptTemplate promptTemplate = webFallback ? WEB_FALLBACK_PROMPT_TEMPLATE : DEFAULT_PROMPT_TEMPLATE;
        Prompt newPrompt = promptTemplate.create(Map.of("question", userMessage.getText(),
                "context", context), chatClientRequest.prompt().getOptions());

        log.info("## 重新构建的增强提示词: {}", newPrompt.getUserMessage().getText());

        // 重新构建 ChatClientRequest，设置重新构建的 “增强提示词”
        ChatClientRequest newChatClientRequest = ChatClientRequest.builder()
                .prompt(newPrompt)
                .build();

        return streamAdvisorChain.nextStream(newChatClientRequest);
    }

    /**
     * 构建上下文
     * @param documents
     * @return
     */
    private String buildContext(List<Document> documents) {
        StringBuilder contextTemp = new StringBuilder();

        for (Document document : documents) {
            contextTemp.append(String.format("""
                        %s
                        ---\n
                        """, document.getText()));
        }

        return contextTemp.toString();
    }

    @Override
    public String getName() {
        // 获取类名称
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 1; // order 值越小，越先执行
    }
}
