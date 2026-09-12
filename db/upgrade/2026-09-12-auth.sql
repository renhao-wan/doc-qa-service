-- ============================================================
-- 2026-09-12 新增：多用户会话隔离 + JWT 鉴权
--
-- ⚠️ 这个目录下的脚本**不会自动执行**，是给「已经跑起来的库」用的一次性迁移。
--    db/init/01-schema.sql 只在数据卷为空时（首次启动）执行，改它对现有库无效。
--    对现有库执行本文件：
--      docker compose exec -T postgres psql -U postgres -d robot < db/upgrade/2026-09-12-auth.sql
--
--    全部语句都带 IF NOT EXISTS / WHERE 条件，重复执行是安全的。
--    等引入 Flyway（TODO 阶段四）后，本文件会以 V2__auth.sql 的形式被正式接管。
--
-- ⚠️ 比较两条供给路径（本脚本 vs 01-schema.sql）的结构时，请按「集合」而非「列序」：
--    ALTER TABLE ADD COLUMN 只能把新列追加到表尾，所以本脚本跑完后 t_chat 是
--    (…, update_time, user_id)、文件表是 (…, update_time, uploader_id)，
--    而 01-schema.sql 建出的表把 user_id / uploader_id 放在中间。
--    列类型、可空性、默认值、索引、COMMENT 全都一致，仅物理列序不同——这是 ADD COLUMN 的固有结果。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 用户表
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
-- ⚠️ 仅限**本地开发**：生产部署**不得**执行本脚本（或其预置账号段），
--    否则会带上两个密码可公开推知的登录凭证；生产账号应由正式的开通流程生成。
-- 哈希由 BCryptPasswordEncoder 兼容的算法生成（$2b$ 前缀，Spring Security 的
-- BCrypt 实现支持 $2a$ / $2b$ / $2y$ 三种前缀）。
INSERT INTO t_user (username, password_hash, nickname)
VALUES ('demo',  '$2b$10$hhFzXa78QJ7kFZAaVO2aK.J2HlOhQWJzrxp7/uI8dXW.4bCvtgK4a', '演示账号 A'),
       ('demo2', '$2b$10$mgKXZgtlRKoUSyVPf499PeLUpNhugMOAvvXxb3JLEuW4WzlfR23c6', '演示账号 B')
ON CONFLICT (username) DO NOTHING;

-- ------------------------------------------------------------
-- 2. t_chat 增加归属人
-- ------------------------------------------------------------
ALTER TABLE t_chat ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0;

-- 存量对话挂到 demo 名下：既不丢数据，也满足 NOT NULL 的约定
UPDATE t_chat
SET user_id = (SELECT id FROM t_user WHERE username = 'demo')
WHERE user_id = 0;

-- 去掉默认值：此后每个 INSERT 都必须显式写入 user_id，
-- 漏写会直接报错而不是静默落到 user_id=0 的「无主」状态
ALTER TABLE t_chat ALTER COLUMN user_id DROP DEFAULT;

-- ⚠️ 注释必须与 db/init/01-schema.sql 中同名语句逐字一致：
-- COMMENT 语句会写进 pg_description，注释分叉即 schema 分叉，
-- 「迁移既有库」与「全新部署」两条路径就收敛不到同一形态。
COMMENT ON COLUMN t_chat.user_id IS '归属用户 ID，逻辑关联 t_user.id；对话按用户隔离';

-- ------------------------------------------------------------
-- 3. 对话列表的索引必须跟着查询条件一起改
-- ------------------------------------------------------------
-- 查询从「全表按 update_time 倒序」变成「WHERE user_id = ? ORDER BY update_time DESC」，
-- 旧索引 (update_time DESC) 的前缀对不上，会被完全弃用、退化成全表扫描 + 排序。
-- 新索引把过滤列放前缀、排序列跟在后面，ORDER BY ... DESC LIMIT 可直接按索引取数。
-- 与 idx_t_chat_message_chat_uuid_id 是同一个道理。
DROP INDEX IF EXISTS idx_t_chat_update_time;
CREATE INDEX IF NOT EXISTS idx_t_chat_user_update ON t_chat (user_id, update_time DESC);

-- ------------------------------------------------------------
-- 4. 知识库文件表增加上传者
-- ------------------------------------------------------------
ALTER TABLE t_ai_customer_service_file_storage
    ADD COLUMN IF NOT EXISTS uploader_id BIGINT NOT NULL DEFAULT 0;

UPDATE t_ai_customer_service_file_storage
SET uploader_id = (SELECT id FROM t_user WHERE username = 'demo')
WHERE uploader_id = 0;

ALTER TABLE t_ai_customer_service_file_storage ALTER COLUMN uploader_id DROP DEFAULT;

-- 刻意不建 uploader_id 索引：文件列表是全局共享的（ORDER BY create_time DESC，无用户过滤），
-- 删改校验走主键查询后内存比对，两者都用不上这个索引。
COMMENT ON COLUMN t_ai_customer_service_file_storage.uploader_id IS '上传者用户 ID。文件全局共享可见，但只有上传者本人能删除与改备注';
