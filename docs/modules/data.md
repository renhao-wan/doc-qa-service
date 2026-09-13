# 数据层

PostgreSQL 16 + pgvector。本文讲三件事：**表怎么设计的**、**索引为什么这么建**、**schema 怎么演进**。

---

## 1. 表

schema 的唯一来源是 Flyway 迁移脚本，应用启动时自动对账。

| 表 | 建在 | 职责 |
|---|---|---|
| `t_user` | V1 | 用户。密码只存 BCrypt 哈希（固定 60 字符），明文不落库 |
| `t_chat` | V1 | 对话。`user_id` 决定归属，按用户隔离 |
| `t_chat_message` | V1 | 对话消息，`role` 只有 `user` / `assistant` |
| `t_knowledge_base_file` | V1 | 知识库文件记录，含处理状态与上传者 |
| `t_knowledge_base_chunk` | V1 | 上传分片记录 |
| `t_vector_store` | V2 | 向量表，由 Spring AI `PgVectorStore` 读写 |
| `flyway_schema_history` | Flyway | 迁移记录，Flyway 自建 |

外键**一律不建**，关联由应用层维护。这是有意的一致选择——便于分片、便于将来换存储，代价是数据库层不做引用完整性检查。

---

## 2. 索引设计

### 2.1 排序键用 `id` 而不是 `create_time`

```sql
CREATE INDEX idx_t_chat_message_chat_uuid_id ON t_chat_message (chat_uuid, id DESC);
```

取「某对话最近 N 条消息」是最主要的访问路径（记忆 Advisor 取 50 条、历史消息分页同理）。索引带上 `id` 倒序，使 `ORDER BY id DESC LIMIT N` **能直接按索引顺序取数、省掉排序**。

**为什么排序键是 `id` 而不是 `create_time`**：`id` 是 `BIGSERIAL`，单调递增且唯一，不受时间精度、重复值、NULL 的影响。`create_time` 可能重复（同一毫秒落两条），**排序值相等时 `LIMIT` 取哪几条是不确定的**，分页时会因此出现重复或遗漏。

该索引的前缀就是 `chat_uuid`，**可完全替代单列索引**——只建 `(chat_uuid)` 的话，每次都要把该对话的全部消息取出来再 top-N 排序，开销随单对话消息总量增长（实测 300 条时相差约 17 倍）。

### 2.2 过滤列作前缀，排序列跟在后面

```sql
CREATE INDEX idx_t_chat_user_update ON t_chat (user_id, update_time DESC);
```

对话列表的查询是 `WHERE user_id = ? ORDER BY update_time DESC`。**过滤列放前缀、排序列放后面**，分页时可直接按索引取数、免排序。

⚠️ **索引必须跟着查询条件改**。这条索引原先叫 `idx_t_chat_update_time`、只有 `update_time DESC` 一列——加了用户隔离之后，查询条件多了 `user_id`，旧索引的**前缀对不上**，列表分页会退化成全表扫描 + 排序。

这是「功能正常、只是慢」的那类问题，**最难在测试里发现**。最终评审用 `EXPLAIN` 独立验证：走 `Index Scan using idx_t_chat_user_update` 且**无 Sort 节点**。

### 2.3 唯一索引

| 索引 | 表 | 作用 |
|---|---|---|
| `uk_t_user_username` | `t_user` | 用户名唯一 |
| `uk_t_chat_uuid` | `t_chat` | 对话 uuid 全局唯一，前端路由 `/chat/:chatId` 直接用它定位 |
| `uk_kb_file_md5` | `t_knowledge_base_file` | 文件 MD5 唯一，秒传与断点续传都依赖它 |
| `uk_kb_chunk_md5_number` | `t_knowledge_base_chunk` | `(file_md5, chunk_number)` 唯一，**分片重复上传的最终防线** |

⚠️ **PostgreSQL 的唯一索引不约束 NULL**。允许 `NULL` 的话，可以插入任意多行该列为 `NULL` 的记录——所以业务键列**必须显式 `NOT NULL`**，否则 `INSERT ... ON CONFLICT` 的幂等语义会失效。

---

## 3. 时间列一律 `TIMESTAMP`，不是 `TIMESTAMPTZ`

⚠️ **不要改成 `TIMESTAMPTZ`。**

后果链：DO / VO 的时间字段全是 `java.time.LocalDateTime`，而 **pgjdbc 不支持把 `timestamptz` 转成 `LocalDateTime`**，查询会直接抛：

```
Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime
```

这个坑的麻烦之处在于：

- **报错点是所有 SELECT**（例如 `selectByMd5`），看起来像 Mapper 或驱动的问题，很难第一时间定位到是列类型。
- **INSERT / UPDATE 不读回值所以不会报**——很容易误判成「已经改好了」。

真要用 `TIMESTAMPTZ`，就得把 DO / VO 的时间字段一并换成 `OffsetDateTime`，改动面很大。

