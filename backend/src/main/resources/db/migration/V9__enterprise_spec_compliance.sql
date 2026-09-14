-- ============================================================
-- Flyway V9 — 企业级研发规范合规补全（规范 §3 数据库设计规范）
-- 1. 补充 is_deleted 字段（规范 §3 必备基础字段）
-- 2. 补充 update_time 字段
-- 3. 补充 create_by/update_by 审计字段
-- 4. 索引命名规范化（idx_tablename_column / uk_tablename_column）
-- 5. 状态字段 CHECK 约束
-- 6. 字段 COMMENT 补充
-- ============================================================

-- ==================== 1. 补充 is_deleted 字段 ====================
-- 规范 §3：每个业务表强制包含 is_deleted 逻辑删除标记
-- 注：work_order 已在 V6 添加；sys_security_audit_log/approval_log/work_order_comment
--     为纯追加日志表，删除审计轨迹是合规风险，不添加 is_deleted

ALTER TABLE IF EXISTS sys_department ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS sys_position ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS sys_permission ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS sys_role ADD COLUMN IF NOT EXISTS is_deleted SMALLINT NOT NULL DEFAULT 0;

-- ==================== 2. 补充 update_time 字段 ====================

ALTER TABLE IF EXISTS sys_password_history ADD COLUMN IF NOT EXISTS update_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

-- ==================== 3. 补充 create_by/update_by 审计字段 ====================
-- 规范 §3：审计字段完整性

-- sys_department
ALTER TABLE IF EXISTS sys_department ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE IF EXISTS sys_department ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_position
ALTER TABLE IF EXISTS sys_position ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE IF EXISTS sys_position ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_permission
ALTER TABLE IF EXISTS sys_permission ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE IF EXISTS sys_permission ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_role
ALTER TABLE IF EXISTS sys_role ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE IF EXISTS sys_role ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_user_role
ALTER TABLE IF EXISTS sys_user_role ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_role_permission
ALTER TABLE IF EXISTS sys_role_permission ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_user_position
ALTER TABLE IF EXISTS sys_user_position ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_password_history
ALTER TABLE IF EXISTS sys_password_history ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- sys_system_setting（如存在）
ALTER TABLE IF EXISTS sys_system_setting ADD COLUMN IF NOT EXISTS create_by BIGINT;
ALTER TABLE IF EXISTS sys_system_setting ADD COLUMN IF NOT EXISTS update_by BIGINT;

-- ==================== 4. 索引命名规范化 ====================
-- 规范 §3：索引名 idx_tablename_column，唯一索引 uk_tablename_column

-- 重命名不合规的唯一索引
ALTER INDEX IF EXISTS uk_username RENAME TO uk_sys_user_username;
ALTER INDEX IF EXISTS uk_role_code RENAME TO uk_sys_role_role_code;
ALTER INDEX IF EXISTS uk_permission_code RENAME TO uk_sys_permission_permission_code;
ALTER INDEX IF EXISTS uk_order_no RENAME TO uk_work_order_order_no;
ALTER INDEX IF EXISTS uk_user_role RENAME TO uk_sys_user_role_user_id_role_id;
ALTER INDEX IF EXISTS uk_role_permission RENAME TO uk_sys_role_permission_role_id_permission_id;
ALTER INDEX IF EXISTS uk_user_position RENAME TO uk_sys_user_position_user_id_position_id;

-- 重命名不合规的普通索引（V1 中创建的 idx_ 前缀索引需确认名称）
-- password_history 相关索引
ALTER INDEX IF EXISTS idx_password_history_user_id RENAME TO idx_sys_password_history_user_id;
ALTER INDEX IF EXISTS idx_password_history_change_time RENAME TO idx_sys_password_history_change_time;

