# OPTIMIZATION.md 全量刚性执行方案

## Context

`docs/OPTIMIZATION.md` 提出了 4 大领域共 10+ 项深度优化（架构解耦、数据一致性、安全体系、数据库设计），但当前代码库仍存在 6 项明确缺陷：work_order 表与 Flowable 强耦合（5 个运行时字段直接回写）、JWT 无 tokenVersion 失效机制、前端 HMAC 共享对称密钥形同虚设、AES 单密钥无轮换能力、日志表带 is_deleted 违反审计合规、枚举用 VARCHAR 存储降低索引效率。

用户已明确要求"刚性执行全部既定方案，严禁任何删减、变通、简化"，并确认 4 项关键决策：
1. **执行范围**：4 大领域全量落地
2. **CSRF 方案**：Spring Security 内置 CSRF Token
3. **KEK 来源**：dev 用环境变量 KEK_BASE64，prod 硬断校验 Vault
4. **数据迁移**：清空 work_order + approval_log + work_order_comment 重建（开发环境数据可清空）

本方案分 8 阶段按依赖关系推进，每阶段独立可验证，编译/启动/API 失败即视为本阶段未通过，禁止进入下一阶段。

---

## 阶段划分（按依赖关系排序）

| 阶段 | 名称 | 依赖 |
|------|------|------|
| P1 | 数据库 Schema 大重构（V7 单脚本） | 无 |
| P2 | Java 实体 / 枚举 / TypeHandler / Mapper 改造 | P1 |
| P3 | 架构解耦：order_process_link + 领域事件 | P1, P2 |
| P4 | 数据一致性：version 乐观锁 + 对账任务 + CDC | P1-P3 |
| P5 | JWT tokenVersion + HttpOnly Cookie | P1, P2 |
| P6 | CSRF：Spring Security 内置 + 废弃 HMAC | P5 |
| P7 | 分层加密 DEK/KEK + KEK 来源硬断 | P1, P2 |
| P8 | 配置文件 / 索引收尾 / 全链路验证 | P1-P7 |

P3 / P5 / P7 在 P2 完成后可并行推进；P4 必须在 P3 后；P6 必须在 P5 后；P8 是最终收尾。

---

## P1：数据库 Schema 大重构（V7 单脚本）

**新建**：`backend/src/main/resources/db/migration/V7__optimization_refactor.sql`

按执行顺序包含 7 段：

1. **清空重建**：`TRUNCATE TABLE work_order, approval_log, work_order_comment RESTART IDENTITY CASCADE`
2. **work_order 表**：
   - DROP 5 个流程字段：`process_instance_id`、`process_definition_id`、`current_node`、`current_assignee`、`current_assignee_name`
   - `status` VARCHAR → SMALLINT（CHECK 1-6），`order_type` VARCHAR → SMALLINT（CHECK 1-6）
   - 新增 `version BIGINT NOT NULL DEFAULT 0`（乐观锁）
   - 所有时间字段 ALTER 为 TIMESTAMPTZ（USING xxx AT TIME ZONE 'UTC'）
3. **新建 order_process_link 表**：`work_order_id`、`process_instance_id`、`process_definition_id` + 审计字段，UNIQUE 约束
4. **3 张日志表分区改造**（approval_log / sys_security_audit_log / work_order_comment）：
   - DROP COLUMN is_deleted（V6 添加的）
   - `CREATE TABLE xxx_new (LIKE xxx ...) PARTITION BY RANGE (create_time)` → 创建当月+下月分区 → 迁移 → DROP+RENAME
   - `CREATE EXTENSION IF NOT EXISTS pg_partman; SELECT partman.create_parent(...)`
   - approval_log 的 action/node_status/before_status/after_status 全部 VARCHAR → SMALLINT + CHECK
