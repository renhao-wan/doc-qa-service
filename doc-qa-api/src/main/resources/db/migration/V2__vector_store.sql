-- ============================================================
-- V2 — 向量表 t_vector_store
--
-- ⚠️ 这份 DDL 是 Spring AI PgVectorStore 内部建表 SQL 的等价副本，必须与
--     application-dev.yml 里 spring.ai.vectorstore.pgvector 的配置严格一致：
--
--       dimensions:    1536            → embedding vector(1536)
--       index-type:    HNSW            → USING HNSW
--       distance-type: COSINE_DISTANCE → vector_cosine_ops
--       table-name:    t_vector_store  → CREATE TABLE t_vector_store
--
--     改了其中任何一项就要同步改这里（新增一个 V3 去 ALTER，不要改本文件），
--     对不上时写入或检索会报维度不匹配 / 找不到算子的错误。
--     结构来源：容器启动日志里 PgVectorStore 执行的建表语句，原样抄录。
--
-- 背景：这张表原先由 PgVectorStore 在启动时自动创建（initialize-schema: true）。
--     那个开关需要 CREATE EXTENSION 权限，且多实例并发启动时 CREATE EXTENSION
--     IF NOT EXISTS 并非原子操作、可能撞 pg_extension 唯一索引；表结构也不进版本库、
--     无法演进。现改由 Flyway 接管，该开关已在 application-dev.yml 中置为 false。
-- ============================================================

-- 原版 SQL 里还有一句 CREATE SCHEMA IF NOT EXISTS public，此处略去：
-- public schema 由 initdb 建好、必然存在，留着只会每次执行都产生一条
-- 「schema "public" already exists, skipping」警告。
CREATE TABLE IF NOT EXISTS public.t_vector_store
(
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS t_vector_store_index
    ON public.t_vector_store USING HNSW (embedding vector_cosine_ops);
