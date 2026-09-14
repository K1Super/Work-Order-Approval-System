# 工单审批流转系统 — 安全加固延续执行计划（Resume Execution）

> **文档版本**：v2.0（延续版）
> **执行日期**：2026-07-19
> **执行标准**：`.cursor/skills/impeccable/SKILL.md` + 用户安全策略（8 大类 §2-§9）
> **核心约束**：禁止简化、禁止替换方案、禁止省略、强制实行；每阶段完成后必须 `mvn compile` 与 `npm run build` 双双通过。
> **前序计划**：`.trae/documents/enterprise-security-hardening-plan.md`（v1.0，已审批，阶段 0-1 已完成）

---

## 一、摘要（Summary）

本延续计划承接上一会话的中断状态。原 8 阶段计划已审批，**阶段 0（依赖与基础设施）** 与 **阶段 1（关键安全漏洞封堵）** 已完成并验证；**阶段 2（数据权限层重建）** 部分完成，但存在 **1 个编译破坏性变更** 与 **6 项未收尾** 工作；阶段 3-8 全部待执行。

本计划聚焦剩余工作，确保按用户要求"编译运行后无任何报错"，并完整交付 §2-§9 全部安全策略。每项变更均基于对实际代码的核对，引用精确的文件路径与行号。

---

## 二、当前状态分析（Current State Analysis）

### 2.1 已完成（阶段 0-1，已编译验证）

| 文件 | 状态 |
|------|------|
| `backend/pom.xml` | ✅ jjwt 0.11.5、Jsoup 1.17.2、Actuator、Flyway 已添加 |
| `backend/.../security/JwtUtil.java` | ✅ jjwt 0.11.5 API 迁移完成 |
| `backend/.../config/RedisConfig.java` | ✅ BasicPolymorphicTypeValidator 替换 LaissezFaireSubTypeValidator |
| `backend/.../config/SecurityConfig.java` | ✅ 移除 `/emergency/**`、`/auth/register` permitAll；Actuator 限超管 |
| `backend/.../controller/EmergencyController.java` | ✅ @Profile("dev") + @PreAuthorize |
| `backend/.../controller/SystemSettingController.java` | ✅ 类级 + 端点级 @PreAuthorize |
| `backend/.../config/DatabaseAutoCreateConfig.java` | ✅ 去硬编码、@ConditionalOnProperty |
| `backend/.../security/JwtAuthenticationFilter.java` | ✅ 移除 URL ?token= |
| `backend/.../security/CustomUserDetails.java` | ✅ 加 departmentId/orgLevel/positionId/roles 字段 |
| `backend/.../security/CustomUserDetailsService.java` | ✅ 加载 roles 与 orgLevel |
| `backend/.../controller/AuthController.java` | ✅ register 加 @PreAuthorize |
| `backend/src/main/resources/application.yml` | ✅ Actuator 端点收窄 |
| `backend/src/main/resources/application-dev.yml` | ✅ 环境变量化 |
| `docker-compose.yml` | ✅ PG + Redis（带 requirepass 与 renamed commands） |
| `.env.example` | ✅ 全量环境变量清单 |
| `backend/src/main/resources/db/migration/V1__init.sql` | ✅ Flyway V1（含 sys_password_history） |

### 2.2 阶段 2 已完成部分

| 文件 | 状态 |
|------|------|
| `backend/.../common/RoleConstants.java` | ✅ 统一角色 ID 映射常量类已创建（1, 2, 10-12, 20-25, 30-36, 40） |
| `backend/.../annotation/DataPermission.java` | ✅ 加 dataScope 与 requireDepartmentMatch 字段 |
| `backend/.../aspect/DataPermissionAspect.java` | ✅ checkDataOwnership 真实实现，fail-closed |
| `backend/.../controller/WorkOrderController.java` | ✅ getWorkOrderByNo/archiveWorkOrder/updateWorkOrder 加 @DataPermission |

### 2.3 🔴 阶段 2 剩余工作（含编译破坏性问题）

经实际核对代码确认以下 7 项待办：

#### 阻塞编译问题 P0-1
**`backend/.../service/WorkOrderService.java:89`** — 接口仅有 `updateWorkOrder(WorkOrder workOrder)` 单参版本；
但 **`backend/.../controller/WorkOrderController.java:175`** 调用 `workOrderService.updateWorkOrder(workOrder, userId)` 双参版本。
**后果**：`mvn compile` 必定失败。
**修复**：接口加 `Result<WorkOrder> updateWorkOrder(WorkOrder workOrder, Long userId)` 重载；实现类补该方法（含所有权校验，仅发起人可改草稿）。