5. **sys_user 表**：新增 `token_version BIGINT NOT NULL DEFAULT 0`、新增 `encrypted_dek VARCHAR(500)`
6. **sys_permission**：`permission_type` VARCHAR → SMALLINT（CHECK 1-3）
7. **索引清理 + 条件索引**：
   - DROP `idx_sys_user_username`（被 UNIQUE 覆盖）
   - DROP `idx_password_history_user_id`（被复合索引覆盖）
   - 新建 `idx_work_order_pending ON work_order(status, applicant_id) WHERE status = 2`
   - 新建 `idx_opl_work_order_id ON order_process_link(work_order_id) WHERE is_deleted = 0`

**验证**：`\d work_order`、`SELECT * FROM pg_partitioned_table`、`SELECT * FROM pg_indexes WHERE indexname='idx_sys_user_username'`（应为空）

**禁止简化**：禁用 DELETE 代替 TRUNCATE；禁用普通表代替分区表；禁用 pg_partman 跳过

---

## P2：Java 实体 / 枚举 / TypeHandler / Mapper 改造

### 枚举改造（String code → int code）
- `WorkOrderStatusEnum`：DRAFT(1)/PENDING(2)/APPROVED(3)/REJECTED(4)/ARCHIVED(5)/TERMINATED(6)
- `ApprovalActionEnum`：SUBMIT(1)/APPROVE(2)/REJECT(3)/RETURN(4)/ARCHIVE(5)/TERMINATE(6)/RESUBMIT(7)/TRANSFER(8)
- 新建 `ApprovalNodeStatusEnum`：PENDING(1)/COMPLETED(2)/REJECTED(3)
- 新建 `PermissionTypeEnum`：MENU(1)/BUTTON(2)/API(3)
- 新建 `OrderTypeEnum`：LEAVE(1)/PURCHASE(2)/REIMBURSEMENT(3)/REPAIR(4)/SUPPLY(5)/OTHER(6)

### 实体字段类型变更
- `WorkOrder.java`：`status`/`orderType` String→Integer；删除 5 个流程字段；新增 `version`；时间字段 LocalDateTime→Instant
- `ApprovalLog.java`：`action`/`nodeStatus`/`beforeStatus`/`afterStatus` String→Integer；删除 `isDeleted`；时间→Instant
- `SecurityAuditLog.java`、`WorkOrderComment.java`：删除 `isDeleted`；时间→Instant
- `User.java`：新增 `tokenVersion`、`encryptedDek`；时间→Instant
- `Permission.java`、`Role.java`、`PasswordHistory.java`、`ResignedEmployee.java`、`SystemSetting.java`：时间→Instant
- 新建 `OrderProcessLink.java` 实体

### TypeHandler / 工具类
- 新建 `backend/src/main/java/com/workorder/config/InstantTypeHandler.java`（Instant ↔ TIMESTAMPTZ）
- 修改 `AuditFieldInterceptor.java`：`convertTimeValue()` 增加 Instant 分支；`now()` 改为 `Instant.now()`

### Mapper XML 全面重写
- `WorkOrderMapper.xml`：BaseResultMap 移除 5 个流程字段映射；删除 selectByProcessInstanceId/selectPendingByAssignee/updateAssignee；update 改为乐观锁 `WHERE id=#{id} AND version=#{version}`；时间字段加 InstantTypeHandler
- `ApprovalLogMapper.xml`：删除 `is_deleted = 0`；查询增加 `AND create_time >= #{startTime}` 强制分区裁剪；时间字段加 InstantTypeHandler
- `UserMapper.xml`：BaseResultMap 增加 token_version/encrypted_dek；INSERT/UPDATE 增加对应列；新增 `incrementTokenVersion`；时间字段加 InstantTypeHandler
- `SecurityAuditLogMapper.xml`、`WorkOrderCommentMapper.xml`：删除 `is_deleted = 0`；时间加 InstantTypeHandler
- 新建 `OrderProcessLinkMapper.xml`

### Mapper Java 接口改造
- `WorkOrderMapper.java`：删除流程字段方法；updateStatus 参数 String→Integer；新增 `acquireAdvisoryLock`
- `ApprovalLogMapper.java`：参数 String→Integer
- 新建 `OrderProcessLinkMapper.java`

