-- V8: 审计日志结构化字段增强 — 规范条款 7（结构化日志字段）
-- 新增 trace_id/span_id/action/path/duration/error_code/error_location 列
-- 用于全链路追踪和结构化日志输出

-- 安全审计日志表新增结构化字段
ALTER TABLE sys_security_audit_log
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(40),
    ADD COLUMN IF NOT EXISTS span_id VARCHAR(40),
    ADD COLUMN IF NOT EXISTS action VARCHAR(50),
    ADD COLUMN IF NOT EXISTS path VARCHAR(200),
    ADD COLUMN IF NOT EXISTS duration BIGINT,
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS error_location VARCHAR(200);

-- 索引：支持按 traceId 检索全链路日志
CREATE INDEX IF NOT EXISTS idx_audit_log_trace_id
    ON sys_security_audit_log(trace_id);

-- 索引：支持按 action 检索操作类型
CREATE INDEX IF NOT EXISTS idx_audit_log_action
    ON sys_security_audit_log(action);