#### P0-2 `WorkOrderServiceImpl.java` 三处 fail-open / 缺校验
- **L851、L865** `isSameDepartmentOrSuperAdmin`：`assigneeUser == null` 时返回 `true`、末尾兜底返回 `true` — 均为 fail-open，须改为 fail-closed（返回 `false`）。
- **L521-527** `getWorkOrderByNo(String orderNo)`：无所有权校验，直接返回工单。
- **L1226-1248** `archiveWorkOrder`：仅校验状态，无所有权校验。
- **L1251-1265** `updateWorkOrder(WorkOrder)`：仅校验状态，无发起人校验；且无 `updateWorkOrder(WorkOrder, Long userId)` 重载。

#### P0-3 `ApprovalController.java:86-91` — `getApprovalLog` 缺 @DataPermission
任意 `workOrderId` 可查询审批日志（IDOR）。
修复：加 `@DataPermission(entityType = "APPROVAL_LOG", checkOwnership = true, resourceIdParam = "workOrderId")`。

#### P0-4 `EmployeeController.java` — 4 个端点缺 @PreAuthorize
- L134 `getDepartments`、L139 `getPositions`、L150 `getSuperiors`、L190 `getResignTypes` — 无任何鉴权注解，任意匿名请求可调用。
- 修复：均加 `@PreAuthorize("isAuthenticated()")`。

#### P0-5 `WorkOrderMapper.xml` — SQL 注入 + 缺 update_time
- **L78** `ORDER BY ${sortField} ${sortOrder}` — `${}` 拼接 SQL 注入。
- **L122-143** `<update id="update">` 的 `<set>` 块缺 `update_time` 字段，导致 `setUpdateTime` 不持久化。
- 修复：用 `<choose><when>` 白名单分支替换 `${}`；`<set>` 加 `<if test="updateTime != null">update_time = #{updateTime},</if>`。

#### P0-6 `DataSecurityServiceImpl.java` — 4 处逻辑缺陷
- **L139-142** `desensitizeData` catch 块 `return data` — fail-open，须返回脱敏占位 `"***"`。
- **L193** `if (orgLevel != 3 || !isFinanceRole(currentUser))` — 逻辑反转。应为 `if (orgLevel > 2 && !isFinanceRole(currentUser))`（orgLevel 0/1/2 管理层可见金额）。
- **L311-338** `canAccessWorkOrderData` — orgLevel 2/3/4/5 全部 `return true`（注释"由 SQL 过滤"，实际 SQL 未过滤），fail-open。须二次调用 `canUserViewWorkOrder` 校验。
- **L496-499** `getClientIp` 返回硬编码 `"SYSTEM_INTERNAL"`。须从 `RequestContextHolder` 解析 `X-Forwarded-For`。
- **L418-442** `getOrgLevel` + **L460-466** `isFinanceRole` + **L469-476** `isHRRole` 使用旧角色 ID（1-14），与 RoleConstants（1-40）不一致，须改用 RoleConstants。

#### P0-7 `EmployeeServiceImpl.java` — 5 处问题
- **L179** `logger.info("...(初始密码: {})", initialPassword)` — 明文密码入日志。
- **L186** 返回明文密码给前端。
- **L334、L345** `logger.info("...新临时密码: {}", ..., newPassword)` — 明文密码入日志（重置密码流程）。
- **L439** `employee.setPassword(passwordEncoder.encode("123456"))` — batchImport 硬编码弱密码。
- **L235-258** `deleteEmployee` 硬删除（破坏审计链）。须改为软删除（status=0 + 写入 sys_resigned_employee）。
- **L510-516** `isDeptAdmin` 用 `14L`、**L521-532** `getHighestUserRole` 用 15-22 — 旧角色 ID 映射，须改用 RoleConstants。

---

## 三、实施方案（Proposed Changes）

### 阶段 2 收尾（P0-1 至 P0-7）— 必须首先完成以解除编译阻塞

#### 2.1 修复 `WorkOrderService.java` 接口（P0-1）
**文件**：`backend/src/main/java/com/workorder/service/WorkOrderService.java`
**改动**：在 L89 之后新增重载方法签名：
```java
/**
 * 更新工单信息（带操作人 ID，用于所有权校验）
 * 阶段 2 修复 A-11/H-07：仅发起人可修改草稿状态工单
 */
Result<WorkOrder> updateWorkOrder(WorkOrder workOrder, Long userId);
```
保留原 `updateWorkOrder(WorkOrder workOrder)` 以兼容（标记为 `@Deprecated`）。

#### 2.2 修复 `WorkOrderServiceImpl.java`（P0-2）
**文件**：`backend/src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`

- **`isSameDepartmentOrSuperAdmin`（L850-866）** 改为 fail-closed：
  ```java
  if (assigneeUser == null) return false;  // fail-closed
  // ... 中间逻辑保留 ...
  return false;  // 末尾兜底改为 false
  ```
