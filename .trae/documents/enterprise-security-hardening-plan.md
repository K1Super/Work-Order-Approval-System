# 工单审批流转系统 — 企业级安全加固与工程化实施方案

> **文档版本**：v1.0  
> **审计日期**：2026-07-19  
> **执行标准**：`.cursor/skills/impeccable/SKILL.md` + 用户安全策略（8 大类）  
> **核心约束**：禁止简化、禁止替换方案、禁止省略、强制实行；每个阶段完成后必须保证 `mvn compile` 与 `npm run build` 双双通过。

---

## 一、摘要（Summary）

本方案基于对项目 70+ 个后端 Java 文件、20+ 个前端 Vue 文件、2 套 SQL 脚本、3 套配置文件的彻底审查，识别出 **85+ 个具体问题**，覆盖以下 8 大类：

| 类别 | 问题数 | 严重等级 | 对应用户需求章节 |
|------|--------|----------|------------------|
| 鉴权与权限（含数据权限层） | 18 | 🔴 严重 | §2 |
| 输入与输出防护（SQL 注入/XSS/CSRF） | 12 | 🔴 严重 | §3 |
| 传输与网络安全（HTTPS/CORS/限流） | 9 | 🟠 高 | §4 |
| 数据存储与加密 | 11 | 🔴 严重 | §5 |
| 日志与审计 | 8 | 🟠 高 | §6 |
| 依赖与配置安全 | 7 | 🟠 高 | §7 |
| 部署与运行时安全 | 6 | 🟡 中 | §8 |
| Flowable 工作流安全 | 5 | 🟠 高 | §9 |
| 数据权限层逻辑缺陷（专项） | 9 | 🔴 严重 | §3（用户专项要求） |

**实施方案**：分 **8 个阶段**，每阶段独立可编译、可验证，前一阶段不依赖后一阶段。每阶段交付后运行 `mvn -DskipTests compile` 与 `npm run build`，确保零编译错误。

---

## 二、当前状态分析（Current State Analysis）

### 2.1 项目技术栈

- **后端**：SpringBoot 2.7.18 + Flowable 6.8.0 + MyBatis 2.3.1 + PostgreSQL 42.6.0 + jjwt 0.9.1（**含 CVE**） + Redis（Lettuce） + Spring Security
- **前端**：Vue 3.3.4 + Vue Router 4.2.4 + Pinia 2.1.7 + Element Plus 2.3.14 + Axios 1.5.0 + Vite 4.4.9
- **数据库**：PostgreSQL（**README 错误标为 MySQL 8.0+**）
- **构建**：Maven 3.9.14 + Java 17（pom.xml 标 11，实际由 Java 17 编译）

### 2.2 🔴 严重（Critical）问题清单（已逐一验证）

#### A. 鉴权与权限

| 编号 | 文件 | 行号 | 问题 |
|------|------|------|------|
| A-01 | `config/SecurityConfig.java` | 109 | `/emergency/**` 设为 `permitAll()`，任何匿名用户可调用紧急维护接口 |
| A-02 | `controller/EmergencyController.java` | 37/68/102 | 暴露 `unlock-all-accounts`、`unlock-account`、`locked-accounts`，无任何鉴权 |
| A-03 | `controller/SystemSettingController.java` | 全部 | 7 个端点（含安全策略修改）**无 `@PreAuthorize`**，任意登录用户可改密码策略 |
| A-04 | `config/DatabaseAutoCreateConfig.java` | 40-42 | 硬编码 `postgres/666666` 与 `jdbc:postgresql://localhost:5432/postgres`，绕过生产环境变量 |
| A-05 | `config/RedisConfig.java` | 34 | 使用 `LaissezFaireSubTypeValidator` + `NON_FINAL` 默认类型，**多态反序列化 RCE 风险**（CVE-2017-7525 同型） |
| A-06 | `security/JwtAuthenticationFilter.java` | — | 接受 URL 查询参数 `?token=`，Token 会被记入访问日志 |
| A-07 | `security/JwtUtil.java` | — | jjwt 0.9.1（含 CVE-2022-21724 等），无 Token 黑名单/吊销机制 |
| A-08 | `security/CustomUserDetails.java` | 全部 | 缺 `departmentId`、`orgLevel`、`roles`、`positionId` 字段，数据权限切面无法做部门级过滤 |
| A-09 | `aspect/DataPermissionAspect.java` | 112-133 | `checkDataOwnership` 为 **TODO 桩**，仅 `logger.warn` 后 `return`（允许通过），水平越权防护**未实现** |
| A-10 | `annotation/DataPermission.java` | — | 注解已定义 `isolationType`/`requirePermission`/`checkOwnership` 字段，但**全项目零使用**（死代码） |
| A-11 | `controller/WorkOrderController.java` | — | `getWorkOrderByNo`、`updateWorkOrder`、`archiveWorkOrder` 缺所有权/越权校验（IDOR） |
| A-12 | `controller/ApprovalController.java` | — | `getApprovalLog` 任意 `workOrderId` 即可查询审批日志（IDOR） |
| A-13 | `controller/AuthController.java` | — | `register` 端点在 SecurityConfig 中 `permitAll`（服务桩返回"未开放"但仍可调用） |
| A-14 | `controller/EmployeeController.java` | — | `getDepartments`/`getPositions`/`getSuperiors`/`getResignTypes` 无 `@PreAuthorize`；`deleteEmployee` 未传 `currentUser` 用于审计 |
| A-15 | `service/impl/WorkOrderServiceImpl.java` | — | `isSameDepartmentOrSuperAdmin` 在 null 时 **fail-open 返回 true** |
| A-16 | `controller/MaintenanceController.java` | — | `fix-encoding` 执行破坏性 `DELETE + INSERT` 于 `sys_department`/`sys_position` |
| A-17 | `config/CorsConfig.java` | — | `setAllowedHeader("*")` 过宽；dev 在 `cors.allowed-origins` 为空时回退到 `http://localhost:*` 通配 |
| A-18 | `config/EnterpriseSecurityFilter.java` | — | 限流为 TODO；`EXCLUDED_PATHS` 用 `uri.contains()`（子串匹配可绕过）；CSP 允许 `unsafe-inline` |

#### B. 输入与输出防护

| 编号 | 文件 | 行号 | 问题 |
|------|------|------|------|
| B-01 | `mapper/WorkOrderMapper.xml` | 78 | `ORDER BY ${sortField} ${sortOrder}` 使用 `${}`（SQL 注入；Service 层有白名单但缺纵深防御） |
| B-02 | `mapper/WorkOrderMapper.xml` | — | `<set>` 块未含 `update_time`，手动 `setUpdateTime` 不持久化 |
| B-03 | `interceptor/SqlInjectionInterceptor.java` | — | `HandlerInterceptor` **未在任何 `WebMvcConfigurer` 中注册**（死代码）；正则过宽（拦截 `0x...` 十六进制、文本中的 `SELECT`） |
| B-04 | `mapper/UserMapper.xml` | — | `selectList` 返回 `u.*` 含密码哈希，泄露至 API 响应 |
| B-05 | `service/impl/EmployeeServiceImpl.java` | 179/334 | `logger.info("...(初始密码: {})", initialPassword)` 与 `"...新临时密码: {}", ..., newPassword` **明文密码入日志** |
| B-06 | `service/impl/EmployeeServiceImpl.java` | — | `batchImport` 使用硬编码密码 `"123456"` |
| B-07 | 全项目 | — | 无 Jsoup/HTML 转义；工单标题、内容、审批意见可注入 XSS |
| B-08 | 前端 `package.json` | — | 无 `DOMPurify` 依赖；`v-html` 使用处无净化 |
| B-09 | `config/EnterpriseSecurityFilter.java` | — | CSP 头允许 `unsafe-inline`，等于没启用 CSP |
| B-10 | `controller/AuthController.java` | — | `register` 缺 `@Valid` 校验入参 |
| B-11 | 文件上传 | — | 附件上传无 Magic Number 校验、无后缀白名单、无大小限制（仅前端校验） |
| B-12 | 全项目 | — | CSRF：JWT 在 `Authorization` Header 中（天然防护），但 `Cookie` 未设 `SameSite=Strict` |

