-- ============================================================
-- V5: 扩展加密敏感字段长度
-- ============================================================
-- 背景：phone / email 字段使用 AES-GCM 256 加密存储（AesEncryptedStringTypeHandler）
--   密文格式：base64(iv):base64(cipherText+authTag)
--     - IV：12 字节 → Base64 = 16 字符
--     - 分隔符 ":" = 1 字符
--     - cipherText = 明文字节 + 16 字节 GCM Auth Tag → Base64 编码
--
--   以 11 位手机号 "13800138000" 为例：
--     cipherText = 11 + 16 = 27 字节 → Base64 = 36 字符
--     密文总长 = 16 + 1 + 36 = 53 字符
--
--   原 phone VARCHAR(20) 远不足以容纳密文，导致任何非空手机号
--   在 INSERT / UPDATE 时触发 "对于可变字符类型来说，值太长了(20)"
--   表现为新增/编辑员工返回 500。
--
--   email VARCHAR(100) 对长邮箱（≥50 字符）密文也可能超长（>105 字符），
--   一并扩展以彻底消除风险。
--
-- 修复：将 phone / email 扩展至 VARCHAR(255)，
--   足以容纳任意实际手机号/邮箱的 AES-GCM 密文（最长约 180 字符），
--   并与 password 字段长度保持一致。
--
-- 涉及表：
--   1. sys_user              — 在职员工主表
--   2. sys_resigned_employee — 离职员工档案表（离职时迁移加密数据）
--
-- 注：ALTER COLUMN TYPE 是幂等操作，重复执行不会报错。
-- ============================================================

-- ---------- sys_user ----------
ALTER TABLE sys_user ALTER COLUMN phone  TYPE VARCHAR(255);
ALTER TABLE sys_user ALTER COLUMN email TYPE VARCHAR(255);

-- ---------- sys_resigned_employee ----------
ALTER TABLE sys_resigned_employee ALTER COLUMN phone  TYPE VARCHAR(255);
ALTER TABLE sys_resigned_employee ALTER COLUMN email TYPE VARCHAR(255);

-- ---------- 注释 ----------
COMMENT ON COLUMN sys_user.phone IS '手机号（AES-GCM 256 加密存储，格式 base64(iv):base64(cipher+tag)）';
COMMENT ON COLUMN sys_user.email IS '邮箱（AES-GCM 256 加密存储，格式 base64(iv):base64(cipher+tag)）';
COMMENT ON COLUMN sys_resigned_employee.phone IS '手机号（AES-GCM 256 加密存储，迁移自 sys_user）';
COMMENT ON COLUMN sys_resigned_employee.email IS '邮箱（AES-GCM 256 加密存储，迁移自 sys_user）';