### 3.1 时间列必须 `NOT NULL DEFAULT now()`

PostgreSQL 的 `ORDER BY ... DESC` **默认 NULLS FIRST**——时间列为 `NULL` 的行会被顶到「最新」的位置，在对话列表里表现为「一条没有时间的对话排在最前面」。

---

## 4. CHECK 约束兜底

枚举与取值范围在库层用 CHECK 约束兜底，不只依赖应用层校验：

| 约束 | 表 | 内容 |
|---|---|---|
| `ck_t_chat_message_role` | `t_chat_message` | `role IN ('user', 'assistant')` |
| `ck_kb_file_status` | `t_knowledge_base_file` | `status BETWEEN 0 AND 4` |
| `ck_kb_file_chunks` | `t_knowledge_base_file` | `total_chunks > 0 AND uploaded_chunks >= 0` |
| `ck_kb_chunk_number` | `t_knowledge_base_chunk` | `chunk_number >= 0` |

---

## 5. Flyway

### 5.1 三个脚本

| 位置 | 版本 | 内容 | 加载环境 |
|---|---|---|---|
| `db/migration/` | `V1` | 三个扩展（vector / hstore / uuid-ossp）+ 五张业务表 | 全部 |
| `db/migration/` | `V2` | 向量表 `t_vector_store` | 全部 |
| `db/dev-migration/` | `V3` | 预置演示账号 | **仅 dev** |

```yaml
# application-dev.yml
locations: classpath:db/migration,classpath:db/dev-migration
# application-prod.yml
locations: classpath:db/migration
```

### 5.2 为什么演示账号必须待在单独目录

⚠️ **Flyway 的迁移脚本是跨环境同一份的。** 把演示账号的 `INSERT` 放进 `db/migration/`，就等于给生产库插两个密码可公开推知的登录凭证。所以它必须待在**只有开发环境会扫到**的目录里。

这也是本模块最重要的一条安全边界。

### 5.3 版本号陷阱（两条，都很隐蔽）

⚠️ **新增生产迁移必须从 `V4` 开始**。`V3` 已被 `db/dev-migration` 的演示账号占用。写成 `V3` 的话，dev 环境（两个目录都加载）会报 **duplicate version** 而启动失败。

⚠️ **生产库绝不能被 dev profile 碰过**。任何被 dev profile 执行过的库，其 `flyway_schema_history` 里都有 V3 记录；切到 prod profile 启动时，Flyway 校验会报：

```
Detected applied migration not resolved locally: 3
```

并**拒绝启动**。真踩了要 `flyway repair`，**不要手工删记录**。

### 5.4 已执行的脚本不能改

Flyway 按 **checksum** 校验。改动已执行过的脚本，会让应用在已有库上直接启动失败：

```
Validate failed: Migration checksum mismatch
```

**要改结构就新增 `V4`、`V5`……，不要回头改 `V1`。**

### 5.5 `baseline-on-migrate: true` 不能去掉

已有的开发库是非空的（都建于本次管改造之前），不开这项会在启动时报：

```
Found non-empty schema(s) "public" but no schema history table
```

并拒绝启动。

开启后，Flyway 在非空库上建立 baseline（版本 1）并**跳过 V1**，从 V2 往后执行；全新空库不受影响，V1 照常执行。

### 5.6 `initialize-schema` 必须保持 `false`

置 `true` 会让 `PgVectorStore` 也在启动时建 `t_vector_store`，与 V2 职责重叠。而且它有三个额外问题：

1. 需要 `CREATE EXTENSION` 权限，且多实例并发启动时 `CREATE EXTENSION IF NOT EXISTS` **并非原子操作**，可能撞 `pg_extension` 唯一索引；
2. 表结构不进版本库、无法演进；
3. 与 Flyway 两边都想建这张表。

### 5.7 V2 的 DDL 必须与配置严格一致

⚠️ 这是最容易在改配置时踩空的一处：

| `spring.ai.vectorstore.pgvector` 配置 | V2 的 DDL |
|---|---|
| `dimensions: 1536` | `embedding vector(1536)` |
| `index-type: HNSW` | `USING HNSW` |
| `distance-type: COSINE_DISTANCE` | `vector_cosine_ops` |
| `table-name: t_vector_store` | `CREATE TABLE t_vector_store` |

**改配置就必须新增迁移脚本去 ALTER**，对不上时写入或检索会报**维度不匹配 / 找不到算子**的错误。

注意 `initialize-schema: false` 关掉的只是**建表**；上面这几个配置项仍然是**运行时**配置——应用读写这张表时按它们生成 SQL。

### 5.8 三张表的扩展依赖

V1 开头集中声明了三个扩展，**都只有 `t_vector_store` 用到**：

- `vector` —— 向量类型与 HNSW 索引
- `hstore` —— Spring AI `PgVectorStore` 建表 SQL 里要求的（`metadata json` 列）
- `"uuid-ossp"` —— `uuid_generate_v4()` 默认值

