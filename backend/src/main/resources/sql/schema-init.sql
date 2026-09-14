-- ============================================================
-- 工单审批流转系统 — 初始化 Schema（幂等版本）
-- ------------------------------------------------------------
-- 本文件为非 Flyway 方式的完整初始化脚本，适用于手动部署或
-- 数据库重建场景。所有 DDL 使用 IF NOT EXISTS，所有 DML
-- 使用 ON CONFLICT DO NOTHING，可重复执行。
--
-- 与 Flyway 迁移等价：
--   V1__init.sql                  → 全部表结构 + 初始数据
--   V2__add_password_history_indexes.sql → 密码历史复合索引
--   V3__add_audit_indexes.sql     → 审计日志查询索引
--
-- 生产环境推荐使用 Flyway（backend/src/main/resources/db/migration/）
-- 本文件作为备选/参考，二者保持内容同步。
-- ============================================================

SET client_encoding = 'UTF-8';

CREATE TABLE IF NOT EXISTS sys_department (
    id SERIAL PRIMARY KEY,
    dept_name VARCHAR(100) NOT NULL,
    dept_code VARCHAR(50) UNIQUE NOT NULL,
    parent_id BIGINT DEFAULT 0,
    org_level SMALLINT DEFAULT 4,
    sort_order INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    description TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_position (
    id SERIAL PRIMARY KEY,
    position_name VARCHAR(100) NOT NULL,
    position_code VARCHAR(50) UNIQUE NOT NULL,
    dept_id BIGINT DEFAULT 0,
    org_level SMALLINT DEFAULT 4,
    sort_order INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    description TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_permission (
    id SERIAL PRIMARY KEY,
    permission_name VARCHAR(100) NOT NULL,
    permission_code VARCHAR(100) UNIQUE NOT NULL,
    permission_type VARCHAR(20) DEFAULT 'menu',
    parent_id BIGINT DEFAULT 0,
    sort_order INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    description TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_role (
    id SERIAL PRIMARY KEY,
    role_name VARCHAR(100) NOT NULL,
    role_code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    status SMALLINT DEFAULT 1,
    org_level SMALLINT DEFAULT 4,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_user (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(100),
    email VARCHAR(100),
    phone VARCHAR(20),
    department VARCHAR(100),
    status SMALLINT DEFAULT 1,
    avatar VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    position_id BIGINT,
    department_id BIGINT,
    superior_id BIGINT,
    org_level SMALLINT DEFAULT 4,
    hire_date VARCHAR(10),
    password_change_time TIMESTAMP,
    employee_id VARCHAR(50),
    password_changed BOOLEAN DEFAULT FALSE,
    login_failure_count SMALLINT DEFAULT 0,
    lock_time TIMESTAMP,
    last_login_time TIMESTAMP,
    last_login_ip VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id SERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS sys_user_position (
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_position UNIQUE (user_id, position_id)
);

-- ============================================================
-- 密码历史表（C-08 修复：UserMapper.xml 引用但原 schema 缺失）
-- 用于 PasswordPolicyService.validatePasswordNotReused 防止密码重复使用
-- 字段 change_time 与 UserMapper.xml INSERT/ORDER BY 一致
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_password_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    change_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_password_history_user_id ON sys_password_history(user_id);
CREATE INDEX IF NOT EXISTS idx_password_history_change_time ON sys_password_history(change_time);

CREATE TABLE IF NOT EXISTS work_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(50) UNIQUE NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    order_type VARCHAR(30) DEFAULT 'leave',
    applicant_id BIGINT NOT NULL,
    applicant_name VARCHAR(100),
    department VARCHAR(100),
    priority SMALLINT DEFAULT 1,
    status VARCHAR(30) DEFAULT 'DRAFT',
    process_instance_id VARCHAR(100),
    process_definition_id VARCHAR(100),
    current_node VARCHAR(100),
    current_assignee VARCHAR(50),
    current_assignee_name VARCHAR(100),
    attachment_url VARCHAR(500),
    remark TEXT,
    submit_time TIMESTAMP,
    complete_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS work_order_comment (
    id BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_name VARCHAR(100),
    content TEXT NOT NULL,
    comment_type VARCHAR(20) DEFAULT 'comment',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS approval_log (
    id BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL,
    order_no VARCHAR(64),
    process_instance_id VARCHAR(64),
    task_id VARCHAR(64),
    task_name VARCHAR(100),
    operator_id BIGINT NOT NULL,
    operator_name VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,
    comment TEXT,
    node_status VARCHAR(20),
    before_status VARCHAR(20),
    after_status VARCHAR(20),
    duration BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_approval_log_work_order_id ON approval_log(work_order_id);
CREATE INDEX IF NOT EXISTS idx_approval_log_process_instance_id ON approval_log(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_approval_log_operator_id ON approval_log(operator_id);
CREATE INDEX IF NOT EXISTS idx_approval_log_create_time ON approval_log(create_time);

CREATE TABLE IF NOT EXISTS sys_resigned_employee (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    username VARCHAR(50) NOT NULL,
    real_name VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    phone VARCHAR(20),
    department VARCHAR(100),
    department_id BIGINT,
    position_id BIGINT,
    position_name VARCHAR(100),
    org_level SMALLINT,
    hire_date DATE,
    resign_date DATE DEFAULT CURRENT_DATE,
    resign_type SMALLINT DEFAULT 1,
    resign_reason TEXT,
    remark TEXT,
    operator_id BIGINT,
    operator_name VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resigned_employee_user_id ON sys_resigned_employee(user_id);
CREATE INDEX IF NOT EXISTS idx_resigned_employee_department_id ON sys_resigned_employee(department_id);
CREATE INDEX IF NOT EXISTS idx_resigned_employee_resign_date ON sys_resigned_employee(resign_date);

CREATE TABLE IF NOT EXISTS sys_security_audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(50),
    action_type VARCHAR(50) NOT NULL,
    description VARCHAR(200),
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    request_url VARCHAR(200),
    status VARCHAR(20),
    details JSONB,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_system_setting (
    id SERIAL PRIMARY KEY,
    group_key VARCHAR(50) NOT NULL DEFAULT 'basic',
    setting_key VARCHAR(100) UNIQUE NOT NULL,
    setting_value TEXT,
    description VARCHAR(200),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 初始数据（全部 ON CONFLICT DO NOTHING，可重复执行）
-- ============================================================

INSERT INTO sys_department (id, dept_name, dept_code, org_level, sort_order) VALUES
(1, '总经办', 'GENERAL_OFFICE', 1, 1),
(2, '人事部', 'HR', 3, 2),
(3, '财务部', 'FINANCE', 3, 3),
(4, '研发部', 'R&D', 2, 4),
(5, '销售部', 'SALES', 2, 5),
(6, '行政部', 'ADMIN', 3, 6),
(7, '采购部', 'PROCUREMENT', 3, 7),
(8, '运维部', 'OPS', 3, 8)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_position (id, position_name, position_code, dept_id, org_level) VALUES
(1, '超级管理员', 'SUPER_ADMIN', 0, 0),
(2, '安全审计管理员', 'SECURITY_AUDIT', 0, 0),
(10, '董事长', 'CHAIRMAN', 1, 1),
(11, '总经理', 'GM', 1, 1),
(12, '副总经理', 'VP', 1, 1),
(20, '研发总监', 'RD_DIR', 4, 2),
(21, '销售总监', 'SALES_DIR', 5, 2),
(22, '财务总监', 'FIN_DIR', 3, 2),
(23, '行政总监', 'ADMIN_DIR', 6, 2),
(24, '人事总监', 'HR_DIR', 2, 2),
(25, '采购总监', 'PROCUREMENT_DIR', 7, 2),
(30, 'HR人事专员', 'HR_SPEC', 2, 3),
(31, '行政专员', 'ADMIN_SPEC', 6, 3),
(32, '费用会计专员', 'ACCOUNTANT_SPEC', 3, 3),
(33, '出纳专员', 'CASHIER_SPEC', 3, 3),
(34, '销售专员', 'SALES_SPEC', 5, 3),
(35, '研发工程师专员', 'RD_ENGINEER_SPEC', 4, 3),
(36, '采购专员', 'PROCUREMENT_SPEC', 7, 3),
(40, '普通员工', 'STAFF', 0, 4)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_permission (id, permission_name, permission_code, sort_order, status) VALUES
(1, '用户管理', 'system:user', 1, 1),
(2, '角色管理', 'system:role', 2, 1),
(3, '部门管理', 'system:dept', 3, 1),
(4, '职位管理', 'system:position', 4, 1),
(5, '创建工单', 'workorder:create', 5, 1),
(6, '审批工单', 'workorder:approve', 6, 1),
(7, '查看全部工单', 'workorder:view-all', 7, 1),
(8, '查看我的工单', 'workorder:view-own', 8, 1),
(9, '提交工单', 'workorder:submit', 9, 1),
(10, '我的工单', 'workorder:mine', 10, 1),
(11, '待办工单', 'workorder:pending', 11, 1),
(12, '撤回工单', 'workorder:withdraw', 12, 1),
(13, '重新提交', 'workorder:resubmit', 13, 1),
(14, '终止工单', 'workorder:terminate', 14, 1),
(15, '归档工单', 'workorder:archive', 15, 1),
(16, '删除工单', 'workorder:delete', 16, 1),
(17, '查看员工管理', 'employee:view', 20, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_role (id, role_name, role_code, org_level, status) VALUES
(1, '超级管理员', 'SUPER_ADMIN', 0, 1),
(2, '安全审计管理员', 'SECURITY_AUDIT', 0, 1),
(10, '董事长', 'CHAIRMAN', 1, 1),
(11, '总经理', 'GM', 1, 1),
(12, '副总经理', 'VP', 1, 1),
(20, '研发总监', 'RD_DIR', 2, 1),
(21, '销售总监', 'SALES_DIR', 2, 1),
(22, '财务总监', 'FIN_DIR', 2, 1),
(23, '行政总监', 'ADMIN_DIR', 2, 1),
(24, '人事总监', 'HR_DIR', 2, 1),
(25, '采购总监', 'PROCUREMENT_DIR', 2, 1),
(30, 'HR人事专员', 'HR_SPEC', 3, 1),
(31, '行政专员', 'ADMIN_SPEC', 3, 1),
(32, '费用会计专员', 'ACCOUNTANT_SPEC', 3, 1),
(33, '出纳专员', 'CASHIER_SPEC', 3, 1),
(34, '销售专员', 'SALES_SPEC', 3, 1),
(35, '研发工程师专员', 'RD_ENGINEER_SPEC', 3, 1),
(36, '采购专员', 'PROCUREMENT_SPEC', 3, 1),
(40, '普通员工', 'STAFF', 4, 1)
ON CONFLICT (id) DO NOTHING;

-- 超级管理员：全部权限（含 employee:view）
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT 1, id, NOW() FROM sys_permission WHERE status = 1
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 安全审计管理员：审计只读权限（查看全部工单 + 员工管理查看）
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT 2, p.id, NOW() FROM sys_permission p
WHERE p.permission_code IN ('workorder:view-all', 'employee:view')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 高管层角色（董事长/总经理/副总经理）：查看+审批+员工管理权限
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT r.id, p.id, NOW()
FROM (SELECT 10 AS id UNION SELECT 11 UNION SELECT 12) r
CROSS JOIN sys_permission p
WHERE p.permission_code IN (
    'workorder:create', 'workorder:approve', 'workorder:view-all',
    'workorder:view-own', 'workorder:submit', 'workorder:mine',
    'workorder:pending', 'workorder:withdraw', 'workorder:resubmit',
    'employee:view'
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 总监层角色（研发/销售/财务/行政/人事/采购总监）：查看+审批+员工管理权限
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT r.id, p.id, NOW()
FROM (SELECT 20 AS id UNION SELECT 21 UNION SELECT 22 UNION SELECT 23
      UNION SELECT 24 UNION SELECT 25) r
CROSS JOIN sys_permission p
WHERE p.permission_code IN (
    'workorder:create', 'workorder:approve', 'workorder:view-all',
    'workorder:view-own', 'workorder:submit', 'workorder:mine',
    'workorder:pending', 'workorder:withdraw', 'workorder:resubmit',
    'employee:view'
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 专员角色（HR/行政/财务/销售/研发/采购）：基础操作权限（无审批权限）
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT r.id, p.id, NOW()
FROM (SELECT 30 AS id UNION SELECT 31 UNION SELECT 32 UNION SELECT 33
      UNION SELECT 34 UNION SELECT 35 UNION SELECT 36) r
CROSS JOIN sys_permission p
WHERE p.permission_code IN (
    'workorder:create', 'workorder:view-own', 'workorder:submit',
    'workorder:mine', 'workorder:pending', 'workorder:withdraw', 'workorder:resubmit'
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 普通员工角色：提交和查看自己的工单
INSERT INTO sys_role_permission (role_id, permission_id, create_time)
SELECT 40, id, NOW() FROM sys_permission
WHERE permission_code IN (
    'workorder:create', 'workorder:view-own', 'workorder:submit',
    'workorder:mine', 'workorder:pending', 'workorder:withdraw', 'workorder:resubmit'
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 默认管理员（KLord / 123456）— 首次登录强制改密
INSERT INTO sys_user (id, username, password, real_name, email, employee_id, org_level, department_id, position_id, status, password_changed) VALUES
(1, 'KLord', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'KLord Administrator', 'klord@company.com', '000001', 0, 1, 1, 1, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES (1, 1, NOW())
ON CONFLICT (user_id, role_id) DO NOTHING;
INSERT INTO sys_user_position (user_id, position_id, create_time) VALUES (1, 1, NOW())
ON CONFLICT (user_id, position_id) DO NOTHING;

INSERT INTO sys_system_setting (group_key, setting_key, setting_value, description) VALUES
('security', 'min_password_length', '8', '密码最小长度'),
('security', 'require_lowercase', 'false', '是否需要小写字母'),
('security', 'require_uppercase', 'false', '是否需要大写字母'),
('security', 'require_digit', 'false', '是否需要数字'),
('security', 'require_special_char', 'false', '是否需要特殊字符'),
('security', 'password_expiry_days', '0', '密码过期天数（0=永不过期）'),
('security', 'max_password_history', '5', '密码历史记录数（防止重复使用）'),
('basic', 'token_expiry_hours', '12', 'Token有效期（小时）'),
('security', 'max_login_attempts', '5', '最大登录失败次数'),
('security', 'lockout_duration', '10', '账户锁定时间（分钟）')
ON CONFLICT (setting_key) DO NOTHING;

-- ============================================================
-- 基础索引
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_sys_user_employee_id ON sys_user(employee_id);
CREATE INDEX IF NOT EXISTS idx_work_order_applicant_id ON work_order(applicant_id);
CREATE INDEX IF NOT EXISTS idx_work_order_status ON work_order(status);
CREATE INDEX IF NOT EXISTS idx_security_audit_log_create_time ON sys_security_audit_log(create_time);

-- ============================================================
-- V2: 密码历史复合索引（按用户查询最近 N 条密码历史）
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_password_history_user_time
    ON sys_password_history(user_id, change_time DESC);

-- ============================================================
-- V3: 审计日志查询索引（按用户/操作类型/IP 查询，异常检测）
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_audit_log_user_time
    ON sys_security_audit_log(user_id, create_time);

CREATE INDEX IF NOT EXISTS idx_audit_log_action_time
    ON sys_security_audit_log(action_type, create_time);

CREATE INDEX IF NOT EXISTS idx_audit_log_ip_time
    ON sys_security_audit_log(ip_address, create_time);
