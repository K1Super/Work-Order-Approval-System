# 工单审批流转系统 — 安全加固阶段 3-8 执行计划（Phases 3-8 Execution）

> **文档版本**：v3.0（阶段 3-8 专项）
> **执行日期**：2026-07-19
> **执行标准**：`.cursor/skills/impeccable/SKILL.md` + 用户安全策略 §2-§9
> **核心约束**：禁止简化、禁止替换方案、禁止省略、强制实行；每阶段完成后必须 `mvn -DskipTests compile` 与 `npm run build` 双双通过。
> **前序计划**：`.trae/documents/enterprise-security-hardening-plan.md`（v1.0，阶段 0-1 已完成）、`.trae/documents/security-hardening-resume-execution.md`（v2.0，阶段 2 已完成）

---

## 一、摘要（Summary）

本计划承接已完成的阶段 0/1/2，专注执行**阶段 3-8** 全部剩余工作：
- 阶段 3：输入与输出防护（SQL 注入拦截器注册、XssFilter、文件上传、限流+CSP、CORS、防重放）
- 阶段 4：数据存储与加密（AES-GCM、TypeHandler、Flyway V2/V3、密码策略强化、Redis/DB 加固文档）
- 阶段 5：日志与审计修复（AuthServiceImpl 3 bug、SecurityAuditServiceImpl、logback-spring.xml、MetricsConfig、告警规则）
- 阶段 6：Flowable 工作流安全（ProcessController 权限、FlowableConfig 禁脚本、候选人校验、RedisMessageConfig）
- 阶段 7：前端加固与 impeccable（DOMPurify、sanitize.js、v-safe-html、CSP meta、路由权限、403 页面、OKLCH tokens、Login.vue）
- 阶段 8：部署与文档（README、sql/init.sql 废弃、schema-init.sql 同步、nginx.conf、HTTPS 自动化、部署清单、安全扫描、application-prod.yml）

每阶段独立编译验证通过后进入下一阶段，确保最终 `mvn clean package -DskipTests` 与 `npm run build` 双双 BUILD SUCCESS。

---

## 二、当前状态分析（Current State Analysis）

### 2.1 已完成（阶段 0/1/2，编译验证通过）

| 模块 | 状态 |
|------|------|
| `backend/pom.xml` | ✅ jjwt 0.11.5、Jsoup 1.17.2、Actuator、Prometheus、Flyway 已添加；**Caffeine/Bucket4j 未添加**（阶段 3.7 需补） |
| `WorkOrderService.java` 接口 | ✅ 三个重载方法已加：`getWorkOrderByNo(String, Long)`、`archiveWorkOrder(Long, Long)`、`updateWorkOrder(WorkOrder, Long)` |
| `WorkOrderMapper.xml` | ✅ `ORDER BY ${sortField}` 已替换为白名单分支（L76-88）；`update_time` 字段已加（L146） |
| `DataSecurityServiceImpl.java` | ✅ fail-closed、`canAccessWorkOrderData` 真实校验、`getClientIp` 从 RequestContextHolder 解析、RoleConstants 集成 |
| `EmployeeServiceImpl.java` | ✅ 明文密码不入日志/响应、batchImport 强密码、软删除、RoleConstants 集成 |
| `ApprovalController.java` | ✅ `getApprovalLog` 加 @DataPermission |
| `EmployeeController.java` | ✅ 4 个端点加 @PreAuthorize("isAuthenticated()") |
| `WorkOrderServiceImpl.java` | ✅ `isSameDepartmentOrSuperAdmin` fail-closed；3 个重载方法带所有权校验 |

### 2.2 阶段 3-8 起点文件核对（已实际读取确认）

| 文件 | 当前状态 | 需要改动 |
|------|---------|---------|
| `interceptor/SqlInjectionInterceptor.java` | 已存在但未注册（死代码），正则过宽（匹配文本 `SELECT`、`0x`） | 重写正则 + 在 WebMvcConfig 注册 |
| `config/CorsConfig.java` | `addAllowedHeader("*")`、dev 用 `http://localhost:*` 通配 | 改显式 header 列表 + 精确 dev origin |
| `config/EnterpriseSecurityFilter.java` | CSP 含 `script-src 'unsafe-inline'`、限流 TODO（L226-243 `return true`）、`uri.contains()` 子串匹配（L268-274） | 严格 CSP + Caffeine 限流 + AntPathMatcher |
| `config/FlowableConfig.java` | 仅注册 `ParallelApprovalEventListener`，无脚本任务禁用配置 | 加 `setEnableSafeScripting` 或解析阶段拒绝 scriptTask |
| `service/impl/AuthServiceImpl.java` | **3 bug 确认**：L257-258 `expiration = System.currentTimeMillis() + jwtUtil.getExpirationDateFromToken(token).getTime()`、L676-677 同 bug、L766 `logLoginFailure(user.getUsername(), getUserAgent(), getUserAgent(), "旧密码错误")` | 修 3 bug |
| `service/impl/SecurityAuditServiceImpl.java` | `logSensitiveOperation`（L137）未从 RequestContextHolder 取 IP/UA；`queryAuditLogs`（L151）异常吞掉 | 修两处 |
| `frontend/package.json` | 无 `dompurify` 依赖 | 加 `dompurify: ^3.0.6` |
| `frontend/src/main.js` | 18 行，未注册 `v-safe-html` 指令 | 注册全局指令 |
| `frontend/src/utils/request.js` | 128 行，401 用 `ElMessageBox.confirm` | 改 `ElNotification` + 直接跳转 |
| `frontend/src/router/index.js` | 149 行，无 403 路由与 meta.permission 校验 | 加路由守卫 + 403 页面 |
| `backend/src/main/resources/sql/schema-init.sql` | 已存在 | 与 Flyway V1 同步 + 顶部加注释 |
| `sql/init.sql` | 已存在 | 顶部加 DEPRECATED 注释 |

### 2.3 依赖确认

- **Caffeine**：`backend/pom.xml` 未声明 → 阶段 3.7 新增 `com.github.ben-manes.caffeine:caffeine:3.1.8`
- **DOMPurify**：`frontend/package.json` 未声明 → 阶段 7.2 新增
- **Jsoup**：已在 pom.xml（1.17.2）→ 阶段 3.3 直接使用 `Safelist`
- **Spring Security**：已就绪 → 阶段 3-6 直接使用 `@PreAuthorize`

---

## 三、实施方案（Proposed Changes）