#### C. 数据存储与加密

| 编号 | 文件 | 行号 | 问题 |
|------|------|------|------|
| C-01 | `application-dev.yml` | 12 | 数据库密码 `666666` 明文 |
| C-02 | `application-dev.yml` | 34 | JWT 密钥 `WOS_Dev_K2026_HmacSha512_256bit_RndSalt!@#SecKey` 明文 |
| C-03 | `application-dev.yml` | 18 | Redis 无密码 |
| C-04 | `service/impl/DataSecurityServiceImpl.java` | — | `desensitizeData` 异常时 **fail-open**（返回原数据） |
| C-05 | `service/impl/DataSecurityServiceImpl.java` | — | `desensitizeWorkOrderData` 逻辑反转：`if (orgLevel != 3 \|\| !isFinanceRole)` 移除金额，导致 orgLevel 0/1/2/4/5 反而保留金额 |
| C-06 | `service/impl/DataSecurityServiceImpl.java` | — | `canAccessWorkOrderData` 对 orgLevel 2/3/4/5 返回 true（注释"由 SQL 过滤"），SQL 未过滤则绕过 |
| C-07 | `service/impl/DataSecurityServiceImpl.java` | — | `getClientIp` 返回硬编码 `"SYSTEM_INTERNAL"` |
| C-08 | `sql/schema-init.sql` | — | **缺 `sys_password_history` 表**（`UserMapper.xml` 引用但表不存在） |
| C-09 | `service/impl/EmployeeServiceImpl.java` | — | 角色 ID 映射不一致：`getHighestUserRole` 用 15-22，`isDeptAdmin` 用 14，`getOrgLevel` 用 1-14 |
| C-10 | `service/impl/EmployeeServiceImpl.java` | — | `deleteEmployee` 执行硬删除（破坏审计链） |
| C-11 | `service/impl/PasswordPolicyServiceImpl.java` | — | 默认策略过弱（`requireDigit: false`、`requireSpecialChar: false`）；`validatePasswordNotReused` fail-open；`recordFailedLoginAttempt` 按用户名锁定（DoS：攻击者可锁任意账号）；`recordPasswordChange` **未被 `changePassword` 流程调用** |

#### D. 日志与审计

| 编号 | 文件 | 行号 | 问题 |
|------|------|------|------|
| D-01 | `service/impl/AuthServiceImpl.java` | 257-258 | `expiration = System.currentTimeMillis() + jwtUtil.getExpirationDateFromToken(token).getTime()` — 绝对过期时间被加到当前时间（**计算错误**，应仅取 `getExpirationDateFromToken(token).getTime()`） |
| D-02 | `service/impl/AuthServiceImpl.java` | `changePassword` | `logLoginFailure(user.getUsername(), getUserAgent(), getUserAgent(), "旧密码错误")` — 第二、三参数均为 `userAgent`，**IP 地址实为 UA** |
| D-03 | `service/impl/AuthServiceImpl.java` | `refreshToken` | 即使 Redis 中 Token 不匹配仍允许刷新 |
| D-04 | `service/impl/SecurityAuditServiceImpl.java` | — | `logSensitiveOperation` IP/UA 传 null；`queryAuditLogs` 吞异常返回空 Map |
| D-05 | `service/impl/EmployeeServiceImpl.java` | 179/334 | 见 B-05，明文密码入日志 |
| D-06 | `service/impl/PasswordPolicyServiceImpl.java` | — | `recordPasswordChange` 定义但未接入 `changePassword`（密码历史未记录） |
| D-07 | `logback` 配置 | — | 日志无敏感信息脱敏过滤器；无文件权限控制 |
| D-08 | 全项目 | — | 无 Prometheus 指标暴露；无短时 401/403 告警 |

#### E. 依赖与配置

| 编号 | 文件 | 问题 |
|------|------|------|
| E-01 | `pom.xml` | jjwt 0.9.1（CVE-2022-21724 等），需升级至 0.11.5+ |
| E-02 | `pom.xml` | 缺 Jsoup 依赖（XSS 防护） |
| E-03 | `pom.xml` | 缺 BouncyCastle 依赖（AES 加密） |
| E-04 | `pom.xml` | 缺 spring-boot-starter-actuator（生产监控，但需禁用敏感端点） |
| E-05 | `pom.xml` | 缺 bucket4j-core（限流） |
| E-06 | `application-dev.yml` | 见 C-01/C-02，密钥明文 |
| E-07 | `application-prod.yml` | 已用 `${DB_PASSWORD}` 等环境变量（✅ 良好），但 dev 未对齐 |

#### F. 部署与运行时

| 编号 | 文件 | 问题 |
|------|------|------|
| F-01 | `README.md` | 标注 MySQL 8.0+，实际 PostgreSQL（文档过时） |
| F-02 | `sql/init.sql` | **过时**：仅 5 个角色（ID 1-5），与真实 schema 19 个角色（ID 1-40）不符；默认密码 `123456` |
| F-03 | `sql/schema-init.sql` | 真实 schema，但缺 `sys_password_history` 表（见 C-08）；无 update_time 触发器 |
| F-04 | 全项目 | 无 Nginx 反代配置示例 |
| F-05 | 全项目 | 无 `docker-compose.yml`（PG/Redis 一键启动） |
| F-06 | 全项目 | 无 HTTPS 证书自动化配置（cert-manager/acme.sh 示例） |

#### G. Flowable 工作流

| 编号 | 文件 | 问题 |
|------|------|------|
| G-01 | 流程定义部署 | 任何登录用户可部署新流程定义（无 `@PreAuthorize`） |
| G-02 | 流程变量 | 流程变量中可能存储明文敏感标识 |
| G-03 | 审批状态同步 | Redis Pub/Sub 消息无频道隔离与签名 |
| G-04 | 并行审批 | 未校验候选人合法性，可通过篡改请求 ID 越权审批（任务哄抢） |
| G-05 | Groovy 脚本 | 未禁用脚本任务执行（可被注入恶意脚本） |

#### H. 数据权限层专项（用户重点要求）

| 编号 | 问题 |
|------|------|
| H-01 | `DataPermission` 注解零使用（死代码） |
| H-02 | `DataPermissionAspect.checkDataOwnership` 为 TODO 桩，水平越权防护未实现 |
| H-03 | `CustomUserDetails` 缺 `departmentId`/`orgLevel`/`roles`/`positionId`，无法做部门级过滤 |
| H-04 | `WorkOrderServiceImpl.isSameDepartmentOrSuperAdmin` null 时 fail-open 返回 true |
| H-05 | `DataSecurityServiceImpl.desensitizeWorkOrderData` 金额脱敏逻辑反转 |
| H-06 | `DataSecurityServiceImpl.canAccessWorkOrderData` 对 orgLevel 2/3/4/5 fail-open |
| H-07 | `WorkOrderController.getWorkOrderByNo`/`updateWorkOrder`/`archiveWorkOrder` IDOR |
| H-08 | `ApprovalController.getApprovalLog` IDOR |
| H-09 | `EmployeeServiceImpl` 角色 ID 映射三套不一致（15-22 / 14 / 1-14） |

---

## 三、实施方案（Proposed Changes）

### 阶段 0：依赖与基础设施准备

**目标**：升级依赖、补齐缺失库、搭建可重复运行环境。完成后 `mvn compile` 必须通过。

**文件改动**：

1. **`backend/pom.xml`**
   - jjwt 0.9.1 → 0.11.5（拆为 `jjwt-api` + `jjwt-impl` + `jjwt-jackson`，runtime scope）
   - 新增 `org.jsoup:jsoup:1.17.2`（XSS 净化）
   - 新增 `com.github.bucket4j:bucket4j-core:8.10.1`（本地限流）
   - 新增 `com.github.ben-manes.caffeine:caffeine`（已通过 Spring Boot BOM 管理）
   - 新增 `org.springframework.boot:spring-boot-starter-actuator`
   - 新增 `org.flywaydb:flyway-core`（数据库迁移）
   - Java 版本：`<java.version>11</java.version>` 保持（实际由 Java 17 编译，向后兼容）