### Service / Controller 类型签名
- `IWorkOrderService.java`、`WorkOrderController.java`、`ApprovalController.java`：所有 String status/orderType → Integer

**验证**：`mvn clean compile` + `mvn spring-boot:run` 启动无错；`GET /api/workorder/list?status=2` 正常

**禁止简化**：禁用 String 类型字段依赖 MyBatis 隐式转换；禁用保留 is_deleted 在日志表

---

## P3：架构解耦 — order_process_link + 领域事件

### 新建事件类（`backend/src/main/java/com/workorder/common/event/`）
`WorkOrderSubmittedEvent`、`WorkOrderApprovedEvent`、`WorkOrderRejectedEvent`、`WorkOrderTerminatedEvent`、`WorkOrderArchivedEvent`、`ProcessTaskAssignedEvent` — 继承 ApplicationEvent

### 新建服务层
- `IOrderProcessLinkService` + Impl：createLink / getProcessInstanceId / getWorkOrderId / removeLink
- `IFlowableQueryService` + Impl：getCurrentTask(workOrderId) / getActiveTasks / isProcessFinished（封装 Flowable 调用）
- 新建 `TaskInfoDTO`：taskId / taskName / assigneeId / assigneeName

### 新建事件监听器
- `WorkOrderEventListener`：`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`
  - 处理 SubmittedEvent → 调用 OrderProcessLinkService.createLink
  - 处理 Approved/Rejected/Terminated/ArchivedEvent → 更新 work_order.status
  - 处理 ProcessTaskAssignedEvent → 仅更新 order_process_link.updateTime

### 改造 Flowable 任务监听器
- `ParallelApprovalEventListener`：TASK_COMPLETED 分支发布 WorkOrderApprovedEvent/RejectedEvent；TASK_CREATED 分支发布 ProcessTaskAssignedEvent

### 重构 WorkOrderServiceImpl（核心改动）
- 新增 `@Autowired ApplicationEventPublisher eventPublisher`、`IOrderProcessLinkService orderProcessLinkService`、`IFlowableQueryService flowableQueryService`
- `submitWorkOrder()`：删除 5 行 set 流程字段代码；改为 `eventPublisher.publishEvent(new WorkOrderSubmittedEvent(...))`
- `populateCurrentAssignee()`：删除（解耦后不回写 work_order）
- `getPendingApprovalList()`：重写为 taskService 查任务 → orderProcessLinkService 反查 workOrderId → 批量查工单
- `handleApproval()`：删除 historyService 直接调用改为 flowableQueryService.isProcessFinished；删除回写流程字段；改用乐观锁 update；发布事件
- `terminateWorkOrder()`、`archiveWorkOrder()`、`resubmitWorkOrder()`：状态判断改用枚举 getCode()；发布对应事件

### 新增前端端点
- `GET /workorder/{id}/current-task`：返回 TaskInfoDTO
- 修改 `WorkOrderDTO`：增加 `taskInfo` 字段
- 前端 `frontend/src/api/workorder.js`：新增 `getWorkOrderCurrentTask(id)`
- 前端列表/详情页：从 `taskInfo.assigneeName` 读取当前审批人

**验证**：提交工单后 `SELECT * FROM order_process_link WHERE work_order_id=?` 有记录；work_order 表无流程字段；`GET /workorder/{id}/current-task` 返回正确任务信息

**禁止简化**：禁用 work_order 表保留任何流程运行时字段作过渡兼容

---

## P4：数据一致性 — version 乐观锁 + 对账任务 + CDC

### 乐观锁落地
- WorkOrderServiceImpl 的 handleApproval / terminateWorkOrder / archiveWorkOrder：先 `SELECT version` → `setVersion(...)` → update WHERE version=#{version}；rows==0 抛"数据已被其他操作修改"
- WorkOrderMapper 新增 `acquireAdvisoryLock(key)` + XML `<select>SELECT pg_advisory_xact_lock(#{key})</select>`；在 submit/handleApproval 方法开头调用

