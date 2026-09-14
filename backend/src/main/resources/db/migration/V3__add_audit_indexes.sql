-- ============================================================
-- V3: 审计日志索引补全
-- 阶段 4 修复 §5 — 数据存储与加密（审计查询性能优化）
--
-- 为 sys_security_audit_log 表补全查询索引：
-- - (user_id, create_time)：按用户查询审计日志
-- - (action_type, create_time)：按操作类型统计
-- - (ip_address, create_time)：按 IP 查询（异常检测）
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_audit_log_user_time
    ON sys_security_audit_log(user_id, create_time);

CREATE INDEX IF NOT EXISTS idx_audit_log_action_time
    ON sys_security_audit_log(action_type, create_time);

CREATE INDEX IF NOT EXISTS idx_audit_log_ip_time
    ON sys_security_audit_log(ip_address, create_time);
