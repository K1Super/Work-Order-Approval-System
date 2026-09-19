# 总体设计

本文档汇总工单审批系统的架构设计、前后端/后端详细设计、安全架构与工作流集成方案，并附历史优化提案，内容摘自《PROJECT_DOCUMENTATION.md》与《OPTIMIZATION.md》。

---

## 1. 系统架构设计

### 1.1 总体架构

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

### 1.2 后端分层架构

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

### 1.3 后端包结构

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

### 1.4 前端架构

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

## 2. 后端详细设计

### 2.1 启动入口设计

#### 2.1.1 WorkOrderApplication

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

#### 2.1.2 启动硬断校验（StartupCheckRunner）

通过 `EnvironmentAware` 在 Spring 上下文最早期阶段校验关键配置，失败即抛 `IllegalStateException` 终止启动。

| 配置项 | 校验规则 |
|--------|----------|
| `jwt.secret` | 非空且长度 ≥ 32 位 |
| `app.security.aes-encryption-key` | 生产环境非空 |
| `app.security.message-signing-key` | 非空且长度 ≥ 32 位 |
| `spring.datasource.url` | 非空 |
| `spring.datasource.username` | 非空 |

所有密钥日志输出采用**脱敏策略**：仅显示后 4 位。

#### 2.1.3 启动时序

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

### 2.2 配置体系

#### 2.2.1 多环境配置

| 文件 | 用途 |
|------|------|
| `application.yml` | 主配置，定义公共配置与默认 profile |
| `application-dev.yml` | 开发环境配置，含本地数据库与默认密钥 |
| `application-prod.yml` | 生产环境配置，密钥强制从环境变量注入 |

#### 2.2.2 核心配置项

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

### 2.3 统一返回结果

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

### 2.4 实体与 DTO 设计

#### 2.4.1 Entity 设计原则

- Entity 仅用于数据库映射，不承载业务方法。
- 所有业务表必须包含审计字段：`create_time`、`update_time`、`create_by`、`update_by`、`is_deleted`。
- 敏感字段（`email`、`phone`）通过 MyBatis `typeHandler` 自动 AES 加解密，业务代码无感知。

#### 2.4.2 DTO 设计原则

- Controller 接收 Request DTO，返回 Response DTO/VO。
- Service 接口层定义 DTO 契约，实现层负责 Entity ↔ DTO 转换。
- 禁止将 Entity 直接暴露到 API 层。

### 2.5 MyBatis 数据访问

#### 2.5.1 Mapper 组织

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

#### 2.5.2 SQL 注入防御

- 所有动态参数必须使用 `#{}` 预编译参数化。
- 禁止在 SQL 中直接使用 `${}` 拼接用户输入。
- 动态排序字段必须经白名单校验。
- 后端对用户输入（工单标题、内容、审批意见）执行 HTML 净化（Jsoup 白名单策略）。

### 2.6 业务服务设计

#### 2.6.1 服务接口列表

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

#### 2.6.2 事务边界

- ServiceImpl 层使用 `@Transactional` 声明事务边界。
- 工单提交、审批、离职处理等跨表操作必须在同一事务内完成。
- 查询类方法不开启事务，避免不必要的数据库连接占用。

#### 2.6.3 工单服务组件化（上帝类拆分）

为收敛 `WorkOrderServiceImpl`（拆分前约 1700 行）的职责并保持行为不变，拆分为**薄门面 + 6 个组件**，均位于 `com.workorder.service.impl`：

| 组件 | 职责 | 事务注解 |
|------|------|----------|
| `WorkOrderServiceImpl`（薄门面） | 实现 `IWorkOrderService` 契约；保留 createDraft、查询类方法、updateWorkOrder / deleteWorkOrder，以及 DTO/实体 XSS 净化、附件/优先级校验、排序白名单 | 仅 `deleteWorkOrder` 带 `@Transactional` |
| `WorkOrderLifecycleOrchestrator` | 生命周期编排：提交 / 提交新单 / 重新提交 / 撤回 / 终止 / 归档，含审批链规则推导、流程启动与初始任务自动完成 | submit / submitNew / resubmit / terminate / withdraw 带 `@Transactional` |
| `WorkOrderApprovalOrchestrator` | 审批编排 `handleApproval`：XSS 净化、咨询锁、候选人双重校验、任务完成、流程终态判定、乐观锁更新、领域事件发布 | `@Transactional`（咨询锁 + 乐观锁并发正确性由此边界保证） |
| `WorkOrderStateMachine` | 状态流转唯一入口：审批终态判定（W-01/W-02）、乐观锁更新原语 `updateWithVersion`、状态描述映射 | 无 |
| `WorkOrderAccessService` | 访问控制：跨部门 / 跨级审批判定、工单可见性（IDOR 防护）、候选人双重校验（防任务哄抢）、任务/工单 ID 反查 | 无 |
| `WorkOrderDisplayAssembler` | 展示字段组装：当前节点 / 当前审批人 / 流程实例 ID 动态填充、下一节点信息、审批层级名称 | 无 |
| `WorkOrderAuditService` | 审计落库（RESUBMIT 直写）与领域事件发布（SUBMIT / APPROVE / REJECT / TERMINATE / WITHDRAW / ARCHIVE） | 无（事件由监听器 AFTER_COMMIT 异步处理） |

