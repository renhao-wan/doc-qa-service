-- ============================================================
-- V3 — 预置演示账号（**仅开发环境**）
--
-- ⚠️ 本文件位于 db/dev-migration/，该 location 只在 application-dev.yml 的
--     spring.flyway.locations 里被加载，生产环境不配置它，因此下面的账号
--     不会跟着迁移进生产库。
--
--     这不是洁癖，是安全问题：Flyway 的迁移脚本是**跨环境同一份**的，
--     把这个 INSERT 放进 db/migration/ 就等于给生产库插两个密码可公开推知的
--     登录凭证。所以它必须待在只有开发环境会扫到的目录里。
--
-- 三个演示账号密码均为 demo123。哈希由 BCryptPasswordEncoder 兼容的算法生成
-- （$2b$ 前缀；Spring Security 的 BCrypt 实现支持 $2a$ / $2b$ / $2y$ 三种前缀）。
-- 明文只在本行注释里保留——开发库限定。
--
-- ON CONFLICT DO NOTHING 让脚本在账号已存在时幂等通过：从旧库（此前由
-- db/init/01-schema.sql 建过账号）启用 Flyway 时，这两个用户名已经在表里了。
-- ============================================================

INSERT INTO t_user (username, password_hash, nickname)
VALUES ('demo',  '$2b$10$hhFzXa78QJ7kFZAaVO2aK.J2HlOhQWJzrxp7/uI8dXW.4bCvtgK4a', '演示账号 A'),
       ('demo2', '$2b$10$mgKXZgtlRKoUSyVPf499PeLUpNhugMOAvvXxb3JLEuW4WzlfR23c6', '演示账号 B')
ON CONFLICT (username) DO NOTHING;