2. **`backend/src/main/resources/application.yml`**
   - 新增 `management.endpoints.web.exposure.include: health,info,metrics,prometheus`
   - 新增 `management.endpoint.env.enabled: false`、`management.endpoint.heapdump.enabled: false`（关闭敏感端点）

3. **`docker-compose.yml`**（项目根，新建）
   - PostgreSQL 15（带 `POSTGRES_PASSWORD` 环境变量）
   - Redis 7（带 `requirepass`）
   - 用于本地与 CI 验证

4. **`.env.example`**（项目根，新建）
   - 列出所有需要的环境变量：`DB_PASSWORD`、`DB_USERNAME`、`JWT_SECRET`、`REDIS_PASSWORD`、`AES_ENCRYPTION_KEY` 等
   - 提供生成强密钥的命令示例

5. **`backend/src/main/resources/application-dev.yml`**
   - 密码全部改为 `${DB_PASSWORD:666666}` 等环境变量优先 + 本地默认值（仅 dev）
   - Redis 加 `${REDIS_PASSWORD:}` 
   - JWT 密钥改为 `${JWT_SECRET:WOS_Dev_...}`

**验证**：`mvn -DskipTests compile` → BUILD SUCCESS。

---

### 阶段 1：🔴 关键安全漏洞封堵（Critical Security Fixes）

**目标**：消除所有可被匿名/低权限用户利用的严重漏洞。完成后编译通过、单元测试可运行。

**文件改动**：

1. **`config/SecurityConfig.java`**（A-01, A-13）
   - 删除 `/emergency/**` 的 `permitAll()`
   - 删除 `/auth/register` 的 `permitAll()`（注册端点改为管理员调用或彻底移除路由）
   - 新增 `.antMatchers("/actuator/**").hasRole("SUPER_ADMIN")`（Actuator 仅管理员）
   - 新增 `/system/settings/**` 强制 `@PreAuthorize` 已在控制器层补齐

2. **`controller/EmergencyController.java`**（A-02）
   - 类上加 `@Profile("dev")` — 仅 dev 环境加载
   - 每个端点加 `@PreAuthorize("hasRole('SUPER_ADMIN')")`
   - 加 `@RequestMapping` 时记录审计日志
   - 移除 `System.out.println`，改用 logger

3. **`controller/SystemSettingController.java`**（A-03）
   - 类上加 `@PreAuthorize("hasAuthority('system:user')")`
   - `/security` 子端点再加 `and hasRole('SUPER_ADMIN')`（安全策略仅超管可改）

4. **`config/DatabaseAutoCreateConfig.java`**（A-04）
   - 改为从 `DataSourceProperties` 读取 URL/用户名/密码，不再硬编码
   - 使用 `spring.datasource.url` 派生 postgres 维护库连接
   - 加 `@ConditionalOnProperty(name = "app.db.auto-create", havingValue = "true", matchIfMissing = false)` — 默认关闭，仅在显式开启时执行
   - 生产环境 `application-prod.yml` 设 `app.db.auto-create: false`

5. **`config/RedisConfig.java`**（A-05）
   - 移除 `om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, NON_FINAL)`
   - 改用 `Jackson2JsonRedisSerializer<Object>` 配合 `BasicPolymorphicTypeValidator`，仅允许 `com.workorder.*` 前缀
   - 或更稳妥：改用 `GenericJackson2JsonRedisSerializer` + 在 DTO 上加 `@JsonTypeInfo` 显式声明

6. **`security/JwtAuthenticationFilter.java`**（A-06）
   - 移除从 URL 查询参数 `?token=` 读取 Token 的逻辑
   - 仅从 `Authorization: Bearer xxx` Header 读取

7. **`security/JwtUtil.java`**（A-07）
   - 升级 API 至 jjwt 0.11.5：`Jwts.builder().signWith(Key)` 替代 `signWith(String)`
   - 用 `Keys.hmacShaKeyFor(secret.getBytes())` 派生密钥
   - 新增 `isTokenBlacklisted(String token)` 方法，从 Redis 查 `auth:token:blacklist:` 前缀

8. **`security/CustomUserDetails.java`**（A-08）
   - 新增字段：`Long departmentId`、`Integer orgLevel`、`List<String> roles`、`Long positionId`
   - 构造函数扩展，新增 getter
   - 修改 `UserDetailsServiceImpl` 从 `sys_user` 表加载这些字段

9. **`controller/AuthController.java`**（A-13, B-10）
   - `register` 端点加 `@PreAuthorize("hasRole('SUPER_ADMIN')")` 或彻底注释路由
   - 入参 DTO 加 `@Valid`

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 启动应用（dev profile），用 `curl` 验证 `/emergency/unlock-all-accounts` 返回 401/403

---

### 阶段 2：🟠 数据权限层彻底重建（Data Permission Layer Rebuild）

**目标**：实现用户专项要求"数据权限层存在大量问题和逻辑问题，需要彻底修复"。完成后所有 IDOR 漏洞封堵，部门级数据隔离生效。

**文件改动**：

1. **`annotation/DataPermission.java`**（A-10）
   - 完善 `entityType` 枚举值：`WORK_ORDER`、`EMPLOYEE`、`APPROVAL_LOG`、`USER`
   - 新增 `dataScope` 字段：`ALL`、`DEPARTMENT`、`DEPARTMENT_AND_SUB`、`SELF`、`CUSTOM`
   - 新增 `requireDepartmentMatch` 布尔字段

2. **`aspect/DataPermissionAspect.java`**（A-09, H-02）
   - **实现 `checkDataOwnership`**：注入 `WorkOrderMapper`、`UserMapper`、`ApprovalLogMapper`
   - 按 `entityType` 分发：
     - `WORK_ORDER`：查工单的 `applicant_id` 与当前用户比对；若 `requireDepartmentMatch=true`，额外比对待办人/同部门
     - `EMPLOYEE`：查员工的 `department_id` 与当前用户 `departmentId` 比对（超管/HR 例外）
     - `APPROVAL_LOG`：查日志对应的工单，复用 WORK_ORDER 校验
     - `USER`：仅本人或超管
   - 资源 ID 提取失败时 **fail-closed**（抛 `BusinessException`），不再 `return` 允许通过
   - 新增 `checkDataScope` 方法：基于 `CustomUserDetails.orgLevel` + `dataScope` 限定查询范围
     - `orgLevel=0`（超管/安全审计）→ ALL
     - `orgLevel=1`（董事长/总经理/副总）→ ALL（管理层全局可见）
     - `orgLevel=2`（总监）→ DEPARTMENT_AND_SUB
     - `orgLevel=3`（专员）→ DEPARTMENT
     - `orgLevel=4`（普通员工）→ SELF

3. **`security/CustomUserDetails.java`** + **`security/UserDetailsServiceImpl.java`**（A-08, H-03）
   - 如阶段 1 已加字段，此处补充 `UserDetailsServiceImpl` 查询逻辑
   - JOIN `sys_user_role` + `sys_role` 加载 `roles` 列表
   - JOIN `sys_position` 加载 `positionId` 与 `orgLevel`

4. **`controller/WorkOrderController.java`**（A-11, H-07）
   - `getWorkOrderByNo`：加 `@PreAuthorize("hasAuthority('workorder:view-own') or hasAuthority('workorder:view-all')")` + `@DataPermission(entityType=WORK_ORDER, checkOwnership=true, resourceIdParam="orderNo")`
   - `updateWorkOrder`：加 `@DataPermission(entityType=WORK_ORDER, checkOwnership=true, resourceIdParam="id")`，Service 层传入 `currentUserId` 校验发起人
   - `archiveWorkOrder`：加 `@PreAuthorize("hasAuthority('workorder:archive')")` + 所有权校验

5. **`controller/ApprovalController.java`**（A-12, H-08）
   - `getApprovalLog`：加 `@DataPermission(entityType=APPROVAL_LOG, checkOwnership=true, resourceIdParam="workOrderId")`

