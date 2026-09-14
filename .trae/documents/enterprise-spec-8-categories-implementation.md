# 企业级研发体系8类统一规范 — 落地执行方案

## Context

工单审批系统当前仅部分符合企业级研发规范。经逐条审查8类规范与代码库的差距，发现以下核心缺陷：
- 错误码使用数字常量（20000/40001），不符合三段式字符串规范（01-000-001）
- Result 响应体缺少 traceId 字段
- 无 SystemException/ValidationException 异常分类
- API URL 无版本前缀、使用单数名词、无连字符
- 数据库部分表缺少 is_deleted/create_by/update_by 审计字段
- 配置前缀 `app.*` 不符合 `{应用名}.{模块}.{key}` 规范
- 部分敏感操作缺少二次鉴权
- 前端 API 路径需同步更新

本方案严格、完整落地全部8类规范，严禁删减、降级、变通。

---

## 阶段一：错误码体系 + 异常分类 + Result 增强（规范 2 + 规范 7）

> 所有后续阶段的前置依赖

### Step 1: 新建 ErrorCode 枚举 — 错误码注册中心

**新建** `backend/src/main/java/com/workorder/common/exception/ErrorCode.java`

```java
public enum ErrorCode {
    // ===== 公共模块 000 =====
    SUCCESS("01-000-000", 200, "操作成功", "Success", ErrorLevel.INFO, 20000),
    SYSTEM_ERROR("01-000-001", 500, "系统内部错误", "Internal server error", ErrorLevel.ERROR, 50001),
    PARAM_INVALID("01-000-002", 400, "参数缺失或非法", "Invalid parameter", ErrorLevel.WARN, 40001),
    AUTH_FAILED("01-000-003", 401, "身份认证失败", "Authentication failed", ErrorLevel.WARN, 40101),
    ACCESS_DENIED("01-000-004", 403, "无访问权限", "Access denied", ErrorLevel.WARN, 40301),
    RESOURCE_NOT_FOUND("01-000-005", 404, "请求资源不存在", "Resource not found", ErrorLevel.WARN, 40401),
    RATE_LIMITED("01-000-006", 429, "请求太频繁", "Too many requests", ErrorLevel.WARN, null),
    REPEAT_REQUEST("01-000-007", 409, "重复请求", "Duplicate request", ErrorLevel.WARN, 60001),

    // ===== 认证模块 001 =====
    LOGIN_BAD_CREDENTIALS("01-001-001", 200, "用户名或密码错误", "Bad credentials", ErrorLevel.WARN, 40101),
    LOGIN_ACCOUNT_LOCKED("01-001-002", 200, "账号已被锁定", "Account locked", ErrorLevel.WARN, 40101),
    LOGIN_ACCOUNT_DISABLED("01-001-003", 200, "账号已被禁用", "Account disabled", ErrorLevel.WARN, 40101),
    TOKEN_EXPIRED("01-001-004", 401, "令牌已过期", "Token expired", ErrorLevel.WARN, 40101),
    TOKEN_INVALID("01-001-005", 401, "令牌无效", "Invalid token", ErrorLevel.WARN, 40101),
    PASSWORD_CHANGE_FAILED("01-001-006", 200, "密码修改失败", "Password change failed", ErrorLevel.ERROR, null),
    REAUTH_FAILED("01-001-007", 403, "二次鉴权失败", "Re-authentication failed", ErrorLevel.WARN, 40301),

    // ===== 工单模块 002 =====
    WORKORDER_CREATE_FAILED("01-002-001", 200, "工单创建失败", "Work order creation failed", ErrorLevel.ERROR, null),
    WORKORDER_SUBMIT_FAILED("01-002-002", 200, "工单提交失败", "Work order submission failed", ErrorLevel.ERROR, null),
    WORKORDER_NOT_FOUND("01-002-003", 404, "工单不存在", "Work order not found", ErrorLevel.WARN, 40401),
    WORKORDER_STATUS_CONFLICT("01-002-004", 409, "工单状态冲突", "Work order status conflict", ErrorLevel.WARN, null),
    WORKORDER_VERSION_MISMATCH("01-002-005", 409, "数据已被修改", "Data version mismatch", ErrorLevel.WARN, null),

    // ===== 审批模块 003 =====
    APPROVAL_ACTION_INVALID("01-003-001", 400, "审批动作无效", "Invalid approval action", ErrorLevel.WARN, null),
    APPROVAL_NOT_ASSIGNEE("01-003-002", 403, "非当前审批人", "Not current assignee", ErrorLevel.WARN, 40301),

    // ===== 员工模块 004 =====
    EMPLOYEE_NOT_FOUND("01-004-001", 404, "员工不存在", "Employee not found", ErrorLevel.WARN, 40401),
    EMPLOYEE_USERNAME_EXISTS("01-004-002", 409, "用户名已存在", "Username already exists", ErrorLevel.WARN, null),

    // ===== 密码模块 007 =====
    PASSWORD_RESET_TOKEN_INVALID("01-007-001", 400, "重置令牌无效或已过期", "Reset token invalid or expired", ErrorLevel.WARN, null),
    PASSWORD_POLICY_VIOLATION("01-007-002", 400, "密码不符合策略要求", "Password policy violation", ErrorLevel.WARN, null),

    // ===== 数据库模块 998 =====
    DB_CONNECTION_FAILED("01-998-001", 500, "数据库连接失败", "Database connection failed", ErrorLevel.ERROR, 50001),
    DB_QUERY_TIMEOUT("01-998-002", 500, "数据库查询超时", "Database query timeout", ErrorLevel.ERROR, 50001),
    ;

    private final String bizCode;        // 三段式字符串 "01-000-001"
    private final int httpStatus;        // 建议 HTTP 状态码
    private final String messageZh;      // 中文消息模板
    private final String messageEn;      // 英文翻译
    private final ErrorLevel level;      // 错误级别
    private final Integer legacyCode;    // 旧数值码（向后兼容，过渡期后移除）
}
```

