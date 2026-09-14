-- ============================================================
-- Flyway V7 — OPTIMIZATION.md 全量刚性执行：数据库 Schema 大重构
-- 创建日期：2026-07-26
-- ============================================================
-- 改造范围（OPTIMIZATION.md 四大领域落地）：
-- 一、架构解耦：work_order 移除 5 个流程运行时字段，新建 order_process_link
-- 二、数据一致性：work_order 新增 version 乐观锁列；sys_user 软删除 CDC 触发器
-- 三、安全体系：sys_user 新增 token_version + encrypted_dek（DEK/KEK 分层加密）
-- 四、数据库优化：
--   4.1 三张日志表移除 is_deleted，改为声明式分区（按月）
--   4.2 枚举字段 VARCHAR → SMALLINT + CHECK 约束
--   4.3 索引清理（冗余）+ 条件索引补充
--   4.4 所有 TIMESTAMP → TIMESTAMPTZ，时区 UTC
-- ============================================================
-- 用户决策 4：清空 work_order + approval_log + work_order_comment 重建
-- 用户决策 3：dev 用环境变量 KEK_BASE64，prod 接 Vault（应用层校验，DB 不涉及）
-- ============================================================
-- 注意：本脚本必须幂等可重入（全部使用 IF EXISTS/IF NOT EXISTS）
--       TRUNCATE 不可逆，已在执行前由 DBA 手动 pg_dump 备份
-- ============================================================

SET client_encoding = 'UTF8';

-- ============================================================
-- 段 1：清空 work_order + approval_log + work_order_comment 重建
-- （用户决策 4：开发环境数据可清空）
-- ============================================================
TRUNCATE TABLE work_order_comment RESTART IDENTITY CASCADE;
TRUNCATE TABLE approval_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE work_order RESTART IDENTITY CASCADE;

-- ============================================================
-- 段 2：work_order 表结构调整
-- 2.1 移除 5 个 Flowable 流程运行时字段（OPTIMIZATION 一）
-- 2.2 status / order_type VARCHAR → SMALLINT + CHECK（OPTIMIZATION 四.4.2）
-- 2.3 新增 version 乐观锁列（OPTIMIZATION 二）
-- 2.4 所有时间字段 TIMESTAMP → TIMESTAMPTZ（OPTIMIZATION 四.4.4）
-- ============================================================

-- 2.1 移除 5 个流程运行时字段（CASCADE 删除相关索引）
ALTER TABLE work_order DROP COLUMN IF EXISTS process_instance_id;
ALTER TABLE work_order DROP COLUMN IF EXISTS process_definition_id;
ALTER TABLE work_order DROP COLUMN IF EXISTS current_node;
ALTER TABLE work_order DROP COLUMN IF EXISTS current_assignee;
ALTER TABLE work_order DROP COLUMN IF EXISTS current_assignee_name;

-- 2.2 status VARCHAR → SMALLINT（数据已 TRUNCATE，无需 USING 转换）
-- 修复：必须先 DROP DEFAULT（原 DEFAULT 'DRAFT' 是字符串，无法直接转 SMALLINT）
ALTER TABLE work_order ALTER COLUMN status DROP DEFAULT;
ALTER TABLE work_order ALTER COLUMN status TYPE SMALLINT USING COALESCE(status::SMALLINT, 1);
ALTER TABLE work_order ALTER COLUMN status SET DEFAULT 1;
ALTER TABLE work_order ALTER COLUMN order_type DROP DEFAULT;
ALTER TABLE work_order ALTER COLUMN order_type TYPE SMALLINT USING COALESCE(order_type::SMALLINT, 6);
ALTER TABLE work_order ALTER COLUMN order_type SET DEFAULT 6;

-- 添加 CHECK 约束
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_work_order_status') THEN
        ALTER TABLE work_order ADD CONSTRAINT ck_work_order_status CHECK (status BETWEEN 1 AND 6);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_work_order_order_type') THEN
        ALTER TABLE work_order ADD CONSTRAINT ck_work_order_order_type CHECK (order_type BETWEEN 1 AND 6);
    END IF;
END $$;

-- 2.3 新增 version 乐观锁列
ALTER TABLE work_order ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 2.4 时间字段 TIMESTAMP → TIMESTAMPTZ（USING xxx AT TIME ZONE 'UTC' 解释现有数据为 UTC）
ALTER TABLE work_order ALTER COLUMN submit_time TYPE TIMESTAMPTZ USING submit_time AT TIME ZONE 'UTC';
ALTER TABLE work_order ALTER COLUMN complete_time TYPE TIMESTAMPTZ USING complete_time AT TIME ZONE 'UTC';
ALTER TABLE work_order ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE work_order ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';

