package io.github.renhaowan.docqa.tool;

import io.github.renhaowan.docqa.model.dto.SearchResultDTO;
import io.github.renhaowan.docqa.service.SearXNGService;
import io.github.renhaowan.docqa.service.SearchResultContentFetcherService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/9/13
 * @Version: v1.0.0
 * @Description: 联网搜索工具，供知识库页的“联网兜底”通过 Function Calling 自主调用
 **/
@Slf4j
@Component
public class WebSearchTool {

    private final SearXNGService searXNGService;
    private final SearchResultContentFetcherService searchResultContentFetcherService;

    public WebSearchTool(SearXNGService searXNGService,
                         SearchResultContentFetcherService searchResultContentFetcherService) {
        this.searXNGService = searXNGService;
        this.searchResultContentFetcherService = searchResultContentFetcherService;
    }

    /**
     * 联网检索，返回带来源链接的页面正文
     *
     * @param query 搜索关键词
     * @return 格式化后的检索上下文；检索失败时返回说明文本而非抛异常
     */
    // name 必须显式指定：提示词里是按 web_search 这个名字让模型去调工具的，
    // 不写就跟方法名 webSearch 绑定，两边对不上只能靠模型自己猜（实测能猜对，但不该依赖）
    @Tool(name = "web_search", description = """
            联网搜索互联网上的实时信息。仅当企业内部知识库的上下文不足以回答用户问题时才调用；
            如果知识库上下文已经能够回答用户问题，不要调用本工具。
            参数 query 为搜索关键词。""")
    public String webSearch(@ToolParam(description = "搜索关键词") String query) {
        log.info("## Function Calling 触发联网搜索，query: {}", query);

        try {
            // 调用 SearXNG 获取搜索结果
            List<SearchResultDTO> searchResults = searXNGService.search(query);

            // 并发请求，获取搜索结果页面的内容
            CompletableFuture<List<SearchResultDTO>> resultsFuture =
                    searchResultContentFetcherService.batchFetch(searchResults, 7, TimeUnit.SECONDS);

            // 过滤掉获取失败的结果
            List<SearchResultDTO> successfulResults = resultsFuture.join().stream()
                    .filter(r -> StringUtils.isNotBlank(r.getContent()))
                    .toList();

            log.info("## 联网搜索完成，命中 {} 条有效结果", successfulResults.size());

            return buildContext(successfulResults);
        } catch (Exception e) {
            // 工具内部必须消化异常：抛出去会让整条 SSE 流转为 error，前端只看到“请求出错”，
            // 而这本该降级为「仅凭知识库回答」。返回说明文本，由模型自行决定下一步。
            log.error("## 联网搜索失败，降级为仅依据知识库回答", e);

            return "联网搜索失败，暂时无法获取互联网信息，请仅依据知识库上下文回答用户问题。";
        }
    }

    /**
     * 构建上下文
     *
     * @param successfulResults 成功获取到正文的搜索结果
     * @return 带来源编号与链接的上下文文本
     */
    private String buildContext(List<SearchResultDTO> successfulResults) {
        if (successfulResults.isEmpty()) {
            return "联网搜索没有返回有效结果，请仅依据知识库上下文回答用户问题。";
        }

        int i = 1;
        StringBuilder contextTemp = new StringBuilder();
        for (SearchResultDTO searchResult : successfulResults) {
            contextTemp.append(String.format("""
                        ### 来源 %s | 相关性: %s
                        - 页面链接: %s
                        - 页面文本:
                        %s
                        \n
                        """, i, searchResult.getScore(), searchResult.getUrl(), searchResult.getContent()));
            i++;
        }

        return contextTemp.toString();
    }
}