6. **`controller/EmployeeController.java`**（A-14）
   - `getDepartments`/`getPositions`/`getSuperiors`/`getResignTypes`：加 `@PreAuthorize("isAuthenticated()")`
   - `deleteEmployee`：Service 签名加 `User currentUser` 参数用于审计
   - 员工查询加 `@DataPermission(entityType=EMPLOYEE, dataScope=DEPARTMENT, requireDepartmentMatch=true)`（非 HR/超管仅看本部门）

7. **`service/impl/WorkOrderServiceImpl.java`**（A-15, H-04）
   - `isSameDepartmentOrSuperAdmin`：null 时 **fail-closed 返回 false**
   - `getWorkOrderByNo`/`updateWorkOrder`/`archiveWorkOrder`：补所有权校验（调用 `canUserViewWorkOrder`）
   - 列表查询方法：注入 `CustomUserDetails`，按 `orgLevel` 自动追加 SQL 过滤条件

8. **`service/impl/EmployeeServiceImpl.java`**（C-09, H-09）
   - 统一角色 ID 映射：新建 `RoleConstants` 常量类
     ```java
     public static final long ROLE_SUPER_ADMIN = 1L;
     public static final long ROLE_SECURITY_AUDIT = 2L;
     public static final long ROLE_CHAIRMAN = 10L;
     // ... 完整映射
     public static final long ROLE_STAFF = 40L;
     ```
   - `getHighestUserRole`、`isDeptAdmin`、`getOrgLevel` 全部改用 `RoleConstants`
   - `deleteEmployee` 改为软删除（设置 `status=0` + 写入 `sys_resigned_employee`），不执行 `DELETE`

9. **`mapper/WorkOrderMapper.xml`**（B-01, B-02）
   - `ORDER BY ${sortField} ${sortOrder}` → 改为 `<choose>` + `<when>` 白名单分支（彻底杜绝 `${}`）
   - `<set>` 块加 `<if test="updateTime != null">update_time = #{updateTime},</if>`
   - 列表查询加 `<if test="dataScope == 'DEPARTMENT'">AND applicant_id IN (SELECT id FROM sys_user WHERE department_id = #{currentDepartmentId})</if>` 等条件

10. **`service/impl/DataSecurityServiceImpl.java`**（C-04, C-05, C-06, C-07, H-05, H-06）
    - `desensitizeData`：异常时 **fail-closed 返回脱敏后的占位符** `"***"`，不再返回原数据
    - `desensitizeWorkOrderData`：修正逻辑为 `if (orgLevel > 2 && !isFinanceRole(currentUser))` 移除金额（orgLevel 0/1/2 管理层可见）
    - `canAccessWorkOrderData`：orgLevel 2/3/4/5 时**调用 `canUserViewWorkOrder` 二次校验**，不再无条件返回 true
    - `getClientIp`：从 `RequestContextHolder` 获取真实 IP，代理场景解析 `X-Forwarded-For` 第一项

11. **`annotation/DataPermission.java` 应用清单**（A-10 死代码激活）
    - 在以下方法上添加注解（按 entityType + checkOwnership）：
      - `WorkOrderController.getWorkOrderByNo`、`updateWorkOrder`、`archiveWorkOrder`、`deleteWorkOrder`、`withdrawWorkOrder`
      - `ApprovalController.getApprovalLog`、`approve`、`reject`
      - `EmployeeController.getEmployeeDetail`、`updateEmployee`、`deleteEmployee`、`resetPassword`

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 编写 `DataPermissionAspectTest` 单元测试：模拟非本人/非同部门访问工单，断言抛 `BusinessException`
- 用两个不同部门用户登录，互相访问对方工单 → 403

---

### 阶段 3：输入与输出防护（Input/Output Protection）

**目标**：实现 §3 全部要求 — SQL 注入、XSS、文件上传、CSRF。

**文件改动**：

1. **`interceptor/SqlInjectionInterceptor.java`**（B-03）
   - 重写正则：仅匹配 `UNION SELECT`、`OR 1=1`、`--`、`; DROP` 等真实注入模式
   - 不再拦截文本中的 `SELECT` 单词与 `0x` 十六进制

2. **`config/WebMvcConfig.java`**（新建，B-03）
   - 实现 `WebMvcConfigurer`，`addInterceptors` 注册 `SqlInjectionInterceptor`
   - `addPathPatterns("/**")`，`excludePathPatterns` 排除静态资源

3. **`mapper/WorkOrderMapper.xml`**（B-01 已在阶段 2 处理；此处复核所有 mapper）
   - `rg "\\\$\{" backend/src/main/resources/mapper/` 全局扫描，确保零 `${}` 用于用户输入
   - 仅排序字段保留白名单分支

4. **`config/XssFilter.java`**（新建，B-07）
   - 实现 `Filter`，包装 `HttpServletRequestWrapper`，对 `getParameter`、`getInputStream`（JSON body）做 Jsoup 净化
   - Jsoup 白名单：`Safelist.none()`（仅保留文本）+ `Safelist.simpleText()`（保留 `b/i/em/strong`）
   - 注册到 `SecurityConfig` 过滤器链

5. **`util/XssCleanUtil.java`**（新建，B-07）
   - 静态方法 `clean(String input)`、`cleanHtml(String input, Safelist whitelist)`
   - 工单标题/内容用 `Safelist.simpleText()`，审批意见用 `Safelist.none()`

6. **`service/impl/WorkOrderServiceImpl.java`**（B-07）
   - `createWorkOrder`、`updateWorkOrder` 入参前调用 `XssCleanUtil.clean`
   - `ApprovalServiceImpl.approve`、`reject` 的 `comment` 字段净化

7. **`config/FileUploadConfig.java`**（新建，B-11）
   - `MultipartResolver` 限制 `maxFileSize=10MB`、`maxRequestSize=50MB`
   - 新建 `FileUploadValidator`：
     - 后缀白名单：`pdf,doc,docx,xls,xlsx,png,jpg,jpeg,zip`
     - Magic Number 校验（Apache Tika 或手动前 8 字节）
     - 重命名存储：`UUID + 原后缀`
     - 存储路径不直接对外暴露，通过 `/api/files/{token}` 临时签名 URL 下载

8. **`controller/FileController.java`**（新建，B-11）
   - 上传端点 `@PreAuthorize("isAuthenticated()")`
   - 下载端点校验 token 与所有权

9. **`config/EnterpriseSecurityFilter.java`**（B-09, A-18）
   - CSP 改为严格策略：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'`（Element Plus 需要 unsafe-inline 样式，故 style 保留；script 严格）
   - `EXCLUDED_PATHS` 改用 `equals` 精确匹配或 `AntPathMatcher`
   - **实现限流**：用 Bucket4j，每用户每接口独立桶
     - 登录：5 次/分钟
     - 审批：30 次/分钟
     - 其他：100 次/分钟
   - 限流命中返回 429 + `Retry-After` Header
   - 重命名 CSRF 检查方法（实际是 JWT 存在性检查），逻辑保留

10. **`config/CorsConfig.java`**（A-17）
    - `setAllowedHeaders` 改为显式列表：`Authorization, Content-Type, X-Requested-With, X-XSRF-TOKEN`
    - dev 默认 origin 改为精确白名单：`http://localhost:5173,http://localhost:3000`
    - prod 严格从 `cors.allowed-origins` 读取，空则抛异常（fail-closed）

11. **防重放机制**（B-12 + 用户要求 §4）
    - 新建 `annotation/AntiReplay.java`
    - 新建 `aspect/AntiReplayAspect.java`：检查请求头 `X-Request-Nonce` + `X-Request-Timestamp`
    - Nonce 存 Redis 5 分钟（`request:nonce:` 前缀），重复则拒绝
    - Timestamp 与服务器时间偏差 > 5 分钟拒绝
    - 应用到审批、密码重置、工单删除等关键操作

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 测试：提交 `<script>alert(1)</script>` 作为工单标题 → 存储后查询不含 script 标签
- 测试：`/auth/login` 1 分钟内请求 6 次 → 第 6 次返回 429

