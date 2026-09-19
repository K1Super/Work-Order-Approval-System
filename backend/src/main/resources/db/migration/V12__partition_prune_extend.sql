-- ============================================================
-- Flyway V12 — 分区表预建 12 个月分区 + DEFAULT 分区回迁说明（W-39）
-- ------------------------------------------------------------
-- 修复：V7 仅把 approval_log / sys_security_audit_log /
--       work_order_comment 三张表预建至 2026-08 分区，其后数据
--       全部涌入 DEFAULT 分区，失去按月裁剪/归档能力。
-- 本迁移：将三张分区表从当前最大月（2026-08）续建至 2027-08，
--       共未来 12 个月（2026-09 ~ 2027-08）。
-- 原则：幂等可重入（to_regclass 检测 + 动态 DDL），不改动既有
--       分区与数据；先加（建分区）后无需删，向前兼容。
-- ============================================================

SET client_encoding = 'UTF8';

-- 逐表、逐月创建缺失分区（2026-09 ~ 2027-08 共 12 个月）
DO $$
DECLARE
    tbl       text;
    m         int;
    start_ts  text;
    end_ts    text;
    part_name text;
BEGIN
    FOR tbl IN SELECT unnest(ARRAY['approval_log', 'sys_security_audit_log', 'work_order_comment'])
    LOOP
        FOR m IN 0..11 LOOP
            start_ts  := to_char(DATE '2026-09-01' + (m || ' month')::interval, 'YYYY-MM-DD');
            end_ts    := to_char(DATE '2026-09-01' + ((m + 1) || ' month')::interval, 'YYYY-MM-DD');
            part_name := tbl || '_' || to_char(DATE '2026-09-01' + (m || ' month')::interval, 'YYYYMM');
            IF to_regclass(part_name) IS NULL THEN
                EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                               part_name, tbl, start_ts, end_ts);
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- ============================================================
-- DEFAULT 分区数据回迁（运维步骤，非本迁移自动执行）
-- ------------------------------------------------------------
-- 说明：V7 建的三张分区表均未声明主键/唯一约束（仅 BIGSERIAL id，
--       无约束），无法安全使用 ON CONFLICT DO NOTHING 做幂等回迁；
--       回迁涉及逐行比对，误操作可能造成重复导入，故此处仅给出
--       运维步骤，由 DBA 在确认 DEFAULT 分区确有泄漏数据后执行。
--
-- 触发条件：本迁移执行前，若有 create_time 落入 2026-09 ~ 2027-08
--       区间的行已写入 DEFAULT 分区（典型为 2026-09 当月数据），
--       需按以下模板逐表、逐月、独立事务回迁。
--
-- 每表每月的执行步骤（以 approval_log 2026-09 为例，执行前后均
-- SELECT COUNT(*) 校验行数一致）：
--   1) 校验 DEFAULT 中落区间的行数：
--        SELECT COUNT(*) FROM approval_log_default
--        WHERE create_time >= '2026-09-01' AND create_time < '2026-10-01';
--   2) 回迁到目标分区（NOT EXISTS 防重，保护 id 唯一）：
--        INSERT INTO approval_log_202609
--        SELECT d.* FROM approval_log_default d
--        WHERE d.create_time >= '2026-09-01' AND d.create_time < '2026-10-01'
--          AND NOT EXISTS (SELECT 1 FROM approval_log_202609 p WHERE p.id = d.id);
--   3) 从 DEFAULT 删除已回迁行：
--        DELETE FROM approval_log_default d
--        WHERE d.create_time >= '2026-09-01' AND d.create_time < '2026-10-01'
--          AND EXISTS (SELECT 1 FROM approval_log_202609 p WHERE p.id = d.id);
--
-- 同一模式适用于 sys_security_audit_log_* 与 work_order_comment_*。
-- ============================================================