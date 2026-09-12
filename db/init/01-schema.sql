-- ============================================================
-- doc-qa-service 数据库初始化脚本
-- 由 docker-compose 挂载到 /docker-entrypoint-initdb.d/，首次启动自动执行
-- 手动执行：psql -U postgres -d robot -f 01-schema.sql
--
-- ⚠️ 该目录**仅在数据目录为空时**执行：首次部署之后，本文件的任何改动都不会被应用。
--    schema 演进需手工 ALTER，或交给 Flyway 等迁移工具（见 TODO 阶段四）。
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
-- ============================================================

-- pgvector 扩展
-- 这里显式声明，让容器初始化阶段就用超级用户把扩展建好——
-- 应用侧的 initialize-schema 虽然也会执行 CREATE EXTENSION IF NOT EXISTS vector，
-- 但那需要连接账号具备建扩展权限，且多实例并发启动时并非原子操作。
-- 扩展由基础设施建好，应用只负责用，是更稳妥的分工。
CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 用户表
--
-- 放在所有业务表之前：t_chat.user_id 与 t_ai_customer_service_file_storage.uploader_id
-- 逻辑上指向 t_user.id（本项目与其他表一致，不建外键，关联由应用层维护）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_user_username ON t_user (username);

COMMENT ON TABLE t_user IS '用户';
COMMENT ON COLUMN t_user.password_hash IS 'BCrypt 哈希（固定 60 字符），明文不落库';

-- 预置演示账号，密码均为 demo123。明文只在本行注释里保留——开发库限定。
-- ⚠️ 仅限**本地开发**：生产部署**不得**执行本脚本的预置账号段（应从本文件删掉或
--    改由正式的开通流程生成账号），否则会带上两个密码可公开推知的登录凭证。
-- 哈希由 BCryptPasswordEncoder 兼容的算法生成（$2b$ 前缀，Spring Security 的
-- BCrypt 实现支持 $2a$ / $2b$ / $2y$ 三种前缀）。
INSERT INTO t_user (username, password_hash, nickname)
VALUES ('demo',  '$2b$10$hhFzXa78QJ7kFZAaVO2aK.J2HlOhQWJzrxp7/uI8dXW.4bCvtgK4a', '演示账号 A'),
       ('demo2', '$2b$10$mgKXZgtlRKoUSyVPf499PeLUpNhugMOAvvXxb3JLEuW4WzlfR23c6', '演示账号 B')
ON CONFLICT (username) DO NOTHING;

-- ------------------------------------------------------------
-- 对话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat
(
    id          BIGSERIAL PRIMARY KEY,
    uuid        VARCHAR(64) NOT NULL,
    summary     VARCHAR(255),
    user_id     BIGINT       NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    update_time TIMESTAMP NOT NULL DEFAULT now()
);

-- uuid 全局唯一，前端路由 /chat/:chatId 直接用它定位对话
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_chat_uuid ON t_chat (uuid);

-- 对话列表的查询是「WHERE user_id = ? ORDER BY update_time DESC」，
-- 过滤列作前缀、排序列跟在后面，分页时可直接按索引取数、免排序。
-- （同理见 t_chat_message 的 idx_t_chat_message_chat_uuid_id）
CREATE INDEX IF NOT EXISTS idx_t_chat_user_update ON t_chat (user_id, update_time DESC);

COMMENT ON TABLE t_chat IS '对话';
COMMENT ON COLUMN t_chat.uuid IS '对话唯一标识，前端路由用';
COMMENT ON COLUMN t_chat.summary IS '对话摘要，取首条消息前 20 字';
COMMENT ON COLUMN t_chat.update_time IS '最后活跃时间：新建对话、以及每轮消息落库时更新；对话列表按它倒序';
COMMENT ON COLUMN t_chat.user_id IS '归属用户 ID，逻辑关联 t_user.id；对话按用户隔离';

-- ------------------------------------------------------------
-- 对话消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat_message
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
CREATE INDEX IF NOT EXISTS idx_t_chat_message_chat_uuid_id ON t_chat_message (chat_uuid, id DESC);

COMMENT ON TABLE t_chat_message IS '对话消息';
COMMENT ON COLUMN t_chat_message.chat_uuid IS '所属对话的 uuid，逻辑关联 t_chat.uuid（应用层维护，无外键）';
COMMENT ON COLUMN t_chat_message.reasoning_content IS '推理内容（deepseek-r1 等推理模型的思考过程）';
COMMENT ON COLUMN t_chat_message.role IS '消息角色：user / assistant';