附带 `ErrorLevel` 枚举（ERROR/WARN/INFO）作为内部枚举。

### Step 2: 新建 ErrorCodeRegistry — 错误码注册表

**新建** `backend/src/main/java/com/workorder/common/exception/ErrorCodeRegistry.java`

功能：
- `static ErrorCode lookup(String bizCode)` — 按 bizCode 查找
- `static ErrorCode lookupByLegacyCode(int legacyCode)` — 按旧码查找
- `static String renderMessage(String bizCode, Object... args)` — 渲染带占位符消息
- `@PostConstruct` 启动自检：扫描所有枚举值确保无重复 bizCode，重复则拒绝启动

### Step 3: 修改 Result.java — 增加 bizCode + traceId

**修改** `backend/src/main/java/com/workorder/common/result/Result.java`

改动：
1. 新增 `private String bizCode` 字段 — 三段式错误码
2. 新增 `private String traceId` 字段 — 从 MDC.get("traceId") 自动填充
3. 构造函数中自动填充 traceId：`this.traceId = org.slf4j.MDC.get("traceId")`
4. 新增工厂方法 `Result.fail(ErrorCode errorCode, Object... args)` — 新规范推荐入口
5. 现有工厂方法（success/error/unauthorized/forbidden/notFound/paramError）全部增加 bizCode 填充
6. `code` 字段添加 `@Deprecated` 注解 + Javadoc 说明过渡期

### Step 4: 修改 BusinessException.java — 支持 ErrorCode 枚举

**修改** `backend/src/main/java/com/workorder/common/exception/BusinessException.java`

改动：
1. 新增 `private ErrorCode errorCode` 字段
2. 新增构造函数 `BusinessException(ErrorCode errorCode, Object... args)`
3. 保留旧构造函数，内部映射到 ErrorCode

### Step 5: 新建 SystemException.java — 系统异常

**新建** `backend/src/main/java/com/workorder/common/exception/SystemException.java`

- 继承 RuntimeException
- 携带 ErrorCode + 原始异常 cause
- 用于数据库连接失败、Redis 不可用等非业务异常
- 不暴露内部细节给前端，统一返回 `01-998-001`

### Step 6: 新建 ValidationException.java — 校验异常

**新建** `backend/src/main/java/com/workorder/common/exception/ValidationException.java`