- **`getWorkOrderByNo`（L521）** 增加所有权校验：调用 `canUserViewWorkOrder(workOrder, currentUserId)`；非发起人/审批人/同部门管理层/view-all 权限则抛 `BusinessException("您没有权限访问此工单")`。需新增 `currentUserId` 参数（或在 Controller 层注入后传入）。鉴于接口签名 `getWorkOrderByNo(String)` 已被外部调用，方案为：**新增重载 `getWorkOrderByNo(String orderNo, Long currentUserId)`**，Controller 改调双参版本；原单参版本标记 `@Deprecated`。
- **`archiveWorkOrder`（L1226）** 增加所有权校验（同 getWorkOrderByNo）。签名改为 `archiveWorkOrder(Long workOrderId, Long userId)`，接口同步更新，Controller 已通过 @DataPermission 校验所有权，Service 层做二次纵深防御。
- **`updateWorkOrder(WorkOrder, Long userId)` 新增**：校验 `existing.getApplicantId().equals(userId)`，否则抛 `BusinessException("只能编辑自己创建的草稿工单")`。

#### 2.3 修复 `ApprovalController.java`（P0-3）
**文件**：`backend/src/main/java/com/workorder/controller/ApprovalController.java`
**改动**：L86 `getApprovalLog` 方法上加：
```java
@DataPermission(entityType = "APPROVAL_LOG", checkOwnership = true, resourceIdParam = "workOrderId")
```
加 import `com.workorder.annotation.DataPermission`。

#### 2.4 修复 `EmployeeController.java`（P0-4）
**文件**：`backend/src/main/java/com/workorder/controller/EmployeeController.java`
**改动**：在以下 4 个方法上加 `@PreAuthorize("isAuthenticated()")`：
- L134 `getDepartments`
- L139 `getPositions`
- L150 `getSuperiors`
- L190 `getResignTypes`

#### 2.5 修复 `WorkOrderMapper.xml`（P0-5）
**文件**：`backend/src/main/resources/mapper/WorkOrderMapper.xml`

- **L76-83** 替换为白名单分支：
  ```xml
  <choose>
      <when test="sortField == 'create_time' and sortOrder == 'ASC'">ORDER BY create_time ASC</when>
      <when test="sortField == 'create_time' and sortOrder == 'DESC'">ORDER BY create_time DESC</when>
      <when test="sortField == 'update_time' and sortOrder == 'ASC'">ORDER BY update_time ASC</when>
      <when test="sortField == 'update_time' and sortOrder == 'DESC'">ORDER BY update_time DESC</when>
      <when test="sortField == 'submit_time' and sortOrder == 'ASC'">ORDER BY submit_time ASC</when>
      <when test="sortField == 'submit_time' and sortOrder == 'DESC'">ORDER BY submit_time DESC</when>
      <when test="sortField == 'priority' and sortOrder == 'ASC'">ORDER BY priority ASC</when>
      <when test="sortField == 'priority' and sortOrder == 'DESC'">ORDER BY priority DESC</when>
      <otherwise>ORDER BY create_time DESC</otherwise>
  </choose>
  ```
- **L141 `<set>` 块末尾** 加：`<if test="updateTime != null">update_time = #{updateTime},</if>`

#### 2.6 修复 `DataSecurityServiceImpl.java`（P0-6）
**文件**：`backend/src/main/java/com/workorder/service/impl/DataSecurityServiceImpl.java`

- **L139-142** catch 块改 fail-closed：构造脱敏后的占位结果返回（每条记录字段值置 `"***"`），不再 `return data`。
- **L193** 改为 `if (orgLevel > 2 && !isFinanceRole(currentUser))`。
- **L311-338** `canAccessWorkOrderData`：orgLevel 2/3/4/5 不再无条件 `return true`，改为调用 `WorkOrderMapper.selectById(workOrderId)` 后校验 `applicantId == currentUserId` 或同部门或 view-all 权限。注入 `WorkOrderMapper`。
- **L496-499** `getClientIp` 改为：
  ```java
  private String getClientIp() {
      try {
          HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder
                  .currentRequestAttributes()).getRequest();
          String ip = req.getHeader("X-Forwarded-For");
          if (ip != null && !ip.isEmpty()) {
              return ip.split(",")[0].trim();
          }
          return req.getRemoteAddr();
      } catch (Exception e) {
          return "SYSTEM_INTERNAL";
      }
  }
  ```
- **L418-442** `getOrgLevel` 改用 `RoleConstants.getOrgLevelByRoleId(roleId)` 遍历 roleIds 取最小值。
- **L460-466** `isFinanceRole` 改用 `RoleConstants.isFinanceRole(roleId)` 遍历。
- **L469-476** `isHRRole` 改用 `RoleConstants.isHrRole(roleId)` 遍历。

#### 2.7 修复 `EmployeeServiceImpl.java`（P0-7）
**文件**：`backend/src/main/java/com/workorder/service/impl/EmployeeServiceImpl.java`