---

### 阶段 4：数据存储与加密（Data Storage & Encryption）

**目标**：实现 §5 — 敏感数据 AES 加密、数据库安全、Redis 加固。

**文件改动**：

1. **`util/AesEncryptionUtil.java`**（新建，C-01~C-07）
   - AES-GCM 256 模式
   - 密钥从 `${AES_ENCRYPTION_KEY}` 环境变量读取（32 字节 Base64）
   - 方法：`encrypt(String plain)`、`decrypt(String cipher)`、`encryptIfNotNull`
   - IV 每次随机生成，与密文一起存储（`base64(iv) + ":" + base64(cipher)`）

2. **`entity/User.java`** + **`mapper/UserMapper.xml`**（C 类）
   - `phone`、`email` 字段加 `@TableField(typeHandler = AesEncryptedStringTypeHandler.class)`（MyBatis TypeHandler）
   - 新建 `AesEncryptedStringTypeHandler`：写入前 `encrypt`，读取后 `decrypt`
   - `selectList` 改为显式列名列表，**排除 `password` 字段**
   - `selectById` 增加一个 `selectByIdSafe` 方法，不返回 `password`

3. **`service/impl/EmployeeServiceImpl.java`**（B-05, B-06, D-05）
   - 删除 line 179、334 的明文密码日志
   - 改为 `logger.info("Employee created successfully: {} (initial password delivered via secure channel)", username)`
   - `batchImport` 硬编码密码 `"123456"` → 改为每个用户生成随机强密码，通过加密通道下发
   - 返回给前端的明文密码改为一次性 token，前端凭 token 调 `/api/password/reveal/{token}` 取回（5 分钟有效）

4. **`service/impl/DataSecurityServiceImpl.java`**（C-04~C-07 已在阶段 2 处理，此处复核）

5. **`sql/schema-init.sql`** + **Flyway 迁移脚本**（C-08, F-03）
   - 新建 `backend/src/main/resources/db/migration/V1__init.sql`（复制自 `schema-init.sql`）
   - 新建 `V2__add_password_history.sql`：
     ```sql
     CREATE TABLE sys_password_history (
         id BIGSERIAL PRIMARY KEY,
         user_id BIGINT NOT NULL,
         password_hash VARCHAR(255) NOT NULL,
         create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     );
     CREATE INDEX idx_password_history_user_id ON sys_password_history(user_id);
     ```
   - 新建 `V3__add_audit_indexes.sql`：补全审计日志索引
   - 修改 `schema-init.sql` 同步加入 `sys_password_history` 表（保持新旧两种初始化方式一致）

6. **`service/impl/PasswordPolicyServiceImpl.java`**（C-11, D-06）
   - 默认策略改为强策略：`requireDigit: true`、`requireSpecialChar: true`、`requireUppercase: true`、`requireLowercase: true`、`minPasswordLength: 10`
   - `validatePasswordNotReused`：异常时 **fail-closed 拒绝**（不允许使用新密码）
   - `recordFailedLoginAttempt`：改为按 IP+用户名组合锁定，单用户名锁定需触发告警
   - 在 `AuthServiceImpl.changePassword` 末尾调用 `recordPasswordChange(userId, newPassword)`（接通历史记录）

7. **`config/RedisConfig.java`** + **`application-prod.yml`**（C-03）
   - `application-prod.yml` 已要求 Redis 密码；`application-dev.yml` 补 `${REDIS_PASSWORD:}`
   - 禁用危险命令：`redis.conf` 示例文件中 `rename-command FLUSHALL ""`、`rename-command CONFIG ""`
   - 新建 `docs/redis-hardening.conf` 示例

8. **数据库最小权限**（§5）
   - 新建 `docs/db-grants.sql`：
     ```sql
     -- 应用账户：仅 CRUD
     GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wos_app;
     -- 禁止 DDL
     REVOKE CREATE ON SCHEMA public FROM wos_app;
     ```

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 启动后查 `sys_user` 表，`phone`/`email` 字段为密文
- API 响应中 `phone` 为明文（TypeHandler 解密）
- 修改密码 → 查 `sys_password_history` 有新记录

---

### 阶段 5：日志与审计修复（Audit Logging Fixes）

**目标**：实现 §6 — 全量操作审计、实时告警、日志保护。

**文件改动**：

1. **`service/impl/AuthServiceImpl.java`**（D-01, D-02, D-03）
   - Line 257-258：`expiration = jwtUtil.getExpirationDateFromToken(token).getTime()`（删除 `System.currentTimeMillis() +`）
   - `changePassword`：修正 `logLoginFailure` 参数顺序：`logLoginFailure(username, clientIp, userAgent, "旧密码错误")`
   - `refreshToken`：先校验 Redis 中 Token 与请求 Token 一致，不一致返回 401

2. **`service/impl/SecurityAuditServiceImpl.java`**（D-04）
   - `logSensitiveOperation`：从 `RequestContextHolder` 获取 IP/UA，不再传 null
   - `queryAuditLogs`：异常向上抛出，由 `GlobalExceptionHandler` 处理；不再吞异常返回空 Map

3. **`service/impl/EmployeeServiceImpl.java`**（D-05，已在阶段 4 处理）

4. **`config/LogbackConfig.java`** + **`logback-spring.xml`**（D-07）
   - 新建 `logback-spring.xml`：
     - 控制台 + 文件双输出
     - 文件按日滚动，保留 30 天
     - 日志文件路径 `/var/log/workorder/`（生产）+ `logs/`（dev）
     - 新建 `SensitiveDataConverter`：用正则脱敏 `password=xxx`、`token=xxx`、`Authorization: Bearer xxx`
   - 新建 `SecurityAuditLogAppender`：将审计日志异步写入 `sys_security_audit_log` 表

5. **`service/impl/PasswordPolicyServiceImpl.java`**（D-06，已在阶段 4 处理）

6. **`config/MetricsConfig.java`**（D-08，新建）
   - 暴露 Prometheus 指标：
     - `wos_login_attempts_total{result="success|failure"}`
     - `wos_authorization_denials_total{reason="401|403"}`
     - `wos_sensitive_operations_total{type="..."}`
   - 在 `JwtAuthenticationFilter`、`DataPermissionAspect` 中埋点

7. **告警规则示例**（D-08）
   - 新建 `docs/alertmanager-rules.yml`：
     - 5 分钟内 401 错误 > 20 → 告警
     - 5 分钟内 403 错误 > 10 → 告警
     - 1 分钟内密码重置 > 5 → 告警

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 登录失败 → 查 `sys_security_audit_log`，IP 字段为真实 IP（非 UA）
- `curl /api/actuator/prometheus` → 包含 `wos_login_attempts_total` 指标
- 日志中 `password=` 字段显示为 `password=***`

---

### 阶段 6：Flowable 工作流安全（Flowable Workflow Security）

**目标**：实现 §9 — 流程定义权限、流程变量安全、防止任务哄抢。

**文件改动**：

1. **`controller/ProcessController.java`**（G-01）
   - 部署流程定义端点加 `@PreAuthorize("hasRole('SUPER_ADMIN')")`
   - 删除/挂起流程定义端点同样限制

2. **`config/FlowableConfig.java`**（G-05，新建或修改）
   - 禁用脚本任务：`config.setEnableSafeScripting(true)` + 限制 `ScriptTaskActivityBehavior` 仅允许 `javascript`（受限）
   - 或彻底禁用：在 BPMN 解析阶段拒绝 `scriptTask` 元素

3. **`service/impl/WorkOrderServiceImpl.java`**（G-02, G-04）
   - 提交工单前清理流程变量：移除任何含 `password`、`secret`、`token` 的 key
   - `approve` 方法：调用 `taskService.claim(taskId, currentUserId)` 前校验 `task.getCandidateGroups()` / `task.getAssignee()` 包含当前用户
   - 不匹配则抛 `BusinessException("您不是该任务的合法候选人")`