- 继承 RuntimeException
- 携带 ErrorCode + 字段级错误列表 `List<FieldViolation>`
- FieldViolation 内部类：field + message

### Step 7: 修改 GlobalExceptionHandler.java — 适配新异常体系

**修改** `backend/src/main/java/com/workorder/config/GlobalExceptionHandler.java`

改动：
1. 新增 `@ExceptionHandler(SystemException.class)` — 返回 500 + ErrorCode
2. 新增 `@ExceptionHandler(ValidationException.class)` — 返回 400 + 字段级错误
3. 修改 `handleBusinessException()` — 从 BusinessException.errorCode 提取 bizCode
4. 修改通用兜底 `handleException()` — 构造 SystemException 并输出
5. 所有 handler 返回的 Result 统一通过 `Result.fail(errorCode)` 构建

### Step 8: 前端适配双码制

**修改** `frontend/src/constants/index.js`
- 新增 `BIZ_CODE` 常量对象，映射三段式错误码
- 保留 `RESULT_CODE` 向后兼容

**修改** `frontend/src/utils/request.js`
- 成功判断：`res.bizCode === '01-000-000' || res.code === RESULT_CODE.SUCCESS`
- 401 判断：`bizCode === '01-000-003' || code === 40101`
- 从 `res.traceId` 提取并更新到 logger

---

## 阶段二：API 设计规范落地（规范 1）

### Step 9: API 版本前缀

**修改** `backend/src/main/resources/application-dev.yml`
- `context-path: /api` → `context-path: /api/v1`

**修改** `backend/src/main/resources/application-prod.yml`（同上）

**修改** `frontend/vite.config.js`
- proxy `/api` 的 rewrite 保持不变（前端 baseURL 改为 `/api/v1`）

**修改** `frontend/src/utils/request.js`
- `baseURL: '/api'` → `baseURL: '/api/v1'`

**修改** `backend/src/main/java/com/workorder/config/SecurityConfig.java`
- 验证所有 antMatchers 路径在 context-path 变更后仍正确（Spring Security 匹配的是 context-path 之后的路径，无需改动）

### Step 10: URL 复数名词 + 连字符

URL 映射表（Controller @RequestMapping + 前端 API URL 同步更新）：

| 旧路径 | 新路径 | Controller |
|--------|--------|-----------|
| `/workorder` | `/work-orders` | WorkOrderController |
| `/auth` | `/auth` | AuthController（auth 是不可数名词，保持） |
| `/employee` | `/employees` | EmployeeController |
| `/approval` | `/approvals` | ApprovalController |
| `/process` | `/processes` | ProcessController |
| `/system/settings` | `/system/settings` | SystemSettingController（已合规） |
| `/password-reset` | `/password-resets` | PasswordResetController |
| `/client-log` | `/client-logs` | ClientLogController |

AuthController 子路径重构：

| 旧路径 | 新路径 | HTTP方法 | 说明 |
|--------|--------|---------|------|
| `/auth/login` | `/auth/sessions` | POST | 登录=创建会话 |
| `/auth/logout` | `/auth/sessions` | DELETE | 登出=删除会话 |
| `/auth/refresh-token` | `/auth/sessions/refresh` | POST | 刷新令牌 |
| `/auth/change-password` | `/auth/password` | PUT | 修改密码 |
| `/auth/current-user` | `/auth/me` | GET | 当前用户 |
| `/auth/register` | `/auth/users` | POST | 注册用户 |

**修改所有 Controller** 的 @RequestMapping + @GetMapping/@PostMapping 等
**修改所有前端 API 文件** 的 URL 路径：
- `frontend/src/api/workorder.js`
- `frontend/src/api/auth.js`
- `frontend/src/api/log.js`
- 其他引用后端 API 的文件

### Step 11: 旧 URL 兼容重定向

**新建** `backend/src/main/java/com/workorder/filter/ApiCompatFilter.java`

功能：
- 拦截旧路径请求（如 `/v1/workorder/**`），返回 301 重定向到新路径（`/v1/work-orders/**`）
- 响应头添加 `Deprecation: true` + `Sunset: <3个月后日期>`
- 使用 `@Profile("!prod")` 可在过渡期结束后移除