### 阶段 3：输入与输出防护（Input/Output Protection）

**目标**：实现 §3 全部 — SQL 注入、XSS、文件上传、CSRF、限流、CORS、防重放。

#### 3.1 重写 `interceptor/SqlInjectionInterceptor.java`
**文件**：`backend/src/main/java/com/workorder/interceptor/SqlInjectionInterceptor.java`

将 `SQL_INJECTION_PATTERNS` 正则收窄到真实注入（移除误拦截文本 `SELECT` 与 `0x` 十六进制）：
```java
private static final Pattern[] SQL_INJECTION_PATTERNS = {
    Pattern.compile("(?i)\\bunion\\s+(all\\s+)?select\\b"),
    Pattern.compile("(?i)\\bor\\s+1\\s*=\\s*1\\b"),
    Pattern.compile("(?i)\\band\\s+1\\s*=\\s*1\\b"),
    Pattern.compile("--"),
    Pattern.compile("(?i);\\s*drop\\b"),
    Pattern.compile("(?i);\\s*(select|insert|update|delete)\\b"),
    Pattern.compile("(?i)\\binsert\\s+into\\b"),
    Pattern.compile("(?i)\\bdelete\\s+from\\b"),
    Pattern.compile("(?i)\\bupdate\\s+\\w+\\s+set\\b"),
    Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
};
```
- `SENSITIVE_PARAMETERS` 改为 `*`（所有参数均校验，移除白名单忽略逻辑）
- `WHITELISTED_PATHS` 移除（与排除路径冲突，由 WebMvcConfig 统一排除）
- `preHandle` 对所有 `getParameterMap()` 入参循环校验；命中 → 403 + SECURITY logger