- **L179** 改为 `logger.info("员工创建成功: {} (初始密码通过安全通道下发)", employee.getUsername());`
- **L186** 不再返回明文密码。改为返回一次性 token：`Result.success("员工创建成功，初始密码已生成，请凭 token 在 5 分钟内调用 /api/password/reveal/{token} 取回", createdUser)`。token 存 Redis（`pwd:reveal:{token}` → 明文密码，TTL 300s）。
  - 注：本阶段先简化为"明文密码改为通过日志脱敏 + 不返回明文到响应体，由管理员另行通知"，完整 token 机制在阶段 4 实现（涉及 Redis 与加密）。本阶段改为：响应只返回成功消息，不含密码；日志不打印明文。
- **L334、L345** 同样移除明文密码日志。
- **L439** batchImport 改为为每个用户生成随机强密码（已有 `generateRandomPassword` 方法，L575+），不再用 `"123456"`。
- **L235-258** `deleteEmployee` 改为软删除：
  ```java
  User update = new User();
  update.setId(id);
  update.setStatus(0);  // 软删除：禁用
  userMapper.updateById(update);
  // 同步写入 sys_resigned_employee（type=4 软删除）
  ```
- **L510-516** `isDeptAdmin` 改用 `RoleConstants.isDeptAdmin(roleId)` 遍历 roleIds。
- **L521-532** `getHighestUserRole` 改用 RoleConstants 常量与 `getOrgLevelByRoleId` 返回最高优先级角色代码。

**阶段 2 验证**：
```powershell
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\backend'
mvn -DskipTests compile
```
必须 BUILD SUCCESS。

---

### 阶段 3：输入与输出防护（Input/Output Protection）

**目标**：实现 §3 — SQL 注入、XSS、文件上传、CSRF、限流、CORS、防重放。

#### 3.1 `interceptor/SqlInjectionInterceptor.java` 重写
- 正则改为仅匹配真实注入模式：`(?i)union\s+select`、`(?i)\bor\s+1\s*=\s*1\b`、`--`、`(?i);\s*drop\b`、`(?i)\binsert\s+into\b`、`(?i)\bdelete\s+from\b`
- 移除对文本 `SELECT` 单词与 `0x` 十六进制的误拦截。

#### 3.2 `config/WebMvcConfig.java` 新建
- 实现 `WebMvcConfigurer.addInterceptors`，注册 `SqlInjectionInterceptor`
- `addPathPatterns("/**")`，`excludePathPatterns("/static/**", "/webjars/**", "/actuator/**")`

#### 3.3 `util/XssCleanUtil.java` 新建
- 静态方法 `clean(String input)`：`Jsoup.clean(input, Safelist.none())`
- 静态方法 `cleanHtml(String input)`：`Jsoup.clean(input, Safelist.simpleText())`
- 工单标题/内容用 `clean`，审批意见用 `clean`

#### 3.4 `config/XssFilter.java` 新建
- 实现 `Filter`，包装 `HttpServletRequestWrapper`，对 `getParameterValues`、`getInputStream`（JSON body）做 Jsoup 净化
- 注册到 `SecurityConfig` 过滤器链最前

#### 3.5 `service/impl/WorkOrderServiceImpl.java` + `ApprovalServiceImpl`
- `createWorkOrder`/`updateWorkOrder` 入参前调用 `XssCleanUtil.clean` 净化 title/content
- `handleApproval` 的 comment 字段净化

#### 3.6 `config/FileUploadConfig.java` 新建 + `controller/FileController.java` 新建
- `FileUploadConfig`：`maxFileSize=10MB`、`maxRequestSize=50MB`
- `FileUploadValidator`：后缀白名单（pdf,doc,docx,xls,xlsx,png,jpg,jpeg,zip）、Magic Number 校验（前 8 字节比对）、UUID 重命名
- `FileController`：上传 `@PreAuthorize("isAuthenticated()")`，下载校验 token + 所有权

#### 3.7 `config/EnterpriseSecurityFilter.java` 重写限流与 CSP
- **CSP 严格化**：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'`
- **EXCLUDED_PATHS** 改用 `AntPathMatcher` 精确匹配，不再 `uri.contains()`
- **限流**（用 Caffeine 本地令牌桶，替代 Bucket4j — 因 Bucket4j 在 Maven Central 解析失败已移除）：
  - 登录 `/auth/login`：5 次/分钟/IP
  - 审批 `/approval/**`：30 次/分钟/用户
  - 其他：100 次/分钟/用户
  - 命中返回 429 + `Retry-After` Header
  - 配置项：`app.rate-limit.login-per-minute`、`app.rate-limit.approve-per-minute`、`app.rate-limit.default-per-minute`

#### 3.8 `config/CorsConfig.java` 严格化
- `setAllowedHeaders` 改为显式列表：`Authorization, Content-Type, X-Requested-With, X-XSRF-TOKEN`
- dev 默认 origin 改为精确白名单：`http://localhost:5173,http://localhost:3000`
- prod 空白名单 fail-closed（抛异常）