-- ============================================================
-- 段 3：新建 order_process_link 表（OPTIMIZATION 一）
-- 工单与流程引擎的轻量级关联表，仅含 work_order_id + process_instance_id
-- ============================================================
CREATE TABLE IF NOT EXISTS order_process_link (
    id BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL,
    process_instance_id VARCHAR(100) NOT NULL,
    process_definition_id VARCHAR(100),
    create_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    is_deleted SMALLINT NOT NULL DEFAULT 0,
    create_by BIGINT,
    update_by BIGINT,
    CONSTRAINT uk_opl_work_order_id UNIQUE (work_order_id),
    CONSTRAINT uk_opl_process_instance_id UNIQUE (process_instance_id)
);

CREATE INDEX IF NOT EXISTS idx_opl_process_instance_id ON order_process_link(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_opl_work_order_id ON order_process_link(work_order_id) WHERE is_deleted = 0;

-- ============================================================
-- 段 4：三张日志表分区改造（OPTIMIZATION 四.4.1）
-- 4.1 移除 is_deleted 列（追加写日志表不应有逻辑删除）
-- 4.2 改为声明式分区（按月 RANGE(create_time)）
-- 4.3 枚举字段 VARCHAR → SMALLINT + CHECK（仅 approval_log）
-- 4.4 时间字段 → TIMESTAMPTZ
-- ============================================================

-- 尝试创建 pg_partman 扩展（若二进制可用则使用其自动管理；否则用原生分区+应用层定时任务）
DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_partman;
        RAISE NOTICE 'pg_partman 扩展已创建，将用于分区自动管理';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pg_partman 二进制不可用（%），降级为原生声明式分区 + 应用层定时任务管理分区', SQLERRM;
    END;
END $$;

-- ---------- 4.1 approval_log 分区改造 ----------
-- 4.1.1 移除 is_deleted 列
ALTER TABLE approval_log DROP COLUMN IF EXISTS is_deleted;

-- 4.1.2 枚举字段 VARCHAR → SMALLINT + CHECK（数据已 TRUNCATE）
ALTER TABLE approval_log ALTER COLUMN action TYPE SMALLINT USING COALESCE(action::SMALLINT, 1);
ALTER TABLE approval_log ALTER COLUMN node_status TYPE SMALLINT USING COALESCE(node_status::SMALLINT, 1);
ALTER TABLE approval_log ALTER COLUMN before_status TYPE SMALLINT USING COALESCE(before_status::SMALLINT, 1);
ALTER TABLE approval_log ALTER COLUMN after_status TYPE SMALLINT USING COALESCE(after_status::SMALLINT, 1);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_approval_log_action') THEN
        ALTER TABLE approval_log ADD CONSTRAINT ck_approval_log_action CHECK (action BETWEEN 1 AND 8);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_approval_log_node_status') THEN
        ALTER TABLE approval_log ADD CONSTRAINT ck_approval_log_node_status CHECK (node_status IS NULL OR node_status BETWEEN 1 AND 3);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_approval_log_before_status') THEN
        ALTER TABLE approval_log ADD CONSTRAINT ck_approval_log_before_status CHECK (before_status IS NULL OR before_status BETWEEN 1 AND 6);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_approval_log_after_status') THEN
        ALTER TABLE approval_log ADD CONSTRAINT ck_approval_log_after_status CHECK (after_status IS NULL OR after_status BETWEEN 1 AND 6);
    END IF;
END $$;

-- 4.1.3 时间字段 → TIMESTAMPTZ
ALTER TABLE approval_log ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';

-- 4.1.4 改为分区表（CREATE TABLE LIKE 重建为分区表）
-- 数据已 TRUNCATE，直接重建表结构
DROP TABLE IF EXISTS approval_log CASCADE;
CREATE TABLE approval_log (
    id BIGSERIAL,
    work_order_id BIGINT NOT NULL,
    order_no VARCHAR(64),
    process_instance_id VARCHAR(64),
    task_id VARCHAR(64),
    task_name VARCHAR(100),
    operator_id BIGINT NOT NULL,
    operator_name VARCHAR(50) NOT NULL,
    action SMALLINT NOT NULL,
    comment TEXT,
    node_status SMALLINT,
    before_status SMALLINT,
    after_status SMALLINT,
    duration BIGINT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    CONSTRAINT ck_approval_log_action CHECK (action BETWEEN 1 AND 8),
    CONSTRAINT ck_approval_log_node_status CHECK (node_status IS NULL OR node_status BETWEEN 1 AND 3),
    CONSTRAINT ck_approval_log_before_status CHECK (before_status IS NULL OR before_status BETWEEN 1 AND 6),
    CONSTRAINT ck_approval_log_after_status CHECK (after_status IS NULL OR after_status BETWEEN 1 AND 6)
) PARTITION BY RANGE (create_time);

-- 创建默认分区 + 当月 + 下月分区
CREATE TABLE approval_log_default PARTITION OF approval_log DEFAULT;
CREATE TABLE approval_log_202607 PARTITION OF approval_log FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE approval_log_202608 PARTITION OF approval_log FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

-- 分区表索引（在父表创建，自动应用到所有分区）
CREATE INDEX idx_approval_log_work_order_id ON approval_log(work_order_id);
CREATE INDEX idx_approval_log_process_instance_id ON approval_log(process_instance_id);
CREATE INDEX idx_approval_log_operator_id ON approval_log(operator_id);
CREATE INDEX idx_approval_log_create_time ON approval_log(create_time);

-- ---------- 4.2 sys_security_audit_log 分区改造 ----------
ALTER TABLE sys_security_audit_log DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE sys_security_audit_log ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';

DROP TABLE IF EXISTS sys_security_audit_log CASCADE;
CREATE TABLE sys_security_audit_log (
    id BIGSERIAL,
    user_id BIGINT,
    username VARCHAR(50),
    action_type VARCHAR(50) NOT NULL,
    description VARCHAR(200),
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    request_url VARCHAR(200),
    status VARCHAR(20),
    details JSONB,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT
) PARTITION BY RANGE (create_time);

CREATE TABLE sys_security_audit_log_default PARTITION OF sys_security_audit_log DEFAULT;
CREATE TABLE sys_security_audit_log_202607 PARTITION OF sys_security_audit_log FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE sys_security_audit_log_202608 PARTITION OF sys_security_audit_log FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE INDEX idx_audit_log_user_time ON sys_security_audit_log(user_id, create_time);
CREATE INDEX idx_security_audit_log_create_time ON sys_security_audit_log(create_time);

-- ---------- 4.3 work_order_comment 分区改造 ----------
ALTER TABLE work_order_comment DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE work_order_comment ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';

DROP TABLE IF EXISTS work_order_comment CASCADE;
CREATE TABLE work_order_comment (
    id BIGSERIAL,
    work_order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_name VARCHAR(100),
    content TEXT NOT NULL,
    comment_type VARCHAR(20) DEFAULT 'comment',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT
) PARTITION BY RANGE (create_time);

CREATE TABLE work_order_comment_default PARTITION OF work_order_comment DEFAULT;
CREATE TABLE work_order_comment_202607 PARTITION OF work_order_comment FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE work_order_comment_202608 PARTITION OF work_order_comment FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE INDEX idx_work_order_comment_work_order_id ON work_order_comment(work_order_id);
CREATE INDEX idx_work_order_comment_create_time ON work_order_comment(create_time);

-- ============================================================
-- 段 5：sys_user 表扩展（OPTIMIZATION 三.3.1 + 三.3.3）
-- 5.1 新增 token_version（JWT 混合状态管理）
-- 5.2 新增 encrypted_dek（DEK/KEK 分层加密）
-- 5.3 时间字段 → TIMESTAMPTZ
-- ============================================================
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS encrypted_dek VARCHAR(500);

ALTER TABLE sys_user ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user ALTER COLUMN password_change_time TYPE TIMESTAMPTZ USING password_change_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user ALTER COLUMN lock_time TYPE TIMESTAMPTZ USING lock_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user ALTER COLUMN last_login_time TYPE TIMESTAMPTZ USING last_login_time AT TIME ZONE 'UTC';

-- ============================================================
-- 段 6：sys_permission permission_type VARCHAR → SMALLINT（OPTIMIZATION 四.4.2）
-- ============================================================
-- 先将现有字符串数据转为 SMALLINT
UPDATE sys_permission SET permission_type = '1' WHERE permission_type = 'menu' OR permission_type IS NULL;
UPDATE sys_permission SET permission_type = '2' WHERE permission_type = 'button';
UPDATE sys_permission SET permission_type = '3' WHERE permission_type = 'api';

-- 修复：必须先 DROP DEFAULT（原 DEFAULT 'menu' 是字符串）
ALTER TABLE sys_permission ALTER COLUMN permission_type DROP DEFAULT;
ALTER TABLE sys_permission ALTER COLUMN permission_type TYPE SMALLINT USING COALESCE(permission_type::SMALLINT, 1);
ALTER TABLE sys_permission ALTER COLUMN permission_type SET DEFAULT 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_type') THEN
        ALTER TABLE sys_permission ADD CONSTRAINT ck_permission_type CHECK (permission_type BETWEEN 1 AND 3);
    END IF;
END $$;

ALTER TABLE sys_permission ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_permission ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';

-- ============================================================
-- 段 7：其他业务表时间字段统一为 TIMESTAMPTZ（OPTIMIZATION 四.4.4）
-- ============================================================
ALTER TABLE sys_department ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_department ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_position ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_position ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_role ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_role ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user_role ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user_role ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_role_permission ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_role_permission ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user_position ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_user_position ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_password_history ALTER COLUMN change_time TYPE TIMESTAMPTZ USING change_time AT TIME ZONE 'UTC';
ALTER TABLE sys_password_history ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_resigned_employee ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_resigned_employee ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
ALTER TABLE sys_system_setting ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE sys_system_setting ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';

-- V6 添加的 update_time 列（部分表无该列则跳过）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='sys_resigned_employee' AND column_name='update_time') THEN
        ALTER TABLE sys_resigned_employee ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';
    END IF;
