-- ============================================================
-- V4: 密码重置令牌表
-- ============================================================
-- 用途：支持企业级密码重置流程
--   1. 管理员触发重置 → 生成一次性 token
--   2. token 通过安全通道（重置链接）传递给用户
--   3. 用户点击链接 → 输入新密码 → 后端验证 token 并设置新密码
--
-- 安全特性：
--   - token 为 64 字符随机十六进制串（256 位熵）
--   - token 哈希存储（防数据库泄露时被重放）
--   - 15 分钟过期
--   - 一次性使用（used 标志）
--   - 记录操作者 ID（审计）
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_password_reset_token (
    id              BIGSERIAL       PRIMARY KEY,
    -- token 的 SHA-256 哈希值（数据库不存明文，防泄露重放）
    token_hash      VARCHAR(64)     NOT NULL,
    -- 目标用户（要重置密码的用户）
    user_id         BIGINT          NOT NULL,
    -- 操作者（触发重置的管理员），可为空（自助找回密码场景）
    operator_id     BIGINT,
    -- 过期时间（UTC）
    expiry_time     TIMESTAMP       NOT NULL,
    -- 是否已使用（一次性令牌）
    used            BOOLEAN         NOT NULL DEFAULT FALSE,
    -- 使用时间（审计）
    used_time       TIMESTAMP,
    -- 创建时间
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 唯一索引：token_hash 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_password_reset_token_hash
    ON sys_password_reset_token (token_hash);

-- 查询索引：按 user_id 查询用户的有效 token
CREATE INDEX IF NOT EXISTS idx_password_reset_token_user
    ON sys_password_reset_token (user_id);

-- 查询索引：按 expiry_time 清理过期 token
CREATE INDEX IF NOT EXISTS idx_password_reset_token_expiry
    ON sys_password_reset_token (expiry_time);

-- 外键约束：user_id 引用 sys_user(id)
ALTER TABLE sys_password_reset_token
    ADD CONSTRAINT fk_password_reset_token_user
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE;

COMMENT ON TABLE  sys_password_reset_token IS '密码重置令牌表（一次性、15分钟过期）';
COMMENT ON COLUMN sys_password_reset_token.token_hash IS 'token 的 SHA-256 哈希（64位十六进制），数据库不存明文';
COMMENT ON COLUMN sys_password_reset_token.user_id IS '目标用户 ID（要重置密码的用户）';
COMMENT ON COLUMN sys_password_reset_token.operator_id IS '操作者 ID（触发重置的管理员），自助场景为空';
COMMENT ON COLUMN sys_password_reset_token.expiry_time IS 'token 过期时间（UTC）';
COMMENT ON COLUMN sys_password_reset_token.used IS '是否已使用（一次性令牌）';
COMMENT ON COLUMN sys_password_reset_token.used_time IS 'token 使用时间（审计）';
