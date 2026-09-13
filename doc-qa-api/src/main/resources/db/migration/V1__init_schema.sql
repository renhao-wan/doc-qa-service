-- ============================================================
-- V1 — 业务表初始化
--
-- 由 Flyway 在应用启动时执行（配置见 application-dev.yml 的 spring.flyway）。
-- 本文件**生产与开发共用**，因此不得出现任何预置数据——
-- 演示账号在 classpath:db/dev-migration/ 下，那个 location 只在开发环境加载。
--
-- ⚠️ 已执行过的迁移脚本不要修改：Flyway 按 checksum 校验，改动会让应用在已有库上
--     直接启动失败（Validate failed: Migration checksum mismatch）。要改结构就新增
--     V4、V5……，不要回头改 V1。
--
-- 约定：
--   * 时间列统一用 TIMESTAMP + NOT NULL DEFAULT now()，与应用侧的 java.time.LocalDateTime 对应。
--     ⚠️ 不要改成 TIMESTAMPTZ：pgjdbc 不支持把 timestamptz 读成 LocalDateTime，
--     查询会直接抛「Cannot convert the column of type TIMESTAMPTZ to requested type
--     java.time.LocalDateTime」，且失败点分散在所有 SELECT 上，很难第一时间定位到是列类型的问题。
--     真要用 TIMESTAMPTZ，就得把 DO / VO 的时间字段一并改成 OffsetDateTime，改动面很大。
--   * NOT NULL 必不可少——PostgreSQL 的 ORDER BY ... DESC 默认 NULLS FIRST，
--     时间列为 NULL 的行会被顶到「最新」的位置。
--   * 枚举取值用 CHECK 约束在数据库层兜底，不只依赖应用层。
--   * 这里刻意不写 IF NOT EXISTS：Flyway 保证每个脚本只执行一次，加上它反而会让
--     「库里已有同名表但结构与本脚本不一致」这种情况静默通过，并记录成迁移成功。
--     失败要趁早。
-- ============================================================

-- ------------------------------------------------------------
-- 扩展
--
-- 三个扩展都只有 t_vector_store 用到（V2），集中放在这里声明：
-- 每个扩展都要超级用户权限，且多实例并发启动时 CREATE EXTENSION IF NOT EXISTS
-- 并非原子操作，让它们先于任何建表语句一次建好，是更稳妥的分工。
-- ------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;
-- 以下两个是 Spring AI PgVectorStore 建表时要求的（metadata json 列与 uuid 主键默认值）。
-- 本项目的表结构里没有直接用到它们，但 V2 的 DDL 与 starter 保持一致，故一并声明。
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ------------------------------------------------------------
-- 用户表
--
-- 放在所有业务表之前：t_chat.user_id 与 t_knowledge_base_file.uploader_id
-- 逻辑上指向 t_user.id（本项目与其他表一致，不建外键，关联由应用层维护）。
-- ------------------------------------------------------------
CREATE TABLE t_user
(
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64),
    create_time   TIMESTAMP    NOT NULL DEFAULT now(),
    update_time   TIMESTAMP    NOT NULL DEFAULT now()
);

-- 用户名唯一。必须 NOT NULL + 唯一索引：PostgreSQL 的唯一索引不约束 NULL，
-- 允许 NULL 的话可以插入任意多行 username 为 NULL 的记录，登录时的按名查询也无从谈起。
CREATE UNIQUE INDEX uk_t_user_username ON t_user (username);

COMMENT ON TABLE t_user IS '用户';
COMMENT ON COLUMN t_user.password_hash IS 'BCrypt 哈希（固定 60 字符），明文不落库';

-- ------------------------------------------------------------
-- 对话表
-- ------------------------------------------------------------
CREATE TABLE t_chat
(
    id          BIGSERIAL PRIMARY KEY,
    uuid        VARCHAR(64) NOT NULL,
    summary     VARCHAR(255),
    user_id     BIGINT       NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    update_time TIMESTAMP NOT NULL DEFAULT now()
);

-- uuid 全局唯一，前端路由 /chat/:chatId 直接用它定位对话
CREATE UNIQUE INDEX uk_t_chat_uuid ON t_chat (uuid);

-- 对话列表的查询是「WHERE user_id = ? ORDER BY update_time DESC」，
-- 过滤列作前缀、排序列跟在后面，分页时可直接按索引取数、免排序。
-- （同理见 t_chat_message 的 idx_t_chat_message_chat_uuid_id）
CREATE INDEX idx_t_chat_user_update ON t_chat (user_id, update_time DESC);

COMMENT ON TABLE t_chat IS '对话';
COMMENT ON COLUMN t_chat.uuid IS '对话唯一标识，前端路由用';
COMMENT ON COLUMN t_chat.summary IS '对话摘要，取首条消息前 20 字';
COMMENT ON COLUMN t_chat.update_time IS '最后活跃时间：新建对话、以及每轮消息落库时更新；对话列表按它倒序';
COMMENT ON COLUMN t_chat.user_id IS '归属用户 ID，逻辑关联 t_user.id；对话按用户隔离';

-- ------------------------------------------------------------
-- 对话消息表
-- ------------------------------------------------------------
CREATE TABLE t_chat_message
(
    id                BIGSERIAL PRIMARY KEY,
    chat_uuid         VARCHAR(64) NOT NULL,
    content           TEXT,
    reasoning_content TEXT,
    role              VARCHAR(32) NOT NULL,
    create_time       TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_t_chat_message_role CHECK (role IN ('user', 'assistant'))
);