**事务边界规则**：

- 拆分前后事务边界完全一致：所有写操作仍由编排器或门面上的 `@Transactional(rollbackFor = Exception.class)` 声明。
- `submitNewWorkOrder` 内部自调用 `submitWorkOrder`（同 bean 自调用绕过代理），与拆分前共享同一事务的语义一致；`createDraft` 为跨 bean 调用，join 当前事务。
- 审批并发正确性（`pg_advisory_xact_lock` 咨询锁 + `version` 乐观锁）由 `handleApproval` 的事务边界保证，拆分后不变。

#### 2.6.4 员工服务组件化（上帝类拆分）

`EmployeeServiceImpl`（拆分前约 1760 行）以同样的边界治理方式拆分为**薄门面 + 4 个业务组件 + 1 个访问控制组件**：

| 组件 | 职责 | 事务注解 |
|------|------|----------|
| `EmployeeServiceImpl`（薄门面） | 实现 `IEmployeeService` 契约，全部方法纯委托 | 无 |
| `EmployeeQueryService` | 分页列表（部门数据隔离 + 角色/角色名填充）、详情、全量导出、字典查询（部门/职位/角色）、上级列表 | 无 |
| `EmployeeLifecycleOrchestrator` | 员工创建（唯一工号生成 / 强密码 / DEK 三段式分层加密）、更新（工号/密码白名单 + DEK 上下文）、逻辑删除（超管保护 + 冻结工单联动）、启用/禁用（tokenVersion 失效联动）、批量导入（单条容错） | create / update / delete / toggle / batchImport 均 `@Transactional(rollbackFor = Exception.class)` |
| `EmployeePasswordService` | 企业级密码重置流程（超管保护 / 安全规则 / 二次验证 / 一次性令牌链接组装）与随机强密码生成 | 仅 resetPassword `@Transactional` |
| `EmployeeRoleService` | 角色全量替换分配 + 按最高权限角色自动计算组织层级（org_level） | 仅 assignRoles `@Transactional` |
| `EmployeeAccessGuard` | 访问控制判定：部门管理员 / 超级管理员、最高角色展示名、跨部门访问拦截（空值安全） | 无 |

**事务边界规则**（与工单拆分一致）：

- 写操作的事务注解随方法迁移至组件；门面跨 bean 调用 join 组件事务，与拆分前 controller → service 代理的行为一致。
- DEK per-user 分层加密三段式（insert → 生成 DEK → DekContext 内重加密 email/phone）收敛为编排器私有方法 `insertUserWithDek`，创建与批量导入共用，DEK 失败不阻断主流程（DekMigrationRunner 启动补迁移）。
- 禁用员工时 `incrementTokenVersion` + `TokenVersionCache.evict` 联动逻辑原样保留（OPTIMIZATION 三.3.1）。

### 2.7 全局异常处理

通过 `@ControllerAdvice` 统一捕获异常并转换为 `Result<T>`：

| 异常类型 | 处理方式 |
|----------|----------|
| `MethodArgumentNotValidException` | 参数校验失败，返回 40000 |
| `AccessDeniedException` | 无权限，返回 40300 |
| `UsernameNotFoundException` | 用户不存在，返回 40100 |
| `BusinessException` | 业务异常，返回对应业务码 |
| `Exception` | 未知异常，返回 50000，并记录错误日志 |

### 2.8 MyBatis 拦截器

#### 2.8.1 审计字段拦截器

自动填充 `createBy`、`updateBy`、`createTime`、`updateTime`、`isDeleted`：

- `INSERT`：自动填充全部审计字段。
- `UPDATE`：自动填充 `updateBy`、`updateTime`。

#### 2.8.2 AES 加密拦截器

通过自定义 `AesEncryptedStringTypeHandler` 对指定字段加解密：

- 写入：`email`、`phone` 明文 → AES-GCM 256 密文 → 数据库。
- 读取：数据库密文 → 解密 → 实体字段明文。

