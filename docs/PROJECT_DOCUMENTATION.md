# 工单审批流转系统 — 完整项目文档

> 文档版本：v1.0  
> 作者：KLord  
> 最后更新：2026-07-26  
> 适用范围：SpringBoot 2.7.18 + Vue 3.3.4 + Flowable 6.8.0 全栈架构

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈与运行环境](#2-技术栈与运行环境)
3. [系统架构设计](#3-系统架构设计)
4. [数据库设计](#4-数据库设计)
5. [后端详细设计](#5-后端详细设计)
6. [前端详细设计](#6-前端详细设计)
7. [安全架构](#7-安全架构)
8. [工作流引擎集成](#8-工作流引擎集成)
9. [API 接口规范](#9-api-接口规范)
10. [部署与运维](#10-部署与运维)
11. [开发规范与治理](#11-开发规范与治理)
12. [典型问题与根因分析](#12-典型问题与根因分析)
13. [附录](#13-附录)

---

## 1. 项目概述

### 1.1 项目定位

工单审批流转系统是一套面向企业内部使用的**流程驱动型审批平台**，核心目标是通过 BPMN 工作流引擎实现企业各类审批事务的电子化、标准化、可追溯化。

系统覆盖以下核心场景：

- **请假审批**：员工提交请假申请，经过部门主管、HR、总经理等节点审批。
- **采购审批**：采购申请经过部门经理、采购总监、财务审批。
- **报销审批**：费用报销经过直属上级、财务专员、财务总监审批。
- **通用审批**：支持自定义 BPMN 流程定义，快速适配企业新审批类型。
- **员工与组织架构管理**：支持部门、职位、角色、用户全生命周期管理，含离职归档。
- **安全审计**：记录登录、密码变更、审批操作等安全事件，满足合规审计要求。

### 1.2 核心能力矩阵

| 能力域 | 关键功能 |
|--------|----------|
| 工单管理 | 草稿、提交、审批、驳回、撤回、重新提交、终止、归档 |
| 流程引擎 | BPMN 部署、流程启动、任务认领、并行会签、委派、状态同步 |
| 权限控制 | RBAC 接口级授权 + 数据级权限（部门/个人范围） |
| 安全体系 | JWT 认证、HMAC 请求签名、AES 字段加密、BCrypt 密码哈希、XSS/SQL 注入防御 |
| 组织架构 | 部门、职位、角色、员工、上级关系、离职档案 |
| 审计合规 | 审批日志、安全审计日志、操作人/时间全记录 |
| 系统治理 | 启动硬断校验、Flyway 数据库版本迁移、Actuator 监控 |

### 1.3 项目目录概览

```
Work Order Approval System/
├── backend/                          # 后端工程（SpringBoot）
│   ├── src/main/java/com/workorder/  # Java 源码
│   ├── src/main/resources/           # 配置文件、Mapper XML、BPMN、Flyway 脚本
│   └── pom.xml                       # Maven 依赖
├── frontend/                         # 前端工程（Vue 3 + Vite）
│   ├── src/                          # 源码
│   ├── public/
│   └── package.json
├── sql/                              # 早期开发脚本（已废弃，仅作参考）
├── docs/                             # 项目文档
│   ├── ARCHITECTURE.md               # 架构设计文档
│   └── PROJECT_DOCUMENTATION.md      # 本完整项目文档
└── .github/workflows/                # CI/CD 流水线
```

---

## 2. 技术栈与运行环境

### 2.1 后端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.7.18 | 应用容器、自动装配、REST 服务 |
| Spring Security | 5.7.10 | 认证、授权、过滤器链 |
| Flowable | 6.8.0 | BPMN 工作流引擎 |
| MyBatis Spring Boot Starter | 2.3.1 | ORM 数据访问 |
| PostgreSQL | 13+ | 关系型数据库 |
| Redis | 6+ | 缓存、Session、Nonce 防重放 |
| JWT (jjwt) | 0.11.x | 无状态认证令牌 |
| Flyway | 8.x | 数据库版本迁移 |
| PageHelper | — | 分页插件 |
| Jsoup | — | HTML 输入净化 |
| Maven | 3.8+ | 构建工具 |

### 2.2 前端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.3.4 | 前端框架 |
| Vue Router | 4.2.4 | 路由管理 |
| Pinia | 2.1.7 | 全局状态管理 |
| Element Plus | 2.3.14 | UI 组件库 |
| Vite | 4.5.14 | 构建与开发服务器 |
| Axios | — | HTTP 请求 |
| DOMPurify | — | XSS 净化 |
| SCSS | — | 样式预处理器 |

### 2.3 运行环境要求

| 环境 | 要求 |
|------|------|
| 操作系统 | Windows 10/11（项目目标平台）、Linux Server |
| JDK | OpenJDK 17+ |
| Node.js | 18 LTS+ |
| 数据库 | PostgreSQL 13+ |
| 缓存 | Redis 6+ |
| 浏览器 | Chrome 90+、Edge 90+、Firefox 88+ |

### 2.4 默认连接信息（开发环境）

| 组件 | 地址/账号 |
|------|-----------|
| 后端服务 | http://localhost:8080/api |
| 前端开发服务器 | http://localhost:5173 |
| 数据库 | work_order_system / postgres / 666666 / 5432 |
| 默认管理员 | KLord / 123456 |
| 管理员密码哈希 | `$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi` |

---

## 3. 系统架构设计

### 3.1 总体架构

系统采用经典的前后分离架构，前端为 Vue 3 SPA，后端为 SpringBoot REST API，数据持久化使用 PostgreSQL，工作流引擎 Flowable 与业务服务同进程部署。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              客户端层                                    │
│  浏览器 / Edge / Chrome                                                  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ HTTPS / HTTP (dev)
┌──────────────────────────────▼──────────────────────────────────────────┐
│                              前端层                                      │
│  Vue 3 SPA + Pinia + Vue Router + Element Plus + Axios                  │
│  - JWT 管理 / HMAC 签名 / 动态路由 / XSS 净化指令                        │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ REST API (JSON + JWT + HMAC)
┌──────────────────────────────▼──────────────────────────────────────────┐
│                              网关/安全层                                 │
│  Nginx 反向代理（生产）+ EnterpriseSecurityFilter + JwtAuthenticationFilter│
│  - XSS 清洗 / 限流 / HMAC 签名校验 / JWT 解析                            │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────┐
│                              业务层                                      │
│  Controller → Service → ServiceImpl → Mapper → PostgreSQL                │
│  - 工单服务 / 员工服务 / 认证服务 / 审批服务 / 流程服务                   │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────┐
│                            工作流引擎层                                  │
│  Flowable 6.8.0 ProcessEngine + BPMN Repository + RuntimeService          │
│  - 流程定义部署 / 流程实例执行 / 任务调度 / 异步执行器                   │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────┐
│                            数据持久层                                    │
│  PostgreSQL：业务表 + Flowable ACT_* 运行时/历史表                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 后端分层架构

后端严格遵循分层架构，禁止跨层调用。

```
HTTP Request
    │
    ▼
┌─────────────────────────────────────┐
│  EnterpriseSecurityFilter           │  XSS 清洗 / 限流 / HMAC 签名校验
├─────────────────────────────────────┤
│  JwtAuthenticationFilter            │  JWT 解析 / 认证信息注入
├─────────────────────────────────────┤
│  Controller 层                      │  参数校验、调用 Service、返回 Result
├─────────────────────────────────────┤
│  Service 接口层                     │  业务契约定义
├─────────────────────────────────────┤
│  ServiceImpl 实现层                 │  业务逻辑、事务、DTO/Entity 转换
├─────────────────────────────────────┤
│  Mapper / DAO 层                    │  MyBatis 数据访问
├─────────────────────────────────────┤
│  PostgreSQL Database                │  持久化存储
└─────────────────────────────────────┘
```

### 3.3 后端包结构

```
com.workorder
├── WorkOrderApplication.java          # 启动入口（纯容器装配工）
├── annotation/                        # 自定义注解（数据权限等）
├── aspect/                            # AOP 切面（日志、权限）
├── common/                            # 横切关注点
│   ├── constant/                      #   系统常量
│   ├── enums/                         #   枚举
│   ├── exception/                     #   全局异常处理
│   └── result/                        #   统一返回 Result、分页对象
├── config/                            # 框架配置类
│   ├── SecurityConfig.java            #   安全过滤器链
│   ├── FlowableConfig.java            #   Flowable 引擎配置
│   ├── EnterpriseSecurityFilter.java  #   企业安全过滤器
│   └── ...
├── controller/                        # REST 控制器
├── dao/                               # MyBatis Mapper 接口
├── dto/                               # 数据传输对象（Request/Response）
├── entity/                            # 数据库实体
├── interceptor/                       # MyBatis 拦截器（审计字段、AES 加密）
├── listener/                          # Flowable 事件监听器
├── security/                          # 安全组件（JWT、UserDetails 等）
├── service/                           # 业务接口与实现
│   ├── IXXXService.java
│   └── impl/
│       └── XXXServiceImpl.java
└── util/                              # 工具类
```

### 3.4 前端架构

```
frontend/src
├── main.js                    # 入口：7 步加载流水线
├── App.vue                    # 根组件
├── api/                       # 按业务模块组织的 API 接口
│   ├── auth.js
│   └── workorder.js
├── components/                # 通用组件
├── constants/                 # 前端常量
├── directives/                # 自定义指令（v-safe-html、权限指令）
├── hooks/                     # 组合式函数
├── router/                    # 路由配置
│   ├── index.js               #   静态路由
│   └── addDynamicRoutes.js    #   动态路由注册
├── store/                     # Pinia 状态
│   └── user.js                #   用户/RBAC
├── styles/                    # 全局 SCSS
├── utils/                     # 工具
│   ├── auth.js                #   Token 读写
│   ├── request.js             #   Axios 拦截器（JWT/HMAC/错误处理）
│   └── hmac.js                #   HMAC 签名计算
└── views/                     # 页面视图
    ├── employee/              #   员工管理
    ├── layout/                #   布局框架
    ├── process/               #   流程管理
    ├── system/                #   系统管理
    ├── workorder/             #   工单管理
    └── error/                 #   错误页
```

---

## 4. 数据库设计

### 4.1 数据库选型与命名规范

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

### 4.2 数据库 ER 图

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

### 4.3 数据表详细设计

#### 4.3.1 sys_user（用户表/员工表）

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

#### 4.3.2 sys_role（角色表）

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

#### 4.3.3 sys_permission（权限表）

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

#### 4.3.4 sys_user_role（用户角色关联表）

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

#### 4.3.5 sys_role_permission（角色权限关联表）

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

#### 4.3.6 sys_department（部门表）

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

#### 4.3.7 sys_position（职位表）

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

#### 4.3.8 work_order（工单表）

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

#### 4.3.9 approval_log（审批日志表）

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

#### 4.3.10 work_order_comment（工单评论表）

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

#### 4.3.11 sys_resigned_employee（离职员工档案表）

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

#### 4.3.12 sys_password_history（密码历史表）

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

#### 4.3.13 sys_password_reset_token（密码重置令牌表）

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

#### 4.3.14 sys_security_audit_log（安全审计日志表）

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

#### 4.3.15 sys_system_setting（系统设置表）

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

### 4.4 数据字典

#### 4.4.1 用户状态（sys_user.status）

| 值 | 含义 |
|----|------|
| -2 | 已离职（迁移到 sys_resigned_employee） |
| -1 | 已删除（历史遗留） |
| 0 | 禁用 |
| 1 | 启用 |

#### 4.4.2 组织层级（org_level）

| 值 | 层级 | 典型角色 |
|----|------|----------|
| 0 | 超级管理员 | SUPER_ADMIN、SECURITY_AUDIT |
| 1 | 决策层 | 董事长、总经理、副总经理 |
| 2 | 管理层 | 各总监（研发总监、财务总监等） |
| 3 | 职能层 | 专员（HR 专员、财务专员等） |
| 4 | 基层 | 普通员工 |

#### 4.4.3 工单状态（work_order.status）

| 值 | 含义 |
|----|------|
| DRAFT | 草稿 |
| PENDING | 审批中 |
| APPROVED | 已通过 |
| REJECTED | 已驳回 |
| ARCHIVED | 已归档 |
| TERMINATED | 已终止 |

#### 4.4.4 工单类型（work_order.order_type）

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

#### 4.4.5 审批操作类型（approval_log.action）

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

#### 4.4.6 离职类型（sys_resigned_employee.resign_type）

| 值 | 含义 |
|----|------|
| 1 | 主动辞职 |
| 2 | 被动辞退 |
| 3 | 合同到期 |
| 4 | 退休 |
| 5 | 其他 |

### 4.5 Flyway 数据库迁移

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

### 4.6 Flowable 引擎表

Flowable 6.8.0 启动时自动创建以下表族（以 `act_` 为前缀）：

| 表族 | 说明 |
|------|------|
| ACT_RE_* | Repository 表，存储流程定义、部署信息 |
| ACT_RU_* | Runtime 表，存储运行中的流程实例、任务、变量、Job |
| ACT_HI_* | History 表，存储历史流程实例、任务、变量、活动 |
| ACT_ID_* | Identity 表，用户/组信息（本项目禁用，使用 sys_user/sys_role） |
| ACT_GE_* | General 表，通用数据与属性 |

---

## 5. 后端详细设计

### 5.1 启动入口设计

#### 5.1.1 WorkOrderApplication

后端入口类 `com.workorder.WorkOrderApplication` 严格遵循**纯容器装配工**定位，不包含任何业务逻辑或 `@Bean` 定义。

```java
@SpringBootApplication(
    scanBasePackages = "com.workorder",
    excludeName = {
        "org.springframework.boot.devtools.autoconfigure.DevToolsAutoConfiguration"
    }
)
@MapperScan(basePackages = "com.workorder.dao")
public class WorkOrderApplication {
    public static void main(String[] args) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ConfigurableApplicationContext context = SpringApplication.run(
            WorkOrderApplication.class, args);
        stopWatch.stop();
        // 启动基线埋点：总耗时 + JVM 内存占用率
    }
}
```

**关键设计点**：

- `scanBasePackages = "com.workorder"`：显式限定扫描范围，禁止使用 `.*` 通配符。
- `excludeName` 排除 DevTools：防止生产环境热部署导致内存泄漏；使用字符串形式避免无 devtools 依赖时编译报错。
- `@MapperScan("com.workorder.dao")`：精确扫描 Mapper 接口，避免误识别 Service/Controller 接口。

#### 5.1.2 启动硬断校验（StartupCheckRunner）

通过 `EnvironmentAware` 在 Spring 上下文最早期阶段校验关键配置，失败即抛 `IllegalStateException` 终止启动。

| 配置项 | 校验规则 |
|--------|----------|
| `jwt.secret` | 非空且长度 ≥ 32 位 |
| `app.security.aes-encryption-key` | 生产环境非空 |
| `app.security.message-signing-key` | 非空且长度 ≥ 32 位 |
| `spring.datasource.url` | 非空 |
| `spring.datasource.username` | 非空 |

所有密钥日志输出采用**脱敏策略**：仅显示后 4 位。

#### 5.1.3 启动时序

```
T0  main() 入口
    └─ StopWatch.start() 启动耗时基线埋点
T1  SpringApplication.run()
    ├─ EnvironmentAware 最早期
    │   └─ StartupCheckRunner.validateCriticalConfigs() 硬断校验
    ├─ Bean 装配阶段
    │   ├─ SecurityConfig (@Order HIGHEST_PRECEDENCE)
    │   ├─ FlowableConfig (@AutoConfigureBefore)
    │   └─ 业务 Bean
    └─ ApplicationRunner 容器就绪
        └─ BPMN 部署校验（仅告警不阻断）
T2  StopWatch.stop()
    └─ logStartupBaseline()：总耗时 + JVM 内存占用率
```

### 5.2 配置体系

#### 5.2.1 多环境配置

| 文件 | 用途 |
|------|------|
| `application.yml` | 主配置，定义公共配置与默认 profile |
| `application-dev.yml` | 开发环境配置，含本地数据库与默认密钥 |
| `application-prod.yml` | 生产环境配置，密钥强制从环境变量注入 |

#### 5.2.2 核心配置项

```yaml
# 服务器
server:
  port: 8080
  servlet:
    context-path: /api

# 数据源
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5432/work_order_system?stringtype=unspecified
    username: postgres
    password: 666666

# JWT
jwt:
  secret: ${JWT_SECRET:...}
  expiration: 86400000  # 24小时
  header: Authorization
  prefix: Bearer

# Flowable
flowable:
  check-process-definitions: false
  process-definition-location-prefix: classpath:/processes/
  async-executor-activate: false
  database-schema-update: true
  history-level: full

# 应用安全
app:
  security:
    aes-encryption-key: ${AES_ENCRYPTION_KEY:...}
    message-signing-key: ${MESSAGE_SIGNING_KEY:...}
  rate-limit:
    login-per-minute: 5
    approve-per-minute: 30
    default-per-minute: 100
```

### 5.3 统一返回结果

所有 Controller 返回统一结构 `Result<T>`：

```java
public class Result<T> {
    private Integer code;       // 业务状态码
    private String message;     // 提示信息
    private T data;             // 数据载荷
    private Long timestamp;     // 时间戳
}
```

**状态码约定**：

| 状态码 | 含义 |
|--------|------|
| 20000 | 成功（SUCCESS） |
| 40000 | 参数错误 |
| 40100 | 未认证/登录失效 |
| 40300 | 无权限 |
| 40400 | 资源不存在 |
| 50000 | 服务器内部错误 |

### 5.4 实体与 DTO 设计

#### 5.4.1 Entity 设计原则

- Entity 仅用于数据库映射，不承载业务方法。
- 所有业务表必须包含审计字段：`create_time`、`update_time`、`create_by`、`update_by`、`is_deleted`。
- 敏感字段（`email`、`phone`）通过 MyBatis `typeHandler` 自动 AES 加解密，业务代码无感知。

#### 5.4.2 DTO 设计原则

- Controller 接收 Request DTO，返回 Response DTO/VO。
- Service 接口层定义 DTO 契约，实现层负责 Entity ↔ DTO 转换。
- 禁止将 Entity 直接暴露到 API 层。

### 5.5 MyBatis 数据访问

#### 5.5.1 Mapper 组织

Mapper 接口位于 `com.workorder.dao`，XML 映射文件位于 `backend/src/main/resources/mapper/`。

主要 Mapper：

| Mapper | 职责 |
|--------|------|
| `UserMapper` | 用户/员工 CRUD、角色权限查询 |
| `WorkOrderMapper` | 工单 CRUD、列表查询 |
| `ApprovalLogMapper` | 审批日志记录与查询 |
| `ResignedEmployeeMapper` | 离职员工档案 |
| `PasswordHistoryMapper` | 密码历史 |
| `PasswordResetTokenMapper` | 密码重置令牌 |
| `SecurityAuditLogMapper` | 安全审计日志 |
| `SystemSettingMapper` | 系统设置 |

#### 5.5.2 SQL 注入防御

- 所有动态参数必须使用 `#{}` 预编译参数化。
- 禁止在 SQL 中直接使用 `${}` 拼接用户输入。
- 动态排序字段必须经白名单校验。
- 后端对用户输入（工单标题、内容、审批意见）执行 HTML 净化（Jsoup 白名单策略）。

### 5.6 业务服务设计

#### 5.6.1 服务接口列表

| 服务接口 | 职责 |
|----------|------|
| `IAuthService` | 登录、登出、Token 刷新、当前用户信息、改密 |
| `IWorkOrderService` | 工单全生命周期管理 |
| `IEmployeeService` | 员工 CRUD、角色分配、状态管理、密码重置 |
| `IResignedEmployeeService` | 离职处理、离职档案查询 |
| `IApproverResolverService` | 审批人解析（按部门/职位/上级） |
| `IPasswordPolicyService` | 密码策略校验（复杂度、历史重复） |
| `IPasswordResetService` | 密码重置令牌生成与消费 |
| `ISecurityAuditService` | 安全审计日志记录 |
| `ISystemSettingService` | 系统设置读写 |
| `IEmployeeExportService` | 员工数据导出（CSV） |
| `IDataSecurityService` | 数据权限校验与过滤 |

#### 5.6.2 事务边界

- ServiceImpl 层使用 `@Transactional` 声明事务边界。
- 工单提交、审批、离职处理等跨表操作必须在同一事务内完成。
- 查询类方法不开启事务，避免不必要的数据库连接占用。

### 5.7 全局异常处理

通过 `@ControllerAdvice` 统一捕获异常并转换为 `Result<T>`：

| 异常类型 | 处理方式 |
|----------|----------|
| `MethodArgumentNotValidException` | 参数校验失败，返回 40000 |
| `AccessDeniedException` | 无权限，返回 40300 |
| `UsernameNotFoundException` | 用户不存在，返回 40100 |
| `BusinessException` | 业务异常，返回对应业务码 |
| `Exception` | 未知异常，返回 50000，并记录错误日志 |

### 5.8 MyBatis 拦截器

#### 5.8.1 审计字段拦截器

自动填充 `createBy`、`updateBy`、`createTime`、`updateTime`、`isDeleted`：

- `INSERT`：自动填充全部审计字段。
- `UPDATE`：自动填充 `updateBy`、`updateTime`。

#### 5.8.2 AES 加密拦截器

通过自定义 `AesEncryptedStringTypeHandler` 对指定字段加解密：

- 写入：`email`、`phone` 明文 → AES-GCM 256 密文 → 数据库。
- 读取：数据库密文 → 解密 → 实体字段明文。

**关键约束**：

- `AesEncryptedStringTypeHandler` 不使用 `@Component` 和 `@MappedTypes(String.class)`，避免被 MyBatis 全局注册到所有 String 字段。
- 通过 `AesTypeHandlerInitializer` 在 `@PostConstruct` 中注入 `AesEncryptionUtil`。
- 仅在 Mapper XML 中显式指定 `typeHandler` 的字段生效。

### 5.9 Controller 接口总览

| 控制器 | 基础路径 | 主要能力 |
|--------|----------|----------|
| `AuthController` | `/auth` | 登录、注册、登出、刷新 Token、当前用户、改密 |
| `WorkOrderController` | `/workorder` | 工单 CRUD、提交、审批、撤回、终止、归档 |
| `ApprovalController` | `/approval` | 审批操作（通过/驳回/转办/评论） |
| `EmployeeController` | `/employee` | 员工管理、角色分配、离职、导出 |
| `ProcessController` | `/process` | 流程定义、流程实例、任务查询 |
| `SystemSettingController` | `/setting` | 系统设置读写 |
| `PasswordResetController` | `/password-reset` | 密码重置令牌校验与消费 |
| `FileController` | `/file` | 文件上传下载 |
| `EmergencyController` | `/emergency` | 紧急维护端点（仅 dev + SUPER_ADMIN） |
| `MaintenanceController` | `/maintenance` | 系统维护接口 |

---

## 6. 前端详细设计

### 6.1 启动入口（main.js）

前端入口采用 **7 步加载流水线**，实现白屏治理、权限预加载与动态路由。

```
Step 1: createApp(App)                          创建 Vue 应用实例
Step 2: registerDirectives(app)                 注册 DOMPurify 安全指令 + 权限指令
Step 3: 注册 Element Plus 图标                  全局循环注册所有图标
Step 4: app.use(createPinia()).use(router)      链式注册 Pinia → Router
Step 5: 生产环境严格降级                         关闭 devtools 和 performance
Step 6: 全局异常捕获前置                         挂载 errorHandler 和 unhandledrejection
Step 7: initApp()                               权限预加载 + 动态路由 + mount
```

### 6.2 路由架构

采用**静态基础路由 + 动态业务路由**双层模式：

- **静态路由**：登录页、404、无权限页、首页框架，启动即注册。
- **动态路由**：`initApp` 阶段拉取用户权限后，根据 `permissions` 动态 `addRoute` 注册业务路由。
- **路由守卫**：`router.beforeEach` 校验 Token 与页面权限，未授权跳转登录。

### 6.3 状态管理（Pinia）

`store/user.js` 管理用户状态：

```javascript
const useUserStore = defineStore('user', {
  state: () => ({
    token: '',
    userInfo: {},
    roles: [],
    permissions: []
  }),
  getters: {
    hasRole: (state) => (role) => state.roles.includes(role),
    hasPermission: (state) => (perm) => state.permissions.includes(perm)
  },
  actions: {
    fetchUserInfo,
    fetchRbac,
    login,
    logout,
    resetState
  }
})
```

### 6.4 网络层（Axios 拦截器）

`utils/request.js` 集中处理 HTTP 横切逻辑。

**请求拦截器**：

- 注入 `Authorization: Bearer <JWT>`。
- 计算 HMAC-SHA256 签名，写入 `X-Signature`（Base64）。
- 注入 `X-Timestamp`（毫秒时间戳）与 `X-Nonce`（UUID）。
- multipart 文件上传跳过 HMAC 签名。

**响应拦截器**：

- 统一处理业务码 `Result.code`。
- `40100` → 清除 Token 并跳转登录。
- `40300` → 提示无权限。
- `50000` → 全局错误提示。
- 网络异常兜底。

### 6.5 自定义指令

| 指令 | 用途 |
|------|------|
| `v-safe-html` | 替代 `v-html`，使用 DOMPurify 净化 HTML，防止 XSS |
| `v-permission` | 按钮级权限控制，无权限时隐藏/禁用元素 |
| `v-role` | 角色级权限控制 |

### 6.6 UI 组件策略

- **Element Plus 按需引入**：通过 `unplugin-vue-components` + `unplugin-auto-import` 自动按需加载。
- **全局调用型组件样式手动注入**：`Message`、`MessageBox`、`Notification`、`Loading` 的 CSS 在 `main.js` 手动引入。
- **图标全局注册**：所有 `@element-plus/icons-vue` 图标在 `main.js` 循环注册为全局组件。

### 6.7 页面视图组织

| 目录 | 页面 |
|------|------|
| `views/layout/` | 系统布局框架、侧边栏、顶部导航 |
| `views/workorder/` | 我的工单、待办工单、全部工单、提交工单、工单详情 |
| `views/employee/` | 员工列表、员工编辑、离职管理 |
| `views/system/` | 用户管理、角色管理、权限管理、系统设置 |
| `views/process/` | 流程定义、流程实例、任务管理 |
| `views/error/` | 403、404 错误页 |

---

## 7. 安全架构

### 7.1 纵深防御体系

系统从网络层到字段层共设 7 道防线：

```
① 传输层: HTTPS (HSTS 强制)
② 网关层: Nginx 反向代理 + IP 白名单 (actuator)
③ 过滤器层: EnterpriseSecurityFilter
     - XSS 清洗（请求体净化）
     - 限流令牌桶
     - HMAC-SHA256 签名校验（防篡改）
     - 时间戳 + Nonce（防重放）
④ 认证层: JwtAuthenticationFilter
     - 解析 Bearer Token
     - 校验签名/过期
     - 注入 SecurityContext
⑤ 授权层: Spring Security + @PreAuthorize
     - RBAC 角色级控制
     - 数据级权限（行级过滤）
⑥ 业务层: Service 业务校验
⑦ 数据层: MyBatis #{} 参数化 + AES 字段加密
```

### 7.2 安全过滤器链

```
HTTP Request
    │
    ▼
┌─────────────────────────────────┐
│ EnterpriseSecurityFilter         │  ← addFilterBefore(UsernamePasswordAuthenticationFilter)
│  - XSS 清洗                      │
│  - 限流令牌桶                     │
│  - HMAC 签名校验                  │
└──────────────┬──────────────────┘
               ▼
┌─────────────────────────────────┐
│ JwtAuthenticationFilter          │  ← addFilterBefore(UsernamePasswordAuthenticationFilter)
│  - 解析 JWT                      │
│  - 校验签名/过期                  │
│  - 设置 Authentication           │
└──────────────┬──────────────────┘
               ▼
       DispatcherServlet → Controller
```

### 7.3 JWT 认证

- **令牌生成**：登录成功后由 `AuthServiceImpl` 调用 `JwtUtil` 生成。
- **令牌载荷**：`subject=userId`，`claims` 包含 `username`、`roles`。
- **签名算法**：HS256，密钥来自 `jwt.secret`（启动硬断 ≥32 位）。
- **存储方式**：前端 `localStorage`，请求头 `Authorization: Bearer <token>`。
- **会话策略**：`SessionCreationPolicy.STATELESS`，完全无状态。

### 7.4 HMAC-SHA256 请求签名

针对敏感写操作启用请求体签名，防篡改防重放。

**签名算法**：

```
HMAC-SHA256(secret, method + fullUrl + body + timestamp)
```

> 前端签名前剥离 query string，与后端 `request.getRequestURI()` 对齐。

**请求头**：

```
X-Signature: <Base64>
X-Timestamp: <epoch-ms>
```

**服务端校验**：

1. 时间戳偏差 ≤ 5 分钟（防重放窗口）。
2. 重算签名与请求头一致。
3. 白名单端点（登录/注册/密码重置/Actuator/Swagger）跳过验签。
4. multipart 文件上传跳过验签。

### 7.5 RBAC + 数据权限

#### 7.5.1 接口级权限

使用 Spring Security `@PreAuthorize` 控制接口访问：

```java
@PreAuthorize("hasAuthority('workorder:submit')")
@PostMapping
public Result<WorkOrder> submitNewWorkOrder(@RequestBody WorkOrderDTO dto) { ... }
```

#### 7.5.2 数据级权限

通过自定义 `@DataPermission` 注解 + AOP 实现：

```java
@DataPermission(entityType = "WORK_ORDER", checkOwnership = true, resourceIdParam = "id")
@PostMapping("/{id}/archive")
public Result<WorkOrder> archiveWorkOrder(@PathVariable Long id) { ... }
```

校验规则：

- 当前用户为工单发起人。
- 当前用户为当前审批人。
- 当前用户拥有 `workorder:view-all` 权限。
- 当前用户为工单所在部门的管理层。

#### 7.5.3 角色层级

| 角色 | 权限范围 |
|------|----------|
| SUPER_ADMIN | 全部数据，所有操作 |
| SECURITY_AUDIT | 安全审计日志查看 |
| 决策层/管理层 | 本部门及下级部门数据 |
| 职能层/基层 | 个人数据 |

### 7.6 XSS / SQL 注入 / CSRF 防御

| 威胁 | 防御点 | 实现 |
|------|--------|------|
| 存储型 XSS | 前端渲染 | `v-safe-html` + DOMPurify |
| 反射型 XSS | 响应头 | `X-XSS-Protection: 1; mode=block` |
| MIME 嗅探 | 响应头 | `X-Content-Type-Options: nosniff` |
| 点击劫持 | 响应头 | `X-Frame-Options: DENY` |
| SQL 注入 | MyBatis | 全部使用 `#{}` 参数化 |
| SQL 注入 | 输入校验 | `@Valid` + 白名单 |
| CSRF | 会话策略 | JWT 无状态，禁用 CSRF（不依赖 Cookie 认证） |

### 7.7 密码与凭证安全

- **密码哈希**：`BCryptPasswordEncoder(12 rounds)`。
- **认证管理器**：显式 `ProviderManager` + `DaoAuthenticationProvider`，强制使用 `BCryptPasswordEncoder`。
- **密码历史**：`sys_password_history` 记录最近 N 次密码，防止重复使用。
- **密码重置令牌**：64 字符随机十六进制串，SHA-256 哈希存库，15 分钟过期，一次性使用。
- **首次登录强制改密**：`password_changed=false` 的用户登录后必须修改密码。

### 7.8 超级管理员密码重置安全规则

| 场景 | 规则 |
|------|------|
| 非超管重置超管密码 | 禁止 |
| 超管重置其他超管密码 | 禁止 |
| 非超管重置自己密码 | 禁止（必须使用密码修改） |
| 超管重置自己密码 | 需要二次验证（当前操作密码） |

### 7.9 安全响应头清单

```
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Strict-Transport-Security: max-age=31536000; includeSubDomains
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
X-XSS-Protection: 1; mode=block
```

### 7.10 接口白名单

```
permitAll:
  - /auth/login
  - /password-reset/validate
  - /password-reset/confirm
  - /css/**, /js/**, /images/**
  - /favicon.ico

hasRole('SUPER_ADMIN'):
  - /actuator/**
  - /emergency/** (且 @Profile("dev"))

anyRequest().authenticated()
```

---

## 8. 工作流引擎集成

### 8.1 Flowable 引擎定位

Flowable 6.8.0 作为审批流转核心引擎，负责：

- BPMN 2.0 流程定义的部署、解析、执行。
- 用户任务（UserTask）的创建、认领、完成、委派。
- 并行会签（多实例任务）的状态同步。
- 异步任务（定时器、Service Task）的调度执行。

### 8.2 引擎配置（FlowableConfig）

`FlowableConfig` 实现 `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>`，在引擎创建前注入自定义配置。

```java
@AutoConfigureBefore(ProcessEngineAutoConfiguration.class)
public class FlowableConfig implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {
    @Override
    public void configure(SpringProcessEngineConfiguration config) {
        // 1. 注册 ScriptTaskParseHandler：禁止部署含 scriptTask 的流程定义
        // 2. 注册全局事件监听器：并行审批状态同步
    }
}
```

### 8.3 BPMN 流程模型

典型审批流程结构：

```
[发起人] → (提交) → [部门主管审批] → ┬─ 通过 ─→ [并行会签: 技术/财务/法务] → [归档]
                                    ├─ 驳回 ─→ [发起人修改]
                                    └─ 委派 ─→ [被委派人] → [原审批人]
```

- **UserTask**：人工审批节点，通过 `candidateGroups` / `assignee` 指派。
- **ServiceTask**：系统自动节点，禁止 ScriptTask。
- **并行多实例**：会签场景，通过事件监听器同步状态。

### 8.4 ScriptTask 安全拦截

**红线策略**：在 BPMN 解析阶段拒绝任何 `scriptTask`，防止恶意 `groovy` / `javascript` 脚本导致 RCE。

```java
customPreHandlers.add(new ScriptTaskParseHandler() {
    @Override
    protected void executeParse(BpmnParse bpmnParse, ScriptTask scriptTask) {
        throw new SecurityException("禁止部署包含 ScriptTask 的流程定义...");
    }
});
```

### 8.5 AsyncExecutor 生命周期治理

Flowable 的 `AsyncExecutor` 负责 `ACT_RU_JOB` 表中异步任务调度，系统对其生命周期做闭环治理。

**启动阶段**：

```java
@DependsOn({"dataSource", "transactionManager"})
```

**关闭阶段（@PreDestroy）**：

```java
processEngineProvider.getIfAvailable()  // 惰性获取，打破循环依赖
asyncExecutor.shutdown()
轮询 isActive()，最长等待 30s
```

**循环依赖治理**：`FlowableConfig` 作为 `EngineConfigurationConfigurer` 被引擎创建时引用，直接使用 `@Autowired ProcessEngine` 会形成循环。改用 `ObjectProvider<ProcessEngine>` 惰性注入。

### 8.6 流程定义文件

BPMN 文件位于 `backend/src/main/resources/processes/`：

| 文件 | 流程类型 |
|------|----------|
| `leave-approval.bpmn20.xml` | 请假审批 |
| `overtime-approval.bpmn20.xml` | 加班审批 |
| `reimbursement-approval.bpmn20.xml` | 报销审批 |
| `purchase-approval.bpmn20.xml` | 采购审批 |
| `repair-approval.bpmn20.xml` | 维修审批 |
| `supply-approval.bpmn20.xml` | 物资领用审批 |
| `business-approval.bpmn20.xml` | 通用业务审批 |
| `enterprise-general.bpmn20.xml` | 企业通用审批 |
| `general-approval.bpmn20.xml` | 通用审批 |

### 8.7 启动期 BPMN 部署校验

`StartupCheckRunner` 在容器就绪后校验核心 BPMN 是否已部署：

```java
long count = repositoryService.createProcessDefinitionQuery().count();
if (count == 0) {
    log.warn("⚠️ 未部署任何流程定义，审批流转功能不可用");
}
```

仅告警，不阻断启动，避免首次部署时系统无法启动。

---

## 9. API 接口规范

### 9.1 认证接口

#### POST /api/auth/login

**描述**：用户登录

**请求体**：

```json
{
  "username": "KLord",
  "password": "123456"
}
```

**响应体**：

```json
{
  "code": 20000,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400000,
    "user": {
      "id": 1,
      "username": "KLord",
      "realName": "KLord Administrator",
      "roles": ["SUPER_ADMIN"],
      "permissions": ["system:user", "workorder:view-all", "..."]
    }
  }
}
```

#### POST /api/auth/logout

**描述**：用户登出

**请求头**：`Authorization: Bearer <token>`

**响应体**：

```json
{
  "code": 20000,
  "message": "登出成功"
}
```

#### POST /api/auth/change-password

**描述**：修改密码

**请求体**：

```json
{
  "oldPassword": "原密码",
  "newPassword": "新密码"
}
```

### 9.2 工单接口

#### POST /api/workorder

**描述**：直接提交新工单

**权限**：`workorder:submit`

**请求体**：

```json
{
  "title": "请假申请",
  "content": "因私事请假一天",
  "orderType": "leave",
  "priority": 2,
  "remark": ""
}
```

#### POST /api/workorder/draft

**描述**：创建工单草稿

**权限**：`workorder:submit`

#### POST /api/workorder/{id}/submit

**描述**：提交草稿工单

**权限**：`workorder:submit`

#### GET /api/workorder/{id}

**描述**：查询工单详情

**权限**：`workorder:mine` 或 `workorder:view-all`

#### GET /api/workorder/my

**描述**：查询我的工单列表

**权限**：`workorder:mine`

**查询参数**：`status`、`orderType`、`keyword`、`pageNum`、`pageSize`

#### GET /api/workorder/list

**描述**：查询全部工单（管理员/管理层）

**权限**：`workorder:view-all` 或 `workorder:approve`

#### GET /api/workorder/pending

**描述**：查询待我审批的工单

**权限**：`workorder:pending`

#### POST /api/workorder/{id}/withdraw

**描述**：撤回工单

**权限**：`workorder:submit`

#### POST /api/workorder/{id}/terminate

**描述**：终止工单

**权限**：`system:user` 或 `workorder:approve`

#### POST /api/workorder/{id}/archive

**描述**：归档工单

**权限**：`system:user`

**数据权限**：校验当前用户对该工单的所有权

#### PUT /api/workorder/{id}

**描述**：更新工单信息

**权限**：`workorder:submit`

**数据权限**：仅发起人可改草稿

#### DELETE /api/workorder/{id}

**描述**：删除工单

**权限**：`workorder:submit`

### 9.3 审批接口

#### POST /api/approval/{taskId}/approve

**描述**：审批通过

**权限**：`workorder:approve`

**请求体**：

```json
{
  "comment": "同意",
  "workOrderId": 100
}
```

#### POST /api/approval/{taskId}/reject

**描述**：审批驳回

**权限**：`workorder:approve`

#### POST /api/approval/{taskId}/transfer

**描述**：转办任务

**权限**：`workorder:approve`

### 9.4 员工管理接口

#### GET /api/employee/list

**描述**：员工列表分页查询

**权限**：`system:user` 或 `workorder:view-all`

**查询参数**：`pageNum`、`pageSize`、`keyword`、`deptId`、`positionId`、`orgLevel`

#### GET /api/employee/{id}

**描述**：员工详情

**权限**：`system:user` 或 `workorder:view-all`

#### POST /api/employee

**描述**：创建员工

**权限**：`system:user`

#### PUT /api/employee/{id}

**描述**：更新员工

**权限**：`system:user`

#### DELETE /api/employee/{id}

**描述**：删除员工（逻辑删除）

**权限**：`system:user`

#### PUT /api/employee/{id}/status

**描述**：切换员工状态

**权限**：`system:user`

#### PUT /api/employee/{id}/reset-password

**描述**：重置员工密码

**权限**：`system:user`

**请求体**：

```json
{
  "operatorPassword": "当前操作密码（超管重置自己时需要）"
}
```

**响应体**：

```json
{
  "code": 20000,
  "message": "密码重置成功",
  "data": {
    "resetToken": "64 字符随机 token",
    "resetLink": "/password-reset?token=xxx"
  }
}
```

#### PUT /api/employee/{id}/roles

**描述**：分配角色

**权限**：`system:user`

#### GET /api/employee/export

**描述**：导出员工 CSV

**权限**：`system:user`

**响应**：`Content-Type: text/csv`

#### POST /api/employee/{id}/resign

**描述**：员工离职处理

**权限**：`system:user`

### 9.5 密码重置接口

#### POST /api/password-reset/validate

**描述**：验证重置 token 是否有效

**权限**：公开访问（permitAll）

#### POST /api/password-reset/confirm

**描述**：消费 token 并设置新密码

**权限**：公开访问（permitAll）

### 9.6 系统设置接口

#### GET /api/setting/{groupKey}

**描述**：按分组查询设置

#### PUT /api/setting

**描述**：更新设置

**权限**：`system:user`

### 9.7 文件接口

#### POST /api/file/upload

**描述**：文件上传

**权限**：已认证

**Content-Type**：`multipart/form-data`

#### GET /api/file/download/{filename}

**描述**：文件下载

---

## 10. 部署与运维

### 10.1 开发环境启动

#### 10.1.1 后端启动

```bash
# 1. 确保 PostgreSQL 和 Redis 已启动
# 2. 创建数据库 work_order_system
# 3. 执行 Maven 构建
cd backend
mvn clean install -DskipTests

# 4. 启动应用
mvn spring-boot:run
# 或使用 java -jar
java -jar target/work-order-system-*.jar
```

#### 10.1.2 前端启动

```bash
cd frontend
npm install
npm run dev
```

### 10.2 生产环境部署

#### 10.2.1 环境变量

| 变量 | 说明 | 必填 |
|------|------|------|
| `SPRING_PROFILES_ACTIVE` | 激活 profile，生产环境必须为 `prod` | 是 |
| `DB_HOST` | 数据库主机 | 是 |
| `DB_PORT` | 数据库端口 | 否（默认 5432） |
| `DB_NAME` | 数据库名 | 否（默认 work_order_system） |
| `DB_USERNAME` | 数据库用户名 | 是 |
| `DB_PASSWORD` | 数据库密码 | 是 |
| `REDIS_HOST` | Redis 主机 | 是 |
| `REDIS_PORT` | Redis 端口 | 否（默认 6379） |
| `REDIS_PASSWORD` | Redis 密码 | 否 |
| `JWT_SECRET` | JWT 签名密钥（≥32 位） | 是 |
| `AES_ENCRYPTION_KEY` | AES 加密密钥（32 字节 Base64） | 是 |
| `MESSAGE_SIGNING_KEY` | HMAC 签名密钥（≥32 位） | 是 |
| `SERVER_PORT` | 服务端口 | 否（默认 8080） |
| `CORS_ORIGINS` | CORS 允许来源 | 是 |

#### 10.2.2 生产配置要点

- `application-prod.yml` 中密钥**无默认值**，必须全部通过环境变量注入。
- 生产环境禁用 DevTools。
- Actuator 端点仅暴露 `health`、`info`、`metrics`、`prometheus`，并叠加 Nginx IP 白名单。
- 启用 HTTPS，配置 HSTS。
- 数据库使用独立账号，最小权限原则。

### 10.3 Nginx 反向代理示例

```nginx
server {
    listen 443 ssl http2;
    server_name workorder.company.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    # HSTS
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # 安全响应头
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;
    add_header X-XSS-Protection "1; mode=block";

    location / {
        root /var/www/work-order-frontend;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://localhost:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Actuator 仅允许内网 IP
    location /api/actuator/ {
        allow 10.0.0.0/8;
        deny all;
        proxy_pass http://localhost:8080/api/actuator/;
    }
}
```

### 10.4 监控与日志

#### 10.4.1 Actuator 端点

| 端点 | 用途 |
|------|------|
| `/api/actuator/health` | 健康检查 |
| `/api/actuator/info` | 应用信息 |
| `/api/actuator/metrics` | 指标数据 |
| `/api/actuator/prometheus` | Prometheus 格式指标 |

#### 10.4.2 日志配置

- 开发环境：`logs/dev.log`，DEBUG 级别。
- 生产环境：结构化日志输出，支持按日期切割。
- 安全审计日志单独输出到 `logback-audit.xml` 配置的 appender。

### 10.5 数据库备份

```bash
# 每日全量备份
pg_dump -h localhost -U postgres -d work_order_system > backup_$(date +%Y%m%d).sql

# 保留最近 30 天备份
find /backups -name "backup_*.sql" -mtime +30 -delete
```

---

## 11. 开发规范与治理

### 11.1 代码规范

- **命名规范**：Java 类名大驼峰，方法/变量小驼峰，常量全大写下划线分隔。
- **注释规范**：类、方法必须包含 JavaDoc，复杂业务逻辑必须注释。
- **分层规范**：Controller 不写业务逻辑，ServiceImpl 不写 SQL，Mapper 不写业务逻辑。
- **异常规范**：业务异常使用 `BusinessException`，禁止直接抛 `RuntimeException`。

### 11.2 安全红线

1. **禁止使用 ScriptTask**：BPMN 解析阶段拦截。
2. **禁止使用 `${}` 拼接 SQL**：全部使用 `#{}`。
3. **禁止使用 `v-html`**：全部替换为 `v-safe-html`。
4. **禁止生产环境弱密钥**：JWT/AES/HMAC 密钥启动硬断。
5. **禁止 DevTools 进入生产**：`excludeName` 显式排除。
6. **禁止匿名访问业务接口**：除登录、密码重置外全部需认证。

### 11.3 审计规范

- 所有业务表必须包含审计字段。
- 删除操作必须逻辑删除，记录 `update_by` 和 `update_time`。
- 审批操作、登录登出、密码变更、权限变更必须记录审计日志。

### 11.4 测试规范

- 单元测试：Service 层核心业务逻辑覆盖。
- 集成测试：Controller 接口端到端测试。
- 安全测试：HMAC 签名、JWT、AES 加解密、SQL 注入、XSS 防御验证。

### 11.5 Git 分支规范

| 分支 | 用途 |
|------|------|
| `main` | 生产发布分支 |
| `develop` | 开发集成分支 |
| `feature/*` | 功能开发分支 |
| `bugfix/*` | 缺陷修复分支 |
| `hotfix/*` | 生产热修复分支 |

---

## 12. 典型问题与根因分析

### 12.1 AES TypeHandler 全局注册导致登录失败

**现象**：登录时返回 "Bad credentials"，数据库中用户存在。

**根因**：`AesEncryptedStringTypeHandler` 同时标注 `@Component` 和 `@MappedTypes(String.class)`，被 MyBatis 全局注册到所有 String 参数。导致 `WHERE username = #{username}` 的查询参数被 AES 加密，而数据库存的是明文 "KLord"，无法匹配。

**修复**：移除 `@Component` 和 `@MappedTypes`，通过 `AesTypeHandlerInitializer` 静态注入 `AesEncryptionUtil`；仅在 Mapper XML 中显式指定 `typeHandler` 的字段生效。

### 12.2 AuthenticationManager 使用默认 DelegatingPasswordEncoder

**现象**：正确密码登录失败，提示 "Bad credentials"。

**根因**：`authConfig.getAuthenticationManager()` 返回的全局 AuthenticationManager 可能使用 Spring Security 默认的 `DelegatingPasswordEncoder`，要求哈希带 `{bcrypt}` 前缀，而数据库中哈希为 `$2a$10$...` 无前缀格式。

**修复**：显式用 `ProviderManager` + `DaoAuthenticationProvider` 构建全局 `AuthenticationManager` bean，强制使用 `BCryptPasswordEncoder`。

### 12.3 AES 加密配置不匹配导致手机号显示密文

**现象**：手机号显示为 `kSrpmoO1NbDEkwcm:MvI9KJ1mC2jsEwKy9cCGjg==`。

**根因**：`AesEncryptionUtil.java` 读取顶层属性 `${aes.encryption-key}`，而配置文件使用嵌套属性 `app.security.aes-encryption-key`，导致密钥为空，每次重启生成随机密钥。

**修复**：修正 `@Value` 注解读取嵌套属性，并在 dev 环境设置固定默认密钥。

### 12.4 硬编码成功码导致操作按钮失效

**现象**：编辑员工后无成功提示、对话框不关闭、列表不刷新。

**根因**：`handleSubmit` 中硬编码 `response = { code: 200 }`，而实际 `RESULT_CODE.SUCCESS = 20000`。

**修复**：重写 `handleSubmit` 使用真实后端响应。

### 12.5 员工删除后仍显示且可重复删除

**现象**：删除员工后列表仍显示，再次删除仍成功。

**根因**：`EmployeeServiceImpl.deleteEmployee` 错误使用 `updateById` 仅设置 `status=0`，而非调用 `deleteById` 设置 `is_deleted=1`。

**修复**：改为 `deleteById` 执行逻辑删除，使 `is_deleted=1` 生效。

### 12.6 FlowableConfig 循环依赖导致启动失败

**现象**：Spring Boot 启动报错循环依赖。

**根因**：`FlowableConfig` 作为 `EngineConfigurationConfigurer` 被引擎创建时引用，内部直接 `@Autowired ProcessEngine` 形成 `FlowableConfig → ProcessEngine → FlowableConfig` 循环。

**修复**：改用 `ObjectProvider<ProcessEngine>` 惰性注入，仅在 `@PreDestroy` 关闭时按需获取。

### 12.7 phone/email 字段长度不足导致新增/编辑失败

**现象**：新增或编辑员工时返回 500，提示 "值太长了"。

**根因**：AES-GCM 密文格式为 `base64(iv):base64(cipher+tag)`，11 位手机号密文约 53 字符，原 `phone VARCHAR(20)` 不足。

**修复**：通过 Flyway V5 将 `phone`、`email` 扩展至 `VARCHAR(255)`。

---

## 13. 附录

### 13.1 关键文件索引

| 文件 | 路径 | 职责 |
|------|------|------|
| 后端入口 | `backend/src/main/java/com/workorder/WorkOrderApplication.java` | 容器装配 + 启动校验 |
| 安全配置 | `backend/src/main/java/com/workorder/config/SecurityConfig.java` | 过滤器链 + 授权规则 |
| 企业安全过滤器 | `backend/src/main/java/com/workorder/config/EnterpriseSecurityFilter.java` | XSS/限流/HMAC 验签 |
| Flowable 配置 | `backend/src/main/java/com/workorder/config/FlowableConfig.java` | 引擎配置 + 生命周期 |
| JWT 过滤器 | `backend/src/main/java/com/workorder/security/JwtAuthenticationFilter.java` | JWT 认证 |
| JWT 工具 | `backend/src/main/java/com/workorder/security/JwtUtil.java` | Token 生成与解析 |
| 数据权限注解 | `backend/src/main/java/com/workorder/annotation/DataPermission.java` | 数据权限元数据 |
| 数据权限切面 | `backend/src/main/java/com/workorder/aspect/DataPermissionAspect.java` | 数据权限校验 |
| 用户 Mapper | `backend/src/main/resources/mapper/UserMapper.xml` | 用户数据访问 |
| 工单 Mapper | `backend/src/main/resources/mapper/WorkOrderMapper.xml` | 工单数据访问 |
| Flyway V1 | `backend/src/main/resources/db/migration/V1__init.sql` | 初始 schema |
| 前端入口 | `frontend/src/main.js` | 7 步加载流水线 |
| 前端路由 | `frontend/src/router/index.js` | 静态路由 |
| 动态路由 | `frontend/src/router/addDynamicRoutes.js` | 动态路由注册 |
| 前端请求层 | `frontend/src/utils/request.js` | Axios 拦截器 |
| HMAC 签名 | `frontend/src/utils/hmac.js` | HMAC-SHA256 计算 |
| 安全指令 | `frontend/src/directives/index.js` | v-safe-html、权限指令 |
| 用户状态 | `frontend/src/store/user.js` | Pinia 用户/RBAC |

### 13.2 术语表

| 术语 | 含义 |
|------|------|
| RBAC | Role-Based Access Control，基于角色的访问控制 |
| BPMN | Business Process Model and Notation，业务流程建模标记法 |
| JWT | JSON Web Token，无状态认证令牌 |
| HMAC | Hash-based Message Authentication Code，哈希消息认证码 |
| AES | Advanced Encryption Standard，高级加密标准 |
| BCrypt | 基于 Blowfish 的密码哈希算法 |
| RCE | Remote Code Execution，远程代码执行 |
| SPA | Single Page Application，单页应用 |
| XSS | Cross-Site Scripting，跨站脚本攻击 |
| CSRF | Cross-Site Request Forgery，跨站请求伪造 |
| IDOR | Insecure Direct Object Reference，不安全的直接对象引用 |
| AsyncExecutor | Flowable 异步任务执行器 |
| ACT_RU_JOB | Flowable 运行时任务表 |
| 硬断 | 启动期强制校验，失败即终止启动 |
| 脱敏 | 日志中隐藏敏感信息（仅显后 4 位） |

### 13.3 默认账号信息

| 账号 | 密码 | 角色 |
|------|------|------|
| KLord | 123456 | SUPER_ADMIN |

首次登录后建议立即修改默认密码。

### 13.4 数据库连接示例

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5432/work_order_system?stringtype=unspecified
    username: postgres
    password: 666666
```

---

> 本文档为活文档，随项目演进持续更新。任何架构变更、数据库变更、接口变更均须同步修订本文档。