#### 3.9 `annotation/AntiReplay.java` + `aspect/AntiReplayAspect.java` 新建
- 注解 `@AntiReplay` 应用于审批、密码重置、工单删除
- 切面校验请求头 `X-Request-Nonce` + `X-Request-Timestamp`
- Nonce 存 Redis 5 分钟（`request:nonce:` 前缀），重复拒绝
- Timestamp 与服务器偏差 > 5 分钟拒绝

**阶段 3 验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 提交 `<script>alert(1)</script>` 作为工单标题 → 存储后不含 script 标签
- `/auth/login` 1 分钟内 6 次请求 → 第 6 次返回 429

---

### 阶段 4：数据存储与加密（Data Storage & Encryption）

**目标**：实现 §5 — AES 加密、数据库安全、Redis 加固、密码策略。

#### 4.1 `util/AesEncryptionUtil.java` 新建
- AES-GCM 256 模式，密钥从 `${AES_ENCRYPTION_KEY}` 读取（32 字节 Base64）
- `encrypt(String plain)` / `decrypt(String cipher)` / `encryptIfNotNull`
- IV 每次随机，密文格式 `base64(iv) + ":" + base64(cipher)`

#### 4.2 `config/AesEncryptedStringTypeHandler.java` 新建
- MyBatis TypeHandler，写入前 `encrypt`，读取后 `decrypt`
- 处理 null 与非加密格式（兼容旧数据）

#### 4.3 `entity/User.java` + `mapper/UserMapper.xml`
- `phone`、`email` 字段加 `@TableField(typeHandler = AesEncryptedStringTypeHandler.class)`
- `selectList` 改为显式列名，排除 `password`
- 新增 `selectByIdSafe`：不返回 password

#### 4.4 `db/migration/V2__add_password_history.sql` 新建（如尚未建）
- 建表 `sys_password_history(id, user_id, password_hash, create_time)` + 索引

#### 4.5 `db/migration/V3__add_audit_indexes.sql` 新建
- 补全 `sys_security_audit_log` 索引：`(user_id, create_time)`、`(action_type, create_time)`

#### 4.6 `service/impl/PasswordPolicyServiceImpl.java` 强化
- 默认策略改为强策略：`requireDigit/requireSpecialChar/requireUppercase/requireLowercase: true`，`minPasswordLength: 10`
- `validatePasswordNotReused`：异常时 fail-closed 拒绝
- `recordFailedLoginAttempt`：改为按 IP+用户名组合锁定
- `AuthServiceImpl.changePassword` 末尾调用 `recordPasswordChange`（接通密码历史）

#### 4.7 `service/impl/EmployeeServiceImpl.java` 完整 token 机制
- 创建/重置密码后生成一次性 token 存 Redis，前端凭 token 5 分钟内取回

#### 4.8 `docs/redis-hardening.conf` 新建 + `docs/db-grants.sql` 新建
- redis.conf：`requirepass`、`rename-command FLUSHALL ""`、`rename-command CONFIG ""`、`bind 127.0.0.1`
- db-grants.sql：应用账户仅 CRUD，`REVOKE CREATE ON SCHEMA public`

**阶段 4 验证**：
- 启动后 `SELECT phone, email FROM sys_user` → 密文
- API 响应 `phone` → 明文（TypeHandler 解密）
- 修改密码 → `sys_password_history` 有新记录

---

### 阶段 5：日志与审计修复（Audit Logging Fixes）

**目标**：实现 §6 — 全量审计、实时告警、日志保护。

#### 5.1 `service/impl/AuthServiceImpl.java` 修 3 个 bug
- `expiration = jwtUtil.getExpirationDateFromToken(token).getTime()`（删除 `System.currentTimeMillis() +`）
- `changePassword` 的 `logLoginFailure` 参数顺序修正：`(username, clientIp, userAgent, "旧密码错误")`
- `refreshToken`：先校验 Redis Token 与请求一致，不一致返回 401

#### 5.2 `service/impl/SecurityAuditServiceImpl.java`
- `logSensitiveOperation`：从 `RequestContextHolder` 获取 IP/UA
- `queryAuditLogs`：异常向上抛，由 `GlobalExceptionHandler` 处理

#### 5.3 `src/main/resources/logback-spring.xml` 新建
- 控制台 + 文件双输出，按日滚动，保留 30 天
- 新建 `SensitiveDataConverter`：正则脱敏 `password=xxx`、`token=xxx`、`Authorization: Bearer xxx`
- 审计日志 appender 异步写入 `sys_security_audit_log`

#### 5.4 `config/MetricsConfig.java` 新建
- Prometheus 指标：`wos_login_attempts_total{result}`、`wos_authorization_denials_total{reason}`、`wos_sensitive_operations_total{type}`
- 在 `JwtAuthenticationFilter`、`DataPermissionAspect` 埋点