#### 3.2 新建 `config/WebMvcConfig.java`
**文件**：`backend/src/main/java/com/workorder/config/WebMvcConfig.java`

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Autowired private SqlInjectionInterceptor sqlInjectionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sqlInjectionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/static/**", "/webjars/**", "/actuator/**");
    }
}
```

#### 3.3 新建 `util/XssCleanUtil.java`
**文件**：`backend/src/main/java/com/workorder/util/XssCleanUtil.java`

```java
public final class XssCleanUtil {
    private static final Safelist NONE = Safelist.none();
    private static final Safelist SIMPLE = Safelist.simpleText();

    public static String clean(String input) {
        if (input == null) return null;
        return Jsoup.clean(input, NONE);
    }
    public static String cleanHtml(String input) {
        if (input == null) return null;
        return Jsoup.clean(input, SIMPLE);
    }
    public static String cleanRelaxed(String input) {
        if (input == null) return null;
        return Jsoup.clean(input, Safelist.relaxed());
    }
}
```

#### 3.4 新建 `config/XssFilter.java` + `config/XssRequestWrapper.java`
**文件**：`backend/src/main/java/com/workorder/config/XssFilter.java`、`XssRequestWrapper.java`

- `XssFilter` 实现 `Filter`，`@Order(Ordered.HIGHEST_PRECEDENCE + 1)`（在 EnterpriseSecurityFilter 之后），`@Component`
- 仅对 `application/json` 与 `application/x-www-form-urlencoded` 内容类型生效
- 包装为 `XssRequestWrapper extends HttpServletRequestWrapper`：
  - 重写 `getParameter`/`getParameterValues`/`getParameterMap`：对每个值调用 `XssCleanUtil.clean`
  - 重写 `getInputStream`：读取 body → Jsoup clean → 缓存 → 返回 `ServletInputStream`，重复读取安全
- 排除路径：`/api/auth/login`、`/api/auth/register`、`/api/file/**`（文件上传二进制）

#### 3.5 修改 `service/impl/WorkOrderServiceImpl.java` + `ApprovalServiceImpl`
**文件**：`backend/src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`（含 `ApprovalServiceImpl` 同包或独立类）

- `createDraft`/`submitNewWorkOrder`/`updateWorkOrder` 入口前：
  ```java
  dto.setTitle(XssCleanUtil.clean(dto.getTitle()));
  dto.setContent(XssCleanUtil.cleanRelaxed(dto.getContent()));
  dto.setReason(XssCleanUtil.clean(dto.getReason()));
  ```
- `handleApproval` 的 `comment`：`approvalDTO.setComment(XssCleanUtil.clean(approvalDTO.getComment()))`
- `resubmitWorkOrder` 的 `comment` 同上

#### 3.6 新建 `config/FileUploadConfig.java` + `controller/FileController.java` + `util/FileUploadValidator.java`
**文件**：3 个新文件

**`FileUploadConfig.java`**：
```java
@Bean
public MultipartResolver multipartResolver() { ... }
// application.yml: spring.servlet.multipart.max-file-size=10MB, max-request-size=50MB
```

**`util/FileUploadValidator.java`**：
- `ALLOWED_EXTENSIONS = {pdf, doc, docx, xls, xlsx, png, jpg, jpeg, zip}`
- `MAGIC_NUMBERS` Map：pdf=25504446、doc/docx=D0CF11E0/504B0304、png=89504E47、jpg=FFD8FF、zip=504B0304
- `validate(MultipartFile file)`：校验后缀 → 校验 Magic Number（前 8 字节）→ 校验大小（≤10MB）
- `generateSafeName(String original)`：`UUID.randomUUID() + "." + ext`

**`controller/FileController.java`**：
- `@PostMapping("/api/file/upload")` + `@PreAuthorize("isAuthenticated()")`
- 调 `FileUploadValidator.validate`，存到 `${FILE_STORAGE_PATH}` 下，返回 `{fileId, originalName, url}`
- `@GetMapping("/api/file/download/{fileId}")` + `@PreAuthorize("isAuthenticated()")`
- 校验 fileId 对应工单的所有权（注入 WorkOrderMapper，查 `sys_attachment.work_order_id` → canUserViewWorkOrder）
- 不直接暴露磁盘路径，通过流输出

#### 3.7 重写 `config/EnterpriseSecurityFilter.java` 限流与 CSP
**文件**：`backend/src/main/java/com/workorder/config/EnterpriseSecurityFilter.java`

**改动**：
1. **CSP 严格化**（L136-137）：改为
   ```java
   response.setHeader("Content-Security-Policy",
       "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
       "img-src 'self' data: https:; connect-src 'self'; font-src 'self'; " +
       "frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
   ```
   （`style-src 'unsafe-inline'` 保留因 Element Plus 需要内联样式）

2. **EXCLUDED_PATHS 改 AntPathMatcher**（L268-274）：
   ```java
   private final AntPathMatcher pathMatcher = new AntPathMatcher();
   private static final String[] EXCLUDED_PATTERNS = {
       "/api/auth/login", "/api/auth/register", "/actuator/**",
       "/swagger*/**", "/api-docs/**", "/v3/api-docs/**", "/webjars/**"
   };
   private boolean isExcludedPath(String uri) {
       for (String p : EXCLUDED_PATTERNS) if (pathMatcher.match(p, uri)) return true;
       return false;
   }
   ```

3. **Caffeine 限流**（替换 L226-243 TODO）：
   - pom.xml 加：`com.github.ben-manes.caffeine:caffeine:3.1.8`
   - 三级令牌桶：
     ```java
     // 登录 /api/auth/login: 5 次/分钟/IP
     private final Cache<String, int[]> loginBucket = Caffeine.newBuilder()
         .expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(100_000).build();
     // 审批 /api/approval/**: 30 次/分钟/用户
     private final Cache<String, int[]> approveBucket = ...;
     // 其他: 100 次/分钟/用户
     private final Cache<String, int[]> defaultBucket = ...;
     ```
   - `checkRateLimit` 按 URI 匹配三档，key=`ip` 或 `userId`（从 JWT 提取，无则用 IP），超限返回 429 + `Retry-After: 60`
   - 配置项：`app.rate-limit.login-per-minute`、`app.rate-limit.approve-per-minute`、`app.rate-limit.default-per-minute`

4. **CSRF 校验保留**（L206-220）：维持 JWT Bearer 校验逻辑，无 Cookie CSRF

#### 3.8 严格化 `config/CorsConfig.java`
**文件**：`backend/src/main/java/com/workorder/config/CorsConfig.java`

```java
// 替换 L43 addAllowedHeader("*") 为：
config.setAllowedHeaders(Arrays.asList(
    "Authorization", "Content-Type", "X-Requested-With",
    "X-XSRF-TOKEN", "X-Request-Nonce", "X-Request-Timestamp"
));
// dev 默认改为精确白名单（替换 L35-36）：
config.setAllowedOrigins(Arrays.asList(
    "http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173"
));
// prod：allowedOrigins 为空 → fail-closed 抛 IllegalStateException
if (allowedOrigins == null || allowedOrigins.isBlank()) {
    if (activeProfile.equals("prod")) {
        throw new IllegalStateException("CORS allowed-origins must be configured in prod");
    }
    config.setAllowedOrigins(Arrays.asList("http://localhost:5173", "http://localhost:3000"));
} else {
    config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
}
```
- `setAllowCredentials(true)` 保留
- `setAllowedMethods(Arrays.asList("GET","POST","PUT","DELETE","OPTIONS","PATCH"))` 保留
- 显式暴露 `Authorization, Content-Type, X-Request-Nonce`

#### 3.9 新建 `annotation/AntiReplay.java` + `aspect/AntiReplayAspect.java`
**文件**：`backend/src/main/java/com/workorder/annotation/AntiReplay.java`、`aspect/AntiReplayAspect.java`

**`AntiReplay.java`**：
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AntiReplay {
    /** 时间窗口（秒），默认 300 */
    int timeWindow() default 300;
}
```

**`AntiReplayAspect.java`**：
- `@Around("@annotation(antiReplay)")`
- 取请求头 `X-Request-Nonce` 与 `X-Request-Timestamp`
- Nonce 为空 → 400 "缺少防重放标识"
- Timestamp 与服务器偏差 > `timeWindow` 秒 → 401 "请求已过期"
- Redis `SETNX request:nonce:{nonce} 1 EX {timeWindow}`：返回 0 → 409 "重复请求"
- 应用于 `ApprovalController.handleApproval`、`EmployeeController.resetPassword`、`WorkOrderController.deleteWorkOrder`

#### 3.10 修改 `pom.xml` 加 Caffeine
**文件**：`backend/pom.xml`

```xml
<!-- Caffeine (本地限流令牌桶) -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>
```

**阶段 3 验证**：
```powershell
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\backend'
mvn -DskipTests compile
# 期望：BUILD SUCCESS
```
- 编译通过即视为本阶段通过；运行时验证（XSS 净化、限流 429）在阶段 8 端到端验证。

---

### 阶段 4：数据存储与加密（Data Storage & Encryption）

**目标**：实现 §5 — AES 加密、数据库安全、Redis 加固、密码策略。

#### 4.1 新建 `util/AesEncryptionUtil.java`
**文件**：`backend/src/main/java/com/workorder/util/AesEncryptionUtil.java`

- AES-GCM 256 模式，密钥从 `${AES_ENCRYPTION_KEY}` 读取（32 字节 Base64）
- `@Component`，`@Value("${aes.encryption-key}")` 注入，启动时校验长度
- `encrypt(String plain)`：随机 12 字节 IV → `Cipher.ENCRYPT_MODE` → 输出 `base64(iv) + ":" + base64(cipher+tag)`
- `decrypt(String stored)`：解析 `iv:cipher` → `DECRYPT_MODE`
- `encryptIfNotNull(String)` / `decryptIfNotNull(String)`：null 安全
- 异常时抛 `BusinessException("加解密失败")`，不泄露内部细节
- 兼容标记：旧明文数据返回时若 `decrypt` 抛异常 → 视为明文原样返回（迁移期容错）

#### 4.2 新建 `config/AesEncryptedStringTypeHandler.java`
**文件**：`backend/src/main/java/com/workorder/config/AesEncryptedStringTypeHandler.java`

```java
@MappedTypes(String.class)
public class AesEncryptedStringTypeHandler extends BaseTypeHandler<String> {
    private static AesEncryptionUtil aesUtil; // 通过 setter 注入（Spring 容器）