-- approval_log 相关索引（已符合 idx_tablename_column 命名规范，无需重命名）
-- 注：PostgreSQL 不支持将索引 RENAME 为自身名称（会报 "relation already exists"）
-- ALTER INDEX IF EXISTS idx_approval_log_work_order_id RENAME TO idx_approval_log_work_order_id;
-- ALTER INDEX IF EXISTS idx_approval_log_process_instance_id RENAME TO idx_approval_log_process_instance_id;
-- ALTER INDEX IF EXISTS idx_approval_log_operator_id RENAME TO idx_approval_log_operator_id;
-- ALTER INDEX IF EXISTS idx_approval_log_create_time RENAME TO idx_approval_log_create_time;

-- ==================== 4.5 数据清洗：归一化存量数据以兼容即将添加的 CHECK 约束 ====================
-- 规范 §3：status 仅允许 0(禁用)/1(启用)，priority 仅允许 1-4
-- 历史遗留：sys_user 存在 status=-2（异常/锁定态），统一归一化为 0（禁用，保守不激活）
UPDATE sys_user SET status = 0 WHERE status NOT IN (0, 1);
UPDATE sys_role SET status = 1 WHERE status NOT IN (0, 1);
UPDATE sys_department SET status = 1 WHERE status NOT IN (0, 1);
UPDATE sys_position SET status = 1 WHERE status NOT IN (0, 1);
UPDATE sys_permission SET status = 1 WHERE status NOT IN (0, 1);
-- work_order.priority 可能为 NULL 或超范围，归一化为 2（中）
UPDATE work_order SET priority = 2 WHERE priority IS NULL OR priority NOT BETWEEN 1 AND 4;

-- ==================== 5. 状态字段 CHECK 约束 ====================
-- 规范 §3：状态字段注释中明确每个值含义 + CHECK 约束

ALTER TABLE IF EXISTS sys_user ADD CONSTRAINT ck_sys_user_status CHECK (status IN (0, 1));
ALTER TABLE IF EXISTS sys_role ADD CONSTRAINT ck_sys_role_status CHECK (status IN (0, 1));
ALTER TABLE IF EXISTS sys_department ADD CONSTRAINT ck_sys_department_status CHECK (status IN (0, 1));
ALTER TABLE IF EXISTS sys_position ADD CONSTRAINT ck_sys_position_status CHECK (status IN (0, 1));
ALTER TABLE IF EXISTS sys_permission ADD CONSTRAINT ck_sys_permission_status CHECK (status IN (0, 1));
ALTER TABLE IF EXISTS work_order ADD CONSTRAINT ck_work_order_priority CHECK (priority BETWEEN 1 AND 4);

-- ==================== 6. 字段 COMMENT 补充 ====================

COMMENT ON COLUMN sys_department.is_deleted IS '逻辑删除标识：0-未删除 1-已删除';
COMMENT ON COLUMN sys_department.create_by IS '创建人ID';
COMMENT ON COLUMN sys_department.update_by IS '更新人ID';

COMMENT ON COLUMN sys_position.is_deleted IS '逻辑删除标识：0-未删除 1-已删除';
COMMENT ON COLUMN sys_position.create_by IS '创建人ID';
COMMENT ON COLUMN sys_position.update_by IS '更新人ID';

COMMENT ON COLUMN sys_permission.is_deleted IS '逻辑删除标识：0-未删除 1-已删除';
COMMENT ON COLUMN sys_permission.create_by IS '创建人ID';
COMMENT ON COLUMN sys_permission.update_by IS '更新人ID';

COMMENT ON COLUMN sys_role.is_deleted IS '逻辑删除标识：0-未删除 1-已删除';
COMMENT ON COLUMN sys_role.create_by IS '创建人ID';
COMMENT ON COLUMN sys_role.update_by IS '更新人ID';

COMMENT ON COLUMN sys_user.status IS '账号状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_role.status IS '角色状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_department.status IS '部门状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_position.status IS '职位状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_permission.status IS '权限状态：0-禁用 1-启用';
COMMENT ON COLUMN work_order.priority IS '优先级：1-低 2-中 3-高 4-紧急';

COMMENT ON COLUMN sys_password_history.update_time IS '更新时间';
COMMENT ON COLUMN sys_password_history.update_by IS '更新人ID';