### 定时对账任务
- 新建 `backend/src/main/java/com/workorder/task/DataIntegrityReconciliationTask.java`：`@Scheduled(fixedRate = 30*60*1000)`
  - 扫描 work_order.applicant_id 不在 sys_user 的记录 → 写入 sys_security_audit_log + 追加 remark "[系统提示：申请人已离职]"
  - 扫描 approval_log.operator_id 同上
- `WorkOrderApplication.java` 主启动类增加 `@EnableScheduling`

### CDC 模拟（Postgres 触发器 + LISTEN/NOTIFY）
- V7 SQL 追加：`CREATE OR REPLACE FUNCTION notify_sys_user_soft_delete()` + `CREATE TRIGGER trg_sys_user_soft_delete AFTER UPDATE ON sys_user`
- 新建 `SysUserDeleteListener.java`：`@PostConstruct` 启动后台线程 LISTEN，收到 notify 发布 `SysUserLogicallyDeletedEvent`
- 新建 `SysUserDeleteEventHandler.java`：`@EventListener` 处理事件，标记该用户所有 PENDING 工单 remark

**验证**：并发审批第二次返回错误；软删除用户后 1 秒内工单 remark 自动追加；启动 30 分钟后日志显示对账任务执行

**禁止简化**：禁用跳过对账定时任务；禁用跳过 CDC 机制（触发器+LISTEN/NOTIFY 是 Postgres 原生 CDC 等价实现）

---

## P5：JWT tokenVersion + HttpOnly Cookie

### JwtUtil 改造
- `generateToken(String, Long)` → `generateToken(String, Long, Long tokenVersion)`
- claims 增加 `tokenVersion`
- 新增 `getTokenVersionFromToken(String)`

### JwtAuthenticationFilter 改造
- 解析 token 中的 tokenVersion，加载 user 后比较 `user.getTokenVersion().equals(tokenVersion)`，不匹配跳过认证
- `extractTokenFromRequest`：优先从 Cookie `WOS_TOKEN` 读取，兼容 Authorization Header（过渡期）

### AuthServiceImpl 改造
- 登录响应删除 `token` 字段（改由 Cookie 传递）
- `changePassword()` / `disableUser()` / `resetPassword()` 调用 `userMapper.incrementTokenVersion(userId)`

### UserMapper 新增方法
- `incrementTokenVersion(id)`：`UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{id}`

### AuthController 写入 Cookie
- 登录成功：`Cookie cookie = new Cookie("WOS_TOKEN", token); cookie.setHttpOnly(true); cookie.setSecure(true); cookie.setPath("/"); cookie.setMaxAge(...); cookie.setAttribute("SameSite", "Strict"); response.addCookie(cookie);`
- logout：`cookie.setMaxAge(0)`

### 前端 Token 存储迁移
- `frontend/src/utils/auth.js`：删除 localStorage 读写；`getToken()` 返回 null；保留 sessionStorage 缓存 userInfo
- `frontend/src/store/user.js`：token 不再前端持有；isLoggedIn 改为 `!!userInfo.value?.userId`
- `frontend/src/utils/request.js`：删除 Authorization Header 注入；增加 `config.withCredentials = true`
- `frontend/src/router/index.js`：判断登录改为 `userStore.isLoggedIn`
- `frontend/src/api/auth.js`：logout 删除 Authorization Header

**验证**：DevTools → Application → Cookies 显示 WOS_TOKEN; HttpOnly; Secure; SameSite=Strict；localStorage 无 token；改密后旧 Cookie 访问返回 401

**禁止简化**：禁用 localStorage 存储 Token；禁用 dev 环境 Secure=false 降级（用 localhost 视为安全上下文）

---

## P6：CSRF — Spring Security 内置 + 废弃 HMAC

### SecurityConfig 启用 CSRF
- 替换 `.csrf().disable()`：
  ```java
  .csrf(csrf -> csrf
      .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
      .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
      .ignoringRequestMatchers(
          request -> "/api/auth/login".equals(request.getRequestURI()),
          request -> "/api/password-reset/validate".equals(request.getRequestURI()),
          request -> "/api/password-reset/confirm".equals(request.getRequestURI())
      )
  )
  ```