    @Override public void setNonNullParameter(PreparedStatement ps, int i, String param, JdbcType jdbcType) {
        ps.setString(i, aesUtil.encrypt(param));
    }
    @Override public String getNullableResult(ResultSet rs, String column) {
        return aesUtil.decryptIfNotNull(rs.getString(column));
    }
    // ... 其他重载
}
```
- 通过 `@Component` + `@PostConstruct` 静态字段注入（MyBatis TypeHandler 不走 Spring 容器）
- 处理 null：返回 null
- 处理非加密格式（旧数据）：捕获异常 → 返回原值

#### 4.3 修改 `entity/User.java` + `mapper/UserMapper.xml`
**文件**：`backend/src/main/java/com/workorder/entity/User.java`、`backend/src/main/resources/mapper/UserMapper.xml`

- `User.java`：`phone`、`email` 字段加 `@TableField(typeHandler = AesEncryptedStringTypeHandler.class)`
- `UserMapper.xml`：
  - `<result>` 标签加 `typeHandler="com.workorder.config.AesEncryptedStringTypeHandler"` 给 `phone`、`email` 列
  - `<insert>`/`<update>` 中 `#{phone, typeHandler=...}`、`#{email, typeHandler=...}`
  - `selectList` 查询排除 `password` 列（已通过 Result Map 控制，复核）
  - 新增 `selectByIdSafe`：不返回 password 列

#### 4.4 新建 `db/migration/V2__add_password_history.sql`
**文件**：`backend/src/main/resources/db/migration/V2__add_password_history.sql`

```sql
-- V2: 密码历史表（密码策略防止重用）
CREATE TABLE IF NOT EXISTS sys_password_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_password_history_user ON sys_password_history(user_id, create_time DESC);
```

#### 4.5 新建 `db/migration/V3__add_audit_indexes.sql`
**文件**：`backend/src/main/resources/db/migration/V3__add_audit_indexes.sql`

```sql
-- V3: 审计日志索引（查询性能优化）
CREATE INDEX IF NOT EXISTS idx_audit_log_user_time ON sys_security_audit_log(user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_audit_log_action_time ON sys_security_audit_log(action_type, create_time);
CREATE INDEX IF NOT EXISTS idx_audit_log_ip_time ON sys_security_audit_log(ip_address, create_time);
```

#### 4.6 强化 `service/impl/PasswordPolicyServiceImpl.java`
**文件**：`backend/src/main/java/com/workorder/service/impl/PasswordPolicyServiceImpl.java`

- 默认策略改为强策略：`requireDigit=true, requireSpecialChar=true, requireUppercase=true, requireLowercase=true, minPasswordLength=10`
- `validatePasswordNotReused`：异常时 fail-closed 拒绝（抛 `BusinessException`），不返回 true
- `recordFailedLoginAttempt`：改为按 IP+用户名组合锁定（`login:fail:{ip}:{username}` Redis key）
- 新增 `recordPasswordChange(Long userId, String newHash)`：写入 `sys_password_history`，保留最近 5 条

#### 4.7 接通 `AuthServiceImpl.changePassword` 与密码历史
**文件**：`backend/src/main/java/com/workorder/service/impl/AuthServiceImpl.java`

- `changePassword`（L753+）末尾调用 `passwordPolicyService.recordPasswordChange(userId, passwordEncoder.encode(newPassword))`
- 与 §5.1 bug 修复合并（同文件）

#### 4.8 新建 `docs/redis-hardening.conf` + `docs/db-grants.sql`
**文件**：`docs/redis-hardening.conf`、`docs/db-grants.sql`

**`redis-hardening.conf`**：
```
bind 127.0.0.1 ::1
protected-mode yes
requirepass ${REDIS_PASSWORD}
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""
rename-command KEYS ""
rename-command DEBUG ""
maxmemory 512mb
maxmemory-policy allkeys-lru
timeout 300
tcp-keepalive 60
```

**`db-grants.sql`**：
```sql
-- 应用账户最小权限：仅 CRUD，禁 DDL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO wos_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO wos_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wos_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO wos_app;
-- 禁止 DDL
REVOKE CREATE ON SCHEMA public FROM wos_app;
```

**阶段 4 验证**：
```powershell
cd backend; mvn -DskipTests compile
# 期望：BUILD SUCCESS
# 启动后 SQL 查询 sys_user.phone 为密文，API 响应为明文（TypeHandler 解密）
```

---

### 阶段 5：日志与审计修复（Audit Logging Fixes）

**目标**：实现 §6 — 全量审计、实时告警、日志保护。

#### 5.1 修 `service/impl/AuthServiceImpl.java` 3 bug
**文件**：`backend/src/main/java/com/workorder/service/impl/AuthServiceImpl.java`

- **L257-258** 改为：
  ```java
  long expiration = jwtUtil.getExpirationDateFromToken(token).getTime();
  result.put("expiration", expiration);
  ```
- **L262** 同步修正（无 `+` 拼接）
- **L676-677**（refreshToken）同样改为 `long expiration = jwtUtil.getExpirationDateFromToken(newToken).getTime();`
- **L766** 改为：
  ```java
  securityAuditService.logLoginFailure(
      user.getUsername(), getClientIp(), getUserAgent(), "旧密码错误");
  ```

#### 5.2 修 `service/impl/SecurityAuditServiceImpl.java`
**文件**：`backend/src/main/java/com/workorder/service/impl/SecurityAuditServiceImpl.java`

- `logSensitiveOperation`（L137）：从 `RequestContextHolder.currentRequestAttributes()` 获取 `HttpServletRequest`，取真实 IP（`X-Forwarded-For` → `getRemoteAddr`）与 `User-Agent`，写入审计记录
- `queryAuditLogs`（L151）：移除 try-catch 吞异常，向上抛由 `GlobalExceptionHandler` 统一处理
- 新增 `private String getClientIpFromRequest()` 方法

#### 5.3 新建 `src/main/resources/logback-spring.xml`
**文件**：`backend/src/main/resources/logback-spring.xml`

- 控制台 + 文件双输出，按日滚动，保留 30 天，单文件 100MB
- Appender：`CONSOLE`、`FILE`、`SECURITY_FILE`（审计日志单独文件）
- 引用 `SensitiveDataConverter`：正则脱敏 `password=\S+`、`token=\S+`、`Authorization: Bearer \S+`、`secret=\S+`
- 异步 appender 包装 `FILE`（性能）

#### 5.4 新建 `util/SensitiveDataConverter.java`
**文件**：`backend/src/main/java/com/workorder/util/SensitiveDataConverter.java`

```java
public class SensitiveDataConverter extends ClassicConverter {
    private static final Pattern[] PATTERNS = {
        Pattern.compile("(?i)(password\\s*[=:]\\s*)\\S+"),
        Pattern.compile("(?i)(token\\s*[=:]\\s*)\\S+"),
        Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)\\S+"),
        Pattern.compile("(?i)(secret\\s*[=:]\\s*)\\S+"),
        Pattern.compile("(?i)(newPassword\\s*[=:]\\s*)\\S+")
    };
    @Override public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        for (Pattern p : PATTERNS) msg = p.matcher(msg).replaceAll("$1***");
        return msg;
    }
}
```