**关键约束**：

- `AesEncryptedStringTypeHandler` 不使用 `@Component` 和 `@MappedTypes(String.class)`，避免被 MyBatis 全局注册到所有 String 字段。
- 通过 `AesTypeHandlerInitializer` 在 `@PostConstruct` 中注入 `AesEncryptionUtil`。
- 仅在 Mapper XML 中显式指定 `typeHandler` 的字段生效。

### 2.9 Controller 接口总览

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

## 3. 前端详细设计

### 3.1 启动入口（main.js）

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

### 3.2 路由架构

采用**静态基础路由 + 动态业务路由**双层模式：

- **静态路由**：登录页、404、无权限页、首页框架，启动即注册。
- **动态路由**：`initApp` 阶段拉取用户权限后，根据 `permissions` 动态 `addRoute` 注册业务路由。
- **路由守卫**：`router.beforeEach` 校验 Token 与页面权限，未授权跳转登录。

### 3.3 状态管理（Pinia）

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

### 3.4 网络层（Axios 拦截器）

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

### 3.5 自定义指令

| 指令 | 用途 |
|------|------|
| `v-safe-html` | 替代 `v-html`，使用 DOMPurify 净化 HTML，防止 XSS |
| `v-permission` | 按钮级权限控制，无权限时隐藏/禁用元素 |
| `v-role` | 角色级权限控制 |

### 3.6 UI 组件策略

- **Element Plus 按需引入**：通过 `unplugin-vue-components` + `unplugin-auto-import` 自动按需加载。
- **全局调用型组件样式手动注入**：`Message`、`MessageBox`、`Notification`、`Loading` 的 CSS 在 `main.js` 手动引入。
- **图标全局注册**：所有 `@element-plus/icons-vue` 图标在 `main.js` 循环注册为全局组件。

### 3.7 页面视图组织

| 目录 | 页面 |
|------|------|
| `views/layout/` | 系统布局框架、侧边栏、顶部导航 |
| `views/workorder/` | 我的工单、待办工单、全部工单、提交工单、工单详情 |
| `views/employee/` | 员工列表、员工编辑、离职管理 |
| `views/system/` | 用户管理、角色管理、权限管理、系统设置 |
| `views/process/` | 流程定义、流程实例、任务管理 |
| `views/error/` | 403、404 错误页 |

---

## 4. 安全架构

### 4.1 纵深防御体系

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

### 4.2 安全过滤器链

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

### 4.3 JWT 认证

- **令牌生成**：登录成功后由 `AuthServiceImpl` 调用 `JwtUtil` 生成。
- **令牌载荷**：`subject=userId`，`claims` 包含 `username`、`roles`。
- **签名算法**：HS256，密钥来自 `jwt.secret`（启动硬断 ≥32 位）。
- **存储方式**：前端 `localStorage`，请求头 `Authorization: Bearer <token>`。
- **会话策略**：`SessionCreationPolicy.STATELESS`，完全无状态。

### 4.4 HMAC-SHA256 请求签名

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

### 4.5 RBAC + 数据权限

#### 4.5.1 接口级权限

使用 Spring Security `@PreAuthorize` 控制接口访问：

```java
@PreAuthorize("hasAuthority('workorder:submit')")
@PostMapping
public Result<WorkOrder> submitNewWorkOrder(@RequestBody WorkOrderDTO dto) { ... }
```

#### 4.5.2 数据级权限

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

#### 4.5.3 角色层级

| 角色 | 权限范围 |
|------|----------|
| SUPER_ADMIN | 全部数据，所有操作 |
| SECURITY_AUDIT | 安全审计日志查看 |
| 决策层/管理层 | 本部门及下级部门数据 |
| 职能层/基层 | 个人数据 |

### 4.6 XSS / SQL 注入 / CSRF 防御

| 威胁 | 防御点 | 实现 |
|------|--------|------|
| 存储型 XSS | 前端渲染 | `v-safe-html` + DOMPurify |
| 反射型 XSS | 响应头 | CSP `script-src 'self'`（已移除 X-XSS-Protection） |
| MIME 嗅探 | 响应头 | `X-Content-Type-Options: nosniff` |
| 点击劫持 | 响应头 | `X-Frame-Options: DENY` |
| SQL 注入 | MyBatis | 全部使用 `#{}` 参数化 |
| SQL 注入 | 输入校验 | `@Valid` + 白名单 |
| CSRF | 会话策略 | JWT 无状态，禁用 CSRF（不依赖 Cookie 认证） |

### 4.7 密码与凭证安全

