-- ============================================================
-- doc-qa-service 数据库初始化脚本
-- 由 docker-compose 挂载到 /docker-entrypoint-initdb.d/，首次启动自动执行
-- 手动执行：psql -U postgres -d robot -f 01-schema.sql
-- ============================================================

-- pgvector 扩展
-- 这里显式声明，让容器初始化阶段就用超级用户把扩展建好——
-- 应用侧的 initialize-schema 虽然也会执行 CREATE EXTENSION IF NOT EXISTS vector，
-- 但那需要连接账号具备建扩展权限，且多实例并发启动时并非原子操作。
-- 扩展由基础设施建好，应用只负责用，是更稳妥的分工。
CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 对话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat
(
    id          BIGSERIAL PRIMARY KEY,
    uuid        VARCHAR(64) NOT NULL,
    summary     VARCHAR(255),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);

-- uuid 全局唯一，前端路由 /chat/:chatId 直接用它定位对话
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_chat_uuid ON t_chat (uuid);

COMMENT ON TABLE t_chat IS '对话';
COMMENT ON COLUMN t_chat.uuid IS '对话唯一标识，前端路由用';
COMMENT ON COLUMN t_chat.summary IS '对话摘要，取首条消息前 20 字';

-- ------------------------------------------------------------
-- 对话消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat_message
(
    id                BIGSERIAL PRIMARY KEY,
    chat_uuid         VARCHAR(64) NOT NULL,
    content           TEXT,
    reasoning_content TEXT,
    role              VARCHAR(32),
    create_time       TIMESTAMP
);

-- 按对话查历史消息是最主要的访问路径；CustomChatMemoryAdvisor 也靠它取最近 N 条
CREATE INDEX IF NOT EXISTS idx_t_chat_message_chat_uuid ON t_chat_message (chat_uuid);

COMMENT ON TABLE t_chat_message IS '对话消息';
COMMENT ON COLUMN t_chat_message.chat_uuid IS '所属对话的 uuid，逻辑关联 t_chat.uuid（应用层维护，无外键）';
COMMENT ON COLUMN t_chat_message.reasoning_content IS '推理内容（deepseek-r1 等推理模型的思考过程）';
COMMENT ON COLUMN t_chat_message.role IS '消息角色：user / assistant';

-- ------------------------------------------------------------
-- 知识库文件表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_customer_service_file_storage
(
    id              BIGSERIAL PRIMARY KEY,
    file_md5        VARCHAR(64),
    file_name       VARCHAR(255),
    file_path       VARCHAR(512),
    file_size       BIGINT,
    total_chunks    INTEGER,
    uploaded_chunks INTEGER,
    status          INTEGER,
    remark          VARCHAR(512),
    create_time     TIMESTAMP,
    update_time     TIMESTAMP
);

-- 秒传与断点续传都依赖按 MD5 定位文件记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_storage_md5 ON t_ai_customer_service_file_storage (file_md5);

COMMENT ON TABLE t_ai_customer_service_file_storage IS '知识库文件存储记录';
COMMENT ON COLUMN t_ai_customer_service_file_storage.file_md5 IS '文件 MD5，用于秒传与断点续传';
COMMENT ON COLUMN t_ai_customer_service_file_storage.status IS '处理状态：0 上传中 / 1 待向量化 / 2 向量化中 / 3 已完成 / 4 失败';

-- ------------------------------------------------------------
-- 分片信息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_file_chunk_info
(
    id           BIGSERIAL PRIMARY KEY,
    file_md5     VARCHAR(64) NOT NULL,
    chunk_number INTEGER     NOT NULL,
    chunk_path   VARCHAR(512),
    chunk_size   BIGINT,
    create_time  TIMESTAMP
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