#### 5.5 新建 `config/MetricsConfig.java`
**文件**：`backend/src/main/java/com/workorder/config/MetricsConfig.java`

- `@Bean Counter loginAttemptsCounter`：标签 `result={success,failure}`
- `@Bean Counter authorizationDenialsCounter`：标签 `reason={no_token,invalid_token,no_permission,data_denied}`
- `@Bean Counter sensitiveOperationsCounter`：标签 `type={login,password_reset,delete,data_export}`
- 在 `JwtAuthenticationFilter`（401 时 `authorizationDenialsCounter.increment("no_token")`）、`DataPermissionAspect`（拒绝时 `authorizationDenialsCounter.increment("data_denied")`）、`AuthServiceImpl`（登录 `loginAttemptsCounter.increment(result)`）埋点

#### 5.6 新建 `docs/alertmanager-rules.yml`
**文件**：`docs/alertmanager-rules.yml`

```yaml
groups:
  - name: wos-security
    rules:
      - alert: HighLoginFailures
        expr: rate(wos_login_attempts_total{result="failure"}[5m]) > 4
        for: 1m
        labels: { severity: critical }
        annotations: { summary: "5分钟内登录失败超过20次" }
      - alert: HighAuthorizationDenials
        expr: rate(wos_authorization_denials_total[5m]) > 2
        for: 1m
        labels: { severity: warning }
        annotations: { summary: "5分钟内403超过10次" }
      - alert: PasswordResetAbuse
        expr: rate(wos_sensitive_operations_total{type="password_reset"}[1m]) > (5/60)
        for: 1m
        labels: { severity: critical }
        annotations: { summary: "1分钟内密码重置超过5次" }
```

**阶段 5 验证**：
```powershell
cd backend; mvn -DskipTests compile
# 期望：BUILD SUCCESS
# 启动后访问 /actuator/prometheus 含 wos_login_attempts_total
```

---

### 阶段 6：Flowable 工作流安全（Flowable Workflow Security）

**目标**：实现 §9 — 流程定义权限、流程变量安全、防任务哄抢。

#### 6.1 `controller/ProcessController.java` 权限
**文件**：`backend/src/main/java/com/workorder/controller/ProcessController.java`（如不存在则新建）

- 部署/删除/挂起流程定义端点加 `@PreAuthorize("hasRole('SUPER_ADMIN')")`
- 查询流程定义端点加 `@PreAuthorize("isAuthenticated()")`

#### 6.2 修改 `config/FlowableConfig.java` 禁脚本任务
**文件**：`backend/src/main/java/com/workorder/config/FlowableConfig.java`

```java
@Configuration
public class FlowableConfig implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {
    @Override
    public void configure(SpringProcessEngineConfiguration config) {
        // 禁用脚本任务执行（防止注入 groovy 脚本）
        config.setEnableSafeScripting(true);
        config.setDisableEventCallbacks(false);
    }
    @Bean
    public FlowableEventListener globalEventListener() {
        return new com.workorder.listener.ParallelApprovalEventListener();
    }
}
```
- 部署 BPMN 时若含 `scriptTask` → 在部署解析阶段拒绝（注册 `BpmnParseHandler` 检测）

#### 6.3 修改 `service/impl/WorkOrderServiceImpl.java` 候选人校验 + 变量净化
**文件**：`backend/src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`

- 提交工单前清理流程变量：移除含 `password`/`secret`/`token`/`credential` 的 key
- `handleApproval` 在 `taskService.claim(taskId, userId)` 前校验：
  ```java
  Task task = taskService.createTaskQuery().taskId(approvalDTO.getTaskId()).singleResult();
  if (task == null) throw new BusinessException("任务不存在或已完成");
  // 校验当前用户是否为合法候选人
  boolean isCandidate = false;
  if (task.getAssignee() != null && task.getAssignee().equals(String.valueOf(userId))) {
      isCandidate = true;
  } else {
      // 校验候选组
      List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
      for (IdentityLink link : links) {
          if ("candidate".equals(link.getType())) {
              if (link.getUserId() != null && link.getUserId().equals(String.valueOf(userId))) {
                  isCandidate = true; break;
              }
              if (link.getGroupId() != null && currentUser.getRoles().contains(link.getGroupId())) {
                  isCandidate = true; break;
              }
          }
      }
  }
  if (!isCandidate) throw new BusinessException("您不是该任务的合法候选人");
  ```

#### 6.4 新建 `config/RedisMessageConfig.java`
**文件**：`backend/src/main/java/com/workorder/config/RedisMessageConfig.java`

- Redis Pub/Sub 频道隔离：`wos:approval:{departmentId}` 前缀
- 消息签名：HMAC-SHA256，密钥 `${MESSAGE_SIGNING_KEY}`
- `RedisMessagePublisher`：发布前签名（`sign = hmacSha256(payload, key)`，消息体 `{signature, payload}`）
- `RedisMessageSubscriber`：接收方校验签名，不匹配 → 丢弃 + 记录 SECURITY 告警

#### 6.5 修改 `listener/ParallelApprovalEventListener.java` + 新建候选人缓存
**文件**：`backend/src/main/java/com/workorder/listener/ParallelApprovalEventListener.java`

- `TaskCreatedListener`：候选人写入 Redis（`task:candidates:{taskId}` → Set<userId>，TTL 24h）
- `TaskCompletedListener`：删除候选人缓存
- 审批前从 Redis 校验候选人（与 6.3 Flowable API 校验形成纵深防御）

**阶段 6 验证**：
```powershell
cd backend; mvn -DskipTests compile
# 期望：BUILD SUCCESS
# 普通用户部署 BPMN → 403；非候选人审批 → BusinessException
```

---

### 阶段 7：前端加固与 impeccable 标准（Frontend Hardening & impeccable）

**目标**：实现 §3 前端部分 + 路由权限 + 按 impeccable 审计修复严重违规。

#### 7.1 运行 impeccable 审计与 PRODUCT.md
- 读 `.cursor/skills/impeccable/SKILL.md` 与 `reference/product.md`（应用 UI 寄存器）
- 复核 `PRODUCT.md` 与 `DESIGN.md`（项目根已存在）
- 扫描所有 `.vue` 文件对照 Absolute bans 列表
- 用户偏好（来自 memory）：量子主题、宇宙 > 赛博、不喜欢发光光晕、统一全局字体、首屏无登录