- **密码哈希**：`BCryptPasswordEncoder(12 rounds)`。
- **认证管理器**：显式 `ProviderManager` + `DaoAuthenticationProvider`，强制使用 `BCryptPasswordEncoder`。
- **密码历史**：`sys_password_history` 记录最近 N 次密码，防止重复使用。
- **密码重置令牌**：64 字符随机十六进制串，SHA-256 哈希存库，15 分钟过期，一次性使用。
- **首次登录强制改密**：`password_changed=false` 的用户登录后必须修改密码。

### 4.8 超级管理员密码重置安全规则

| 场景 | 规则 |
|------|------|
| 非超管重置超管密码 | 禁止 |
| 超管重置其他超管密码 | 禁止 |
| 非超管重置自己密码 | 禁止（必须使用密码修改） |
| 超管重置自己密码 | 需要二次验证（当前操作密码） |

### 4.9 安全响应头清单

```
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Strict-Transport-Security: max-age=31536000; includeSubDomains  # 仅 prod 下发
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

### 4.10 接口白名单

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

## 5. 工作流引擎集成

### 5.1 Flowable 引擎定位

Flowable 6.8.0 作为审批流转核心引擎，负责：

- BPMN 2.0 流程定义的部署、解析、执行。
- 用户任务（UserTask）的创建、认领、完成、委派。
- 并行会签（多实例任务）的状态同步。
- 异步任务（定时器、Service Task）的调度执行。

### 5.2 引擎配置（FlowableConfig）

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

### 5.3 BPMN 流程模型

典型审批流程结构：

```
[发起人] → (提交) → [部门主管审批] → ┬─ 通过 ─→ [并行会签: 技术/财务/法务] → [归档]
                                    ├─ 驳回 ─→ [发起人修改]
                                    └─ 委派 ─→ [被委派人] → [原审批人]