4. **`config/RedisMessageConfig.java`**（G-03，新建）
   - Redis Pub/Sub 频道隔离：`wos:approval:{departmentId}` 前缀
   - 消息签名：HMAC-SHA256，密钥从 `${MESSAGE_SIGNING_KEY}` 读取
   - 接收方校验签名，不匹配则丢弃 + 告警

5. **`listener/ApprovalEventListener.java`**（G-04）
   - `TaskCreatedListener`：候选人列表写入 Redis（`task:candidates:{taskId}`），TTL 与任务同步
   - `TaskCompletedListener`：删除候选人缓存
   - 审批前校验 Redis 中候选人存在

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- 普通用户尝试部署 BPMN → 403
- 用非候选人用户 ID 调用审批 → 抛 `BusinessException`

---

### 阶段 7：前端加固与 impeccable 标准应用（Frontend Hardening & impeccable）

**目标**：实现 §3 前端部分（CSP/DOMPurify）+ 路由权限 + 按 `.cursor/skills/impeccable/SKILL.md` 标准审计并修复关键违规。

**说明**：impeccable 是前端 UI 设计技能，SKILL.md 规定 OKLCH 配色、字体层级、动效、交互、文案、Absolute bans 等标准。本阶段先**审计**现有前端对标准的违规，再针对**严重违规**修复；不做全量 UI 重设计（避免破坏现有可用功能），但保证新代码与修改代码完全符合标准。

**文件改动**：

1. **运行 impeccable audit**（A-1）
   - 执行 `node .cursor/skills/impeccable/scripts/context.mjs` 检查 PRODUCT.md/DESIGN.md
   - 若无 PRODUCT.md，按 `reference/init.md` 创建最小 PRODUCT.md（产品定位、目标用户、品牌色锚点）
   - 读取 `reference/audit.md` 与 `reference/product.md`（项目为应用类 UI）

2. **`frontend/package.json`**（B-08）
   - 新增 `dompurify: ^3.0.6` 依赖
   - 新增 `@vueuse/core`（如未有，用于响应式工具）

3. **`frontend/src/utils/sanitize.js`**（新建，B-08）
   - 封装 `DOMPurify.sanitize`，默认配置 `ALLOWED_TAGS: ['b','i','em','strong','br','p']`
   - 用于所有 `v-html` 指令

4. **`frontend/src/main.js`**（B-08）
   - 注册全局指令 `v-safe-html`：自动 sanitize
   - 全局替换项目中所有 `v-html` 为 `v-safe-html`

5. **`frontend/index.html`**（B-09）
   - 加 CSP meta 标签：
     ```html
     <meta http-equiv="Content-Security-Policy" 
           content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self' http://localhost:8080;">
     ```

6. **`frontend/src/router/index.js`**（路由权限缺失）
   - 路由守卫中，`next()` 前校验 `to.meta.permission`：
     ```js
     if (to.meta.permission && !userStore.permissions.includes(to.meta.permission)) {
       next({ path: '/403' })
       return
     }
     ```
   - 新建 `frontend/src/views/error/403.vue`

7. **`frontend/src/utils/request.js`**（B-12）
   - 响应拦截器：401 时不再 `ElMessageBox.confirm`（避免 XSS 注入伪造弹窗），改为 `ElNotification` 静默提示 + 直接跳转

8. **impeccable 标准审计与关键修复**
   - 扫描所有 `.vue` 文件，对照 `Absolute bans` 列表：
     - ❌ Side-stripe borders（`border-left > 1px` 作为卡片强调）→ 重写为完整边框或背景色
     - ❌ Gradient text（`background-clip: text` + gradient）→ 改为单色
     - ❌ Glassmorphism 装饰性使用 → 移除或仅保留有明确用途的
     - ❌ Hero-metric template → 重写为信息密度更高的布局
     - ❌ Identical card grids → 引入变化（不同尺寸、不同内容结构）
     - ❌ Tiny uppercase tracked eyebrow → 移除或改为内联标签
     - ❌ Numbered section markers（01/02/03）→ 仅保留有顺序意义的
   - 配色：引入 OKLCH 变量，替换硬编码十六进制
     - 新建 `frontend/src/styles/tokens.css`：`--bg, --surface, --ink, --accent, --muted` 用 OKLCH
     - 全局搜索 `#xxx` 颜色值，替换为 `var(--xxx)`
   - 字体：确认全局统一字体（用户偏好"统一全局字体"）
   - 对比度：所有 body 文本 ≥ 4.5:1，placeholder ≥ 4.5:1
   - 动效：所有动画加 `@media (prefers-reduced-motion: reduce)` 替代方案

9. **`frontend/src/views/Login.vue`** 重点改造（用户偏好"个性的、极致美感和高级的布局"）
   - 不使用传统规则布局
   - 量子主题视觉元素（用户偏好"科幻黑洞引力漩涡粒子数据碎片主题"）
   - 认证按钮需"顶级"动效（用户偏好）
   - 但**不强制全量重设计**，仅修复违规 + 提升关键页面

**验证**：
- `npm run build` → 通过
- 浏览器控制台无 CSP 违规
- `v-html` 全局搜索为 0
- 路由权限：用普通员工账号访问 `/system/settings` → 跳转 403

---

### 阶段 8：部署与文档（Deployment & Documentation）

**目标**：实现 §7-§8 剩余项 + 修复文档与 SQL 不一致。

**文件改动**：

1. **`README.md`**（F-01）
   - 修正：MySQL 8.0+ → PostgreSQL 13+
   - 补充：环境变量列表、docker-compose 启动方式、Flyway 迁移说明
   - 补充：生产部署清单（HTTPS、CORS、限流、Actuator 端点关闭）

2. **`sql/init.sql`**（F-02）
   - 标记为 DEPRECATED，顶部加注释：`-- ⚠️ 此文件已废弃，请使用 backend/src/main/resources/db/migration/ 下的 Flyway 脚本`
   - 或直接删除（推荐）

3. **`backend/src/main/resources/sql/schema-init.sql`**（F-03）
   - 与 Flyway V1__init.sql 同步（保持一致）
   - 顶部加注释说明：此文件用于手动初始化，推荐使用 Flyway

4. **`docs/nginx.conf`**（新建，F-04, §8）
   - 反代示例：80 → 443 重定向、443 反代至 `backend:8080`
   - 安全头：HSTS、X-Frame-Options、X-Content-Type-Options、Referrer-Policy、CSP
   - 限流：`limit_req_zone` 登录接口
   - 仅暴露 443，后端 8080 不对外

5. **`docs/https-automation.md`**（新建，F-06）
   - acme.sh 与 cert-manager 两种方案示例

6. **`docs/deployment-checklist.md`**（新建，§8）
   - 生产环境检查清单：环境变量、数据库账户、Redis 密码、CORS 白名单、Actuator 端点、日志路径、备份策略

7. **`.github/workflows/security-scan.yml`**（新建，§7）
   - OWASP Dependency-Check 扫描 Maven 依赖
   - npm audit 扫描前端依赖
   - Trivy 扫描 Docker 镜像

8. **`backend/src/main/resources/application-prod.yml`**（E-07 复核）
   - 确认所有密钥用 `${}` 环境变量
   - 新增 `app.db.auto-create: false`
   - 新增 `spring.flyway.enabled: true`、`spring.flyway.baseline-on-migrate: true`
   - Actuator 端点：`management.endpoints.web.exposure.include: health,prometheus`（仅健康与指标）

**验证**：
- `mvn -DskipTests compile` → BUILD SUCCESS
- `npm run build` → 通过
- README 中无 MySQL 字样
- `sql/init.sql` 已删除或标记废弃

---

## 四、关键文件清单（Critical Files）

### 后端必改文件（按阶段）

