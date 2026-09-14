-- ============================================================
-- ⚠️ 已废弃 — 请勿在生产环境使用此文件
-- ------------------------------------------------------------
-- 本文件为早期开发阶段的初始化脚本，包含 DROP TABLE 等危险操作，
-- 不适用于生产环境部署。
--
-- 生产环境请使用：
--   backend/src/main/resources/sql/schema-init.sql
-- （幂等设计，支持增量迁移，参考 backend/src/main/resources/db/migration/ 下的 Flyway 脚本）
--
-- 废弃日期：2026-07
-- 替代文件：backend/src/main/resources/sql/schema-init.sql
-- ============================================================

DROP TABLE IF EXISTS sys_user CASCADE;
CREATE TABLE sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    phone VARCHAR(20),
    department VARCHAR(100),
    status SMALLINT NOT NULL DEFAULT 1, -- 状态：0-禁用 1-启用
    avatar VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_username ON sys_user (username);
COMMENT ON TABLE sys_user IS '用户表';
COMMENT ON COLUMN sys_user.id IS '用户ID';
COMMENT ON COLUMN sys_user.username IS '用户名';
COMMENT ON COLUMN sys_user.password IS '密码（BCrypt加密）';
COMMENT ON COLUMN sys_user.real_name IS '真实姓名';
COMMENT ON COLUMN sys_user.email IS '邮箱';
COMMENT ON COLUMN sys_user.phone IS '手机号';
COMMENT ON COLUMN sys_user.department IS '部门';
COMMENT ON COLUMN sys_user.status IS '状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_user.avatar IS '头像URL';
COMMENT ON COLUMN sys_user.create_time IS '创建时间';
COMMENT ON COLUMN sys_user.update_time IS '更新时间';

-- 自动更新update_time触发器函数
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS '
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
' LANGUAGE plpgsql;

