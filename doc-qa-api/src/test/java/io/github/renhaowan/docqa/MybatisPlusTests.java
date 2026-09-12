package io.github.renhaowan.docqa;

import io.github.renhaowan.docqa.domain.dos.ChatDO;
import io.github.renhaowan.docqa.domain.mapper.ChatMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MyBatis-Plus 写入的冒烟测试。
 * <p>
 * 类上加 {@code @Transactional} 让每个测试方法结束后回滚：INSERT 照样真实执行
 * （一样能验证 Mapper 与表结构对得上），但不会把数据留在库里。
 * 否则每跑一次测试就往 t_chat 插一条「新对话」，开发库很快被测试数据淹没。
 */
@SpringBootTest
@Transactional
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