#### 5.5 `docs/alertmanager-rules.yml` 新建
- 5 分钟 401 > 20 → 告警；5 分钟 403 > 10 → 告警；1 分钟密码重置 > 5 → 告警

**阶段 5 验证**：
- 登录失败 → `sys_security_audit_log` IP 字段为真实 IP
- `/actuator/prometheus` 含 `wos_login_attempts_total`
- 日志中 `password=` 显示为 `password=***`

---

### 阶段 6：Flowable 工作流安全（Flowable Workflow Security）

**目标**：实现 §9 — 流程定义权限、流程变量安全、防任务哄抢。

#### 6.1 `controller/ProcessController.java`
- 部署/删除/挂起流程定义端点加 `@PreAuthorize("hasRole('SUPER_ADMIN')")`

#### 6.2 `config/FlowableConfig.java`
- 禁用脚本任务执行：配置 `setEnableSafeScripting(true)`，或解析阶段拒绝 `scriptTask`

#### 6.3 `service/impl/WorkOrderServiceImpl.java`
- 提交工单前清理流程变量：移除含 `password`/`secret`/`token` 的 key
- `approve` 方法调用 `taskService.claim` 前校验 `task.getCandidateGroups()` / `task.getAssignee()` 包含当前用户，否则抛 `BusinessException("您不是该任务的合法候选人")`

#### 6.4 `config/RedisMessageConfig.java` 新建
- Redis Pub/Sub 频道隔离：`wos:approval:{departmentId}` 前缀
- 消息签名：HMAC-SHA256，密钥 `${MESSAGE_SIGNING_KEY}`
- 接收方校验签名，不匹配丢弃 + 告警

#### 6.5 `listener/ApprovalEventListener.java` 新建
- `TaskCreatedListener`：候选人写入 Redis（`task:candidates:{taskId}`）
- `TaskCompletedListener`：删除候选人缓存
- 审批前校验 Redis 候选人

**阶段 6 验证**：
- 普通用户部署 BPMN → 403
- 非候选人审批 → `BusinessException`

---

### 阶段 7：前端加固与 impeccable 标准（Frontend Hardening & impeccable）

**目标**：实现 §3 前端部分 + 路由权限 + 按 impeccable 审计修复严重违规。

#### 7.1 运行 impeccable 审计
- 读 `.cursor/skills/impeccable/SKILL.md` 与 `reference/*.md`
- 若无 `frontend/PRODUCT.md`，按 `reference/init.md` 创建最小 PRODUCT.md
- 扫描所有 `.vue` 文件对照 Absolute bans 列表

#### 7.2 `frontend/package.json` 加依赖
- `dompurify: ^3.0.6`

#### 7.3 `frontend/src/utils/sanitize.js` 新建
- 封装 `DOMPurify.sanitize`，默认 `ALLOWED_TAGS: ['b','i','em','strong','br','p']`

#### 7.4 `frontend/src/main.js`
- 注册全局指令 `v-safe-html`，自动 sanitize
- 全局替换 `v-html` 为 `v-safe-html`

#### 7.5 `frontend/index.html`
- 加 CSP meta：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self' http://localhost:8080`

#### 7.6 `frontend/src/router/index.js`
- 路由守卫校验 `to.meta.permission`，无权限跳 `/403`
- 新建 `frontend/src/views/error/403.vue`

#### 7.7 `frontend/src/utils/request.js`
- 401 响应拦截器改为 `ElNotification` 静默提示 + 直接跳转，不再 `ElMessageBox.confirm`

#### 7.8 impeccable 关键修复
- Absolute bans 违规修复：side-stripe borders、gradient text、装饰性 glassmorphism、tiny uppercase tracked eyebrow、numbered section markers
- 新建 `frontend/src/styles/tokens.css`：OKLCH 变量 `--bg, --surface, --ink, --accent, --muted`
- 全局搜索硬编码 `#xxx` 替换为 `var(--xxx)`
- 所有动画加 `@media (prefers-reduced-motion: reduce)` 替代
- 对比度：body 文本 ≥ 4.5:1，placeholder ≥ 4.5:1

#### 7.9 `frontend/src/views/Login.vue` 重点提升
- 修复 Absolute bans 违规
- 量子主题视觉元素（用户偏好）
- 认证按钮顶级动效
- 不做全量重设计，仅修复违规 + 提升关键页面

**阶段 7 验证**：
- `npm run build` → 通过
- 浏览器控制台无 CSP 违规
- `v-html` 全局搜索为 0
- 普通员工访问 `/system/settings` → 跳转 403

---

### 阶段 8：部署与文档（Deployment & Documentation）

**目标**：实现 §7-§8 剩余项 + 修复文档与 SQL 不一致。

#### 8.1 `README.md`
- 修正 MySQL 8.0+ → PostgreSQL 13+
- 补充环境变量列表、docker-compose 启动、Flyway 迁移说明、生产部署清单

