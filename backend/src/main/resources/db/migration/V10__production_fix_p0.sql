-- ============================================================
-- Flyway V10 — 生产级修复实施（fix-spec v1.0 / 安全审计报告 v2.8.2）
-- 覆盖：W-03（link 部分唯一索引）、W-05（审计表 Schema 对齐）、
--       W-06（离职状态 CHECK 语义统一）、W-15（种子序列 setval）、
--       W-35（employee_id 唯一约束）
-- 原则：幂等可重入、先备份语义（删除重复行保留最新）、向前兼容
-- ============================================================

-- ==================== W-03：order_process_link 部分唯一索引 ====================
-- 问题：V7 全局唯一 uk_opl_work_order_id 与「逻辑删除 + 重新提交」冲突，
--       重提交 INSERT 必触发唯一键冲突且被服务层吞掉，工单与流程永久失联。
-- 修复：改为部分唯一索引 WHERE is_deleted = 0，逻辑删除行不参与唯一性约束。

-- 1. 清理重复 active 行（保留最新一条，其余逻辑删除）
UPDATE order_process_link SET is_deleted = 1
WHERE id IN (
    SELECT a.id
    FROM order_process_link a
    JOIN order_process_link b
      ON a.work_order_id = b.work_order_id
     AND a.is_deleted = 0 AND b.is_deleted = 0
     AND a.id < b.id
);

-- 2. 删除全局唯一约束及其隐式索引、冗余条件索引
ALTER TABLE order_process_link DROP CONSTRAINT IF EXISTS uk_opl_work_order_id;
DROP INDEX IF EXISTS uk_opl_work_order_id;
DROP INDEX IF EXISTS idx_opl_work_order_id;

-- 3. 建立部分唯一索引（仅约束 active 行）
CREATE UNIQUE INDEX uk_opl_work_order_id_active
    ON order_process_link(work_order_id)
    WHERE is_deleted = 0;

-- ==================== W-05：审计表 Schema 对齐（以数据库为事实源） ====================
-- V7 已删除 sys_security_audit_log.is_deleted，此处幂等兜底；
-- 审计日志 append-only，不再存在逻辑删除列。
ALTER TABLE sys_security_audit_log DROP COLUMN IF EXISTS is_deleted;

-- ==================== W-06：离职状态与 sys_user.status CHECK 统一 ====================
-- 状态字典（以业务为准）：-2 已离职（应用写入）、-1 已离职（历史遗留）、0 禁用、1 启用
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS ck_sys_user_status;

-- 归一化存量异常值（V9 已将 -1/-2 洗为 0，此处兜底保护后续异常写入）
UPDATE sys_user SET status = -1 WHERE status NOT IN (-2, -1, 0, 1);

ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_status CHECK (status IN (-2, -1, 0, 1));

COMMENT ON COLUMN sys_user.status IS '账号状态：-2 已离职（应用写入）、-1 已离职（历史）、0 禁用、1 启用';

-- ==================== W-35：employee_id 唯一约束（消除并发同工号） ====================
-- 1. 去重：同一 employee_id 的 active 用户仅保留 id 最小者，其余置 NULL（employee_id 可空）
UPDATE sys_user SET employee_id = NULL
WHERE is_deleted = 0
  AND employee_id IS NOT NULL
  AND id NOT IN (
      SELECT MIN(id) FROM sys_user WHERE is_deleted = 0 AND employee_id IS NOT NULL GROUP BY employee_id
  );

-- 2. 将普通索引替换为部分唯一索引
DROP INDEX IF EXISTS idx_sys_user_employee_id;
CREATE UNIQUE INDEX uk_sys_user_employee_id_active
    ON sys_user(employee_id)
    WHERE is_deleted = 0 AND employee_id IS NOT NULL;

-- ==================== W-15：种子表序列重置（新环境首条插入无主键冲突） ====================
SELECT setval(pg_get_serial_sequence('sys_department', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_department));
SELECT setval(pg_get_serial_sequence('sys_position', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_position));
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_permission));
SELECT setval(pg_get_serial_sequence('sys_role', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_role));
SELECT setval(pg_get_serial_sequence('sys_user', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_user));
SELECT setval(pg_get_serial_sequence('sys_system_setting', 'id'), (SELECT COALESCE(MAX(id), 1) FROM sys_system_setting));