-- 取「某对话最近 N 条消息」是最主要的访问路径（CustomChatMemoryAdvisor 取 50 条、
-- 历史消息分页同理）。索引带上 id 倒序，使 ORDER BY id DESC LIMIT N
-- 能直接按索引顺序取数、省掉排序；只建 chat_uuid 单列的话，每次都要把该对话的
-- 全部消息取出来再 top-N 排序，开销随单对话消息总量增长（实测 300 条时相差约 17 倍）。
--
-- 排序键用 id 而非 create_time：id 是单调递增且唯一的物理主键，
-- 不受时间精度、重复值、NULL 的影响，分页时也不会因排序值相等而出现重复或遗漏。
-- 该索引的前缀就是 chat_uuid，可完全替代原先的单列索引。
CREATE INDEX idx_t_chat_message_chat_uuid_id ON t_chat_message (chat_uuid, id DESC);

COMMENT ON TABLE t_chat_message IS '对话消息';
COMMENT ON COLUMN t_chat_message.chat_uuid IS '所属对话的 uuid，逻辑关联 t_chat.uuid（应用层维护，无外键）';
COMMENT ON COLUMN t_chat_message.reasoning_content IS '推理内容（deepseek-r1 等推理模型的思考过程）';
COMMENT ON COLUMN t_chat_message.role IS '消息角色：user / assistant';

-- ------------------------------------------------------------
-- 知识库文件表
-- ------------------------------------------------------------
CREATE TABLE t_knowledge_base_file
(
    id               BIGSERIAL    PRIMARY KEY,
    file_md5         VARCHAR(64)  NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    stored_file_name VARCHAR(512) NOT NULL DEFAULT '',
    file_size        BIGINT       NOT NULL DEFAULT 0,
    total_chunks     INTEGER      NOT NULL,
    uploaded_chunks  INTEGER      NOT NULL DEFAULT 0,
    status           INTEGER      NOT NULL,
    uploader_id      BIGINT       NOT NULL,
    remark           VARCHAR(512),
    create_time      TIMESTAMP    NOT NULL DEFAULT now(),
    update_time      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_kb_file_status CHECK (status BETWEEN 0 AND 4),
    CONSTRAINT ck_kb_file_chunks CHECK (total_chunks > 0 AND uploaded_chunks >= 0)
);

-- 秒传与断点续传都依赖按 MD5 定位文件记录。
-- file_md5 是事实上的业务主键，必须 NOT NULL：PostgreSQL 的唯一索引不约束 NULL，
-- 允许 NULL 的话可以插入任意多行 file_md5 为 NULL 的记录，
-- 且 INSERT ... ON CONFLICT (file_md5) 的幂等语义也会失效。
CREATE UNIQUE INDEX uk_kb_file_md5 ON t_knowledge_base_file (file_md5);

COMMENT ON TABLE t_knowledge_base_file IS '知识库文件存储记录';
COMMENT ON COLUMN t_knowledge_base_file.file_md5 IS '文件 MD5，用于秒传与断点续传';
COMMENT ON COLUMN t_knowledge_base_file.stored_file_name IS '合并后实际落盘的文件名（{时间戳}_{原始文件名}）。只存文件名不存绝对路径：目录由 knowledge-base.file-storage-path 推导，换机器或挪目录后记录依然有效。上传中（status=0）为空串';
COMMENT ON COLUMN t_knowledge_base_file.status IS '处理状态：0 上传中 / 1 待向量化 / 2 向量化中 / 3 已完成 / 4 失败';
COMMENT ON COLUMN t_knowledge_base_file.uploaded_chunks IS '已上传分片数。刻意冗余（可由 t_knowledge_base_chunk 统计得出），用一次原子自增换掉高频 count(*)';
COMMENT ON COLUMN t_knowledge_base_file.uploader_id IS '上传者用户 ID。文件全局共享可见，但只有上传者本人能删除与改备注';

-- ------------------------------------------------------------
-- 分片信息表
-- ------------------------------------------------------------
CREATE TABLE t_knowledge_base_chunk
(
    id           BIGSERIAL   PRIMARY KEY,
    file_md5     VARCHAR(64) NOT NULL,
    chunk_number INTEGER     NOT NULL,
    chunk_name   VARCHAR(64) NOT NULL,
    chunk_size   BIGINT      NOT NULL DEFAULT 0,
    create_time  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_kb_chunk_number CHECK (chunk_number >= 0)
);

-- 唯一约束是分片重复上传的最终防线：
-- 前端并发上传，应用层的「先查再插」存在竞态，靠这个索引兜底。
-- 代码侧配合 INSERT ... ON CONFLICT (file_md5, chunk_number) DO NOTHING 实现幂等
-- （见 KnowledgeBaseChunkMapper.insertChunkIgnoreDuplicate）。
-- ⚠️ 不能改成「插入后捕获 DuplicateKeyException」：PostgreSQL 中一旦违反约束，
-- 整个事务立即进入 aborted 状态，而 uploadChunk 是 @Transactional 的，方法内 catch 救不回来。
CREATE UNIQUE INDEX uk_kb_chunk_md5_number ON t_knowledge_base_chunk (file_md5, chunk_number);

COMMENT ON TABLE t_knowledge_base_chunk IS '上传分片信息';
COMMENT ON COLUMN t_knowledge_base_chunk.chunk_number IS '分片序号，从 0 开始';
COMMENT ON COLUMN t_knowledge_base_chunk.chunk_name IS '分片文件名（如 0.chunk）。刻意只存文件名不存绝对路径：所在目录可由 chunk-path 配置 + file_md5 推导，换机器或挪目录后记录依然有效';