### Step 12: 分页响应规范化

**修改** `backend/src/main/java/com/workorder/common/result/PageResult.java`
- 字段 `pageNum` 增加 `@JsonProperty("page")` + `@JsonAlias("pageNum")`
- 字段 `pageSize` 增加 `@JsonProperty("page_size")` + `@JsonAlias("pageSize")`
- 实际序列化输出使用新字段名 `page`/`page_size`，反序列化同时接受新旧字段名

**修改** `backend/src/main/java/com/workorder/common/result/PageRequest.java`
- `pageNum` 增加 `@JsonAlias("page")`
- `pageSize` 增加 `@JsonAlias("page_size")`
- 新增 `sort` 参数支持规范格式 `?sort=-created_at,id`

**修改前端分页组件** — 请求参数适配 `page`/`page_size`，响应数据适配新字段名

---

## 阶段三：数据库设计规范补全（规范 3）

### Step 13: Flyway 迁移脚本

**新建** `backend/src/main/resources/db/migration/V9__enterprise_spec_compliance.sql`

内容：

1. **补充 is_deleted 字段**（已有 is_deleted 的表跳过）：
   - `sys_department` — ADD COLUMN is_deleted SMALLINT NOT NULL DEFAULT 0
   - `sys_position` — ADD COLUMN is_deleted SMALLINT NOT NULL DEFAULT 0
   - `sys_permission` — ADD COLUMN is_deleted SMALLINT NOT NULL DEFAULT 0
   - `sys_role` — ADD COLUMN is_deleted SMALLINT NOT NULL DEFAULT 0

2. **补充 update_time 字段**：
   - `sys_password_history` — ADD COLUMN update_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP

3. **补充 create_by/update_by 审计字段**：
   - `sys_department` — ADD create_by BIGINT; ADD update_by BIGINT
   - `sys_position` — ADD create_by BIGINT; ADD update_by BIGINT
   - `sys_permission` — ADD create_by BIGINT; ADD update_by BIGINT
   - `sys_role` — ADD create_by BIGINT; ADD update_by BIGINT
   - `sys_user_role` — ADD update_by BIGINT
   - `sys_role_permission` — ADD update_by BIGINT
   - `sys_user_position` — ADD update_by BIGINT
   - `sys_password_history` — ADD update_by BIGINT
   - `sys_system_setting` — ADD create_by BIGINT; ADD update_by BIGINT

4. **索引命名规范化**（ALTER INDEX RENAME TO）：
   - `uk_username` → `uk_sys_user_username`
   - `uk_role_code` → `uk_sys_role_role_code`
   - `uk_permission_code` → `uk_sys_permission_permission_code`
   - `uk_order_no` → `uk_work_order_order_no`

5. **状态字段 CHECK 约束**：
   - `sys_user.status` — CHECK (status IN (0, 1))
   - `sys_role.status` — CHECK (status IN (0, 1))
   - `work_order.priority` — CHECK (priority BETWEEN 1 AND 4)

6. **字段 COMMENT 补充** — 对新增字段添加 COMMENT ON COLUMN

### Step 14: 实体类同步更新

- 确认 `Department.java`/`Position.java`/`Permission.java`/`Role.java` 实体类包含新增字段
- 如实体类不存在，新建对应实体类

---

## 阶段四：代码风格与命名规范落地（规范 4）

### Step 15: 消除魔法值

**修改** `backend/src/main/java/com/workorder/controller/ApprovalController.java`
- `dto.setAction("APPROVE")` → 使用 ApprovalActionEnum 常量
- `dto.setAction("REJECT")` → 使用 ApprovalActionEnum 常量

**新建** `backend/src/main/java/com/workorder/common/enums/ApprovalActionEnum.java`
- APPROVE("APPROVE"), REJECT("REJECT"), RETURN("RETURN"), TERMINATE("TERMINATE")

**修改** `backend/src/main/java/com/workorder/controller/SystemSettingController.java`
- 消除手写 try-catch + `Result.error("...")`，改为 throw SystemException/ValidationException

### Step 16: Checkstyle 配置

**新建** `backend/checkstyle.xml`

