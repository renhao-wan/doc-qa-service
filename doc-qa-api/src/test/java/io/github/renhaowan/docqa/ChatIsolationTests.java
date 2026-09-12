package io.github.renhaowan.docqa;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话归属隔离的集成测试。
 * <p>
 * 每个用例都同时覆盖两个方向：既验证「越权被拦」（失败路径），也验证
 * 「本人操作正常生效」（成功路径）。只断失败路径会漏掉一大类回归——
 * 例如把归属校验的判定写反、或让「影响行数为 0」永远成立，
 * 副作用是正常功能整体失灵，而越权用例反而依旧是绿的。
 * <p>
 * ⚠️ 成功路径只覆盖 rename / delete / message-list 这三条**不调模型**的接口。
 * SSE 互通接口的成功路径会真的请求大模型，既慢又花配额，属于手工验收的范畴（见计划 Task 9），
 * 所以它只测「越权被拦」。
 * <p>
 * ⚠️ 断言一律基于「本用例自己造的数据」，不要依赖「某个演示账号名下恰好有几条对话」
 * 这类环境前提——那会把测试数据是否干净变成用例能否通过的条件，反过来限制人工造数据。
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

        String demoChatUuid = insertChat(demoId, "demo 的对话");
        String demo2ChatUuid = insertChat(demo2Id, "demo2 的对话");

        // demo2 的列表里应当有自己的那条、且看不到 demo 的。
        // 断言的是「内容」而不是条数：demo2 名下本来有几条对话取决于运行环境，
        // 写死条数等于给所有后续用例加了一条隐含约定（「不要用 demo2 建对话」）。
        // size 取大一点，保证本用例造的数据一定落在这一页里。
        mockMvc.perform(post("/chat/list")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // PageResponse 的数组直接挂在 data 上，没有 records 这一层
                .andExpect(jsonPath("$.data[*].uuid", hasItem(demo2ChatUuid)))
                .andExpect(jsonPath("$.data[*].uuid", not(hasItem(demoChatUuid))));
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

        // 越权请求被拒还不够，得确认它确实没删掉东西——
        // 否则「先删后判归属」这种顺序错误同样能让 errorCode 断言通过
        assertEquals(1L, countByUuid(uuid).longValue());
    }

    @Test
    void testRenameOthersChatRejected() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");
        Long chatId = selectIdByUuid(uuid);

        mockMvc.perform(post("/chat/summary/rename")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + chatId + ",\"summary\":\"被篡改的标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20000"));

        assertEquals("demo 的对话", chatMapper.selectById(chatId).getSummary());
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
        // （成功路径会真调模型，见类注释：不在此覆盖。）
        mockMvc.perform(post("/chat/completion")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"chatId\":\"" + uuid + "\",\"modelName\":\"deepseek-v3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20000"));
    }

    // ==================== 成功路径 ====================
    // 这三条不碰模型 API：被测的 Service 方法都不依赖大模型，
    // 所以可以放进自动化测试，既省配额，又能挡住「功能整体失灵但越权用例仍绿」的变异。

    @Test
    void testRenameOwnChatSucceeds() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "重命名前的标题");
        Long chatId = selectIdByUuid(uuid);

        mockMvc.perform(post("/chat/summary/rename")
                        .header("Authorization", "Bearer " + login("demo", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + chatId + ",\"summary\":\"重命名后的标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 必须回查库：只断 success:true 挡不住「count == 0 恒成立」这类变异——
        // 那种写法同样会返回成功，但摘要根本没写进去
        assertEquals("重命名后的标题", chatMapper.selectById(chatId).getSummary());
    }

    @Test
    void testDeleteOwnChatSucceeds() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "待删除的对话");

        mockMvc.perform(post("/chat/delete")
                        .header("Authorization", "Bearer " + login("demo", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uuid\":\"" + uuid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 同上：返回值说删了不算数，得回查库确认真的没了
        assertEquals(0L, countByUuid(uuid).longValue());
    }

    @Test
    void testReadOwnMessagesSucceeds() throws Exception {
        Long demoId = userMapper.selectByUsername("demo").getId();
        String uuid = insertChat(demoId, "demo 的对话");

        // 本用例造的是空对话，所以只断 success——
        // data 为空时 Service 返回的是 null，断 length() 没有意义。
        // 这条用例挡的是「归属校验把所有请求都判成越权」：那样这里会拿到 20000 而不是 true。
        mockMvc.perform(post("/chat/message/list")
                        .header("Authorization", "Bearer " + login("demo", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":10,\"chatId\":\"" + uuid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
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
     * 按 UUID 取对话主键 ID
     *
     * @param uuid 对话 UUID
     * @return 对话 ID
     */
    private Long selectIdByUuid(String uuid) {
        return chatMapper.selectOne(Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUuid, uuid)).getId();
    }

    /**
     * 按 UUID 统计对话条数（删除用例用它确认记录真的没了）
     *
     * @param uuid 对话 UUID
     * @return 条数
     */
    private Long countByUuid(String uuid) {
        return chatMapper.selectCount(Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUuid, uuid));
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
