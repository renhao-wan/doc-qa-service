package io.github.renhaowan.docqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.docqa.domain.dos.KnowledgeBaseFileDO;
import io.github.renhaowan.docqa.domain.mapper.KnowledgeBaseFileMapper;
import io.github.renhaowan.docqa.domain.mapper.UserMapper;
import io.github.renhaowan.docqa.enums.KnowledgeBaseFileStatusEnum;
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
 * 知识库文件删改权限的集成测试。
 * <p>
 * ⚠️ 只测「越权被拒」的路径。用合法 token 删自己的文件会真的走到
 * vectorStore.delete() 与本地文件删除——前者不在本测试的事务里（走的是自己的连接），
 * 回滚不掉，会给开发库留垃圾。删除成功路径属于手工验收（Task 9）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MarkdownFilePermissionTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private KnowledgeBaseFileMapper fileStorageMapper;
    @Autowired
    private UserMapper userMapper;

    @Test
    void testDeleteOthersFileRejected() throws Exception {
        Long id = insertCompletedFile("demo");

        mockMvc.perform(post("/knowledge-base/md/delete")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isOk())
                // 与对话侧刻意不同：这里返回「无权操作」而不是「不存在」——
                // 文件在列表里人人可见，谎称不存在只会让用户困惑
                .andExpect(jsonPath("$.errorCode").value("20010"));
    }

    @Test
    void testUpdateOthersFileRejected() throws Exception {
        Long id = insertCompletedFile("demo");

        mockMvc.perform(post("/knowledge-base/md/update")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id + ",\"remark\":\"被改掉的备注\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20010"));
    }

    @Test
    void testListIsSharedAcrossUsers() throws Exception {
        insertCompletedFile("demo");

        // 知识库是共享可见的：demo2 能看到 demo 上传的文件
        mockMvc.perform(post("/knowledge-base/md/list")
                        .header("Authorization", "Bearer " + login("demo2", "demo123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"size\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    /**
     * 插入一条「已完成」状态的文件记录
     * <p>
     * 状态取 COMPLETED 而不是 UPLOADING：UPLOADING 的记录会在权限校验之前
     * 被判为可删除，且 stored_file_name 为空串。用 COMPLETED 才是真实场景，
     * 也让「权限校验确实拦住了」这一结论更干净。
     *
     * @param uploaderUsername 上传者用户名
     * @return 记录主键 ID
     */
    private Long insertCompletedFile(String uploaderUsername) {
        Long uploaderId = userMapper.selectByUsername(uploaderUsername).getId();
        LocalDateTime now = LocalDateTime.now();

        KnowledgeBaseFileDO record = KnowledgeBaseFileDO.builder()
                .fileMd5(UUID.randomUUID().toString().replace("-", ""))
                .fileName("测试文档.md")
                .storedFileName(System.currentTimeMillis() + "_测试文档.md")
                .fileSize(1024L)
                .totalChunks(1)
                .uploadedChunks(1)
                .status(KnowledgeBaseFileStatusEnum.COMPLETED.getCode())
                .uploaderId(uploaderId)
                .createTime(now)
                .updateTime(now)
                .build();

        fileStorageMapper.insert(record);
        return record.getId();
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