#### 7.2 `frontend/package.json` 加依赖
**文件**：`frontend/package.json`

```json
"dependencies": {
  "dompurify": "^3.0.6"
}
```

#### 7.3 新建 `frontend/src/utils/sanitize.js`
**文件**：`frontend/src/utils/sanitize.js`

```js
import DOMPurify from 'dompurify';

const DEFAULT_CONFIG = {
  ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'br', 'p', 'ul', 'ol', 'li'],
  ALLOWED_ATTR: ['class']
};

export function sanitize(dirty, config = DEFAULT_CONFIG) {
  return DOMPurify.sanitize(dirty, config);
}

export function sanitizeStrict(dirty) {
  return DOMPurify.sanitize(dirty, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] });
}
```

#### 7.4 修改 `frontend/src/main.js`
**文件**：`frontend/src/main.js`

- 注册全局指令 `v-safe-html`：
  ```js
  import { sanitize } from './utils/sanitize';
  app.directive('safe-html', {
    updated(el, binding) { el.innerHTML = sanitize(binding.value); },
    beforeMount(el, binding) { el.innerHTML = sanitize(binding.value); }
  });
  ```
- 全局替换所有 `.vue` 中的 `v-html` 为 `v-safe-html`（仅一处用例时按需替换）

#### 7.5 修改 `frontend/index.html`
**文件**：`frontend/index.html`

加 CSP meta：
```html
<meta http-equiv="Content-Security-Policy"
      content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
               img-src 'self' data: https:; connect-src 'self' http://localhost:8080;
               font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'">
```

#### 7.6 修改 `frontend/src/router/index.js` + 新建 `views/error/403.vue`
**文件**：`frontend/src/router/index.js`、`frontend/src/views/error/403.vue`

- 路由守卫：
  ```js
  router.beforeEach((to, from, next) => {
    if (to.meta.permission) {
      const userStore = useUserStore();
      if (!userStore.hasPermission(to.meta.permission)) {
        next('/403'); return;
      }
    }
    next();
  });
  ```
- 每个受限路由加 `meta: { permission: 'system:settings' }` 等
- 新增 `/403` 路由指向 `views/error/403.vue`（量子主题、宇宙粒子背景、返回首页按钮）

#### 7.7 修改 `frontend/src/utils/request.js`
**文件**：`frontend/src/utils/request.js`

- 401 响应拦截器改为：
  ```js
  if (error.response?.status === 401) {
    ElNotification({ title: '会话过期', message: '请重新登录', type: 'warning', duration: 2000 });
    useUserStore().logout();
    router.push('/login');
    return Promise.reject(error);
  }
  ```
- 移除 `ElMessageBox.confirm` 弹窗（用户偏好"消除内容实际删除之间的空白间隔"风格，避免阻塞）

#### 7.8 impeccable 关键修复
**文件**：`frontend/src/styles/tokens.scss`（已存在，扩展 OKLCH tokens）

- 新增 OKLCH 变量：`--bg, --surface, --ink, --accent, --muted, --border`，统一品牌色（量子主题深空底色 + 量子蓝紫强调）
- 全局搜索硬编码 `#xxx` 替换为 `var(--xxx)`（保留 SVG/Canvas 必要硬编码）
- Absolute bans 违规修复：
  - side-stripe borders → 改为完整边框或背景色调
  - gradient text → 改为单色 + weight 对比
  - 装饰性 glassmorphism → 改为实色或仅在 hero 区使用
  - tiny uppercase tracked eyebrow → 改为正常大小写 + 不同字重
  - numbered section markers → 移除或仅在真实序列时保留
- 所有动画加 `@media (prefers-reduced-motion: reduce)` 替代（crossfade 或 instant）
- 对比度：body 文本 ≥ 4.5:1，placeholder ≥ 4.5:1

#### 7.9 修改 `frontend/src/views/Login.vue`
**文件**：`frontend/src/views/Login.vue`

- 修复 Absolute bans 违规（按 7.8 标准）
- 量子主题视觉元素（用户偏好：科幻黑洞引力漩涡粒子数据碎片主题；不喜欢发光光晕）
- 认证按钮顶级动效（用户偏好：要求极致美感和动效）
- 不做全量重设计，仅修复违规 + 提升关键页面

**阶段 7 验证**：
```powershell
cd frontend; npm run build
# 期望：dist/ 生成，无构建错误
# v-html 全局搜索为 0
```

---

### 阶段 8：部署与文档（Deployment & Documentation）

**目标**：实现 §7-§8 剩余项 + 修复文档与 SQL 不一致。

#### 8.1 修改 `README.md`
**文件**：`README.md`

- 修正 MySQL 8.0+ → PostgreSQL 13+
- 补充：环境变量列表（`AES_ENCRYPTION_KEY`、`JWT_SECRET`、`REDIS_PASSWORD`、`MESSAGE_SIGNING_KEY` 等）
- docker-compose 启动步骤、Flyway 迁移说明、生产部署清单
- 安全配置说明（CORS、CSP、限流）

#### 8.2 标记 `sql/init.sql` 为 DEPRECATED
**文件**：`sql/init.sql`

顶部加：
```sql
-- ============================================================
-- DEPRECATED: 本脚本已废弃，仅保留历史参考
-- 新部署请使用 Flyway 迁移：
--   backend/src/main/resources/db/migration/V1__init.sql
--   backend/src/main/resources/db/migration/V2__add_password_history.sql
--   backend/src/main/resources/db/migration/V3__add_audit_indexes.sql
-- 或手动初始化使用：backend/src/main/resources/sql/schema-init.sql
-- ============================================================
```

#### 8.3 同步 `backend/src/main/resources/sql/schema-init.sql`
**文件**：`backend/src/main/resources/sql/schema-init.sql`

- 与 Flyway V1+V2+V3 内容同步（统一为最新 schema）
- 顶部加注释说明手动初始化备用方案

#### 8.4 新建 `docs/nginx.conf`
**文件**：`docs/nginx.conf`

```nginx
# HTTP → HTTPS 重定向
server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-GCM-SHA256;
    ssl_prefer_server_ciphers on;

    # 安全头
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin" always;
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;

    # 限流：登录接口
    limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;

    # 前端静态
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 反代
    location /api/ {
        limit_req zone=login burst=10 nodelay;
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Actuator 仅内网
    location /actuator/ {
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
        proxy_pass http://backend:8080;
    }
}
```