- 自定义 CookieCsrfTokenRepository 子类：`newCookie` 方法设置 `SameSite=Strict`

### 删除 HmacSignatureFilter
- 删除 `backend/src/main/java/com/workorder/security/HmacSignatureFilter.java`（或移除 @Component）
- 删除 `app.security.message-signing-key` 配置项

### EnterpriseSecurityFilter 适配
- 删除 CSRF 校验逻辑（Bearer Token 检测）— 由 Spring Security CSRF filter 接管

### 前端去除 HMAC，加 CSRF Token
- `frontend/src/utils/request.js`：
  - 删除 HMAC_KEY、hmacSha256Base64、bufferToBase64 函数及签名代码块
  - 新增 `getCookie(name)` 函数读取 XSRF-TOKEN
  - 请求拦截器：`const xsrf = getCookie('XSRF-TOKEN'); if (xsrf) config.headers['X-XSRF-TOKEN'] = xsrf;`
  - axios 配置 `withCredentials: true`

**验证**：Network 中 POST 携带 X-XSRF-TOKEN，无 X-Signature/X-Timestamp；curl 无 XSRF-TOKEN POST 返回 403

**禁止简化**：禁用保留 HmacSignatureFilter 作为兼容；禁用 CSRF ignoringRequestMatchers 范围扩大

---

## P7：分层加密 DEK/KEK + KEK 来源硬断

### 新建 KmsService 抽象
- `KmsService` 接口：`byte[] getKek()` + `String getSource()`
- `DevEnvKmsService`：`@Profile("dev")` + `@Component`；`@Value("${app.security.kek-base64}")`；未配置用 dev 默认值（WARN 日志）
- `ProdVaultKmsService`：`@Profile("prod")` + `@Component`；`@PostConstruct` 硬断校验 Vault 配置，缺失抛 `IllegalStateException` 启动失败；调用 Vault REST API `/v1/secret/data/kek`

### 新建 DekService
- `generateAndEncryptDek()`：生成 32 字节随机 DEK，用 KEK 加密（AES-GCM），返回 base64 密文
- `byte[] decryptDek(String encryptedDek)`
- `reEncryptDek(oldEncryptedDek, newKek)`：密钥轮换用

### 重构 AesEncryptionUtil → AesDekEncryptionUtil
- `AesEncryptionUtil` 旧 `encrypt(String)`/`decrypt(String)` 标 `@Deprecated`（兼容旧密文）
- 新建 `AesDekEncryptionUtil`：`encrypt(plain, encryptedDek)` / `decrypt(stored, encryptedDek)` / `isEncryptedFormat`
- 兼容逻辑：密文格式 + encryptedDek 为 null → 走旧 AES 密钥；非加密格式 → 原样返回

### 重写 AesEncryptedStringTypeHandler
- 新建 `DekContext.java`：ThreadLocal 持有当前操作用户的 encryptedDek
- `AesEncryptedStringTypeHandler`：`setNonNullParameter` 从 DekContext 取 encryptedDek 调用 `aesDekEncryptionUtil.encrypt`；`getNullableResult` 从结果集 `encrypted_dek` 列读取后解密
- 修改 `UserMapper.xml`：BaseResultMap 增加 `<result column="encrypted_dek" property="encryptedDek"/>`
- 修改 `EmployeeServiceImpl.java`、`AuthServiceImpl.java`：INSERT 前 `user.setEncryptedDek(dekService.generateAndEncryptDek())`；调用 `DekContext.set(user.getEncryptedDek())` 后 `userMapper.insert(user)`，finally `DekContext.clear()`

### 配置文件
- `application-dev.yml`：增加 `app.security.kek-base64: ${KEK_BASE64:dev-default-kek-32bytes-base64}`
- `application-prod.yml`：增加 `app.security.vault.url/token/secret-path`，删除 `aes-encryption-key`

