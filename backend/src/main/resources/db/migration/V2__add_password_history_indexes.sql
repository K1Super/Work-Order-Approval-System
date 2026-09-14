-- ============================================================
-- V2: 密码历史表索引补全
-- 阶段 4 修复 §5 — 数据存储与加密
--
-- 注：sys_password_history 表已在 V1__init.sql 中创建
-- 本迁移补全复合索引（用于按用户查询最近 N 条密码历史）
--
-- 兼容修复：旧版 V1 表定义误用 create_time，与 UserMapper.xml 的
-- change_time 不一致。此处幂等地将 create_time 列改名为 change_time，
-- 保证 V1（旧版已执行）与 V1（新版字段已修正）两种环境都能正确迁移。
-- ============================================================

-- 兼容旧 V1：若 create_time 列存在且 change_time 不存在，则重命名
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'sys_password_history' AND column_name = 'create_time')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_name = 'sys_password_history' AND column_name = 'change_time') THEN
        ALTER TABLE sys_password_history RENAME COLUMN create_time TO change_time;
    END IF;
END $$;

-- 旧索引 idx_password_history_create_time 若存在则删除（列已改名，旧索引失效）
DROP INDEX IF EXISTS idx_password_history_create_time;

-- 复合索引：按用户查询密码历史（user_id + change_time DESC）
CREATE INDEX IF NOT EXISTS idx_password_history_user_time
    ON sys_password_history(user_id, change_time DESC);

-- 删除旧的单列索引（已被复合索引覆盖，可选保留）
-- DROP INDEX IF EXISTS idx_password_history_user_id;
