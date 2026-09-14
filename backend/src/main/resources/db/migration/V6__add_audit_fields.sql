-- ============================================================
-- Flyway V6 — 通用审计字段补全（规范 §2.7.1 每张业务表必含）
--
-- 为所有业务数据表补齐标准审计字段：
--   is_deleted   逻辑删除标识（0-未删除，1-已删除）
--   create_by    创建人ID
--   update_by    更新人ID
--   update_time  更新时间（缺失的表一并补齐）
--
-- 设计原则：
-- 1. 全部使用 ADD COLUMN IF NOT EXISTS，保证脚本幂等可重入；
-- 2. is_deleted 默认 0，并对存量数据回填 0，确保既有记录不被误判为已删除；
-- 3. create_by/update_by 允许 NULL，由 AuditFieldInterceptor 在写入时自动填充当前操作人；
-- 4. 纯日志/历史表（approval_log/sys_security_audit_log/sys_password_history/work_order_comment）
--    同步补齐字段以满足规范统一性，update_by/update_time 在追加写场景下保持 NULL。
-- ============================================================

SET client_encoding = 'UTF-8';

-- ---------- sys_department ----------
ALTER TABLE sys_department ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_department ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_department ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ---------- sys_position ----------
ALTER TABLE sys_position ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_position ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_position ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ---------- sys_permission（缺 update_time） ----------
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_role ----------
ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ---------- sys_user ----------
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ---------- sys_user_role（关系表，缺 update_time） ----------
ALTER TABLE sys_user_role ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user_role ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_user_role ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_user_role ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_role_permission（关系表，缺 update_time） ----------
ALTER TABLE sys_role_permission ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_role_permission ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_role_permission ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_role_permission ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_user_position（关系表，缺 update_time） ----------
ALTER TABLE sys_user_position ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user_position ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_user_position ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_user_position ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_password_history（历史表，缺 update_time） ----------
ALTER TABLE sys_password_history ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_password_history ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_password_history ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_password_history ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- work_order ----------
ALTER TABLE work_order ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE work_order ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE work_order ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ---------- work_order_comment（日志型，缺 update_time） ----------
ALTER TABLE work_order_comment ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE work_order_comment ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE work_order_comment ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE work_order_comment ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- approval_log（日志型，缺 update_time） ----------
ALTER TABLE approval_log ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE approval_log ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE approval_log ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE approval_log ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_resigned_employee（缺 update_time） ----------
ALTER TABLE sys_resigned_employee ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_resigned_employee ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_resigned_employee ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_resigned_employee ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_security_audit_log（日志型，缺 update_time） ----------
ALTER TABLE sys_security_audit_log ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_security_audit_log ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_security_audit_log ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_security_audit_log ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- sys_system_setting ----------
ALTER TABLE sys_system_setting ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_system_setting ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_system_setting ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ---------- sys_password_reset_token（令牌表，规范统一补齐） ----------
ALTER TABLE sys_password_reset_token ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_password_reset_token ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE sys_password_reset_token ADD COLUMN IF NOT EXISTS update_by BIGINT;
ALTER TABLE sys_password_reset_token ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ============================================================
-- 存量数据回填：确保既有记录 is_deleted = 0（未被误判为已删除）
-- ============================================================
UPDATE sys_department      SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_position        SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_permission      SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_role            SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_user            SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_user_role       SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_role_permission SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_user_position   SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_password_history SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE work_order          SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE work_order_comment  SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE approval_log        SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_resigned_employee SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_security_audit_log SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_system_setting  SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;
UPDATE sys_password_reset_token SET is_deleted = 0 WHERE is_deleted IS NULL OR is_deleted <> 0;

-- ============================================================
-- 逻辑删除字段索引（高频过滤字段建立普通索引，规范 §2.7.2 第 5 条）
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_sys_user_is_deleted        ON sys_user(is_deleted);
CREATE INDEX IF NOT EXISTS idx_work_order_is_deleted      ON work_order(is_deleted);
CREATE INDEX IF NOT EXISTS idx_sys_resigned_employee_is_del ON sys_resigned_employee(is_deleted);
CREATE INDEX IF NOT EXISTS idx_sys_department_is_deleted  ON sys_department(is_deleted);
CREATE INDEX IF NOT EXISTS idx_sys_role_is_deleted        ON sys_role(is_deleted);
