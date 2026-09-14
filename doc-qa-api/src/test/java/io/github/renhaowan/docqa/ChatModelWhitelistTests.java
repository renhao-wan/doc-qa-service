package io.github.renhaowan.docqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.docqa.domain.dos.ChatDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import io.github.renhaowan.docqa.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话模型白名单的集成测试。
 * <p>
 * 背景：模型名由前端每请求传入，而服务端只有一把 DASHSCOPE_API_KEY。没有白名单时，
 * 任何登录用户都能指定一个未列出的模型（实测 qwen-max 一次调通）来消耗同一个 key 的额度——
 * 名字猜错只是调不通，猜对就直接用掉了，所以风险是**枚举**而不是瞎猜。
 * <p>
 * ⚠️ 这两条用例都**不真的请求大模型**：列表接口是只读的，而校验发生在构建 Flux 之前
 * （HTTP 头还没发出，异常仍能变成 Response JSON）。断言响应体是 JSON 而非事件流，
 * 本身就证明了请求没有走到调用模型那一步。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatModelWhitelistTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChatMapper chatMapper;
    @Autowired
    private UserMapper userMapper;

    /**
     * 与 ChatController 读的是同一份配置。
     * 断言「接口如实透出配置」而不是写死模型名——写死的话，调一次白名单就得改一次测试。
     */
    @Value("${chat.allowed-models}")
    private List<String> allowedModels;

    @Test
    void testModelListReflectsConfig() throws Exception {
        mockMvc.perform(get("/chat/models")
                        .header("Authorization", "Bearer " + login("demo", "demo123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", equalTo(allowedModels)));
    }

    @Test
    void testUnlistedModelRejected() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");

        // 用**自己的**对话发请求，这样唯一能拦住它的就是模型名本身：
        // 若换成别人的对话，归属校验也会抛出，用例就分不清是哪一条拦下的
        mockMvc.perform(post("/chat/completion")
                        .header("Authorization", "Bearer " + login("demo", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"chatId\":\"" + uuid + "\",\"modelName\":\"qwen-max\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20011"));
    }

    /**
     * 插入一条归属于指定用户的对话
     *
     * @param userId  归属用户 ID
     * @param summary 对话摘要
     * @return 对话 UUID
     */
    private String insertChat(Long userId, String summary) {
        String uuid = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        chatMapper.insert(ChatDO.builder()
                .uuid(uuid)
                .userId(userId)
                .summary(summary)
                .createTime(now)
                .updateTime(now)
                .build());
        return uuid;
    }

    /**
     * 登录并取出 token
     *
     * @param username 用户名
     * @param password 密码
     * @return JWT 字符串
     */
    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
