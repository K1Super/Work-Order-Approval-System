# 数据库设计

> 来源：PROJECT_DOCUMENTATION.md 第 4 章「数据库设计」，仅重编号。

## 1. 数据库设计

### 1.1 数据库选型与命名规范

- **数据库**：PostgreSQL 13+
- **数据库名**：`work_order_system`
- **字符集**：UTF-8
- **时区**：Asia/Shanghai
- **表命名**：
  - 业务表：`sys_` 前缀（系统管理）或无前缀（工单核心）
  - Flowable 表：`act_` 前缀，由引擎自动创建
- **字段命名**：下划线命名法（`create_time`），Java 实体通过 `map-underscore-to-camel-case` 自动映射
- **主键策略**：PostgreSQL `SERIAL` / `BIGSERIAL` 自增
- **删除策略**：业务表统一使用逻辑删除（`is_deleted` 字段），不物理删除
- **审计字段**：每张业务表必须包含 `create_time`、`update_time`、`create_by`、`update_by`、`is_deleted`

### 1.2 数据库 ER 图

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   sys_user      │     │  sys_user_role   │     │   sys_role      │
├─────────────────┤     ├──────────────────┤     ├─────────────────┤
│ PK id           │◄────┤ PK id            │────►│ PK id           │
│    username     │     │ FK user_id       │     │    role_name    │
│    password     │     │ FK role_id       │     │    role_code    │
│    real_name    │     │    create_time   │     │    org_level    │
│    email        │     └──────────────────┘     └────────┬────────┘
│    phone        │                                       │
│    department   │     ┌──────────────────┐              │
│    position_id  │     │ sys_user_position│              │
│    department_id│     ├──────────────────┤              │
│    status       │◄────┤ FK user_id       │              │
│    org_level    │     │ FK position_id   │              │
└─────────────────┘     └──────────────────┘              │
        │                                                   │
        │         ┌──────────────────┐                      │
        │         │   sys_position   │                      │
        │         ├──────────────────┤                      │
        └────────►│ PK id            │                      │
                  │    position_name │                      │
                  │ FK dept_id       │                      │
                  └──────────────────┘                      │
                            │                               │
                  ┌─────────┴─────────┐                     │
                  │   sys_department  │                     │
                  ├───────────────────┤                     │
                  │ PK id             │                     │
                  │    dept_name      │                     │
                  │    dept_code      │                     │
                  └───────────────────┘                     │
                                                            │
                  ┌──────────────────────┐                  │
                  │ sys_role_permission  │                  │
                  ├──────────────────────┤                  │
                  │ PK id                │◄─────────────────┘
                  │ FK role_id           │
                  │ FK permission_id     │
                  └──────────────────────┘
                            │
                  ┌─────────┴─────────┐
                  │  sys_permission   │
                  ├───────────────────┤
                  │ PK id             │
                  │    permission_name│
                  │    permission_code│
                  │    permission_type│
                  │    url            │
                  │    method         │
                  └───────────────────┘

┌─────────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│   work_order    │────►│  approval_log    │     │ work_order_comment  │
├─────────────────┤     ├──────────────────┤     ├─────────────────────┤
│ PK id           │     │ PK id            │     │ PK id               │
│    order_no     │     │ FK work_order_id │     │ FK work_order_id    │
│    title        │     │    order_no      │     │ FK user_id          │
│    content      │     │    operator_id   │     │    content          │
│    order_type   │     │    operator_name │     │    comment_type     │
│ FK applicant_id │     │    action        │     │    create_time      │
│    status       │     │    comment       │     └─────────────────────┘
│    process_*    │     │    before_status │
│    current_*    │     │    after_status  │
└─────────────────┘     └──────────────────┘

┌─────────────────────────┐     ┌─────────────────────────┐
│  sys_resigned_employee  │     │  sys_password_history   │
├─────────────────────────┤     ├─────────────────────────┤
│ PK id                   │     │ PK id                   │
│ FK user_id              │     │ FK user_id              │
│    username             │     │    password_hash        │
│    real_name            │     │    change_time          │
│    email                │     └─────────────────────────┘
│    phone                │
│    department_id        │     ┌─────────────────────────┐
│    position_id          │     │ sys_password_reset_token│
│    resign_date          │     ├─────────────────────────┤
│    resign_type          │     │ PK id                   │
└─────────────────────────┘     │    token_hash           │
                                │ FK user_id              │