#### 8.2 `sql/init.sql`
- 顶部加注释标记 DEPRECATED，指向 Flyway 迁移脚本

#### 8.3 `backend/src/main/resources/sql/schema-init.sql`
- 与 Flyway V1__init.sql 同步（保持一致）
- 顶部加注释说明手动初始化备用

#### 8.4 `docs/nginx.conf` 新建
- 80 → 443 重定向、443 反代至 backend:8080
- 安全头：HSTS、X-Frame-Options DENY、X-Content-Type-Options nosniff、Referrer-Policy strict-origin、CSP
- 限流：`limit_req_zone` 登录接口
- 仅暴露 443

#### 8.5 `docs/https-automation.md` 新建
- acme.sh 与 cert-manager 两种方案示例

#### 8.6 `docs/deployment-checklist.md` 新建
- 生产环境检查清单：环境变量、数据库账户、Redis 密码、CORS、Actuator、日志路径、备份策略

#### 8.7 `.github/workflows/security-scan.yml` 新建
- OWASP Dependency-Check 扫描 Maven 依赖
- npm audit 扫描前端
- Trivy 扫描 Docker 镜像

#### 8.8 `backend/src/main/resources/application-prod.yml`
- 确认所有密钥用 `${}` 环境变量（无默认值）
- `app.db.auto-create: false`
- `spring.flyway.enabled: true`、`spring.flyway.baseline-on-migrate: true`
- Actuator：`management.endpoints.web.exposure.include: health,prometheus`

**阶段 8 验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- `npm run build` → 通过
- README 无 MySQL 字样
- Flyway 迁移脚本 V1/V2/V3 齐全

---

## 四、执行顺序与依赖（Execution Order）

```
阶段 2 收尾（P0-1 至 P0-7）— 解除编译阻塞
  └─ 阶段 3（输入输出防护）— 可与阶段 4 并行
       └─ 阶段 4（数据存储加密）
            └─ 阶段 5（日志审计修复）
                 └─ 阶段 6（Flowable 安全）
                      └─ 阶段 7（前端加固 + impeccable）
                           └─ 阶段 8（部署与文档）
```

每阶段独立编译验证通过后进入下一阶段。

---

## 五、假设与决策（Assumptions & Decisions）

### 5.1 关键决策（延续 v1.0 计划）
1. **限流实现**：用 Caffeine 本地令牌桶替代 Bucket4j（Bucket4j 在 Maven Central 解析失败）。生产可平滑替换为 Redis 分布式限流。
2. **明文密码处理（阶段 2）**：本阶段先改为"日志脱敏 + 响应不返回明文"，完整一次性 token 机制在阶段 4 实现（涉及 Redis 与加密）。
3. **`getWorkOrderByNo` 重载**：新增 `getWorkOrderByNo(String, Long)` 双参版本；原单参版本标记 `@Deprecated` 但保留（避免破坏外部调用）。
4. **`updateWorkOrder` 重载**：同上，新增双参版本，原单参 `@Deprecated`。
5. **`archiveWorkOrder` 重载**：新增 `archiveWorkOrder(Long, Long)`，Controller 改调双参版本。
6. **DataSecurityServiceImpl 的 canAccessWorkOrderData**：注入 `WorkOrderMapper` 做二次校验，不依赖 SQL 过滤的假设。
7. **EmployeeServiceImpl 软删除**：`deleteEmployee` 改为 `status=0`，不写入 `sys_resigned_employee`（避免循环依赖），仅记录审计日志。

### 5.2 假设
- 用户本地已安装 Java 17、Maven 3.9+、Node.js 18+、PostgreSQL 13+、Redis 6+（或使用 docker-compose）。
- 用户接受 dev 环境使用 `${ENV_VAR:default}` 模式（本地默认值便于启动），生产强制环境变量。
- 用户接受 Flyway 引入（首次部署执行 V1__init.sql 创建全部表）。
- 用户接受前端不做全量 UI 重设计，仅审计 + 修复严重违规 + 提升 Login.vue。
- 用户接受 `sql/init.sql` 被废弃（保留 `schema-init.sql` 与 Flyway 双轨）。

### 5.3 不做的事项（明确排除，延续 v1.0）
- 不升级 SpringBoot 2.7.18 → 3.x
- 不引入 Sentinel（用 Caffeine/Bucket4j 替代）
- 不引入 Vault/Kubernetes Secrets（用环境变量替代）
- 不引入 ELK（用 logback 文件 + Prometheus 替代）
- 不引入 WAF（基础设施层，文档说明）
- 不做前端全量 UI 重设计（仅审计 + 修复严重违规）

---

## 六、验证步骤（Verification）

### 6.1 每阶段强制验证
```powershell
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\backend'
mvn -DskipTests compile

cd '..\frontend'
npm run build
```
两者均必须成功，方可进入下一阶段。

