-- ============================================================
-- Flyway V11 — 生产级修复实施 P1（fix-spec v1.0 W-09 / W-10）
-- 主题：离职表（sys_resigned_employee）敏感字段（email/phone）加密
-- 原则：幂等可重入、向前兼容；回滚以应用层移除 typeHandler 为基准，
--       无需反向迁移（加密列对旧版本应用透明，读取时原样返回密文不报错）
-- ============================================================

-- ==================== W-09：离职表 email/phone 挂 AES TypeHandler ====================
-- 实现位置（代码层）：ResignedEmployeeMapper.xml
--   - resultMap：email/phone 挂 typeHandler="com.workorder.config.AesEncryptedStringTypeHandler"
--   - insert    ：#{email, typeHandler=...} / #{phone, typeHandler=...}
-- 效果：新写入的离职记录 email/phone 由应用层 AES-GCM（KEK/DEK 分层）加密后落盘，
--       查询时由同一 TypeHandler 解密返回明文，与 sys_user 行为完全一致。
-- 数据流确认：ResignedEmployeeServiceImpl 离职时从 User 实体拷贝的 email/phone
--       已经是解密后的明文（UserMapper resultMap 已挂同一 typeHandler），
--       insert 时经本表 typeHandler 自动加密，服务层无需改动。

-- 存量明文迁移取舍（务实方案）：
--   1) AES-GCM 密文格式为 base64(iv):base64(cipher+tag)，IV 每次随机生成，密钥为
--      应用内存中的 KEK/DEK 分层密钥（DekContext/encrypted_dek），SQL 层无法等价
--      调用应用层算法；pgcrypto 的 encrypt/decrypt 与 Java AES/GCM/NoPadding 的
--      IV 编排/认证标签拼接/Base64 编码不一致，无法用 SQL 完成等价迁移，故不在
--      本迁移中做数据改写。
--   2) 存量明文行的兼容：AesEncryptedStringTypeHandler 读取时对非加密格式
--      （非 iv:cipher 结构）原样返回，历史明文数据查询/展示不受影响（历史遗留兼容）。
--   3) 正式迁移方式（应用层一次性脚本，本迁移不含）：
--      按 user_id 关联 sys_user，逐行用同一 DEK（sys_user.encrypted_dek + KEK
--      解密）或 legacy AES 密钥（work-order-system.security.aes-encryption-key），
--      经 AesEncryptionUtil 加密后 UPDATE 写回 sys_resigned_employee.email/phone；
--      脚本执行前先备份，执行后校验全部行已为加密格式。
--   4) 若确认存量数据仅来自 dev/测试环境，可视为历史遗留直接忽略。

-- 兜底：确保 email/phone 列宽足以容纳 AES-GCM 密文
--   （V5 已扩展至 VARCHAR(255)，此处幂等兜底，重复执行不报错）
ALTER TABLE sys_resigned_employee ALTER COLUMN phone  TYPE VARCHAR(255);
ALTER TABLE sys_resigned_employee ALTER COLUMN email TYPE VARCHAR(255);

-- ==================== W-10：删除加密列 LIKE 搜索 ====================
-- 实现位置（代码层）：ResignedEmployeeMapper.xml selectList / countTotal
--   - keyword 过滤移除 phone LIKE（加密列无法 LIKE：对密文模糊匹配必然失效，
--     且若先全表解密将引入性能与安全风险）
--   - keyword 仅保留 real_name / username 等非加密列
-- 说明：sys_user 表 UserMapper.xml 的 selectList/countTotal 同样存在
--       phone LIKE 隐患，属后续批次（本次范围仅限离职表，不越界修改）。

-- phone_hmac 盲索引设计（本次未实施，理由见下）：
--   后续若出现"按手机号等值查询"需求，启用方案：
--     ALTER TABLE sys_resigned_employee ADD COLUMN IF NOT EXISTS phone_hmac VARCHAR(64);
--     COMMENT ON COLUMN sys_resigned_employee.phone_hmac IS
--       '手机号 HMAC-SHA256 盲索引（应用层 盐+明文 计算，仅支持等值匹配，禁止模糊查询）';
--     CREATE INDEX IF NOT EXISTS idx_resigned_employee_phone_hmac
--       ON sys_resigned_employee(phone_hmac);
--   写入：应用层以固定盐 HMAC-SHA256(盐 || phone) 得 64 字符十六进制，insert 随行写入；
--   查询：应用层先算同一 HMAC 再等值匹配 phone_hmac，避免对密文列全表解密。
--   结论：当前 ResignedEmployeeMapper 无任何按 phone 等值查询的方法（仅有
--   keyword 模糊 / selectById / selectByUserId / insert），按最小实现原则
--   本次不加列，避免引入无调用方的死列；未来出现等值查询需求时按上述 DDL +
--   应用层计算启用即可。