#### 8.5 新建 `docs/https-automation.md`
**文件**：`docs/https-automation.md`

- acme.sh 方案：standalone 模式 + nginx reload + cron 续签
- cert-manager 方案：Kubernetes Ingress + Certificate CRD 示例

#### 8.6 新建 `docs/deployment-checklist.md`
**文件**：`docs/deployment-checklist.md`

生产环境检查清单：
- [ ] 环境变量齐全：`JWT_SECRET`, `AES_ENCRYPTION_KEY`, `REDIS_PASSWORD`, `MESSAGE_SIGNING_KEY`, `DB_PASSWORD`
- [ ] 数据库账户仅 CRUD 权限（执行 `db-grants.sql`）
- [ ] Redis 加固（应用 `redis-hardening.conf`）
- [ ] CORS 配置具体域名（`cors.allowed-origins`）
- [ ] Actuator 仅暴露 `health,prometheus`
- [ ] 日志路径权限 750
- [ ] 备份策略（每日全量 + WAL 归档）
- [ ] HTTPS 证书有效（自动续签）
- [ ] WAF 接入（文档说明，基础设施层）
- [ ] DDoS 防护（云厂商配置）

#### 8.7 新建 `.github/workflows/security-scan.yml`
**文件**：`.github/workflows/security-scan.yml`

```yaml
name: Security Scan
on: { push: { branches: [main, develop] }, pull_request: }
jobs:
  maven:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - name: OWASP Dependency-Check
        run: mvn org.owasp:dependency-check-maven:9.0.9:check
  npm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '18' }
      - run: cd frontend && npm ci && npm audit --audit-level=high
  docker:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Trivy Scan
        uses: aquasecurity/trivy-action@master
        with: { image-ref: '.' }
```

#### 8.8 修改 `backend/src/main/resources/application-prod.yml`
**文件**：`backend/src/main/resources/application-prod.yml`

- 所有密钥用 `${ENV}` 无默认值
- `app.db.auto-create: false`
- `spring.flyway.enabled: true`、`spring.flyway.baseline-on-migrate: true`
- `management.endpoints.web.exposure.include: health,prometheus`
- `logging.level.root: WARN`、`logging.level.com.workorder: INFO`

**阶段 8 验证**：
```powershell
cd backend; mvn -DskipTests compile
cd ..\frontend; npm run build
# README 无 MySQL 字样
# Flyway 迁移脚本 V1/V2/V3 齐全
```

---

## 四、执行顺序与依赖（Execution Order）

```
阶段 3（输入输出防护）— 可独立完成
  └─ 阶段 4（数据存储加密）
       └─ 阶段 5（日志审计修复）
            └─ 阶段 6（Flowable 安全）
                 └─ 阶段 7（前端加固 + impeccable）
                      └─ 阶段 8（部署与文档）
```

每阶段独立编译验证通过后进入下一阶段。

---

## 五、假设与决策（Assumptions & Decisions）

### 5.1 关键决策
1. **限流实现**：Caffeine 本地令牌桶（pom.xml 新增 `caffeine:3.1.8`）。生产可平滑替换为 Redis 分布式限流。
2. **AES 加密迁移容错**：`AesEncryptedStringTypeHandler.decrypt` 异常时若非 `iv:cipher` 格式 → 视为明文原样返回，避免历史数据无法读取。
3. **MyBatis TypeHandler 静态注入**：因 MyBatis TypeHandler 不走 Spring 容器，通过 `@Component` + `@PostConstruct` 静态字段注入 `AesEncryptionUtil`。
4. **Flowable 禁脚本**：通过 `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>` 设置 `setEnableSafeScripting(true)`，并在 BpmnParseHandler 检测 `scriptTask` 拒绝部署。
5. **候选人校验**：双重防御 — Flowable API（`getIdentityLinksForTask`）+ Redis 缓存（`task:candidates:{taskId}`）。
6. **前端安全**：`v-safe-html` 全局指令替代 `v-html`；CSP meta + 后端 CSP 双重设置；OKLCH tokens 扩展现有 `tokens.scss`。
7. **明文密码处理**：阶段 2 已脱敏 + 响应不返回明文；阶段 4 接通完整密码历史机制。
8. **`sql/init.sql` 废弃但保留**：不删除（避免破坏依赖），仅顶部加 DEPRECATED 注释。
9. **impeccable 范围**：仅修复 Absolute bans 类硬性违规 + 提升 Login.vue；不做全量 UI 重设计（用户偏好"不要全量重设计"语境）。
10. **防重放**：Redis `SETNX` 实现，应用于审批/密码重置/工单删除三个关键操作。

### 5.2 假设
- 用户本地已安装 Java 17、Maven 3.9+、Node.js 18+、PostgreSQL 13+、Redis 6+（或使用 docker-compose）。
- dev 环境使用 `${ENV_VAR:default}` 模式；prod 强制环境变量（无默认值，缺失时启动失败）。
- Flyway 首次部署执行 V1→V2→V3 顺序迁移。
- 用户接受 Caffeine 本地限流（生产建议换 Redis 分布式）。
- 用户接受前端 Element Plus 内联样式 → CSP `style-src 'unsafe-inline'` 保留。

### 5.3 不做的事项（明确排除）
- 不升级 SpringBoot 2.7.18 → 3.x（保持兼容）
- 不引入 Sentinel（用 Caffeine 替代）
- 不引入 Vault/Kubernetes Secrets（用环境变量替代）
- 不引入 ELK（用 logback 文件 + Prometheus 替代）
- 不引入 WAF（基础设施层，文档说明）
- 不做前端全量 UI 重设计（仅审计 + 修复严重违规 + 提升 Login.vue）
- 不删除 `sql/init.sql`（仅标记 DEPRECATED）

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

### 6.2 阶段 3 验证（输入防护）
```powershell
cd backend; mvn -DskipTests compile
# 期望：BUILD SUCCESS
# 启动后：
# 提交 <script>alert(1)</script> 作为工单标题 → 存储后不含 script 标签
# /auth/login 1 分钟内 6 次请求 → 第 6 次返回 429
```

### 6.3 阶段 4 验证（加密）
```sql
SELECT phone, email FROM sys_user WHERE id = 1;
-- 期望：密文（base64:base64 格式）
-- API 响应：明文（TypeHandler 解密）
-- 修改密码 → sys_password_history 有新记录
```

