package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.tool.WebSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: Renhao-Wan
 * @Description: 联网搜索工具的定义解析测试：确认 @Tool 注解能被解析成模型可用的工具定义
 **/
class ToolCallbackSmokeTests {

    @Test
    void shouldResolveWebSearchTool() {
        // 只做定义解析，不执行工具，故依赖可传 null
        ToolCallback[] callbacks = ToolCallbacks.from(new WebSearchTool(null, null));

        assertThat(callbacks).hasSize(1);

        // 工具名是写在提示词里的调用契约，改名必须同步改 KnowledgeBaseAdvisor 的 WEB_FALLBACK_PROMPT_TEMPLATE
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("web_search");
        assertThat(callbacks[0].getToolDefinition().description()).isNotBlank();

        // query 是工具的唯一入参，缺了模型就没法传搜索词
        assertThat(callbacks[0].getToolDefinition().inputSchema()).contains("query");
    }
}