┌─────────────────────────┐     │    expiry_time          │
│  sys_security_audit_log │     │    used                 │
├─────────────────────────┤     │    used_time            │
│ PK id                   │     └─────────────────────────┘
│    user_id              │
│    action_type          │     ┌─────────────────────────┐
│    ip_address           │     │   sys_system_setting    │
│    request_url          │     ├─────────────────────────┤
│    details (JSONB)      │     │ PK id                   │
│    create_time          │     │    group_key            │
└─────────────────────────┘     │    setting_key          │
                                │    setting_value        │
                                └─────────────────────────┘
```

### 1.3 数据表详细设计

#### 1.3.1 sys_user（用户表/员工表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 用户 ID |
| employee_id | VARCHAR(50) | UNIQUE | 员工工号，6 位数字唯一标识 |
| username | VARCHAR(50) | UNIQUE NOT NULL | 登录用户名 |
| password | VARCHAR(255) | NOT NULL | BCrypt 哈希密码 |
| real_name | VARCHAR(100) | | 真实姓名 |
| email | VARCHAR(255) | | 邮箱（AES-GCM 加密存储） |
| phone | VARCHAR(255) | | 手机号（AES-GCM 加密存储） |
| department | VARCHAR(100) | | 部门名称（冗余字段） |
| department_id | BIGINT | | 所属部门 ID |
| position_id | BIGINT | | 职位 ID |
| superior_id | BIGINT | | 直属上级 ID |
| org_level | SMALLINT | DEFAULT 4 | 组织层级：0-超管，1-决策层，2-管理层，3-职能层，4-基层 |
| status | SMALLINT | DEFAULT 1 | 状态：0-禁用，1-启用，-1 已删除，-2 已离职 |
| hire_date | VARCHAR(10) | | 入职日期 |
| avatar | VARCHAR(500) | | 头像 URL |
| password_change_time | TIMESTAMP | | 密码最后修改时间 |
| password_changed | BOOLEAN | DEFAULT FALSE | 是否已修改初始密码 |
| login_failure_count | SMALLINT | DEFAULT 0 | 连续登录失败次数 |
| lock_time | TIMESTAMP | | 账户锁定时间 |
| last_login_time | TIMESTAMP | | 最后登录时间 |
| last_login_ip | VARCHAR(50) | | 最后登录 IP |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**关键索引**：

- `idx_sys_user_username`：用户名查询（登录）
- `idx_sys_user_employee_id`：工号查询
- `idx_sys_user_is_deleted`：逻辑删除过滤

#### 1.3.2 sys_role（角色表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 角色 ID |
| role_name | VARCHAR(100) | NOT NULL | 角色名称 |
| role_code | VARCHAR(50) | UNIQUE NOT NULL | 角色编码（如 SUPER_ADMIN） |
| description | TEXT | | 角色描述 |
| status | SMALLINT | DEFAULT 1 | 状态 |
| org_level | SMALLINT | DEFAULT 4 | 角色层级 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

#### 1.3.3 sys_permission（权限表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 权限 ID |
| permission_name | VARCHAR(100) | NOT NULL | 权限名称 |
| permission_code | VARCHAR(100) | UNIQUE NOT NULL | 权限编码（如 system:user） |
| permission_type | VARCHAR(20) | DEFAULT 'menu' | 类型：menu/button/api |
| parent_id | BIGINT | DEFAULT 0 | 父权限 ID |
| url | VARCHAR(255) | | 菜单路径或 API 路径 |
| method | VARCHAR(10) | | 请求方法（GET/POST/PUT/DELETE） |
| icon | VARCHAR(100) | | 菜单图标 |
| sort_order | INT | DEFAULT 0 | 排序 |
| status | SMALLINT | DEFAULT 1 | 状态 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

#### 1.3.4 sys_user_role（用户角色关联表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 主键 |
| user_id | BIGINT | NOT NULL | 用户 ID |
| role_id | BIGINT | NOT NULL | 角色 ID |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**约束**：`UNIQUE (user_id, role_id)`

#### 1.3.5 sys_role_permission（角色权限关联表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 主键 |
| role_id | BIGINT | NOT NULL | 角色 ID |
| permission_id | BIGINT | NOT NULL | 权限 ID |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**约束**：`UNIQUE (role_id, permission_id)`

#### 1.3.6 sys_department（部门表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 部门 ID |
| dept_name | VARCHAR(100) | NOT NULL | 部门名称 |
| dept_code | VARCHAR(50) | UNIQUE NOT NULL | 部门编码 |
| parent_id | BIGINT | DEFAULT 0 | 父部门 ID |
| org_level | SMALLINT | DEFAULT 4 | 组织层级 |
| sort_order | INT | DEFAULT 0 | 排序 |
| status | SMALLINT | DEFAULT 1 | 状态 |
| description | TEXT | | 描述 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

#### 1.3.7 sys_position（职位表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 职位 ID |
| position_name | VARCHAR(100) | NOT NULL | 职位名称 |
| position_code | VARCHAR(50) | UNIQUE NOT NULL | 职位编码 |
| dept_id | BIGINT | DEFAULT 0 | 所属部门 ID |
| org_level | SMALLINT | DEFAULT 4 | 组织层级 |
| sort_order | INT | DEFAULT 0 | 排序 |
| status | SMALLINT | DEFAULT 1 | 状态 |
| description | TEXT | | 描述 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

#### 1.3.8 work_order（工单表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 工单 ID |
| order_no | VARCHAR(50) | UNIQUE NOT NULL | 工单编号 |
| title | VARCHAR(200) | NOT NULL | 标题 |
| content | TEXT | | 内容 |
| order_type | VARCHAR(30) | DEFAULT 'leave' | 工单类型 |
| applicant_id | BIGINT | NOT NULL | 申请人 ID |
| applicant_name | VARCHAR(100) | | 申请人姓名 |
| department | VARCHAR(100) | | 申请部门 |
| department_id | BIGINT | | 申请部门 ID |
| priority | SMALLINT | DEFAULT 1 | 优先级：1-低，2-中，3-高，4-紧急 |
| status | VARCHAR(30) | DEFAULT 'DRAFT' | 状态：DRAFT/PENDING/APPROVED/REJECTED/ARCHIVED/TERMINATED |
| process_instance_id | VARCHAR(100) | | Flowable 流程实例 ID |
| process_definition_id | VARCHAR(100) | | Flowable 流程定义 ID |
| current_node | VARCHAR(100) | | 当前审批节点 |
| current_assignee | VARCHAR(50) | | 当前审批人 ID |
| current_assignee_name | VARCHAR(100) | | 当前审批人姓名 |
| attachment_url | VARCHAR(500) | | 附件 URL |
| remark | TEXT | | 备注 |
| submit_time | TIMESTAMP | | 提交时间 |
| complete_time | TIMESTAMP | | 完成时间 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**关键索引**：

- `uk_order_no`：工单编号唯一
- `idx_work_order_applicant_id`：按申请人查询
- `idx_work_order_status`：按状态查询
- `idx_work_order_status_type`：状态+类型复合查询
- `idx_work_order_is_deleted`：逻辑删除过滤

#### 1.3.9 approval_log（审批日志表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 日志 ID |
| work_order_id | BIGINT | NOT NULL | 工单 ID |
| order_no | VARCHAR(64) | | 工单编号 |
| process_instance_id | VARCHAR(64) | | 流程实例 ID |
| task_id | VARCHAR(64) | | 任务 ID |
| task_name | VARCHAR(100) | | 任务节点名称 |
| operator_id | BIGINT | NOT NULL | 操作人 ID |
| operator_name | VARCHAR(50) | NOT NULL | 操作人姓名 |
| action | VARCHAR(20) | NOT NULL | 操作类型 |
| comment | TEXT | | 审批意见 |
| node_status | VARCHAR(20) | | 节点状态 |
| before_status | VARCHAR(20) | | 操作前状态 |
| after_status | VARCHAR(20) | | 操作后状态 |
| duration | BIGINT | | 处理时长（毫秒） |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**关键索引**：

- `idx_approval_log_work_order_id`：按工单查询审批历史
- `idx_approval_log_process_instance_id`：按流程实例查询
- `idx_approval_log_operator_id`：按操作人查询
- `idx_approval_log_create_time`：按时间查询
- `idx_approval_log_action`：按操作类型统计

#### 1.3.10 work_order_comment（工单评论表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 评论 ID |
| work_order_id | BIGINT | NOT NULL | 工单 ID |
| user_id | BIGINT | NOT NULL | 用户 ID |
| user_name | VARCHAR(100) | | 用户姓名 |
| content | TEXT | NOT NULL | 评论内容 |
| comment_type | VARCHAR(20) | DEFAULT 'comment' | 评论类型 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

#### 1.3.11 sys_resigned_employee（离职员工档案表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 主键 |
| user_id | BIGINT | NOT NULL | 原用户 ID |
| username | VARCHAR(50) | NOT NULL | 用户名 |
| real_name | VARCHAR(50) | NOT NULL | 真实姓名 |
| email | VARCHAR(255) | | 邮箱（AES 加密） |
| phone | VARCHAR(255) | | 手机号（AES 加密） |
| department | VARCHAR(100) | | 部门 |
| department_id | BIGINT | | 部门 ID |
| position_id | BIGINT | | 职位 ID |
| position_name | VARCHAR(100) | | 职位名称 |
| org_level | SMALLINT | | 组织层级 |
| hire_date | DATE | | 入职日期 |
| resign_date | DATE | DEFAULT CURRENT_DATE | 离职日期 |
| resign_type | SMALLINT | DEFAULT 1 | 离职类型：1-主动辞职，2-被动辞退，3-合同到期，4-退休，5-其他 |
| resign_reason | TEXT | | 离职原因 |
| remark | TEXT | | 备注 |
| operator_id | BIGINT | | 操作人 ID |
| operator_name | VARCHAR(50) | | 操作人姓名 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**关键索引**：

- `idx_resigned_employee_user_id`：按原用户查询
- `idx_resigned_employee_department_id`：按部门统计
- `idx_resigned_employee_resign_date`：按离职日期统计
- `idx_sys_resigned_employee_is_del`：逻辑删除过滤

#### 1.3.12 sys_password_history（密码历史表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 主键 |
| user_id | BIGINT | NOT NULL | 用户 ID |
| password_hash | VARCHAR(255) | NOT NULL | 历史密码哈希 |
| change_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 修改时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**关键索引**：

- `idx_password_history_user_time`：复合索引 `(user_id, change_time DESC)`，用于查询最近 N 条密码历史

#### 1.3.13 sys_password_reset_token（密码重置令牌表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 主键 |
| token_hash | VARCHAR(64) | NOT NULL | Token 的 SHA-256 哈希 |
| user_id | BIGINT | NOT NULL | 目标用户 ID |
| operator_id | BIGINT | | 操作者 ID（管理员） |
| expiry_time | TIMESTAMP | NOT NULL | 过期时间 |
| used | BOOLEAN | DEFAULT FALSE | 是否已使用 |
| used_time | TIMESTAMP | | 使用时间 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**约束**：

- `UNIQUE (token_hash)`
- `FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE`

**关键索引**：

- `uk_password_reset_token_hash`：Token 哈希唯一
- `idx_password_reset_token_user`：按用户查询
- `idx_password_reset_token_expiry`：按过期时间清理

#### 1.3.14 sys_security_audit_log（安全审计日志表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 主键 |
| user_id | BIGINT | | 用户 ID（匿名操作可为空） |
| username | VARCHAR(50) | | 用户名 |
| action_type | VARCHAR(50) | NOT NULL | 操作类型 |
| description | VARCHAR(200) | | 描述 |
| ip_address | VARCHAR(50) | | 客户端 IP |
| user_agent | VARCHAR(500) | | User-Agent |
| request_url | VARCHAR(200) | | 请求 URL |
| status | VARCHAR(20) | | 状态：SUCCESS/FAILURE/WARNING |
| details | JSONB | | 扩展详情（JSON 格式） |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

**关键索引**：

- `idx_security_audit_log_create_time`：按时间查询
- `idx_audit_log_user_time`：`(user_id, create_time)`
- `idx_audit_log_action_time`：`(action_type, create_time)`
- `idx_audit_log_ip_time`：`(ip_address, create_time)`，用于异常检测

#### 1.3.15 sys_system_setting（系统设置表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | SERIAL | PRIMARY KEY | 主键 |
| group_key | VARCHAR(50) | DEFAULT 'basic' | 设置分组 |
| setting_key | VARCHAR(100) | UNIQUE NOT NULL | 设置键 |
| setting_value | TEXT | | 设置值 |
| description | VARCHAR(200) | | 描述 |
| create_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |
| is_deleted | SMALLINT | DEFAULT 0 | 逻辑删除标识 |
| create_by | BIGINT | | 创建人 ID |
| update_by | BIGINT | | 更新人 ID |

### 1.4 数据字典

#### 1.4.1 用户状态（sys_user.status）

| 值 | 含义 |
|----|------|
| -2 | 已离职（迁移到 sys_resigned_employee） |
| -1 | 已删除（历史遗留） |
| 0 | 禁用 |
| 1 | 启用 |

#### 1.4.2 组织层级（org_level）

| 值 | 层级 | 典型角色 |
|----|------|----------|
| 0 | 超级管理员 | SUPER_ADMIN、SECURITY_AUDIT |
| 1 | 决策层 | 董事长、总经理、副总经理 |
| 2 | 管理层 | 各总监（研发总监、财务总监等） |
| 3 | 职能层 | 专员（HR 专员、财务专员等） |
| 4 | 基层 | 普通员工 |

#### 1.4.3 工单状态（work_order.status）

| 值 | 含义 |
|----|------|
| DRAFT | 草稿 |
| PENDING | 审批中 |
| APPROVED | 已通过 |
| REJECTED | 已驳回 |
| ARCHIVED | 已归档 |
| TERMINATED | 已终止 |

#### 1.4.4 工单类型（work_order.order_type）

| 值 | 含义 |
|----|------|
| leave | 请假 |
| overtime | 加班 |
| reimbursement | 报销 |
| purchase | 采购 |
| repair | 维修 |
| supply | 物资领用 |
| business | 通用业务 |
| other | 其他 |

#### 1.4.5 审批操作类型（approval_log.action）

| 值 | 含义 |
|----|------|
| SUBMIT | 提交 |
| APPROVE | 通过 |
| REJECT | 驳回 |
| RETURN | 退回 |
| ARCHIVE | 归档 |
| TERMINATE | 终止 |
| RESUBMIT | 重新提交 |
| TRANSFER | 转办 |

#### 1.4.6 离职类型（sys_resigned_employee.resign_type）

| 值 | 含义 |
|----|------|
| 1 | 主动辞职 |
| 2 | 被动辞退 |
| 3 | 合同到期 |
| 4 | 退休 |
| 5 | 其他 |

### 1.5 Flyway 数据库迁移

数据库 schema 通过 Flyway 版本化管理，脚本位于 `backend/src/main/resources/db/migration/`。

| 版本 | 文件 | 说明 |
|------|------|------|
| V1 | `V1__init.sql` | 初始 schema，创建所有业务表、初始数据、默认管理员 |
| V2 | `V2__add_password_history_indexes.sql` | 密码历史表索引补全与字段兼容 |
| V3 | `V3__add_audit_indexes.sql` | 审计日志索引补全 |
| V4 | `V4__add_password_reset_token.sql` | 密码重置令牌表 |
| V5 | `V5__extend_encrypted_columns.sql` | 扩展 phone/email 字段长度以容纳 AES 密文 |
| V6 | `V6__add_audit_fields.sql` | 为所有表补齐审计字段 |

**迁移配置**（`application.yml`）：

```yaml
spring.flyway:
  enabled: true
  locations: classpath:db/migration
  baseline-on-migrate: true
  baseline-version: 0
  validate-on-migrate: false
```

### 1.6 Flowable 引擎表

Flowable 6.8.0 启动时自动创建以下表族（以 `act_` 为前缀）：

| 表族 | 说明 |
|------|------|
| ACT_RE_* | Repository 表，存储流程定义、部署信息 |
| ACT_RU_* | Runtime 表，存储运行中的流程实例、任务、变量、Job |
| ACT_HI_* | History 表，存储历史流程实例、任务、变量、活动 |
| ACT_ID_* | Identity 表，用户/组信息（本项目禁用，使用 sys_user/sys_role） |
| ACT_GE_* | General 表，通用数据与属性 |