### 6.2 阶段 2 收尾验证（编译阻塞解除）
```powershell
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\backend'
mvn -DskipTests compile
# 期望：BUILD SUCCESS（当前会因 P0-1 失败，修复后通过）
```

### 6.3 阶段 3 验证（输入防护）
```powershell
# XSS 测试
curl -X POST http://localhost:8080/api/workorder -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"title":"<script>alert(1)</script>","content":"..."}'
# 查询该工单，title 不含 script 标签

# 限流测试
1..10 | ForEach-Object { curl -i http://localhost:8080/api/auth/login -X POST -H "Content-Type: application/json" -d '{"username":"x","password":"y"}' }
# 期望：第 6 次起 429
```

### 6.4 阶段 4 验证（加密）
```sql
SELECT phone, email FROM sys_user WHERE id = 1;
-- 期望：密文
-- API 响应：明文（TypeHandler 解密）
```

### 6.5 阶段 5 验证（审计日志）
```sql
SELECT username, ip_address, user_agent, action_type FROM sys_security_audit_log ORDER BY id DESC LIMIT 1;
-- 期望：ip_address ≠ user_agent（真实 IP）
```

### 6.6 阶段 6 验证（Flowable）
```powershell
# 普通用户部署 BPMN → 403
# 非候选人审批 → BusinessException
```

### 6.7 阶段 7 验证（前端）
```powershell
cd frontend; npm run build
# 浏览器控制台无 CSP 违规
# v-html 全局搜索为 0
# 普通员工访问 /system/settings → 跳转 /403
```

### 6.8 阶段 8 验证（文档）
```powershell
Select-String -Path README.md -Pattern "MySQL"
# 期望：无匹配
Get-ChildItem backend/src/main/resources/db/migration/
# 期望：V1__init.sql, V2__add_password_history.sql, V3__add_audit_indexes.sql
```

### 6.9 最终全量验证
```powershell
cd backend; mvn clean package -DskipTests
# 期望：BUILD SUCCESS，jar 包生成

cd ..\frontend; npm run build
# 期望：dist/ 生成

# 端到端：
# 1. 登录（限流、审计日志、CSP）
# 2. 创建工单（XSS 净化、数据权限）
# 3. 审批（候选人校验、防重放）
# 4. 跨部门访问被拒（数据权限切面）
# 5. 普通用户改安全设置被拒（@PreAuthorize）
# 6. 紧急端点生产被拒（@Profile）
```

---

## 七、风险与缓解（Risks & Mitigation）

| 风险 | 缓解 |
|------|------|
| 阶段 2 P0-1 编译阻塞已确认 | 首要修复，修复后立即 `mvn compile` 验证 |
| DataSecurityServiceImpl 注入 WorkOrderMapper 可能循环依赖 | 用 `@Lazy` 注入或拆分为独立组件 |
| AES TypeHandler 影响现有明文数据 | 阶段 4 提供迁移脚本：`UPDATE sys_user SET phone = encrypt(phone) WHERE phone NOT LIKE 'enc:%'` |
| Caffeine 限流单实例不一致 | 文档说明生产换 Redis 分布式限流 |
| 前端 CSP 过严导致 Element Plus 失效 | style-src 保留 `'unsafe-inline'`（Element Plus 内联样式需要） |
| impeccable 审计发现大量违规 | 仅修复 Absolute bans 类硬性违规；其他记录到 `docs/impeccable-todo.md` |
| `getWorkOrderByNo`/`updateWorkOrder`/`archiveWorkOrder` 重载导致 Controller 调用不匹配 | 同步修改 Controller 调用点，编译验证 |

---

## 八、交付物清单（Deliverables）

- ✅ 阶段 2 收尾：7 项 P0 修复 + 编译通过
- ✅ 阶段 3：SqlInjectionInterceptor 注册、XssFilter、FileUpload、EnterpriseSecurityFilter 限流+CSP、CorsConfig、AntiReplay
- ✅ 阶段 4：AesEncryptionUtil、TypeHandler、Flyway V2/V3、PasswordPolicy 强化、Redis/DB 加固
- ✅ 阶段 5：AuthServiceImpl 3 bug、SecurityAuditServiceImpl、logback-spring.xml、MetricsConfig、告警规则
- ✅ 阶段 6：ProcessController 权限、FlowableConfig、候选人校验、RedisMessageConfig
- ✅ 阶段 7：DOMPurify、sanitize.js、v-safe-html、CSP meta、路由权限、403 页面、OKLCH tokens、Login.vue 提升
- ✅ 阶段 8：README、init.sql 废弃、schema-init.sql 同步、nginx.conf、https-automation、deployment-checklist、security-scan workflow、application-prod.yml
- ✅ 每阶段 `mvn compile` + `npm run build` 验证通过

---

**计划文件位置**：`c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\.trae\documents\security-hardening-resume-execution.md`

**待用户确认后立即从阶段 2 收尾（P0-1 至 P0-7）开始执行。**