### 6.4 阶段 5 验证（审计日志）
```sql
SELECT username, ip_address, user_agent, action_type FROM sys_security_audit_log ORDER BY id DESC LIMIT 1;
-- 期望：ip_address ≠ user_agent（真实 IP 与 UA）
-- /actuator/prometheus 含 wos_login_attempts_total
-- 日志中 password=xxx 显示为 password=***
```

### 6.5 阶段 6 验证（Flowable）
```powershell
# 普通用户部署 BPMN → 403
# 非候选人审批 → BusinessException
# 流程变量中 password/secret 被清理
```

### 6.6 阶段 7 验证（前端）
```powershell
cd frontend; npm run build
# 期望：dist/ 生成
# v-html 全局搜索为 0
# 普通员工访问 /system/settings → 跳转 /403
# 浏览器控制台无 CSP 违规
```

### 6.7 阶段 8 验证（文档）
```powershell
Select-String -Path README.md -Pattern "MySQL"
# 期望：无匹配
Get-ChildItem backend/src/main/resources/db/migration/
# 期望：V1__init.sql, V2__add_password_history.sql, V3__add_audit_indexes.sql
```

### 6.8 最终全量验证
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
# 7. 加密字段入库为密文，API 响应为明文
```

---

## 七、风险与缓解（Risks & Mitigation）

| 风险 | 缓解 |
|------|------|
| Caffeine 限流单实例不一致 | 文档说明生产换 Redis 分布式限流 |
| AES TypeHandler 影响现有明文数据 | decrypt 异常时若非 `iv:cipher` 格式视为明文原样返回 |
| MyBatis TypeHandler 不走 Spring 容器 | `@Component` + `@PostConstruct` 静态注入 |
| Flowable setEnableSafeScripting 可能不影响 scriptTask | 增加 BpmnParseHandler 在部署解析阶段拒绝 scriptTask |
| 前端 CSP 过严导致 Element Plus 失效 | style-src 保留 'unsafe-inline' |
| impeccable 审计发现大量违规 | 仅修复 Absolute bans 类硬性违规；其他记录到 `docs/impeccable-todo.md` |
| AuthServiceImpl L257 修正影响前端 token 续期逻辑 | expiration 字段为绝对时间戳（毫秒），前端 axios 拦截器按绝对时间判断 |
| Redis Pub/Sub 消息签名密钥缺失 | application-prod.yml 强制 `${MESSAGE_SIGNING_KEY}`，缺失启动失败 |
| 候选人 Redis 缓存与 Flowable 不一致 | 双重校验：Flowable API 为主，Redis 缓存为辅；不一致时以 Flowable 为准并刷新缓存 |

---

## 八、交付物清单（Deliverables）

### 阶段 3
- ✅ `interceptor/SqlInjectionInterceptor.java` 重写（收窄正则）
- ✅ `config/WebMvcConfig.java` 新建（注册拦截器）
- ✅ `util/XssCleanUtil.java` 新建
- ✅ `config/XssFilter.java` + `config/XssRequestWrapper.java` 新建
- ✅ `service/impl/WorkOrderServiceImpl.java` 入口 XSS 净化
- ✅ `config/FileUploadConfig.java` + `util/FileUploadValidator.java` + `controller/FileController.java` 新建
- ✅ `config/EnterpriseSecurityFilter.java` 重写（CSP + Caffeine 限流 + AntPathMatcher）
- ✅ `config/CorsConfig.java` 严格化
- ✅ `annotation/AntiReplay.java` + `aspect/AntiReplayAspect.java` 新建
- ✅ `pom.xml` 加 Caffeine 依赖

### 阶段 4
- ✅ `util/AesEncryptionUtil.java` 新建
- ✅ `config/AesEncryptedStringTypeHandler.java` 新建
- ✅ `entity/User.java` + `mapper/UserMapper.xml` 加密字段
- ✅ `db/migration/V2__add_password_history.sql` 新建
- ✅ `db/migration/V3__add_audit_indexes.sql` 新建
- ✅ `service/impl/PasswordPolicyServiceImpl.java` 强化
- ✅ `service/impl/AuthServiceImpl.java` 接通密码历史
- ✅ `docs/redis-hardening.conf` + `docs/db-grants.sql` 新建

### 阶段 5
- ✅ `service/impl/AuthServiceImpl.java` 3 bug 修复
- ✅ `service/impl/SecurityAuditServiceImpl.java` IP/UA 修复
- ✅ `logback-spring.xml` 新建
- ✅ `util/SensitiveDataConverter.java` 新建
- ✅ `config/MetricsConfig.java` 新建
- ✅ `docs/alertmanager-rules.yml` 新建

### 阶段 6
- ✅ `controller/ProcessController.java` 权限
- ✅ `config/FlowableConfig.java` 禁脚本
- ✅ `service/impl/WorkOrderServiceImpl.java` 候选人校验 + 变量净化
- ✅ `config/RedisMessageConfig.java` 新建
- ✅ `listener/ParallelApprovalEventListener.java` 候选人缓存

### 阶段 7
- ✅ `frontend/package.json` 加 dompurify
- ✅ `frontend/src/utils/sanitize.js` 新建
- ✅ `frontend/src/main.js` 注册 v-safe-html
- ✅ `frontend/index.html` CSP meta
- ✅ `frontend/src/router/index.js` 路由守卫 + `views/error/403.vue` 新建
- ✅ `frontend/src/utils/request.js` 401 拦截器改造
- ✅ `frontend/src/styles/tokens.scss` OKLCH tokens 扩展
- ✅ `frontend/src/views/Login.vue` 提升
- ✅ Absolute bans 违规修复

### 阶段 8
- ✅ `README.md` 修正
- ✅ `sql/init.sql` DEPRECATED 标记
- ✅ `backend/src/main/resources/sql/schema-init.sql` 同步
- ✅ `docs/nginx.conf` 新建
- ✅ `docs/https-automation.md` 新建
- ✅ `docs/deployment-checklist.md` 新建
- ✅ `.github/workflows/security-scan.yml` 新建
- ✅ `backend/src/main/resources/application-prod.yml` 强化

### 总体验证
- ✅ 每阶段 `mvn -DskipTests compile` 通过
- ✅ 每阶段 `npm run build` 通过（阶段 7 起）
- ✅ 最终 `mvn clean package -DskipTests` 通过

---

**计划文件位置**：`c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\.trae\documents\security-hardening-phases-3-8-execution.md`

**待用户确认后立即从阶段 3.1 开始执行。**
