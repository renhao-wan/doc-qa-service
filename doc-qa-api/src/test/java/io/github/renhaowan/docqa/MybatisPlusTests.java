package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.domain.dos.ChatDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

@SpringBootTest
class MybatisPlusTests {

    @Resource
    private ChatMapper chatMapper;

    /**
     * 测试添加数据
     */
    @Test
    void testInsert() {
        chatMapper.insert(ChatDO.builder()
                        .uuid(UUID.randomUUID().toString())
                        .summary("新对话")
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build());
    }

}