本项目的业务表没有直接用到后两个，但 V2 的 DDL 与 starter 保持一致，故一并声明。

集中放在 V1 开头的原因：每个扩展都要超级用户权限，且**多实例并发启动时 `CREATE EXTENSION IF NOT EXISTS` 并非原子操作**——让它们先于任何建表语句一次建好，是更稳妥的分工。

⚠️ `V1` 里刻意**不写 `IF NOT EXISTS`**：Flyway 保证每个脚本只执行一次，加上它反而会让「库里已有同名表但结构与本脚本不一致」这种情况**静默通过并记录成迁移成功**。失败要趁早。

---

## 6. 数据访问约定

- **驱动是 p6spy**（`com.p6spy.engine.spy.P6SpyDriver`，URL 前缀 `jdbc:p6spy:postgresql://`），SQL 打印配置在 `spy.properties`。
- **MyBatis-Plus**，`@MapperScan` 在 `MybatisPlusConfig`，分页插件固定 `DbType.POSTGRE_SQL`。
- **Mapper 普遍用 `default` 方法 + `Wrappers.lambdaQuery()`** 写查询，不走 XML。
- 幂等写入用 `INSERT ... ON CONFLICT DO NOTHING` 并以**影响行数**判断是否首次写入（见 `KnowledgeBaseChunkMapper.insertChunkIgnoreDuplicate`）。

---

## 7. dev 与 prod 的配置差异

生产配置由 `docker-compose.yml` 通过 `SPRING_PROFILES_ACTIVE=prod` 激活。

**本质差异不是 host，而是安全边界与资源约束**：

| 项 | dev | prod | 原因 |
|---|---|---|---|
| `flyway.locations` | 含 `db/dev-migration` | **不含** | 避免演示账号进生产库 |
| `auth.jwt.secret` | `${JWT_SECRET:dev-only-...}` 有缺省值 | `${JWT_SECRET}` **无缺省值** | 缺失即启动失败，不退回公开默认密钥 |
| `spring.ai.openai.api-key` | `${DASHSCOPE_API_KEY:xxx}` | `${DASHSCOPE_API_KEY}` **无缺省值** | dev 的 `xxx` 会让问答静默失败，prod 直接起不来 |
| HikariCP | max 20 / min-idle 5 | max 10 / min-idle 2 | 服务器只有 1.6G 内存 |
| `okhttp.max-idle-connections` | 200 | 50 | 同上 |
| `knowledge-base.file-storage-path` | `./data/files`（相对） | `/app/data/files`（绝对） | 两者等价；生产写绝对路径是为了让「文件落在哪」一眼可见 |
| `searxng.engines` | 含 `yandex` | 不含 | 服务器无代理，依赖境外网络的引擎不通 |

**host 差异**（`postgres` / `searxng` 用服务名）**不写在这里**，由 compose 的环境变量覆盖——本文件管「生产与开发的语义差异」，compose 管「容器与宿主机的地址差异」。分工见 `deployment.md`。

---

## 8. 本模块坑点

1. **`ALTER TABLE ADD COLUMN` 只能把新列追加到表尾**。于是「从 V1 一路迁移上来的库」与「按最新结构全新建的库」，**列序必然不同**。比较两边结构时必须**按集合而非列序**。

2. **只比 `information_schema.columns` 就下「结构一致」的结论是过度断言**。真实差异可能落在没被 diff 的那一维——比如**列注释**。`COMMENT ON COLUMN` 写进 `pg_description`，**注释分叉即 schema 分叉**。正确做法是把列、注释、索引、预置数据四条查询都做集合式比较。

3. **手工建的旧库不跑迁移脚本会抛 `BadSqlGrammarException`**。MyBatis-Plus 生成的列清单包含新列，而旧库没有该列。排查这类错误时，第一个该看的就是「这个库跑过迁移吗」。

4. **`ALTER TABLE ... RENAME` 不会重命名序列与主键约束**。表改名后，库里仍是 `t_ai_customer_service_file_storage_id_seq` 之类的旧名。功能无影响（`BIGSERIAL` 的 `nextval` 按 OID 绑定），但**既有库与全新初始化的库内部对象名会不一致**，需要另起迁移补 `ALTER SEQUENCE` / `RENAME CONSTRAINT`。

5. **`MybatisPlusConfig` 的类注释里 `@Description` 是 `TODO`**，从未填写。

6. **`t_knowledge_base_file.uploaded_chunks` 的实际读数为零**。库层注释说它是「刻意冗余，用一次原子自增换掉高频 `count(*)`」，但当前 `src/main` 里没有任何地方读它——`checkFile` 走的是分片表查询、`mergeChunk` 用的是分片表的实际条数。所以它目前是一列**只写不读**的冗余数据，且可被并发上传或他人抬高。（设计意图与实际使用不一致，记录在案。）