-- 为sys_user表创建更新时间戳触发器
DROP TRIGGER IF EXISTS trigger_update_user_time ON sys_user;
CREATE TRIGGER trigger_update_user_time
    BEFORE UPDATE ON sys_user
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- ============================================================
-- 2. 角色表
-- ============================================================
DROP TABLE IF EXISTS sys_role CASCADE;
CREATE TABLE sys_role (
    id BIGSERIAL PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    status SMALLINT NOT NULL DEFAULT 1, -- 状态：0-禁用 1-启用
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_role_code ON sys_role (role_code);
COMMENT ON TABLE sys_role IS '角色表';
COMMENT ON COLUMN sys_role.id IS '角色ID';
COMMENT ON COLUMN sys_role.role_name IS '角色名称';
COMMENT ON COLUMN sys_role.role_code IS '角色编码';
COMMENT ON COLUMN sys_role.description IS '角色描述';
COMMENT ON COLUMN sys_role.status IS '状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_role.create_time IS '创建时间';
COMMENT ON COLUMN sys_role.update_time IS '更新时间';

-- 为sys_role表创建更新时间戳触发器
DROP TRIGGER IF EXISTS trigger_update_role_time ON sys_role;
CREATE TRIGGER trigger_update_role_time
    BEFORE UPDATE ON sys_role
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- ============================================================
-- 3. 权限表（菜单/按钮级权限）
-- ============================================================
DROP TABLE IF EXISTS sys_permission CASCADE;
CREATE TABLE sys_permission (
    id BIGSERIAL PRIMARY KEY,
    permission_name VARCHAR(100) NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    permission_type SMALLINT NOT NULL DEFAULT 1, -- 类型：1-菜单 2-按钮 3-API接口
    parent_id BIGINT DEFAULT 0,
    url VARCHAR(255),
    method VARCHAR(10),
    icon VARCHAR(100),
    sort_order INT DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 1, -- 状态：0-禁用 1-启用
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_permission_code ON sys_permission (permission_code);
COMMENT ON TABLE sys_permission IS '权限表';
COMMENT ON COLUMN sys_permission.id IS '权限ID';
COMMENT ON COLUMN sys_permission.permission_name IS '权限名称';
COMMENT ON COLUMN sys_permission.permission_code IS '权限编码';
COMMENT ON COLUMN sys_permission.permission_type IS '类型：1-菜单 2-按钮 3-API接口';
COMMENT ON COLUMN sys_permission.parent_id IS '父权限ID';
COMMENT ON COLUMN sys_permission.url IS '菜单路径/API路径';
COMMENT ON COLUMN sys_permission.method IS '请求方法（GET/POST/PUT/DELETE）';
COMMENT ON COLUMN sys_permission.icon IS '图标';
COMMENT ON COLUMN sys_permission.sort_order IS '排序';
COMMENT ON COLUMN sys_permission.status IS '状态：0-禁用 1-启用';
COMMENT ON COLUMN sys_permission.create_time IS '创建时间';
COMMENT ON COLUMN sys_permission.update_time IS '更新时间';

-- 为sys_permission表创建更新时间戳触发器
DROP TRIGGER IF EXISTS trigger_update_permission_time ON sys_permission;
CREATE TRIGGER trigger_update_permission_time
    BEFORE UPDATE ON sys_permission
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- ============================================================
-- 4. 用户角色关联表（多对多）
-- ============================================================
DROP TABLE IF EXISTS sys_user_role CASCADE;
CREATE TABLE sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_user_role ON sys_user_role (user_id, role_id);
CREATE INDEX idx_ur_user_id ON sys_user_role (user_id);
CREATE INDEX idx_ur_role_id ON sys_user_role (role_id);
COMMENT ON TABLE sys_user_role IS '用户角色关联表';
COMMENT ON COLUMN sys_user_role.id IS '主键ID';
COMMENT ON COLUMN sys_user_role.user_id IS '用户ID';
COMMENT ON COLUMN sys_user_role.role_id IS '角色ID';
COMMENT ON COLUMN sys_user_role.create_time IS '创建时间';

-- ============================================================
-- 5. 角色权限关联表（多对多）
-- ============================================================
DROP TABLE IF EXISTS sys_role_permission CASCADE;
CREATE TABLE sys_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_role_permission ON sys_role_permission (role_id, permission_id);
CREATE INDEX idx_rp_role_id ON sys_role_permission (role_id);
CREATE INDEX idx_rp_permission_id ON sys_role_permission (permission_id);
COMMENT ON TABLE sys_role_permission IS '角色权限关联表';
COMMENT ON COLUMN sys_role_permission.id IS '主键ID';
COMMENT ON COLUMN sys_role_permission.role_id IS '角色ID';
COMMENT ON COLUMN sys_role_permission.permission_id IS '权限ID';
COMMENT ON COLUMN sys_role_permission.create_time IS '创建时间';

-- ============================================================
-- 6. 工单表（核心业务表）
-- ============================================================
DROP TABLE IF EXISTS work_order CASCADE;
CREATE TABLE work_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    order_type VARCHAR(50) NOT NULL, -- 工单类型：leave-请假 purchase-采购 reimbursement-报销 other-其他
    applicant_id BIGINT NOT NULL,
    applicant_name VARCHAR(50) NOT NULL,
    department VARCHAR(100),
    priority SMALLINT NOT NULL DEFAULT 1, -- 优先级：1-低 2-中 3-高 4-紧急
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- 状态：DRAFT-草稿 PENDING-审批中 APPROVED-已通过 REJECTED-已驳回 ARCHIVED-已归档 TERMINATED-已终止
    process_instance_id VARCHAR(64), -- 流程实例ID（Flowable）
    process_definition_id VARCHAR(64), -- 流程定义ID（Flowable）
    current_node VARCHAR(100), -- 当前审批节点
    current_assignee BIGINT, -- 当前审批人ID
    current_assignee_name VARCHAR(50), -- 当前审批人姓名
    attachment_url VARCHAR(500),
    remark VARCHAR(500),
    submit_time TIMESTAMP,
    complete_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_order_no ON work_order (order_no);
CREATE INDEX idx_wo_applicant_id ON work_order (applicant_id);
CREATE INDEX idx_wo_status ON work_order (status);
CREATE INDEX idx_wo_process_instance_id ON work_order (process_instance_id);
CREATE INDEX idx_wo_create_time ON work_order (create_time);
CREATE INDEX idx_work_order_status_type ON work_order (status, order_type);
COMMENT ON TABLE work_order IS '工单表';
COMMENT ON COLUMN work_order.id IS '工单ID';
COMMENT ON COLUMN work_order.order_no IS '工单编号';
COMMENT ON COLUMN work_order.title IS '工单标题';
COMMENT ON COLUMN work_order.content IS '工单内容';
COMMENT ON COLUMN work_order.order_type IS '工单类型';
COMMENT ON COLUMN work_order.applicant_id IS '申请人ID';
COMMENT ON COLUMN work_order.applicant_name IS '申请人姓名';
COMMENT ON COLUMN work_order.department IS '申请部门';
COMMENT ON COLUMN work_order.priority IS '优先级：1-低 2-中 3-高 4-紧急';
COMMENT ON COLUMN work_order.status IS '状态';
COMMENT ON COLUMN work_order.process_instance_id IS '流程实例ID（Flowable）';
COMMENT ON COLUMN work_order.process_definition_id IS '流程定义ID（Flowable）';
COMMENT ON COLUMN work_order.current_node IS '当前审批节点';
COMMENT ON COLUMN work_order.current_assignee IS '当前审批人ID';
COMMENT ON COLUMN work_order.current_assignee_name IS '当前审批人姓名';
COMMENT ON COLUMN work_order.attachment_url IS '附件URL';
COMMENT ON COLUMN work_order.remark IS '备注';
COMMENT ON COLUMN work_order.submit_time IS '提交时间';
COMMENT ON COLUMN work_order.complete_time IS '完成时间';
COMMENT ON COLUMN work_order.create_time IS '创建时间';
COMMENT ON COLUMN work_order.update_time IS '更新时间';

-- 为work_order表创建更新时间戳触发器
DROP TRIGGER IF EXISTS trigger_update_workorder_time ON work_order;
CREATE TRIGGER trigger_update_workorder_time
    BEFORE UPDATE ON work_order
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- ============================================================
-- 7. 审批日志表（流程追溯核心）
-- ============================================================
DROP TABLE IF EXISTS approval_log CASCADE;
CREATE TABLE approval_log (
    id BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    process_instance_id VARCHAR(64),
    task_id VARCHAR(64),
    task_name VARCHAR(100),
    operator_id BIGINT NOT NULL,
    operator_name VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL, -- 操作类型：SUBMIT-提交 APPROVE-通过 REJECT-驳回 RETURN-退回 ARCHIVE-归档 TERMINATE-终止 RESUBMIT-重新提交 TRANSFER-转办
    comment TEXT,
    node_status VARCHAR(20), -- 节点状态：PENDING-待处理 COMPLETED-已完成 REJECTED-已驳回
    before_status VARCHAR(20),
    after_status VARCHAR(20),
    duration BIGINT, -- 处理时长（毫秒）
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_al_work_order_id ON approval_log (work_order_id);
CREATE INDEX idx_al_process_instance_id ON approval_log (process_instance_id);
CREATE INDEX idx_al_operator_id ON approval_log (operator_id);
CREATE INDEX idx_al_create_time ON approval_log (create_time);
CREATE INDEX idx_approval_log_action ON approval_log (action, create_time);
COMMENT ON TABLE approval_log IS '审批日志表';
COMMENT ON COLUMN approval_log.id IS '日志ID';
COMMENT ON COLUMN approval_log.work_order_id IS '工单ID';
COMMENT ON COLUMN approval_log.order_no IS '工单编号';
COMMENT ON COLUMN approval_log.process_instance_id IS '流程实例ID';
COMMENT ON COLUMN approval_log.task_id IS '任务ID';
COMMENT ON COLUMN approval_log.task_name IS '任务节点名称';
COMMENT ON COLUMN approval_log.operator_id IS '操作人ID';
COMMENT ON COLUMN approval_log.operator_name IS '操作人姓名';
COMMENT ON COLUMN approval_log.action IS '操作类型';
COMMENT ON COLUMN approval_log.comment IS '审批意见';
COMMENT ON COLUMN approval_log.node_status IS '节点状态';
COMMENT ON COLUMN approval_log.before_status IS '操作前状态';
COMMENT ON COLUMN approval_log.after_status IS '操作后状态';
COMMENT ON COLUMN approval_log.duration IS '处理时长（毫秒）';
COMMENT ON COLUMN approval_log.create_time IS '创建时间';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 插入角色数据
INSERT INTO sys_role (role_name, role_code, description) VALUES
('超级管理员', 'ADMIN', '系统管理员，拥有所有权限'),
('普通员工', 'EMPLOYEE', '普通员工，可提交和查看自己的工单'),
('部门经理', 'MANAGER', '部门经理，负责审批部门内工单'),
('HR专员', 'HR', 'HR专员，负责人事相关审批'),
('财务专员', 'FINANCE', '财务专员，负责财务相关审批');

-- 插入权限数据（菜单+API）
INSERT INTO sys_permission (permission_name, permission_code, permission_type, parent_id, url, method, sort_order) VALUES
-- 一级菜单
('工单管理', 'workorder:manage', 1, 0, '/workorder', NULL, 1),
('系统管理', 'system:manage', 1, 0, '/system', NULL, 2),
('流程管理', 'process:manage', 1, 0, '/process', NULL, 3),

-- 二级菜单 - 工单管理
('我的工单', 'workorder:mine', 1, 1, '/workorder/mine', NULL, 1),
('待我审批', 'workorder:pending', 1, 1, '/workorder/pending', NULL, 2),
('提交工单', 'workorder:submit', 1, 1, '/workorder/create', NULL, 3),
('全部工单', 'workorder:list', 1, 1, '/workorder/list', NULL, 4),

-- 二级菜单 - 系统管理
('用户管理', 'system:user', 1, 2, '/system/user', NULL, 1),
('角色管理', 'system:role', 1, 2, '/system/role', NULL, 2),
('权限管理', 'system:permission', 1, 2, '/system/permission', NULL, 3),

-- API接口权限
('提交工单API', 'api:workorder:create', 3, 1, '/api/workorder', 'POST', 1),
('查询工单列表API', 'api:workorder:list', 3, 1, '/api/workorder/list', 'GET', 2),
('查询工单详情API', 'api:workorder:detail', 3, 1, '/api/workorder/{id}', 'GET', 3),
('审批操作API', 'api:workorder:approve', 3, 1, '/api/workorder/approve', 'POST', 4),
('驳回工单API', 'api:workorder:reject', 3, 1, '/api/workorder/reject', 'POST', 5),
('流程日志API', 'api:workorder:log', 3, 1, '/api/workorder/log/{orderId}', 'GET', 6);

-- 插入用户数据（密码为123456的BCrypt加密结果）
INSERT INTO sys_user (username, password, real_name, email, phone, department, status) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', 'admin@company.com', '13800000001', '技术部', 1),
('zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '张三', 'zhangsan@company.com', '13800000002', '研发部', 1),
('lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '李四', 'lisi@company.com', '13800000003', '研发部', 1),
('wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '王五', 'wangwu@company.com', '13800000004', '产品部', 1),
('zhaoliu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '赵六', 'zhaoliu@company.com', '13800000005', 'HR部', 1),
('sunqi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '孙七', 'sunqi@company.com', '13800000006', '财务部', 1);

-- 分配用户角色（admin拥有所有角色）
INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 1),  -- admin -> ADMIN
(2, 2),  -- zhangsan -> EMPLOYEE
(3, 3),  -- lisi -> MANAGER
(4, 2),  -- wangwu -> EMPLOYEE
(5, 4),  -- zhaoliu -> HR
(6, 5);  -- sunqi -> FINANCE

-- 分配角色权限（ADMIN拥有所有权限）
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10), (1, 11), (1, 12), (1, 13), (1, 14), (1, 15), (1, 16),
(2, 4), (2, 5), (2, 6), (2, 13),  -- EMPLOYEE
(3, 4), (3, 5), (3, 14), (3, 15),  -- MANAGER
(4, 4), (4, 5), (4, 13),  -- EMPLOYEE
(5, 4), (5, 5), (5, 14), (5, 15),  -- HR
(6, 4), (6, 5), (6, 14), (6, 15);  -- FINANCE

-- 重置序列值（确保自增ID从正确位置开始）
SELECT setval('sys_user_id_seq', (SELECT COALESCE(MAX(id), 0) FROM sys_user));
SELECT setval('sys_role_id_seq', (SELECT COALESCE(MAX(id), 0) FROM sys_role));
SELECT setval('sys_permission_id_seq', (SELECT COALESCE(MAX(id), 0) FROM sys_permission));
SELECT setval('sys_user_role_id_seq', (SELECT COALESCE(MAX(id), 0) FROM sys_user_role));
SELECT setval('sys_role_permission_id_seq', (SELECT COALESCE(MAX(id), 0) FROM sys_role_permission));

-- 输出完成信息（使用普通SQL代替DO块）
-- 数据库初始化完成！
-- 默认账号：admin / 123456