### 密钥轮换端点
- 新建 `KeyRotationController`（仅 SUPER_ADMIN）：`POST /admin/keys/rotate` 拉取所有 sys_user → 旧 KEK 解密 DEK → 新 KEK 加密 DEK → 批量 UPDATE encrypted_dek（不修改 phone/email 密文）

**验证**：dev 启动日志显示 KEK 来源；prod 无 VAULT_URL 启动失败；注册新用户 encrypted_dek 非空；查询手机号返回明文；保留旧密文数据可正常解密

**禁止简化**：禁用 prod 用环境变量代替 Vault；禁用 dev 因 Vault 未配置启动失败；禁用单密钥 AES 加密敏感字段

---

## P8：配置文件 / 索引收尾 / 全链路验证

### application.yml 主配置
- `spring.jackson.date-format: com.fasterxml.jackson.databind.util.StdDateFormat`（ISO-8601）
- `spring.jackson.time-zone: UTC`
- `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`
- 增加 `app.security.csrf.enabled: true`

### application-dev.yml / application-prod.yml
- 删除 `app.security.message-signing-key`
- dev：增加 `kek-base64`；prod：增加 `vault.*` 配置
- 删除 prod 的 `aes-encryption-key`

### 索引清理复核
执行 SQL 校验 V7 已落地：`idx_sys_user_username` 不存在；`idx_password_history_user_id` 不存在；`idx_work_order_pending` 存在

### 全链路验证用例（14 项）

| 用例 | 期望 |
|------|------|
| 注册新用户 | encrypted_dek 非空 |
| 登录 | Set-Cookie WOS_TOKEN + XSRF-TOKEN；响应无 token |
| 创建工单 | work_order.status=1 (DRAFT) |
| 提交工单 | status=2 (PENDING)，order_process_link 有记录 |
| 查询列表 | 返回工单 + taskInfo |
| 查询详情 | 显示当前节点 |
| 审批通过 | status=3, version+1 |
| 并发审批 | 第二次返回"数据已被修改" |
| 改密 | token_version+1，旧 Cookie 失效 |
| 禁用用户 | token_version+1，旧 Cookie 失效 |
| 跨站 POST | 403 |
| dev 启动 | 启动成功 |
| prod 启动（无 Vault） | 启动失败 |
| 对账任务 | 30 分钟后日志显示执行 |
| sys_user 软删除 | work_order.remark 自动追加 |

---

## 关键文件清单（按改动量与影响面排序）

1. `backend/src/main/resources/db/migration/V7__optimization_refactor.sql` — P1 新建，承载全部 DDL
2. `backend/src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` — P2/P3/P4 多阶段改造（1300+ 行核心业务）
3. `backend/src/main/java/com/workorder/config/SecurityConfig.java` — P5/P6 启用 CSRF + Cookie
4. `backend/src/main/java/com/workorder/util/AesEncryptionUtil.java` — P7 重构为 DEK/KEK 分层加密
5. `frontend/src/utils/request.js` — P5/P6 前端核心改造（去 HMAC + 加 CSRF + Cookie）

其他改动文件：实体类 10+ 个、枚举类 6 个、Mapper XML 6+ 个、Mapper Java 接口 6+ 个、新建事件/服务/监听器 15+ 个类、前端 5+ 个文件

---

## 不允许的简化项（最终复核清单）

- 禁止保留 work_order 表的 5 个流程运行时字段
- 禁止用 DELETE 代替 TRUNCATE 清空工单
- 禁止跳过 pg_partman 扩展安装
- 禁止保留日志表的 is_deleted 列
- 禁止用 VARCHAR 代替 SMALLINT 存储枚举
- 禁止用 TIMESTAMP WITHOUT TIME ZONE
- 禁止 localStorage 存储 Token
- 禁止保留 HmacSignatureFilter
- 禁止 prod 环境用环境变量代替 Vault
- 禁止 dev 环境因 Vault 未配置而启动失败
- 禁止跳过对账定时任务
- 禁止跳过 CDC 机制
- 禁止 KEK 来源未硬断校验
- 禁止单密钥 AES 加密敏感字段