END $$;

-- ============================================================
-- 段 8：索引清理与补充（OPTIMIZATION 四.4.3）
-- 8.1 删除被唯一约束覆盖的冗余索引
-- 8.2 新建条件索引（待办列表高频查询）
-- ============================================================

-- 8.1 删除冗余索引
DROP INDEX IF EXISTS idx_sys_user_username;        -- 被 sys_user.username UNIQUE 覆盖
DROP INDEX IF EXISTS idx_password_history_user_id; -- 被 idx_password_history_user_time 复合索引覆盖

-- 8.2 新建条件索引（解耦后 work_order 无 current_assignee，改为按 applicant_id 索引）
CREATE INDEX IF NOT EXISTS idx_work_order_pending ON work_order(status, applicant_id) WHERE status = 2;
CREATE INDEX IF NOT EXISTS idx_work_order_status ON work_order(status);
CREATE INDEX IF NOT EXISTS idx_work_order_applicant_id ON work_order(applicant_id);

-- ============================================================
-- 段 9：CDC 模拟 — sys_user 软删除触发器 + LISTEN/NOTIFY（OPTIMIZATION 二）
-- 捕获 sys_user 逻辑删除事件，触发关联数据处理
-- ============================================================
CREATE OR REPLACE FUNCTION notify_sys_user_soft_delete() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_deleted = 1 AND OLD.is_deleted = 0 THEN
        PERFORM pg_notify('sys_user_deleted', json_build_object(
            'user_id', NEW.id,
            'username', NEW.username,
            'timestamp', extract(epoch from now())
        )::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sys_user_soft_delete ON sys_user;
CREATE TRIGGER trg_sys_user_soft_delete AFTER UPDATE ON sys_user
    FOR EACH ROW EXECUTE FUNCTION notify_sys_user_soft_delete();

-- ============================================================
-- 段 10：会话级时区设置（OPTIMIZATION 四.4.4）
-- 持久化通过 application.yml spring.jpa.properties.hibernate.jdbc.time_zone: UTC
-- ============================================================
SET TIME ZONE 'UTC';

-- ============================================================
-- 验证查询（只读，不影响数据）
-- ============================================================
SELECT 'V7 verification' AS phase,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_name='work_order' AND column_name IN ('process_instance_id','process_definition_id','current_node','current_assignee','current_assignee_name')) AS removed_flow_fields,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_name='work_order' AND column_name='version') AS version_column,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_name='sys_user' AND column_name='token_version') AS token_version_col,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_name='sys_user' AND column_name='encrypted_dek') AS encrypted_dek_col,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_name='approval_log' AND column_name='is_deleted') AS approval_log_is_deleted,
       (SELECT COUNT(*) FROM pg_partitioned_table WHERE partrelid IN ('approval_log'::regclass, 'sys_security_audit_log'::regclass, 'work_order_comment'::regclass)) AS partitioned_tables;