**修改** `backend/pom.xml` — 添加 maven-checkstyle-plugin
- 行宽 120、缩进 4 空格、方法长度 50 行、禁止魔法值、命名规范

---

## 阶段五：配置管理规范落地（规范 6）

### Step 17: 配置命名规范化

将所有 `app.*` 前缀改为 `work-order-system.*`：

**修改** `application.yml` / `application-dev.yml` / `application-prod.yml`
- `app.db.*` → `work-order-system.db.*`
- `app.rate-limit.*` → `work-order-system.rate-limit.*`
- `app.security.*` → `work-order-system.security.*`

**修改所有引用** `@Value("${app.*")` 的 Java 文件：
- `SecurityConfig.java`
- `EnterpriseSecurityFilter.java`
- `CorsConfig.java`
- `DekService.java`
- `AesTypeHandlerInitializer.java`
- 其他引用 `app.*` 的类

### Step 18: 配置中心架构预留

**新建** `backend/src/main/java/com/workorder/config/ExternalConfigProperties.java`
- `@ConfigurationProperties(prefix = "work-order-system")` 集中管理
- 文档注释说明未来接入 Nacos/Apollo 的集成点

### Step 19: 敏感配置校验

**新建** `backend/src/main/java/com/workorder/config/SensitiveConfigValidator.java`
- `@Profile("prod")` 下生效
- `@PostConstruct` 检查环境变量是否已注入
- 缺失时拒绝启动

---

## 阶段六：安全开发规范补全（规范 8）

### Step 20: 敏感操作二次鉴权

**新建** `backend/src/main/java/com/workorder/annotation/RequireReAuth.java`
- 自定义注解，标记需要二次鉴权的端点

**新建** `backend/src/main/java/com/workorder/aspect/SensitiveOperationAspect.java`
- 校验请求头 `X-Reauth-Password`（用户当前操作密码）
- 验证通过后允许操作，否则返回 `01-001-007`

**标记端点**：
- `EmployeeController.deleteEmployee()`
- `EmployeeController.resetPassword()`
- `SystemSettingController.saveSecuritySettings()`
- `ProcessController.deployProcess()`
- `ProcessController.deleteDeployment()`

### Step 21: 依赖漏洞扫描

**修改** `backend/pom.xml` — 添加 OWASP dependency-check-maven 插件

### Step 22: 文件上传白名单加固

**检查并修改** `backend/src/main/java/com/workorder/util/FileUploadValidator.java`
- 确认包含文件后缀白名单 + Magic Number 校验 + 文件大小限制

---

## 阶段七：自动化校验与 CI 集成（规范 1 + 规范 4）

### Step 23: OpenAPI 文档生成

**修改** `backend/pom.xml` — 添加 springdoc-openapi 依赖

**新建** `backend/src/main/java/com/workorder/config/OpenApiConfig.java`
- 配置 API 文档元信息
- 自动生成 OpenAPI 3.0 规范文档
- 集成 ErrorCode 描述

---

## 验证方案

### 后端验证
1. `mvn compile` 编译通过
2. ErrorCode 枚举启动自检无重复 bizCode
3. 请求任意 API → 响应体含 bizCode + traceId 字段
4. 触发 BusinessException → 响应体 bizCode 为三段式格式
5. 触发 SystemException → 返回 500 + 01-998-001
6. 旧 URL 请求 → 301 重定向到新 URL + Deprecation 头
7. 分页响应使用 `page`/`page_size` 字段名
8. Checkstyle 检查通过

### 前端验证
1. `vite build` 构建成功
2. API 请求使用 `/api/v1/` 前缀 + 新 URL 路径
3. 响应拦截器正确处理 bizCode 双码制
4. 分页请求使用 `page`/`page_size` 参数
5. 错误提示正确显示中文消息

### 数据库验证
1. Flyway 迁移成功执行 V9
2. 所有目标表含 is_deleted/create_by/update_by
3. 索引命名符合 `idx_tablename_column` / `uk_tablename_column` 规范
4. CHECK 约束生效

### 端到端验证
1. 前端登录 → 新路径 `/api/v1/auth/sessions` → 响应含 bizCode + traceId
2. 前端错误判断兼容新旧码
3. 旧 URL 访问 → 重定向 + Deprecation 头