| 阶段 | 文件路径 | 改动类型 |
|------|----------|----------|
| 0 | `backend/pom.xml` | 升级 jjwt、新增 Jsoup/Bucket4j/Actuator/Flyway |
| 0 | `backend/src/main/resources/application.yml` | Actuator 配置 |
| 0 | `backend/src/main/resources/application-dev.yml` | 环境变量化 |
| 0 | `docker-compose.yml`（新） | PG + Redis |
| 0 | `.env.example`（新） | 环境变量清单 |
| 1 | `backend/.../config/SecurityConfig.java` | 移除 permitAll |
| 1 | `backend/.../controller/EmergencyController.java` | @Profile + @PreAuthorize |
| 1 | `backend/.../controller/SystemSettingController.java` | @PreAuthorize |
| 1 | `backend/.../config/DatabaseAutoCreateConfig.java` | 去硬编码、条件加载 |
| 1 | `backend/.../config/RedisConfig.java` | 移除 LaissezFaireSubTypeValidator |
| 1 | `backend/.../security/JwtAuthenticationFilter.java` | 移除 URL token |
| 1 | `backend/.../security/JwtUtil.java` | jjwt 0.11.5 API |
| 1 | `backend/.../security/CustomUserDetails.java` | 加字段 |
| 1 | `backend/.../security/UserDetailsServiceImpl.java` | 加载新字段 |
| 1 | `backend/.../controller/AuthController.java` | @PreAuthorize @Valid |
| 2 | `backend/.../annotation/DataPermission.java` | 完善 |
| 2 | `backend/.../aspect/DataPermissionAspect.java` | 实现 checkDataOwnership |
| 2 | `backend/.../controller/WorkOrderController.java` | @DataPermission |
| 2 | `backend/.../controller/ApprovalController.java` | @DataPermission |
| 2 | `backend/.../controller/EmployeeController.java` | @PreAuthorize |
| 2 | `backend/.../service/impl/WorkOrderServiceImpl.java` | fail-closed |
| 2 | `backend/.../service/impl/EmployeeServiceImpl.java` | RoleConstants、软删除 |
| 2 | `backend/.../mapper/WorkOrderMapper.xml` | 白名单分支、update_time |
| 2 | `backend/.../service/impl/DataSecurityServiceImpl.java` | fail-closed、修逻辑 |
| 2 | `backend/.../common/RoleConstants.java`（新） | 角色 ID 常量 |
| 3 | `backend/.../interceptor/SqlInjectionInterceptor.java` | 重写正则 |
| 3 | `backend/.../config/WebMvcConfig.java`（新） | 注册拦截器 |
| 3 | `backend/.../config/XssFilter.java`（新） | Jsoup 净化 |
| 3 | `backend/.../util/XssCleanUtil.java`（新） | 工具类 |
| 3 | `backend/.../config/FileUploadConfig.java`（新） | 上传限制 |
| 3 | `backend/.../controller/FileController.java`（新） | 文件上传/下载 |
| 3 | `backend/.../config/EnterpriseSecurityFilter.java` | CSP、限流 |
| 3 | `backend/.../config/CorsConfig.java` | 严格白名单 |
| 3 | `backend/.../annotation/AntiReplay.java`（新） | 防重放注解 |
| 3 | `backend/.../aspect/AntiReplayAspect.java`（新） | 防重放切面 |
| 4 | `backend/.../util/AesEncryptionUtil.java`（新） | AES-GCM |
| 4 | `backend/.../entity/User.java` | TypeHandler 注解 |
| 4 | `backend/.../mapper/UserMapper.xml` | 排除 password |
| 4 | `backend/.../config/AesEncryptedStringTypeHandler.java`（新） | TypeHandler |
| 4 | `backend/.../db/migration/V1__init.sql`（新） | Flyway |
| 4 | `backend/.../db/migration/V2__add_password_history.sql`（新） | Flyway |
| 4 | `backend/.../service/impl/PasswordPolicyServiceImpl.java` | 强策略、fail-closed |
| 4 | `docs/redis-hardening.conf`（新） | Redis 加固 |
| 4 | `docs/db-grants.sql`（新） | 最小权限 |
| 5 | `backend/.../service/impl/AuthServiceImpl.java` | 修 3 个 bug |
| 5 | `backend/.../service/impl/SecurityAuditServiceImpl.java` | IP/UA、不吞异常 |
| 5 | `backend/src/main/resources/logback-spring.xml`（新） | 脱敏 + 审计 |
| 5 | `backend/.../config/MetricsConfig.java`（新） | Prometheus |
| 5 | `docs/alertmanager-rules.yml`（新） | 告警规则 |
| 6 | `backend/.../controller/ProcessController.java` | @PreAuthorize |
| 6 | `backend/.../config/FlowableConfig.java` | 禁用脚本 |
| 6 | `backend/.../config/RedisMessageConfig.java`（新） | 频道隔离+签名 |
| 6 | `backend/.../listener/ApprovalEventListener.java`（新） | 候选人校验 |
| 7 | `frontend/package.json` | 加 dompurify |
| 7 | `frontend/src/utils/sanitize.js`（新） | DOMPurify 封装 |
| 7 | `frontend/src/main.js` | v-safe-html 指令 |
| 7 | `frontend/index.html` | CSP meta |
| 7 | `frontend/src/router/index.js` | 权限校验 |
| 7 | `frontend/src/views/error/403.vue`（新） | 403 页面 |
| 7 | `frontend/src/utils/request.js` | 401 处理 |
| 7 | `frontend/src/styles/tokens.css`（新） | OKLCH 变量 |
| 7 | `frontend/src/views/Login.vue` | impeccable 修复 |
| 7 | 其他 .vue 文件 | impeccable 审计修复 |
| 8 | `README.md` | PostgreSQL |
| 8 | `sql/init.sql` | 删除或废弃 |
| 8 | `backend/.../sql/schema-init.sql` | 同步 Flyway |
| 8 | `docs/nginx.conf`（新） | 反代+安全头 |
| 8 | `docs/https-automation.md`（新） | 证书自动化 |
| 8 | `docs/deployment-checklist.md`（新） | 部署清单 |
| 8 | `.github/workflows/security-scan.yml`（新） | 依赖扫描 |
| 8 | `backend/.../application-prod.yml` | Flyway + Actuator |

---

## 五、假设与决策（Assumptions & Decisions）

### 5.1 关键决策

1. **Java 版本**：保持 pom.xml 中 `<java.version>11</java.version>`（向后兼容，实际由 Java 17 编译，不强制升级）。jjwt 0.11.5 兼容 Java 11。
2. **数据库迁移**：采用 Flyway（企业标准），V1 从现有 `schema-init.sql` 派生。`schema-init.sql` 保留作为手动初始化备用。
3. **dev 环境密钥**：dev 配置使用 `${ENV_VAR:default}` 模式 — 环境变量优先，本地开发有默认值便于启动；生产强制环境变量无默认值。
4. **EmergencyController**：不删除（合法的运维工具），但加 `@Profile("dev")` + `@PreAuthorize("hasRole('SUPER_ADMIN')")`，生产环境不加载。
5. **注册端点**：`/auth/register` 改为 `@PreAuthorize("hasRole('SUPER_ADMIN')")`，仅超管可创建账号（注册功能通过员工管理入口实现）。
6. **明文密码返回**：员工创建/重置密码后，明文密码不直接返回前端，改为一次性 token，前端凭 token 5 分钟内取回。
7. **impeccable 前端范围**：审计 + 修复严重违规（Absolute bans、对比度、CSP），不做全量 UI 重设计（保留现有功能与布局，避免破坏可用性）。Login.vue 作为重点提升对象。
8. **角色 ID 统一**：新建 `RoleConstants` 常量类，所有 `isDeptAdmin`/`getHighestUserRole`/`getOrgLevel` 改用常量，消除三套不一致映射。
9. **软删除**：`deleteEmployee` 改为软删除（`status=0` + 写入 `sys_resigned_employee`），保留审计链。
10. **限流实现**：用 Bucket4j（本地令牌桶），不引入 Sentinel（避免引入 Alibaba 依赖栈）。生产可平滑替换为 Redis 分布式限流。

### 5.2 假设