```

- **UserTask**：人工审批节点，通过 `candidateGroups` / `assignee` 指派。
- **ServiceTask**：系统自动节点，禁止 ScriptTask。
- **并行多实例**：会签场景，通过事件监听器同步状态。

### 5.4 ScriptTask 安全拦截

**红线策略**：在 BPMN 解析阶段拒绝任何 `scriptTask`，防止恶意 `groovy` / `javascript` 脚本导致 RCE。

```java
customPreHandlers.add(new ScriptTaskParseHandler() {
    @Override
    protected void executeParse(BpmnParse bpmnParse, ScriptTask scriptTask) {
        throw new SecurityException("禁止部署包含 ScriptTask 的流程定义...");
    }
});
```

### 5.5 AsyncExecutor 生命周期治理

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

### 5.6 流程定义文件

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

### 5.7 启动期 BPMN 部署校验

`StartupCheckRunner` 在容器就绪后校验核心 BPMN 是否已部署：

```java
long count = repositoryService.createProcessDefinitionQuery().count();
if (count == 0) {
    log.warn("⚠️ 未部署任何流程定义，审批流转功能不可用");
}
```

仅告警，不阻断启动，避免首次部署时系统无法启动。

---

## 6. 关键文件索引

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

---

## 7. 附录：架构优化方向

以下为历史优化提案，其中多数（order_process_link 解耦、DEK/KEK 分层加密、日志分区等）已通过后续工程化修复落地，供演进参考。

数据一致性、安全深度、可扩展性和可观测性方面仍有提升空间
**一、架构层面的核心缺陷与解耦方案**

当前架构最突出的问题是工单主表与流程引擎强耦合。`work_order` 表直接存放 `process_instance_id`、`current_node`、`current_assignee` 等运行时状态，这导致：
- 业务表与 Flowable 生命周期绑定，后续若替换引擎或升级版本，业务表不得不随之调整。
- 工单状态更新依赖流程引擎反向写入，容易出现数据不一致。
- 查询工单详情时，冗余的当前节点信息可能滞后，需要额外同步机制。

**优化方案**：
新建一张轻量级的关联表 `order_process_link`，仅包含 `work_order_id` 和 `process_instance_id` 两个关键字段。工单表只保留业务状态（如 DRAFT、PENDING、APPROVED），不再承载任何流程运行时数据。流程引擎推送状态变更时，通过领域事件通知工单上下文更新业务状态，而不是直接修改工单表的冗余列。前端需要展示当前审批人、节点名称时，通过流程实例 ID 实时调用 Flowable API 或从缓存获取。这样工单模块与流程引擎彻底解耦，两边可独立演进。

**二、数据一致性的深层保障**

尽管规范禁止数据库外键且要求应用层校验，但在并发、异常或人工订正脚本场景下，仍极易产生悬空引用。例如：用户被逻辑删除后，`work_order.applicant_id` 指向不存在的记录；或者关系表残留孤行。

**多层防护策略**：
- 在 Service 层的写操作中使用 Postgres 的 `pg_advisory_xact_lock` 或乐观锁 `version` 字段，防止并发误删。
- 建立定时对账任务（例如每 30 分钟一次），扫描 `work_order.applicant_id`、`approval_log.operator_id` 等引用字段，与 `sys_user` 比对，发现指向已逻辑删除用户的记录时，自动记录异常并尝试修复（如标记“申请人已离职”）。
- 利用 CDC 工具（如 Debezium）捕获 `sys_user` 的逻辑删除事件，触发关联数据处理，做到准实时补偿，而不仅依赖定时任务。

**三、安全体系的全面升级**

**JWT 无状态 → 混合状态管理**
当前 JWT 签发后直至过期始终有效，用户被禁用或密码重置后，旧 Token 仍可访问。改进方法：在 JWT 荷载中增加 `tokenVersion` 字段，与数据库用户表中的版本号对应。每次用户改密或被管理员禁用时，版本号递增。认证过滤器校验版本号，不匹配则直接拒绝。再配合将 Token 存储从 `localStorage` 迁移至 `HttpOnly; Secure; SameSite=Strict` 的 Cookie，彻底杜绝 XSS 窃取。

**前端 HMAC 签名 → 服务端 CSRF Token 或非对称挑战**
浏览器端使用共享对称密钥计算 HMAC，密钥很容易通过开发工具泄露，签名机制形同虚设。应立即废弃。采用 Spring Security 内置的 CSRF 防护，配合 Cookie 的 SameSite 属性即可有效防止跨站请求伪造。如果确有需求验证请求体完整性，可引入 WebCrypto API 生成不可导出的 ECDSA 密钥对：登录时前端生成密钥对，将公钥提交服务端注册；后续敏感请求用私钥签名，服务端用公钥验签。私钥永不出浏览器。

**AES 单密钥 → 分层加密与密钥轮换**
当前所有敏感字段共用同一 AES 密钥，一旦泄露所有历史数据都可解密，且无法实现密钥轮换。企业级做法是引入数据加密密钥（DEK）和密钥加密密钥（KEK）：
- 为每条敏感记录（或每个用户）生成独立的 DEK，用 DEK 加密手机号等字段。
- DEK 本身用 KEK 加密后存于数据库（如 `sys_user.encrypted_dek` 列）。
- KEK 来自外部密钥管理服务（Vault 或云 KMS），应用启动时获取并缓存在内存。
- 密钥轮换时只需重新加密 DEK，历史密文无需改动。
开发环境必须使用隔离密钥，生产环境硬断校验 KEK 来源。

**四、数据库性能与设计优化**

**日志表分区并移除逻辑删除**
`approval_log`、`sys_security_audit_log`、`work_order_comment` 等追加写日志表不应有 `is_deleted` 列，删除审计轨迹是严重合规风险。应移除该列，改为纯追加写。同时启用 Postgres 声明式分区（按月），使用 `pg_partman` 自动管理。历史数据清理直接 `DROP PARTITION`，避免逐行 DELETE 导致的膨胀和锁竞争。查询时务必带上 `create_time` 条件以利用分区裁剪。

**枚举字段字典化**
`work_order.status`、`approval_log.action` 等使用 VARCHAR 存储少量枚举值，既浪费空间又降低索引效率。全部改为 `SMALLINT`，配合 CHECK 约束和代码层枚举类。例如状态码：1-草稿，2-审批中，3-已通过，4-已驳回，5-已归档，6-已终止。这样索引体积更小，比较更快，且杜绝拼写错误。

**索引清理与补充**
删除被唯一约束覆盖的冗余索引，如 `sys_user` 上的 `idx_sys_user_username` 普通索引，以及被复合索引覆盖的 `idx_password_history_user_id`。为待办列表等高频查询创建条件索引：`CREATE INDEX idx_work_order_pending ON work_order(status, current_assignee) WHERE status = 2;`（假设 2 代表审批中）。

**统一时间类型**
将所有 `TIMESTAMP WITHOUT TIME ZONE` 改为 `TIMESTAMPTZ`（`TIMESTAMP WITH TIME ZONE`）。设置数据库 `timezone = 'UTC'`，后端实体使用 `Instant` 或 `OffsetDateTime`，序列化时输出 ISO-8601 格式带时区偏移。这样无论部署在哪个时区，时间都表示同一时刻，彻底规避歧义。