-- ------------------------------------------------------------
-- 知识库文件表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_customer_service_file_storage
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
    CONSTRAINT ck_file_storage_status CHECK (status BETWEEN 0 AND 4),
    CONSTRAINT ck_file_storage_chunks CHECK (total_chunks > 0 AND uploaded_chunks >= 0)
);

-- 秒传与断点续传都依赖按 MD5 定位文件记录。
-- file_md5 是事实上的业务主键，必须 NOT NULL：PostgreSQL 的唯一索引不约束 NULL，
-- 允许 NULL 的话可以插入任意多行 file_md5 为 NULL 的记录，
-- 且 INSERT ... ON CONFLICT (file_md5) 的幂等语义也会失效。
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_storage_md5 ON t_ai_customer_service_file_storage (file_md5);

COMMENT ON TABLE t_ai_customer_service_file_storage IS '知识库文件存储记录';
COMMENT ON COLUMN t_ai_customer_service_file_storage.file_md5 IS '文件 MD5，用于秒传与断点续传';
COMMENT ON COLUMN t_ai_customer_service_file_storage.stored_file_name IS '合并后实际落盘的文件名（{时间戳}_{原始文件名}）。只存文件名不存绝对路径：目录由 customer-service.file-storage-path 推导，换机器或挪目录后记录依然有效。上传中（status=0）为空串';
COMMENT ON COLUMN t_ai_customer_service_file_storage.status IS '处理状态：0 上传中 / 1 待向量化 / 2 向量化中 / 3 已完成 / 4 失败';
COMMENT ON COLUMN t_ai_customer_service_file_storage.uploaded_chunks IS '已上传分片数。刻意冗余（可由 t_file_chunk_info 统计得出），用一次原子自增换掉高频 count(*)';
COMMENT ON COLUMN t_ai_customer_service_file_storage.uploader_id IS '上传者用户 ID。文件全局共享可见，但只有上传者本人能删除与改备注';

-- ------------------------------------------------------------
-- 分片信息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_file_chunk_info
(
    id           BIGSERIAL   PRIMARY KEY,
    file_md5     VARCHAR(64) NOT NULL,
    chunk_number INTEGER     NOT NULL,
    chunk_name   VARCHAR(64) NOT NULL,
    chunk_size   BIGINT      NOT NULL DEFAULT 0,
    create_time  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_file_chunk_number CHECK (chunk_number >= 0)
);

-- 唯一约束是分片重复上传的最终防线：
-- 前端并发上传，应用层的「先查再插」存在竞态，靠这个索引兜底。
-- 代码侧配合 INSERT ... ON CONFLICT (file_md5, chunk_number) DO NOTHING 实现幂等
-- （见 FileChunkInfoMapper.insertChunkIgnoreDuplicate）。
-- ⚠️ 不能改成「插入后捕获 DuplicateKeyException」：PostgreSQL 中一旦违反约束，
-- 整个事务立即进入 aborted 状态，而 uploadChunk 是 @Transactional 的，方法内 catch 救不回来。
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_chunk_md5_number ON t_file_chunk_info (file_md5, chunk_number);

COMMENT ON TABLE t_file_chunk_info IS '上传分片信息';
COMMENT ON COLUMN t_file_chunk_info.chunk_number IS '分片序号，从 0 开始';
COMMENT ON COLUMN t_file_chunk_info.chunk_name IS '分片文件名（如 0.chunk）。刻意只存文件名不存绝对路径：所在目录可由 chunk-path 配置 + file_md5 推导，换机器或挪目录后记录依然有效';

-- ------------------------------------------------------------
-- 向量表 t_vector_store 刻意不在此创建
--
-- ⚠️ Spring AI 的 PgVectorStore **默认不初始化 schema**（initialize-schema 默认 false），
-- 所以这张表不会「自动」出现。本项目在 application-dev.yml 里显式开启了
-- initialize-schema: true，由应用启动时按那里的 dimensions / index-type / distance-type
-- 建表（1536 维 / HNSW / COSINE_DISTANCE）。
--
-- 为什么不写在这里：字段结构必须与 starter 内部 SQL 严格一致，交给它自己建最不容易出错。
-- 也不要手工建，否则结构对不上时插入报错、排查成本高。
--
-- 生产环境应关闭该开关，改由 Flyway 等迁移工具接管（见 TODO 阶段四）。
-- ------------------------------------------------------------