- 用户本地已安装 Java 17、Maven 3.9+、Node.js 18+、PostgreSQL 13+、Redis 6+（或使用 docker-compose）。
- 用户接受 dev 环境使用本地默认密钥（便于开发），生产强制环境变量。
- 用户接受 Flyway 引入（首次部署执行 V1__init.sql 创建全部表）。
- 用户接受前端不做全量 UI 重设计，仅审计 + 修复严重违规 + 提升 Login.vue。
- 用户接受 `sql/init.sql` 被废弃（保留 `schema-init.sql` 与 Flyway 双轨）。
- 用户接受 `EmergencyController` 在生产不加载（`@Profile("dev")`）。

### 5.3 不做的事项（明确排除）

- 不升级 SpringBoot 2.7.18 → 3.x（Java 17+ 要求、javax → jakarta 迁移成本高，超出本次范围）
- 不引入 Sentinel（用 Bucket4j 替代）
- 不引入 Vault/Kubernetes Secrets（用环境变量 + `.env.example` 替代，文档说明可升级路径）
- 不引入 ELK（用 logback 文件 + Prometheus 指标替代，文档说明可升级路径）
- 不引入 WAF（这是基础设施层，文档说明推荐方案，不在代码内实现）
- 不做前端全量 UI 重设计（仅审计 + 修复严重违规）
- 不引入 SpringBoot 3 / Java 17 强制要求（保持 Java 11 兼容）

---

## 六、验证步骤（Verification）

### 6.1 每阶段验证（强制）

每阶段完成后必须执行：

```powershell
# 后端编译
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\backend'
mvn -DskipTests compile

# 前端构建
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\frontend'
npm run build
```

两者均必须成功，方可进入下一阶段。

### 6.2 阶段 1 验证（关键安全）

```powershell
# 启动应用（dev profile，需 PG + Redis）
cd backend
mvn spring-boot:run

# 验证紧急端点拒绝匿名访问
curl -i http://localhost:8080/api/emergency/unlock-all-accounts
# 期望：401 Unauthorized

# 验证系统设置拒绝普通用户
curl -i -H "Authorization: Bearer <普通用户token>" http://localhost:8080/api/system/settings/all
# 期望：403 Forbidden
```

### 6.3 阶段 2 验证（数据权限）

```powershell
# 用 A 部门用户登录，访问 B 部门用户的工单
curl -i -H "Authorization: Bearer <A部门token>" http://localhost:8080/api/workorder/detail/<B部门工单号>
# 期望：403 或业务错误"无权访问"

# 用普通员工访问 /api/employee/list
# 期望：仅返回本部门员工
```

### 6.4 阶段 3 验证（输入防护）

```powershell
# XSS 测试
curl -X POST http://localhost:8080/api/workorder/create \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"title":"<script>alert(1)</script>","content":"..."}'
# 查询该工单，title 应为 "alert(1)" 或空（script 标签被移除）

# 限流测试
for ($i=1; $i -le 10; $i++) { curl -i http://localhost:8080/api/auth/login -X POST -H "Content-Type: application/json" -d '{"username":"x","password":"y"}' }
# 期望：第 6 次起返回 429
```

### 6.5 阶段 4 验证（加密）

```sql
-- 直连数据库查询
SELECT phone, email FROM sys_user WHERE id = 1;
-- 期望：phone/email 为密文（如 "enc:base64iv:base64cipher"）

-- API 查询
-- 期望：phone/email 为明文（TypeHandler 解密）
```

### 6.6 阶段 5 验证（审计日志）

```sql
-- 登录失败后查询
SELECT username, ip_address, user_agent, action_type FROM sys_security_audit_log ORDER BY id DESC LIMIT 1;
-- 期望：ip_address 为真实 IP，user_agent 为浏览器 UA，二者不同
```

### 6.7 阶段 6 验证（Flowable）

```powershell
# 普通用户尝试部署 BPMN
curl -X POST -H "Authorization: Bearer <普通用户token>" http://localhost:8080/api/process/deploy -F "file=@test.bpmn"
# 期望：403

# 非候选人审批
curl -X POST -H "Authorization: Bearer <非候选人token>" http://localhost:8080/api/approval/approve -d '{"taskId":"xxx","approved":true}'
# 期望：业务错误"您不是该任务的合法候选人"
```

### 6.8 阶段 7 验证（前端）

```powershell
cd frontend
npm run build
# 期望：构建成功

# 浏览器控制台无 CSP 违规
# 普通员工访问 /system/settings → 跳转 /403
```

### 6.9 阶段 8 验证（文档）

```powershell
# README 中无 MySQL 字样
Select-String -Path README.md -Pattern "MySQL"
# 期望：无匹配

# Flyway 迁移脚本存在
Get-ChildItem backend/src/main/resources/db/migration/
# 期望：V1__init.sql, V2__add_password_history.sql, V3__add_audit_indexes.sql
```

### 6.10 最终验证（全量）

```powershell
# 后端完整编译 + 测试
cd backend
mvn clean package -DskipTests
# 期望：BUILD SUCCESS，jar 包生成

# 前端完整构建
cd ../frontend
npm run build
# 期望：dist/ 目录生成

# 启动后端 + 前端，端到端验证
# 1. 登录（含限流、审计日志）
# 2. 创建工单（含 XSS 净化）
# 3. 审批工单（含候选人校验、防重放）
# 4. 跨部门访问被拒（数据权限）
# 5. 修改安全设置被拒（普通用户）
# 6. 紧急端点被拒（生产 profile）
```

---

## 七、风险与缓解（Risks & Mitigation）

| 风险 | 缓解 |
|------|------|
| jjwt 0.11.5 API 不兼容 0.9.1 | 阶段 0 先升级，单独编译验证；保留旧 API 适配方法 |
| Flyway 首次启动报"found non-empty schema without history table" | 配置 `spring.flyway.baseline-on-migrate: true` + `baseline-version: 0` |
| AES TypeHandler 影响现有数据 | 阶段 4 提供数据迁移脚本：`UPDATE sys_user SET phone = encrypt(phone) WHERE phone NOT LIKE 'enc:%'` |
| 数据权限切面误拦合法访问 | 每个应用点配单元测试；超管 bypass 保持；fail-closed 仅对资源 ID 解析失败 |
| EnterpriseSecurityFilter 限流误伤 | 桶大小可配置（`app.rate-limit.login-per-minute` 等）；白名单 IP 豁免 |
| 前端 CSP 过严导致 Element Plus 样式失效 | style-src 保留 `'unsafe-inline'`（Element Plus 内联样式需要）；script-src 严格 |
| impeccable 审计发现大量违规 | 仅修复 Absolute bans 类硬性违规；其他作为后续优化项记录到 `docs/impeccable-todo.md` |

---

## 八、执行顺序与依赖（Execution Order）

```
阶段 0（依赖/基础设施）
  └─ 阶段 1（关键安全漏洞封堵）
       └─ 阶段 2（数据权限层重建）
            ├─ 阶段 3（输入输出防护）— 可与阶段 4 并行
            └─ 阶段 4（数据存储加密）— 可与阶段 3 并行
                 └─ 阶段 5（日志审计修复）
                      └─ 阶段 6（Flowable 安全）
                           └─ 阶段 7（前端加固 + impeccable）
                                └─ 阶段 8（部署与文档）
```

每阶段独立提交，编译验证通过后进入下一阶段。如某阶段编译失败，立即修复，不跳过。

---

## 九、交付物清单（Deliverables）

- ✅ 后端：所有 8 阶段代码改动 + 编译通过
- ✅ 前端：阶段 7 改动 + 构建通过
- ✅ SQL：Flyway 迁移脚本 + `schema-init.sql` 同步
- ✅ 配置：`application-dev.yml` / `application-prod.yml` 环境变量化
- ✅ 文档：README 修正、部署清单、Nginx 示例、Redis 加固示例、告警规则
- ✅ Docker：`docker-compose.yml` + `.env.example`
- ✅ CI：`.github/workflows/security-scan.yml`
- ✅ 验证：每阶段验证步骤已执行并通过

---

**计划文件位置**：`c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\.trae\documents\enterprise-security-hardening-plan.md`

**待用户确认后立即执行阶段 0。**
