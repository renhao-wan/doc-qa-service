package io.github.renhaowan.docqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.docqa.domain.dos.ChatDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import io.github.renhaowan.docqa.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话归属隔离的集成测试。
 * <p>
 * 全部用例都验证「越权被拦」，不验证成功路径——成功路径会真的调模型 API，
 * 既慢又花配额，属于手工验收的范畴（见计划 Task 9）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatIsolationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChatMapper chatMapper;
    @Autowired
    private UserMapper userMapper;

    @Test
    void testChatListIsolatedByUser() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        Long demo2Id = userMapper.selectByUsername("demo2").getId();

        insertChat(demoId, "demo 的对话");
        String demo2ChatUuid = insertChat(demo2Id, "demo2 的对话");

        // demo2 只能看到自己的那一 条，看不到 demo 的
        mockMvc.perform(post("/chat/list")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // PageResponse 的数组直接挂在 data 上，没有 records 这一层
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].uuid").value(demo2ChatUuid));
    }

    @Test
    void testDeleteOthersChatRejected() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");

        mockMvc.perform(post("/chat/delete")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uuid\":\"" + uuid + "\"}"))
                .andExpect(status().isOk())
                // 返回「不存在」而不是「无权操作」：后者等于向攻击者确认该 uuid 真实存在
                .andExpect(jsonPath("$.errorCode").value("20000"));
    }

    @Test
    void testRenameOthersChatRejected() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");
        Long chatId = chatMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ChatDO>lambdaQuery().eq(ChatDO::getUuid, uuid)).getId();

        mockMvc.perform(post("/chat/summary/rename")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + chatId + ",\"summary\":\"被篡改的标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20000"));
    }

    @Test
    void testReadOthersMessagesRejected() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");

        mockMvc.perform(post("/chat/message/list")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":10,\"chatId\":\"" + uuid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20000"));
    }

    @Test
    void testSseCompletionOnOthersChatRejected() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");

        // SSE 接口最严重的一处越权：原实现只按 chatUuid 落库，
        // 不校验归属的话 A 能往 B 的对话里写消息，直接污染别人的上下文。
        // 归属校验在构建 Flux 之前同步抛出，所以这里能正常拿到 JSON 错误。
        mockMvc.perform(post("/chat/completion")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"chatId\":\"" + uuid + "\",\"modelName\":\"deepseek-v3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20000"));
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
