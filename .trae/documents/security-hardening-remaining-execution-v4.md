# 工单审批流转系统 — 安全加固剩余工作执行计划 v4.0

> **文档版本**：v4.0（阶段 4.6-8 收尾执行）
> **执行日期**：2026-07-19
> **执行标准**：`.cursor/skills/impeccable/SKILL.md` + 用户安全策略 §2-§9
> **核心约束**：禁止简化、禁止替换方案、禁止省略、强制实行；每阶段完成后必须 `mvn -DskipTests compile`（后端）或 `npm run build`（前端）双双通过。
> **前序计划**：`security-hardening-phases-3-8-execution.md`（v3.0，阶段 3 全部完成 + 阶段 4 部分完成，已 BUILD SUCCESS 验证）

---

## 一、摘要（Summary）

本计划承接已完成的阶段 0/1/2/3 与阶段 4 的前 5 项（4.1-4.5），专注执行**阶段 4.6-8** 全部剩余工作：

- **阶段 4 剩余**（4.6-4.8）：PasswordPolicyServiceImpl 强化、AuthServiceImpl 接通密码历史、docs/redis-hardening.conf + docs/db-grants.sql
- **阶段 5**：日志与审计修复（AuthServiceImpl 3 bug、SecurityAuditServiceImpl 2 bug、logback-spring.xml、SensitiveDataConverter、MetricsConfig、alertmanager-rules.yml）
- **阶段 6**：Flowable 工作流安全（ProcessController 新建、FlowableConfig 禁脚本、WorkOrderServiceImpl 候选人校验+变量净化、RedisMessageConfig、ParallelApprovalEventListener 候选人缓存）
- **阶段 7**：前端加固与 impeccable（DOMPurify、sanitize.js、v-safe-html、CSP meta、路由权限校验+403 页面、request.js 401 改造、OKLCH tokens、Login.vue 提升）
- **阶段 8**：部署与文档（README、init.sql 废弃、schema-init.sql 同步、nginx.conf、https-automation、deployment-checklist、security-scan workflow、application-prod.yml 强化）

每阶段独立编译验证通过后进入下一阶段，确保最终 `mvn clean package -DskipTests` 与 `npm run build` 双双 BUILD SUCCESS。

---

## 二、当前状态分析（Current State Analysis）

### 2.1 已完成（阶段 0/1/2/3 + 阶段 4 前 5 项）

| 模块 | 状态 |
|------|------|
| 阶段 3 全部 12 项（SqlInjectionInterceptor、XssFilter、FileController、EnterpriseSecurityFilter Caffeine 限流、CorsConfig、AntiReplay） | ✅ BUILD SUCCESS |
| `util/AesEncryptionUtil.java` | ✅ AES-GCM 256，`base64(iv):base64(cipher+tag)` 格式 |
| `config/AesEncryptedStringTypeHandler.java` | ✅ 静态注入 + 兼容旧明文 |
| `db/migration/V2__add_password_history_indexes.sql` | ✅ |
| `db/migration/V3__add_audit_indexes.sql` | ✅ |
| `mapper/UserMapper.xml` email/phone typeHandler | ✅ BaseResultMap/insert/update/updateById 四处已配置 |

### 2.2 待完成项的真实状态核对（已通过 Read 实际验证）

| 文件 | 当前真实状态 | 需要改动 |
|------|------------|---------|
| `service/impl/PasswordPolicyServiceImpl.java` L75-86 | `DEFAULT_PASSWORD_POLICY`: minPasswordLength=8、requireDigit=false、requireSpecialChar=false、requireLowercase=false、requireUppercase=false — **弱策略** | 改强：min=10、所有 require=true |
| 同上 L213-229 `validatePasswordNotReused` | catch 块 `return new PasswordValidationResult(true, "跳过历史检查"); // Fail open for safety` — **fail-open** | 改 fail-closed：抛 BusinessException |
| 同上 L281-318 `recordFailedLoginAttempt` | `String attemptsKey = LOGIN_ATTEMPTS_PREFIX + username;` — **仅按 username 锁定** | 改 IP+username 组合 |
| `service/impl/AuthServiceImpl.java` L257-258 | `long expiration = System.currentTimeMillis() + jwtUtil.getExpirationDateFromToken(token).getTime();` — **错误拼接** | 改为 `long expiration = jwtUtil.getExpirationDateFromToken(token).getTime();` |
| 同上 L262-263 catch 块 | 同 bug 重复 | 修复 |
| 同上 L676-677 refreshToken | 同 bug 重复 | 修复 |
| 同上 L766 | `securityAuditService.logLoginFailure(user.getUsername(), getUserAgent(), getUserAgent(), "旧密码错误");` — **getUserAgent() 重复两次** | 第二个参数改 `getClientIp()` |
| 同上 L783-795 changePassword | 缺少 `passwordPolicyService.recordPasswordChange(userId, encodedNewPassword)` 调用 | 补充调用 |
| 同上 L794-795 | `logPasswordChange(userId, user.getUsername(), getUserAgent(), false)` — 第三个参数传 UA 但接口签名是 `ipAddress` | 改为 `getClientIp()` |
| `service/impl/SecurityAuditServiceImpl.java` L137-148 `logSensitiveOperation` | `audit(userId, username, "SENSITIVE_OPERATION", description, null, null, null, "WARNING", details);` — **IP/UA 全 null** | 从 RequestContextHolder 取 IP/UA |
| 同上 L151-158 `queryAuditLogs` | catch 块 `return new HashMap<>();` — **吞异常** | 移除 try-catch，向上抛 |
| `resources/logback-spring.xml` | **不存在**（探索 agent 误报存在） | 新建 |
| `util/SensitiveDataConverter.java` | 不存在 | 新建 |
| `config/MetricsConfig.java` | 不存在 | 新建 |
| `controller/ProcessController.java` | 不存在 | 新建 |
| `config/FlowableConfig.java` | 仅 24 行，只有 globalEventListener Bean，无禁脚本配置 | 改为实现 EngineConfigurationConfigurer + 禁脚本 |
| `config/RedisMessageConfig.java` | 不存在 | 新建 |
| `listener/ParallelApprovalEventListener.java` | 存在但无候选人缓存逻辑 | 加候选人 Redis 缓存 |
| `service/impl/WorkOrderServiceImpl.java` handleApproval | 缺少候选人校验和流程变量净化 | 补充双重防御 |
| `frontend/package.json` | 无 dompurify 依赖 | 加 `dompurify: ^3.0.6` |
| `frontend/src/main.js` | 22 行，未注册 v-safe-html 指令 | 注册全局指令 |
| `frontend/src/utils/request.js` L125-142 `handleUnauthorized` | 使用 `ElMessageBox.confirm` 弹窗 | 改 `ElNotification` + 直接跳转 |
| `frontend/src/router/index.js` | 158 行，有 beforeEach 守卫但**只校验 token，不校验 meta.permission**；无 403 路由 | 加权限校验 + 403 路由 |
| `frontend/src/views/error/403.vue` | 不存在 | 新建 |
| `frontend/index.html` | 无 CSP meta 标签 | 加 CSP meta |
| `frontend/src/styles/tokens.scss` | 90 行，使用 HEX 颜色，无 OKLCH 变量 | 扩展 OKLCH tokens |
| `application-prod.yml` L94 | `exposure: health,info,metrics,prometheus` — **格式错误**（缺 `.include`） | 改为 `exposure.include: health,prometheus` |
| 同上 | 缺 `aes.encryption-key` 配置 | 补充 |
| 同上 | 缺 `app.rate-limit` 配置 | 补充 |
| 同上 | 缺 `app.message.signing-key` 配置 | 补充 |
| `docs/redis-hardening.conf` | 不存在 | 新建 |
| `docs/db-grants.sql` | 不存在 | 新建 |
| `docs/alertmanager-rules.yml` | 不存在 | 新建 |
| `docs/nginx.conf` | 不存在 | 新建 |
| `docs/https-automation.md` | 不存在 | 新建 |
| `docs/deployment-checklist.md` | 不存在 | 新建 |
| `.github/workflows/security-scan.yml` | 不存在 | 新建 |

### 2.3 依赖确认

- **Caffeine**：已添加（阶段 3.10）✅
- **DOMPurify**：`frontend/package.json` 未声明 → 阶段 7.2 新增
- **Jsoup**：已在 pom.xml（1.17.2）✅
- **Spring Security**：已就绪 ✅
- **jjwt**：已就绪（0.11.5）✅

---

## 三、实施方案（Proposed Changes）

### 阶段 4 剩余：数据存储与加密收尾

#### 4.6 强化 `service/impl/PasswordPolicyServiceImpl.java`
**文件**：`backend/src/main/java/com/workorder/service/impl/PasswordPolicyServiceImpl.java`

**改动 1（L75-86 DEFAULT_PASSWORD_POLICY）**：
```java
private static final Map<String, Object> DEFAULT_PASSWORD_POLICY = new LinkedHashMap<String, Object>() {{
    put("minPasswordLength", 10);          // 最小密码长度（强化：8→10）
    put("requireDigit", true);             // 要求包含数字（强化）
    put("requireSpecialChar", true);       // 要求包含特殊字符（强化）
    put("passwordExpiryDays", 90);         // 密码有效期 90 天
    put("tokenExpiryHours", 8);            // Token 过期时间 8 小时
    put("maxLoginAttempts", 5);
    put("lockoutDuration", 30);
    put("requireLowercase", true);         // 要求小写字母（强化）
    put("requireUppercase", true);         // 要求大写字母（强化）
    put("maxPasswordHistory", 5);
}};
```

**改动 2（L225-228 validatePasswordNotReused catch 块）**：
```java
} catch (Exception e) {
    logger.error("检查密码历史失败（fail-closed 拒绝）: {}", e.getMessage(), e);
    // 安全策略：异常时 fail-closed 拒绝密码变更，防止历史检查被绕过
    return new PasswordValidationResult(false,
        "密码历史校验服务异常，为安全起见暂不允许修改密码，请稍后重试或联系管理员");
}
```

**改动 3（L281-318 recordFailedLoginAttempt）**：
- 方法签名增加 `String clientIp` 参数：`public LoginAttemptResult recordFailedLoginAttempt(String username, String clientIp)`
- Redis key 改为 IP+username 组合：
  ```java
  String ipUsernameKey = (clientIp != null ? clientIp : "unknown") + ":" + username;
  String attemptsKey = LOGIN_ATTEMPTS_PREFIX + ipUsernameKey;
  String lockKey = ACCOUNT_LOCKOUT_PREFIX + ipUsernameKey;
  ```
- 同时更新 `isAccountLocked(String username)` → `isAccountLocked(String username, String clientIp)`
- 同步更新 `resetLoginAttempts(String username)` → `resetLoginAttempts(String username, String clientIp)`

**改动 4（同步调用方）**：
- `AuthServiceImpl.java` 中调用 `recordFailedLoginAttempt`/`isAccountLocked`/`resetLoginAttempts` 的位置（login 方法），传入 `clientIp` 参数
- `PasswordPolicyService.java` 接口同步更新方法签名

#### 4.7 修复 `service/impl/AuthServiceImpl.java`（与阶段 5.1 合并执行）
**文件**：`backend/src/main/java/com/workorder/service/impl/AuthServiceImpl.java`

具体修复见阶段 5.1（统一在此处合并修改以避免重复编辑）。

#### 4.8 新建 `docs/redis-hardening.conf` + `docs/db-grants.sql`
**文件 1**：`docs/redis-hardening.conf`
```
# ============================================================
# Redis 加固配置 — 工单审批流转系统
# 部署：复制到 /etc/redis/redis.conf 并替换 ${REDIS_PASSWORD}
# ============================================================

# 网络隔离：仅绑定内网回环
bind 127.0.0.1 ::1
protected-mode yes
port 6379

# 强密码认证（必须通过环境变量注入，不硬编码）
requirepass ${REDIS_PASSWORD}

# 禁用危险命令
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""
rename-command KEYS ""
rename-command DEBUG ""
rename-command SHUTDOWN ""

# 内存与淘汰策略
maxmemory 512mb
maxmemory-policy allkeys-lru

# 连接超时与保活
timeout 300
tcp-keepalive 60
tcp-backlog 511

# 持久化：AOF 优先
appendonly yes
appendfilename "appendonly.aof"
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb

# RDB 备份（双保险）
save 900 1
save 300 10
save 60 10000

# 日志
loglevel notice
logfile /var/log/redis/redis-server.log

# 客户端连接数限制
maxclients 10000
```

**文件 2**：`docs/db-grants.sql`
```sql
-- ============================================================
-- PostgreSQL 应用账户最小权限配置 — 工单审批流转系统
-- 部署：以超级管理员执行，将 wos_app 替换为实际应用账户名
-- ============================================================

-- 1. 创建应用专用账户（如未创建）
-- CREATE USER wos_app WITH PASSWORD '${DB_PASSWORD}';

-- 2. 收回 PUBLIC 模式权限
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- 3. 授予应用账户 schema 使用权
GRANT USAGE ON SCHEMA public TO wos_app;

-- 4. 授予现有表的 CRUD 权限（仅 DML，禁 DDL）
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wos_app;

-- 5. 授予现有序列的使用权
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO wos_app;

-- 6. 设置默认权限（未来 Flyway 创建的表自动授予应用账户）
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wos_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO wos_app;

-- 7. 明确禁止 DDL（收回 CREATE 权限）
REVOKE CREATE ON SCHEMA public FROM wos_app;

-- 8. 验证
-- \dp public.*
-- 应显示 wos_app 仅有 arwdDxt（insert/select/update/delete/truncate/references/trigger）中部分权限
```

**阶段 4 验证**：
```powershell
cd 'c:\Users\Administrator\Desktop\Project\updata\Work Order Approval System\backend'
mvn -DskipTests compile
# 期望：BUILD SUCCESS
```

---

### 阶段 5：日志与审计修复（Audit Logging Fixes）

**目标**：实现 §6 — 全量审计、实时告警、日志保护。

#### 5.1 修 `service/impl/AuthServiceImpl.java` 3 bug + 接通密码历史
**文件**：`backend/src/main/java/com/workorder/service/impl/AuthServiceImpl.java`

**改动 1（L255-264 login 方法 expiration 计算）**：
```java
// Token expiration timestamp（修复：getExpirationDateFromToken().getTime() 已是绝对时间戳，无需加 System.currentTimeMillis()）
try {
    long expiration = jwtUtil.getExpirationDateFromToken(token).getTime();
    result.put("expiration", expiration);
} catch (Exception e) {
    logger.warn("⚠️ 计算过期时间失败，使用当前时间 + 8h: {}", e.getMessage());
    result.put("expiration", System.currentTimeMillis() + (tokenExpiryHours * 60 * 60 * 1000L));
}
```

**改动 2（L674-682 refreshToken 方法 expiration 计算）**：
```java
try {
    long expiration = jwtUtil.getExpirationDateFromToken(newToken).getTime();
    result.put("expiration", expiration);
} catch (Exception e) {
    result.put("expiration", System.currentTimeMillis() + (tokenExpiryHours * 60 * 60 * 1000L));
}
```

**改动 3（L766 changePassword 中 logLoginFailure 调用）**：
```java
// 修复：第二个参数应为 IP，原代码错误地传了两次 getUserAgent()
securityAuditService.logLoginFailure(
    user.getUsername(), getClientIp(), getUserAgent(), "旧密码错误");
```

**改动 4（L794-795 changePassword 中 logPasswordChange 调用）**：
```java
// 修复：第三个参数应为 IP（接口签名是 ipAddress），原代码传了 getUserAgent()
securityAuditService.logPasswordChange(userId, user.getUsername(),
    getClientIp(), false);
```

**改动 5（L789-797 changePassword 末尾接通密码历史）**：
```java
int result = userMapper.updateById(user);
if (result > 0) {
    logger.info("✅ 用户 {} 密码修改成功", userId);

    // 接通密码历史：记录新密码 hash 到 sys_password_history（保留最近 5 条）
    try {
        passwordPolicyService.recordPasswordChange(userId, encodedNewPassword);
        logger.info("📝 用户 {} 密码历史已记录", userId);
    } catch (Exception histEx) {
        logger.error("❌ 记录密码历史失败（不影响密码修改结果）: {}", histEx.getMessage(), histEx);
    }

    // 记录审计日志
    securityAuditService.logPasswordChange(userId, user.getUsername(),
        getClientIp(), false);

    return Result.success(null, "密码修改成功！请重新登录");
}
```

**改动 6（login 方法中调用 recordFailedLoginAttempt/isAccountLocked/resetLoginAttempts 处）**：
- 在调用前先获取 `String clientIp = getClientIp();` 一次
- `passwordPolicyService.isAccountLocked(username, clientIp)`
- `passwordPolicyService.recordFailedLoginAttempt(username, clientIp)`
- 登录成功后 `passwordPolicyService.resetLoginAttempts(username, clientIp)`

#### 5.2 修 `service/impl/SecurityAuditServiceImpl.java`
**文件**：`backend/src/main/java/com/workorder/service/impl/SecurityAuditServiceImpl.java`

**改动 1（L137-148 logSensitiveOperation）**：从 RequestContextHolder 取 IP/UA
```java
@Override
public void logSensitiveOperation(Long userId, String username, String operationType, String description, Map<String, Object> details) {
    if (details == null) {
        details = new HashMap<>();
    }
    details.put("operationType", operationType);
    details.put("timestamp", LocalDateTime.now().toString());

    // 从当前 HTTP 请求上下文获取 IP 与 UA
    HttpServletRequest request = getCurrentRequest();
    String ipAddress = (request != null) ? getClientIpFromRequest(request) : null;
    String userAgent = (request != null) ? request.getHeader("User-Agent") : null;
    String requestUrl = (request != null) ? request.getRequestURI() : null;

    audit(userId, username, "SENSITIVE_OPERATION", description,
          ipAddress, userAgent, requestUrl, "WARNING", details);

    logger.warn("[SECURITY] Sensitive operation by {}: {} - {} (IP: {})",
        username, operationType, description, ipAddress);
}

/**
 * 从 Spring RequestContextHolder 获取当前 HTTP 请求（可能在异步线程中为 null）
 */
private HttpServletRequest getCurrentRequest() {
    try {
        return ((HttpServletRequest) org.springframework.web.context.request.RequestContextHolder
            .currentRequestAttributes())
            .resolveReference(org.springframework.web.context.request.RequestAttributes.REFERENCE_REQUEST);
    } catch (Exception e) {
        return null;
    }
}

/**
 * 从请求中解析真实客户端 IP（支持反向代理）
 */
private String getClientIpFromRequest(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
        // X-Forwarded-For 可能含多级代理，取第一个
        ip = ip.split(",")[0].trim();
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getHeader("X-Real-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getRemoteAddr();
    }
    return ip;
}
```

**改动 2（L150-158 queryAuditLogs）**：移除吞异常
```java
@Override
public Map<String, Object> queryAuditLogs(Map<String, Object> params) {
    // 移除 try-catch：异常向上抛由 GlobalExceptionHandler 统一处理，避免静默失败
    return auditLogMapper.queryWithPagination(params);
}
```

#### 5.3 新建 `src/main/resources/logback-spring.xml`
**文件**：`backend/src/main/resources/logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 引用 SensitiveDataConverter 进行日志脱敏 -->
    <conversionRule conversionWord="msg" converterClass="com.workorder.util.SensitiveDataConverter"/>

    <springProperty scope="context" name="LOG_PATH" source="logging.file.name"
                    defaultValue="/var/log/work-order-system/application.log"/>

    <!-- 控制台输出（dev） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 文件输出（按日滚动，单文件 100MB，保留 30 天） -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}.%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>3GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 审计日志单独文件 -->
    <appender name="SECURITY_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}.security</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}.security.%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
            <maxFileSize>50MB</maxFileSize>
            <maxHistory>90</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 异步包装文件输出（性能优化） -->
    <appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>1024</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
        <appender-ref ref="FILE"/>
    </appender>

    <!-- 审计 logger 单独路由 -->
    <logger name="SECURITY_AUDIT" level="INFO" additivity="false">
        <appender-ref ref="SECURITY_FILE"/>
        <appender-ref ref="CONSOLE"/>
    </logger>

    <!-- 应用包级别 -->
    <logger name="com.workorder" level="INFO"/>
    <logger name="com.workorder.service.impl.SecurityAuditServiceImpl" level="INFO"/>

    <!-- 框架噪音降级 -->
    <logger name="org.springframework" level="WARN"/>
    <logger name="org.flowable" level="ERROR"/>
    <logger name="org.mybatis" level="WARN"/>

    <!-- dev profile：控制台 + 文件 -->
    <springProfile name="dev">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
            <appender-ref ref="ASYNC_FILE"/>
        </root>
    </springProfile>

    <!-- prod profile：仅文件，级别更高 -->
    <springProfile name="prod">
        <root level="WARN">
            <appender-ref ref="ASYNC_FILE"/>
        </root>
    </springProfile>
</configuration>
```

#### 5.4 新建 `util/SensitiveDataConverter.java`
**文件**：`backend/src/main/java/com/workorder/util/SensitiveDataConverter.java`

```java
package com.workorder.util;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * 日志脱敏转换器
 * 自动屏蔽日志中的敏感信息：password、token、Authorization、secret、newPassword
 *
 * 用法：logback-spring.xml 中
 *   <conversionRule conversionWord="msg"
 *       converterClass="com.workorder.util.SensitiveDataConverter"/>
 *
 * @author KLord
 */
public class SensitiveDataConverter extends ClassicConverter {

    private static final Pattern[] SENSITIVE_PATTERNS = {
        // password=xxx, password: xxx, Password : xxx
        Pattern.compile("(?i)(password\\s*[=:]\\s*)\\S+"),
        // token=xxx, token: xxx
        Pattern.compile("(?i)(token\\s*[=:]\\s*)\\S+"),
        // Authorization: Bearer xxx
        Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)\\S+"),
        // secret=xxx, secret: xxx
        Pattern.compile("(?i)(secret\\s*[=:]\\s*)\\S+"),
        // newPassword=xxx
        Pattern.compile("(?i)(newPassword\\s*[=:]\\s*)\\S+"),
        // oldPassword=xxx
        Pattern.compile("(?i)(oldPassword\\s*[=:]\\s*)\\S+")
    };

    private static final String REPLACEMENT = "$1***";

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isEmpty()) {
            return msg;
        }
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            msg = pattern.matcher(msg).replaceAll(REPLACEMENT);
        }
        return msg;
    }
}
```

#### 5.5 新建 `config/MetricsConfig.java`
**文件**：`backend/src/main/java/com/workorder/config/MetricsConfig.java`

```java
package com.workorder.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus 指标配置 — 安全告警埋点
 * 配合 docs/alertmanager-rules.yml 实现实时告警
 *
 * @author KLord
 */
@Configuration
public class MetricsConfig {

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 登录尝试计数器（标签：result=success/failure）
     * 告警：rate(wos_login_attempts_total{result="failure"}[5m]) > 4
     */
    @Bean
    public Counter loginAttemptsCounter() {
        return Counter.builder("wos_login_attempts_total")
            .description("Total login attempts")
            .tag("result", "unknown")
            .register(meterRegistry);
    }

    /**
     * 授权拒绝计数器（标签：reason=no_token/invalid_token/no_permission/data_denied）
     * 告警：rate(wos_authorization_denials_total[5m]) > 2
     */
    @Bean
    public Counter authorizationDenialsCounter() {
        return Counter.builder("wos_authorization_denials_total")
            .description("Authorization denials")
            .tag("reason", "unknown")
            .register(meterRegistry);
    }

    /**
     * 敏感操作计数器（标签：type=login/password_reset/delete/data_export）
     * 告警：rate(wos_sensitive_operations_total{type="password_reset"}[1m]) > 5/60
     */
    @Bean
    public Counter sensitiveOperationsCounter() {
        return Counter.builder("wos_sensitive_operations_total")
            .description("Sensitive operations")
            .tag("type", "unknown")
            .register(meterRegistry);
    }

    /**
     * 增加登录尝试指标
     * @param success true=成功，false=失败
     */
    public void recordLoginAttempt(boolean success) {
        Counter.builder("wos_login_attempts_total")
            .tag("result", success ? "success" : "failure")
            .register(meterRegistry)
            .increment();
    }

    /**
     * 增加授权拒绝指标
     * @param reason no_token/invalid_token/no_permission/data_denied
     */
    public void recordAuthorizationDenial(String reason) {
        Counter.builder("wos_authorization_denials_total")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    /**
     * 增加敏感操作指标
     * @param type login/password_reset/delete/data_export
     */
    public void recordSensitiveOperation(String type) {
        Counter.builder("wos_sensitive_operations_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }
}
```

**埋点位置**（最小侵入）：
- `AuthServiceImpl.login`：成功/失败时调用 `metricsConfig.recordLoginAttempt(boolean)`
- `JwtAuthenticationFilter`（401 时）：调用 `metricsConfig.recordAuthorizationDenial("no_token"或"invalid_token")`
- `DataPermissionAspect`（拒绝时）：调用 `metricsConfig.recordAuthorizationDenial("data_denied")`
- `SecurityAuditServiceImpl.logSensitiveOperation`：调用 `metricsConfig.recordSensitiveOperation(operationType)`

#### 5.6 新建 `docs/alertmanager-rules.yml`
**文件**：`docs/alertmanager-rules.yml`

```yaml
# ============================================================
# Prometheus AlertManager 告警规则 — 工单审批流转系统
# 部署：复制到 Prometheus rules 目录，重启 Prometheus 生效
# ============================================================
groups:
  - name: wos-security
    rules:
      # 5 分钟内登录失败超过 20 次（暴力破解）
      - alert: HighLoginFailures
        expr: rate(wos_login_attempts_total{result="failure"}[5m]) > 4
        for: 1m
        labels:
          severity: critical
          category: security
        annotations:
          summary: "5分钟内登录失败超过20次"
          description: "实例 {{ $labels.instance }} 检测到暴力破解尝试，5分钟平均速率 {{ $value }} 次/秒"

      # 5 分钟内 403 超过 10 次（越权尝试）
      - alert: HighAuthorizationDenials
        expr: rate(wos_authorization_denials_total[5m]) > 2
        for: 1m
        labels:
          severity: warning
          category: security
        annotations:
          summary: "5分钟内授权拒绝超过10次"
          description: "实例 {{ $labels.instance }} 检测到越权尝试，5分钟平均速率 {{ $value }} 次/秒"

      # 1 分钟内密码重置超过 5 次（密码重置滥用）
      - alert: PasswordResetAbuse
        expr: rate(wos_sensitive_operations_total{type="password_reset"}[1m]) > (5/60)
        for: 1m
        labels:
          severity: critical
          category: security
        annotations:
          summary: "1分钟内密码重置超过5次"
          description: "实例 {{ $labels.instance }} 检测到密码重置滥用，1分钟平均速率 {{ $value }} 次/秒"

      # 1 分钟内数据导出超过 10 次（数据泄露风险）
      - alert: DataExportAnomaly
        expr: rate(wos_sensitive_operations_total{type="data_export"}[1m]) > (10/60)
        for: 2m
        labels:
          severity: warning
          category: security
        annotations:
          summary: "1分钟内数据导出超过10次"
          description: "实例 {{ $labels.instance }} 检测到数据导出异常，1分钟平均速率 {{ $value }} 次/秒"

      # 应用宕机
      - alert: WosAppDown
        expr: up{job="work-order-system"} == 0
        for: 2m
        labels:
          severity: critical
          category: availability
        annotations:
          summary: "工单审批流转系统宕机"
          description: "实例 {{ $labels.instance }} 已离线超过 2 分钟"

      # JVM 内存告警
      - alert: HighJvmMemoryUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels:
          severity: warning
          category: resource
        annotations:
          summary: "JVM 堆内存使用率超过 85%"
          description: "实例 {{ $labels.instance }} 堆内存使用率 {{ $value | humanizePercentage }}"
```

**阶段 5 验证**：
```powershell
cd backend; mvn -DskipTests compile
# 期望：BUILD SUCCESS
# 启动后访问 /actuator/prometheus 应含 wos_login_attempts_total 指标
```

---

### 阶段 6：Flowable 工作流安全（Flowable Workflow Security）

**目标**：实现 §9 — 流程定义权限、流程变量安全、防任务哄抢。

#### 6.1 新建 `controller/ProcessController.java`
**文件**：`backend/src/main/java/com/workorder/controller/ProcessController.java`

```java
package com.workorder.controller;

import com.workorder.common.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流程定义管理控制器
 * 仅超级管理员可部署/删除/挂起流程定义（防止注入恶意 groovy 脚本）
 *
 * @author KLord
 */
@RestController
@RequestMapping("/process")
@Api(tags = "流程定义管理")
public class ProcessController {

    @Autowired
    private RepositoryService repositoryService;

    /**
     * 查询流程定义列表（所有已认证用户可访问）
     */
    @GetMapping("/definitions")
    @PreAuthorize("isAuthenticated()")
    @ApiOperation("查询流程定义列表")
    public Result<?> listDefinitions() {
        List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
            .latestVersion()
            .active()
            .orderByProcessDefinitionName().asc()
            .list();

        List<Map<String, Object>> list = defs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("key", d.getKey());
            m.put("version", d.getVersion());
            m.put("deploymentId", d.getDeploymentId());
            return m;
        }).collect(Collectors.toList());

        return Result.success(list);
    }

    /**
     * 部署新流程定义（仅超级管理员）
     * 安全：BPMN 中若含 scriptTask 将在 FlowableConfig 解析阶段被拒绝
     */
    @PostMapping("/deploy")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ApiOperation("部署新流程定义")
    public Result<?> deployProcess(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "name", required = false) String name) {
        if (file == null || file.isEmpty()) {
            return Result.error("文件不能为空");
        }
        try {
            String deploymentName = (name != null && !name.isEmpty()) ? name : file.getOriginalFilename();
            repositoryService.createDeployment()
                .name(deploymentName)
                .addInputStream(file.getOriginalFilename(), file.getInputStream())
                .deploy();
            return Result.success(null, "流程部署成功");
        } catch (Exception e) {
            return Result.error("流程部署失败: " + e.getMessage());
        }
    }

    /**
     * 删除流程定义（仅超级管理员）
     */
    @DeleteMapping("/definitions/{deploymentId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ApiOperation("删除流程定义")
    public Result<?> deleteDeployment(@PathVariable String deploymentId,
                                      @RequestParam(defaultValue = "false") boolean cascade) {
        try {
            repositoryService.deleteDeployment(deploymentId, cascade);
            return Result.success(null, "流程删除成功");
        } catch (Exception e) {
            return Result.error("流程删除失败: " + e.getMessage());
        }
    }

    /**
     * 挂起流程定义（仅超级管理员）
     */
    @PutMapping("/definitions/{processDefinitionId}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ApiOperation("挂起流程定义")
    public Result<?> suspendProcessDefinition(@PathVariable String processDefinitionId) {
        try {
            repositoryService.suspendProcessDefinitionById(processDefinitionId, true, null);
            return Result.success(null, "流程已挂起");
        } catch (Exception e) {
            return Result.error("挂起失败: " + e.getMessage());
        }
    }

    /**
     * 激活流程定义（仅超级管理员）
     */
    @PutMapping("/definitions/{processDefinitionId}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ApiOperation("激活流程定义")
    public Result<?> activateProcessDefinition(@PathVariable String processDefinitionId) {
        try {
            repositoryService.activateProcessDefinitionById(processDefinitionId, true, null);
            return Result.success(null, "流程已激活");
        } catch (Exception e) {
            return Result.error("激活失败: " + e.getMessage());
        }
    }
}
```

#### 6.2 修改 `config/FlowableConfig.java` 禁脚本任务
**文件**：`backend/src/main/java/com/workorder/config/FlowableConfig.java`

```java
package com.workorder.config;

import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.calendar.CycleBusinessCalendar;
import org.flowable.engine.cfg.ProcessEngineConfigurator;
import org.flowable.engine.impl.cfg.SpringProcessEngineConfiguration;
import org.flowable.engine.parse.BpmnParseHandler;
import org.flowable.bpmn.model.ScriptTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.ScriptTaskParseHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flowable 工作流引擎配置类
 * 安全加固：
 * 1. 启用 safe scripting（限制脚本任务权限）
 * 2. 在部署阶段拒绝含 scriptTask 的 BPMN（防止注入 groovy 脚本）
 * 3. 注册并行审批事件监听器
 *
 * @author KLord
 */
@Configuration
public class FlowableConfig implements org.flowable.engine.spring.configurator.EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

    @Override
    public void configure(SpringProcessEngineConfiguration config) {
        // 1. 启用安全脚本（限制脚本任务可调用的 API）
        config.setEnableSafeScripting(true);

        // 2. 替换 ScriptTaskParseHandler：在解析阶段拦截所有 scriptTask
        List<BpmnParseHandler> customHandlers = new ArrayList<>();
        customHandlers.add(new org.flowable.engine.impl.bpmn.parser.handler.ScriptTaskParseHandler() {
            @Override
            protected void executeParse(BpmnParse bpmnParse, ScriptTask scriptTask) {
                // 拒绝任何脚本任务（生产环境严禁执行 BPMN 内嵌脚本）
                throw new SecurityException(
                    "禁止部署包含 ScriptTask 的流程定义（任务 ID: " + scriptTask.getId() +
                    ", 名称: " + scriptTask.getName() + "）。请使用 ServiceTask 或 Listener 替代。");
            }
        });

        List<BpmnParseHandler> defaultHandlers = config.getPreBpmnParseHandlers();
        if (defaultHandlers == null) {
            defaultHandlers = new ArrayList<>();
        }
        defaultHandlers.addAll(customHandlers);
        config.setPreBpmnParseHandlers(defaultHandlers);
    }

    /**
     * 注册全局事件监听器
     * 用于解决并行审批的状态同步问题
     */
    @Bean
    public FlowableEventListener globalEventListener() {
        return new com.workorder.listener.ParallelApprovalEventListener();
    }
}
```

#### 6.3 修改 `service/impl/WorkOrderServiceImpl.java` 候选人校验 + 变量净化
**文件**：`backend/src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`

**改动 1（提交工单前清理流程变量）**：在调用 `runtimeService.startProcessInstanceByKey` 之前，过滤变量：
```java
/**
 * 净化流程变量：移除含敏感关键字的 key
 */
private Map<String, Object> sanitizeProcessVariables(Map<String, Object> variables) {
    if (variables == null) return new HashMap<>();
    Map<String, Object> safe = new HashMap<>();
    for (Map.Entry<String, Object> entry : variables.entrySet()) {
        String key = entry.getKey();
        if (key == null) continue;
        String lowerKey = key.toLowerCase();
        // 拒绝任何含敏感关键字的流程变量
        if (lowerKey.contains("password") || lowerKey.contains("secret")
            || lowerKey.contains("token") || lowerKey.contains("credential")
            || lowerKey.contains("privatekey")) {
            logger.warn("🚫 流程变量 [{}] 含敏感关键字，已剔除", key);
            continue;
        }
        safe.put(key, entry.getValue());
    }
    return safe;
}
```

**改动 2（handleApproval 候选人双重校验）**：在 `taskService.claim(taskId, userId)` 之前：
```java
/**
 * 校验当前用户是否为任务的合法候选人（防任务哄抢）
 * 双重防御：Flowable API + Redis 缓存
 */
private void validateTaskCandidate(String taskId, Long userId, List<String> roleCodes) {
    // 1. Flowable API 校验
    Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
    if (task == null) {
        throw new BusinessException("任务不存在或已完成");
    }

    boolean isCandidate = false;

    // 已分配给当前用户
    if (task.getAssignee() != null && task.getAssignee().equals(String.valueOf(userId))) {
        isCandidate = true;
    } else {
        // 校验候选用户/候选组
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        for (IdentityLink link : links) {
            if (!"candidate".equals(link.getType())) continue;

            // 候选用户匹配
            if (link.getUserId() != null && link.getUserId().equals(String.valueOf(userId))) {
                isCandidate = true;
                break;
            }
            // 候选组（角色）匹配
            if (link.getGroupId() != null && roleCodes != null
                && roleCodes.contains(link.getGroupId())) {
                isCandidate = true;
                break;
            }
        }
    }

    if (!isCandidate) {
        // 2. Redis 缓存二次校验（纵深防御）
        String cacheKey = "task:candidates:" + taskId;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof java.util.Set) {
                @SuppressWarnings("unchecked")
                java.util.Set<String> candidateIds = (java.util.Set<String>) cached;
                if (candidateIds.contains(String.valueOf(userId))) {
                    logger.warn("⚠️ Flowable API 校验未通过但 Redis 缓存命中（数据不一致），用户 {} 任务 {}",
                        userId, taskId);
                    isCandidate = true;
                }
            }
        } catch (Exception e) {
            logger.warn("Redis 候选人缓存校验失败（仅依赖 Flowable API）: {}", e.getMessage());
        }
    }

    if (!isCandidate) {
        logger.error("🚫 用户 {} 不是任务 {} 的合法候选人（越权审批尝试）", userId, taskId);
        throw new BusinessException("您不是该任务的合法候选人，无法审批");
    }
}
```

**调用位置**：在 `handleApproval` 方法获取 `taskId` 后立即调用 `validateTaskCandidate`。

#### 6.4 新建 `config/RedisMessageConfig.java`
**文件**：`backend/src/main/java/com/workorder/config/RedisMessageConfig.java`

```java
package com.workorder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Redis Pub/Sub 消息配置 — 审批状态同步安全
 * 安全措施：
 * 1. 频道隔离：按部门 ID 隔离消息（wos:approval:{departmentId}）
 * 2. 消息签名：HMAC-SHA256 防止伪造
 *
 * @author KLord
 */
@Configuration
public class RedisMessageConfig {

    @Value("${app.message.signing-key:${MESSAGE_SIGNING_KEY:}}")
    private String signingKey;

    @Value("${app.message.channel-prefix:wos:approval}")
    private String channelPrefix;

    /**
     * HMAC-SHA256 签名
     */
    public String sign(String payload) {
        if (signingKey == null || signingKey.isEmpty()) {
            throw new IllegalStateException("消息签名密钥未配置（app.message.signing-key 或 MESSAGE_SIGNING_KEY）");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("消息签名失败", e);
        }
    }

    /**
     * 校验签名
     */
    public boolean verify(String payload, String signature) {
        if (signature == null || signature.isEmpty()) return false;
        try {
            String expected = sign(payload);
            return expected.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构造部门频道路
     */
    public String getChannelForDepartment(Long departmentId) {
        return channelPrefix + ":" + departmentId;
    }

    /**
     * 消息监听容器（订阅所有部门频道由各 listener 自行注册）
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
```

#### 6.5 修改 `listener/ParallelApprovalEventListener.java` 候选人缓存
**文件**：`backend/src/main/java/com/workorder/listener/ParallelApprovalEventListener.java`

在 `TaskCreatedListener` 中将候选人写入 Redis：
```java
// 任务创建时缓存候选人列表（TTL 24 小时）
@Autowired
private RedisTemplate<String, Object> redisTemplate;

private static final long CANDIDATE_CACHE_TTL_HOURS = 24;

// 在 taskCreated 事件处理中：
private void cacheTaskCandidates(Task task) {
    try {
        String cacheKey = "task:candidates:" + task.getId();
        java.util.Set<String> candidateIds = new java.util.HashSet<>();

        // 已分配人
        if (task.getAssignee() != null) {
            candidateIds.add(task.getAssignee());
        }

        // 候选用户/组
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        for (IdentityLink link : links) {
            if ("candidate".equals(link.getType()) && link.getUserId() != null) {
                candidateIds.add(link.getUserId());
            }
            // 候选组解析为具体用户 ID（可选：注入 UserMapper 查询角色对应用户）
        }

        redisTemplate.opsForValue().set(cacheKey, candidateIds,
            CANDIDATE_CACHE_TTL_HOURS, java.util.concurrent.TimeUnit.HOURS);
        logger.debug("已缓存任务 {} 的 {} 个候选人", task.getId(), candidateIds.size());
    } catch (Exception e) {
        logger.warn("缓存任务候选人失败（不影响流程）: {}", e.getMessage());
    }
}

// 在 taskCompleted 事件处理中：
private void evictTaskCandidates(String taskId) {
    try {
        redisTemplate.delete("task:candidates:" + taskId);
    } catch (Exception e) {
        logger.debug("清理候选人缓存失败: {}", e.getMessage());
    }
}
```

**阶段 6 验证**：
```powershell
cd backend; mvn -DskipTests compile
# 期望：BUILD SUCCESS
# 普通用户部署 BPMN → 403
# 非候选人审批 → BusinessException "您不是该任务的合法候选人"
```

---

### 阶段 7：前端加固与 impeccable 标准（Frontend Hardening & impeccable）

**目标**：实现 §3 前端部分 + 路由权限 + 按 impeccable 审计修复严重违规。

#### 7.1 修改 `frontend/package.json` 加 DOMPurify
**文件**：`frontend/package.json`

dependencies 中新增：
```json
"dompurify": "^3.0.6"
```

#### 7.2 新建 `frontend/src/utils/sanitize.js`
**文件**：`frontend/src/utils/sanitize.js`

```javascript
import DOMPurify from 'dompurify'

// 默认配置：允许基础格式化标签
const DEFAULT_CONFIG = {
  ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'br', 'p', 'ul', 'ol', 'li', 'span'],
  ALLOWED_ATTR: ['class']
}

/**
 * 净化 HTML 字符串（默认白名单）
 * @param {string} dirty 待净化的 HTML
 * @param {object} config DOMPurify 配置（可选）
 * @returns {string} 净化后的安全 HTML
 */
export function sanitize(dirty, config = DEFAULT_CONFIG) {
  if (!dirty) return ''
  return DOMPurify.sanitize(dirty, config)
}

/**
 * 严格净化：移除所有 HTML 标签（纯文本）
 * @param {string} dirty 待净化的字符串
 * @returns {string} 纯文本
 */
export function sanitizeStrict(dirty) {
  if (!dirty) return ''
  return DOMPurify.sanitize(dirty, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] })
}

/**
 * 净化工单内容（允许段落、列表、强调）
 */
export function sanitizeWorkOrderContent(dirty) {
  if (!dirty) return ''
  return DOMPurify.sanitize(dirty, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'ul', 'ol', 'li', 'span'],
    ALLOWED_ATTR: ['class']
  })
}
```

#### 7.3 修改 `frontend/src/main.js` 注册 v-safe-html 指令
**文件**：`frontend/src/main.js`

```javascript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/index.scss'
import { sanitize } from './utils/sanitize'

const app = createApp(App)

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 全局安全 HTML 指令：替代 v-html，所有动态 HTML 必须通过此指令
app.directive('safe-html', {
  beforeMount(el, binding) {
    el.innerHTML = sanitize(binding.value)
  },
  updated(el, binding) {
    if (binding.oldValue !== binding.value) {
      el.innerHTML = sanitize(binding.value)
    }
  }
})

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
```

#### 7.4 修改 `frontend/index.html` 加 CSP meta
**文件**：`frontend/index.html`

在 `<head>` 中加：
```html
<meta http-equiv="Content-Security-Policy"
      content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
               img-src 'self' data: https:; connect-src 'self' http://localhost:8080 https:; 
               font-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'">
```

#### 7.5 修改 `frontend/src/router/index.js` 加权限校验 + 403 路由
**文件**：`frontend/src/router/index.js`

**改动 1**：在 routes 数组开头加 403 路由：
```javascript
{
  path: '/403',
  name: 'Forbidden',
  component: () => import('@/views/error/403.vue'),
  meta: { title: '无权访问', requiresAuth: false }
},
```

**改动 2**：在 `router.beforeEach` 中已登录分支内追加权限校验：
```javascript
if (userStore.isUserInfoLoaded) {
  // 权限校验：检查 meta.permission
  if (to.meta.permission) {
    const userPermissions = userStore.permissions || []
    const userRoles = (userStore.userInfo?.roles || []).map(r =>
      r.roleCode || r.code || r
    )

    // 超级管理员拥有所有权限
    const isSuperAdmin = userRoles.includes('ROLE_SUPER_ADMIN') ||
                        userRoles.includes('SUPER_ADMIN')

    if (!isSuperAdmin && !userPermissions.includes(to.meta.permission)) {
      // 无权访问 → 跳转 403
      next({ path: '/403' })
      return
    }
  }
  next()
} else {
  // ...原有 fetchUserInfo 逻辑
}
```

#### 7.6 新建 `frontend/src/views/error/403.vue`
**文件**：`frontend/src/views/error/403.vue`

量子主题 403 页面（遵循用户偏好：科幻黑洞引力漩涡粒子数据碎片主题；不喜欢发光光晕；统一全局字体）：
- 中央黑洞引力漩涡（CSS 动画，无发光光晕）
- 数据碎片粒子环绕
- "无权访问"标题 + 返回首页按钮
- `@media (prefers-reduced-motion: reduce)` 支持

#### 7.7 修改 `frontend/src/utils/request.js` 401 改造
**文件**：`frontend/src/utils/request.js`

将 `handleUnauthorized` 函数改为：
```javascript
import { ElNotification } from 'element-plus'

// 处理未授权（Token失效）— 非阻塞式提示 + 直接跳转
function handleUnauthorized() {
  const userStore = useUserStore()

  // 使用 ElNotification 替代 ElMessageBox.confirm（消除内容实际删除之间的空白间隔）
  ElNotification({
    title: '会话过期',
    message: '登录状态已过期，请重新登录',
    type: 'warning',
    duration: 2000,
    position: 'top-right'
  })

  // 直接重置并跳转（用户偏好：无缝衔接，不阻塞）
  userStore.resetState()
  router.push('/login')
}
```

同时从 import 中移除 `ElMessageBox`（保留 `ElMessage`）。

#### 7.8 修改 `frontend/src/styles/tokens.scss` 扩展 OKLCH tokens
**文件**：`frontend/src/styles/tokens.scss`

在 `:root` 中追加 OKLCH 量子主题变量：
```scss
/* ============================================================
   OKLCH 量子主题 tokens — 工单审批流转系统
   主题：科幻黑洞引力漩涡粒子数据碎片
   配色：深空底色 + 量子蓝紫强调（无发光光晕）
   ============================================================ */
:root {
  /* 保留原有 HEX 变量向后兼容 */
  /* ... 原有变量不动 ... */

  /* OKLCH 量子主题（新增） */
  --oklch-bg: oklch(0.13 0.02 270);              /* 深空黑紫底 */
  --oklch-surface: oklch(0.18 0.03 270);         /* 卡面深紫 */
  --oklch-surface-elevated: oklch(0.22 0.04 270);/* 提升面 */
  --oklch-ink: oklch(0.95 0.01 270);             /* 主文字近白 */
  --oklch-ink-body: oklch(0.88 0.02 270);        /* 正文 */
  --oklch-ink-muted: oklch(0.72 0.02 270);       /* 次要文字（≥4.5:1） */
  --oklch-accent: oklch(0.65 0.18 265);          /* 量子蓝紫 */
  --oklch-accent-hover: oklch(0.70 0.20 265);
  --oklch-accent-active: oklch(0.60 0.16 265);
  --oklch-border: oklch(0.28 0.03 270);          /* 边框 */
  --oklch-border-subtle: oklch(0.22 0.02 270);
  --oklch-success: oklch(0.70 0.15 145);
  --oklch-danger: oklch(0.65 0.22 25);
  --oklch-warning: oklch(0.75 0.15 85);

  /* 量子主题专用变量 */
  --quantum-void: oklch(0.08 0.02 270);          /* 黑洞中心 */
  --quantum-edge: oklch(0.30 0.05 270);          /* 黑洞边缘 */
  --quantum-shard: oklch(0.55 0.15 265);         /* 数据碎片 */
  --quantum-shard-alt: oklch(0.60 0.10 200);     /* 数据碎片蓝绿 */
}
```

#### 7.9 修改 `frontend/src/views/Login.vue` 提升
**文件**：`frontend/src/views/Login.vue`

按 impeccable Absolute bans 标准审计与修复：
- 检查并移除任何 `border-left`/`border-right` > 1px 的彩色装饰
- 检查并移除任何 `background-clip: text` + 渐变背景组合
- 检查并移除装饰性 glassmorphism（保留必要功能）
- 检查并移除所有 section eyebrow（如有的话）
- 量子主题元素：黑洞引力漩涡背景（CSS 动画，无发光光晕，无 square-shaped 粒子，无白光闪屏）
- 认证按钮顶级动效（量子主题贴合，无发光光晕）
- 所有动画加 `@media (prefers-reduced-motion: reduce)` 替代

**阶段 7 验证**：
```powershell
cd frontend; npm run build
# 期望：dist/ 生成，无构建错误
# v-html 全局搜索为 0
# 普通员工访问 /system/settings → 跳转 /403
```

---

### 阶段 8：部署与文档（Deployment & Documentation）

#### 8.1 修改 `README.md`
**文件**：`README.md`

- 修正 MySQL 8.0+ → PostgreSQL 13+
- 补充环境变量列表：`JWT_SECRET`、`AES_ENCRYPTION_KEY`、`REDIS_PASSWORD`、`MESSAGE_SIGNING_KEY`、`DB_PASSWORD`、`CORS_ORIGINS`
- docker-compose 启动步骤
- Flyway 迁移说明（V1/V2/V3 自动执行）
- 生产部署清单引用 `docs/deployment-checklist.md`
- 安全配置说明（CORS、CSP、限流、AES 加密、审计日志）

#### 8.2 标记 `sql/init.sql` 为 DEPRECATED
**文件**：`sql/init.sql`

顶部加：
```sql
-- ============================================================
-- DEPRECATED: 本脚本已废弃，仅保留历史参考
-- 新部署请使用 Flyway 自动迁移：
--   backend/src/main/resources/db/migration/V1__init.sql
--   backend/src/main/resources/db/migration/V2__add_password_history_indexes.sql
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

完整 nginx 反代配置，含：
- HTTP → HTTPS 重定向
- TLS 1.2/1.3 + 强加密套件
- 安全头（HSTS、X-Frame-Options DENY、X-Content-Type-Options nosniff、Referrer-Policy、CSP）
- 限流：登录接口 5r/m
- 前端静态资源 + 后端 API 反代
- Actuator 仅内网访问

#### 8.5 新建 `docs/https-automation.md`
**文件**：`docs/https-automation.md`

- acme.sh 方案：standalone 模式 + nginx reload + cron 续签
- cert-manager 方案：Kubernetes Ingress + Certificate CRD 示例
- 证书过期监控

#### 8.6 新建 `docs/deployment-checklist.md`
**文件**：`docs/deployment-checklist.md`

生产环境检查清单（10+ 项）。

#### 8.7 新建 `.github/workflows/security-scan.yml`
**文件**：`.github/workflows/security-scan.yml`

```yaml
name: Security Scan
on:
  push:
    branches: [main, develop]
  pull_request:
jobs:
  maven:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: OWASP Dependency-Check
        run: mvn org.owasp:dependency-check-maven:9.0.9:check
  npm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '18'
      - run: cd frontend && npm ci && npm audit --audit-level=high
```

#### 8.8 修改 `backend/src/main/resources/application-prod.yml`
**文件**：`backend/src/main/resources/application-prod.yml`

**改动 1（L94 exposure 格式修复）**：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus  # 移除 info,metrics 减少暴露面
  endpoint:
    health:
      show-details: when_authorized
```

**改动 2（补充 aes.encryption-key 与 message.signing-key 配置）**：
```yaml
# AES 加密密钥（必须从环境变量读取，32 字节 Base64）
aes:
  encryption-key: ${AES_ENCRYPTION_KEY}

# 应用消息配置
app:
  # 限流配置
  rate-limit:
    login-per-minute: 5
    approve-per-minute: 30
    default-per-minute: 100
  # Redis Pub/Sub 消息签名密钥
  message:
    signing-key: ${MESSAGE_SIGNING_KEY}
    channel-prefix: wos:approval
  # 安全配置
  security:
    csp:
      enabled: true
```

**阶段 8 验证**：
```powershell
cd backend; mvn -DskipTests compile
cd ..\frontend; npm run build
# README 无 MySQL 字样
# Flyway 迁移脚本 V1/V2/V3 齐全
# application-prod.yml 含 aes.encryption-key 配置
```

---

## 四、执行顺序与依赖（Execution Order）

```
阶段 4.6-4.8（PasswordPolicy + AuthServiceImpl + docs/redis-db）
  └─ 阶段 5（AuthServiceImpl 3 bug + SecurityAuditServiceImpl + logback + MetricsConfig + alertmanager）
       └─ 阶段 6（ProcessController + FlowableConfig + WorkOrderServiceImpl + RedisMessageConfig + Listener）
            └─ 阶段 7（前端 DOMPurify + sanitize + v-safe-html + CSP + 路由权限 + 403 + request.js + OKLCH + Login.vue）
                 └─ 阶段 8（README + init.sql + schema-init + nginx + https + checklist + workflow + application-prod.yml）
```

每阶段独立编译验证通过后进入下一阶段。

---

## 五、假设与决策（Assumptions & Decisions）

### 5.1 关键决策
1. **PasswordPolicyServiceImpl 方法签名变更**：`recordFailedLoginAttempt(username)` → `recordFailedLoginAttempt(username, clientIp)`，同步更新接口与所有调用方。这是必要的破坏性变更，以支持 IP+username 组合锁定。
2. **AES 加密迁移容错**：`AesEncryptedStringTypeHandler.decrypt` 异常时若非 `iv:cipher` 格式 → 视为明文原样返回（已在 4.2 实现）。
3. **Flowable 禁脚本**：通过 `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>` + 替换 `ScriptTaskParseHandler` 在解析阶段拒绝所有 scriptTask。
4. **候选人校验**：双重防御 — Flowable API（`getIdentityLinksForTask`）+ Redis 缓存（`task:candidates:{taskId}`）。
5. **前端安全**：`v-safe-html` 全局指令替代 `v-html`；CSP meta + 后端 CSP 双重设置；OKLCH tokens 扩展现有 `tokens.scss`。
6. **MetricsConfig 埋点**：通过 `MetricsConfig` 集中提供 helper 方法（`recordLoginAttempt` 等），最小侵入式埋点。
7. **401 改造**：用 `ElNotification` 替代 `ElMessageBox.confirm`，直接跳转登录页，符合用户偏好"消除内容实际删除之间的空白间隔"。
8. **403 路由**：新建 `views/error/403.vue`，量子主题，遵循用户偏好。

### 5.2 假设
- 用户本地已安装 Java 17、Maven 3.9+、Node.js 18+、PostgreSQL 13+、Redis 6+（或使用 docker-compose）。
- dev 环境使用 `${ENV_VAR:default}` 模式；prod 强制环境变量（无默认值，缺失时启动失败）。
- Flyway 首次部署执行 V1→V2→V3 顺序迁移。
- 用户接受 OKLCH tokens 与现有 HEX tokens 并存（向后兼容）。

### 5.3 不做的事项（明确排除）
- 不升级 SpringBoot 2.7.18 → 3.x（保持兼容）
- 不引入 Sentinel（用 Caffeine 替代）
- 不引入 Vault/Kubernetes Secrets（用环境变量替代）
- 不引入 ELK（用 logback 文件 + Prometheus 替代）
- 不引入 WAF（基础设施层，文档说明）
- 不做前端全量 UI 重设计（仅审计 + 修复严重违规 + 提升 Login.vue + 新建 403.vue）
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

### 6.2 阶段 4 验证（密码策略 + 密码历史）
- `PasswordPolicyServiceImpl.DEFAULT_PASSWORD_POLICY` 中 minPasswordLength=10、所有 require=true
- `validatePasswordNotReused` 异常时返回 false（fail-closed）
- `recordFailedLoginAttempt(username, clientIp)` Redis key 含 IP
- `changePassword` 末尾调用 `recordPasswordChange`
- `docs/redis-hardening.conf` 与 `docs/db-grants.sql` 存在

### 6.3 阶段 5 验证（审计日志）
- `AuthServiceImpl` L257/262/676 不再含 `System.currentTimeMillis() + jwtUtil.getExpirationDateFromToken`
- `AuthServiceImpl` L766 第二个参数为 `getClientIp()`
- `SecurityAuditServiceImpl.logSensitiveOperation` 调用 `getCurrentRequest()` 取 IP/UA
- `SecurityAuditServiceImpl.queryAuditLogs` 无 try-catch 吞异常
- `logback-spring.xml` 存在并引用 `SensitiveDataConverter`
- `MetricsConfig.java` 含三个 Counter Bean
- `docs/alertmanager-rules.yml` 存在

### 6.4 阶段 6 验证（Flowable）
- `ProcessController.java` 存在，部署/删除/挂起端点加 `@PreAuthorize("hasRole('SUPER_ADMIN')")`
- `FlowableConfig.java` 实现 `EngineConfigurationConfigurer`，含 ScriptTask 拒绝逻辑
- `WorkOrderServiceImpl.handleApproval` 含 `validateTaskCandidate` 调用
- `RedisMessageConfig.java` 存在，含 HMAC 签名方法
- `ParallelApprovalEventListener` 含 Redis 候选人缓存逻辑

### 6.5 阶段 7 验证（前端）
```powershell
cd frontend; npm run build
# 期望：dist/ 生成
# v-html 全局搜索为 0
# 普通员工访问 /system/settings → 跳转 /403
# 浏览器控制台无 CSP 违规
# main.js 含 v-safe-html 指令注册
# request.js 不再含 ElMessageBox.confirm
# tokens.scss 含 --oklch- 前缀变量
```

### 6.6 阶段 8 验证（文档）
```powershell
Select-String -Path README.md -Pattern "MySQL"
# 期望：无匹配
Get-ChildItem backend/src/main/resources/db/migration/
# 期望：V1__init.sql, V2__add_password_history_indexes.sql, V3__add_audit_indexes.sql
Get-ChildItem docs/
# 期望：redis-hardening.conf, db-grants.sql, alertmanager-rules.yml, nginx.conf, https-automation.md, deployment-checklist.md
Get-ChildItem .github/workflows/
# 期望：security-scan.yml
Select-String -Path backend/src/main/resources/application-prod.yml -Pattern "exposure:"
# 期望：exposure.include: health,prometheus
Select-String -Path backend/src/main/resources/application-prod.yml -Pattern "aes:"
# 期望：匹配到 aes.encryption-key 配置
```

### 6.7 最终全量验证
```powershell
cd backend; mvn clean package -DskipTests
# 期望：BUILD SUCCESS，jar 包生成

cd ..\frontend; npm run build
# 期望：dist/ 生成
```

---

## 七、风险与回滚（Risk & Rollback）

### 7.1 主要风险
1. **PasswordPolicyServiceImpl 方法签名变更**会影响所有调用方（AuthService、其他服务）。需要同步更新接口与调用方，否则编译失败。
2. **FlowableConfig 替换 ScriptTaskParseHandler** 可能影响现有 BPMN（如果当前 BPMN 含 scriptTask）。需要确认现有流程定义不含 scriptTask。
3. **AES 加密** UserMapper.xml 已配置 typeHandler，但如果 AesEncryptionUtil 密钥变更会导致历史数据无法解密。dev 环境保留兼容明文容错。

### 7.2 回滚策略
- 每阶段独立 commit，编译验证通过后再进入下一阶段。
- 若某阶段编译失败，回滚到上一阶段 commit 重新分析。
- 关键数据变更前（如 PasswordPolicyServiceImpl 签名变更）先备份当前文件。

---

## 八、执行检查清单（Execution Checklist）

执行顺序（按此顺序逐项执行，每项完成后立即验证）：

### 阶段 4 剩余
- [ ] 4.6 修改 `PasswordPolicyServiceImpl.java`（DEFAULT_PASSWORD_POLICY 强化 + validatePasswordNotReused fail-closed + recordFailedLoginAttempt IP+username）
- [ ] 4.6 同步更新 `PasswordPolicyService.java` 接口签名
- [ ] 4.7 修改 `AuthServiceImpl.java`（login 中调用 recordFailedLoginAttempt/isAccountLocked/resetLoginAttempts 传 clientIp + changePassword 调用 recordPasswordChange）
- [ ] 4.8 新建 `docs/redis-hardening.conf`
- [ ] 4.8 新建 `docs/db-grants.sql`
- [ ] 阶段 4 验证：`mvn -DskipTests compile` → BUILD SUCCESS

### 阶段 5
- [ ] 5.1 修改 `AuthServiceImpl.java`（L257/262/676 expiration bug + L766/L794 IP/UA 修复）
- [ ] 5.2 修改 `SecurityAuditServiceImpl.java`（logSensitiveOperation 从 RequestContextHolder 取 IP/UA + queryAuditLogs 移除吞异常）
- [ ] 5.3 新建 `logback-spring.xml`
- [ ] 5.4 新建 `util/SensitiveDataConverter.java`
- [ ] 5.5 新建 `config/MetricsConfig.java` + 在 JwtAuthenticationFilter/DataPermissionAspect/AuthServiceImpl 埋点
- [ ] 5.6 新建 `docs/alertmanager-rules.yml`
- [ ] 阶段 5 验证：`mvn -DskipTests compile` → BUILD SUCCESS

### 阶段 6
- [ ] 6.1 新建 `controller/ProcessController.java`
- [ ] 6.2 修改 `config/FlowableConfig.java`（禁脚本任务）
- [ ] 6.3 修改 `service/impl/WorkOrderServiceImpl.java`（候选人校验 + 变量净化）
- [ ] 6.4 新建 `config/RedisMessageConfig.java`
- [ ] 6.5 修改 `listener/ParallelApprovalEventListener.java`（候选人缓存）
- [ ] 阶段 6 验证：`mvn -DskipTests compile` → BUILD SUCCESS

### 阶段 7
- [ ] 7.1 修改 `frontend/package.json` 加 dompurify
- [ ] 7.2 新建 `frontend/src/utils/sanitize.js`
- [ ] 7.3 修改 `frontend/src/main.js` 注册 v-safe-html
- [ ] 7.4 修改 `frontend/index.html` 加 CSP meta
- [ ] 7.5 修改 `frontend/src/router/index.js` 加 403 路由 + 权限校验
- [ ] 7.6 新建 `frontend/src/views/error/403.vue`
- [ ] 7.7 修改 `frontend/src/utils/request.js` 401 改造
- [ ] 7.8 修改 `frontend/src/styles/tokens.scss` 扩展 OKLCH tokens
- [ ] 7.9 修改 `frontend/src/views/Login.vue` 提升（按 Absolute bans 修复 + 量子主题）
- [ ] 阶段 7 验证：`npm run build` → BUILD SUCCESS

### 阶段 8
- [ ] 8.1 修改 `README.md`
- [ ] 8.2 修改 `sql/init.sql` 标记 DEPRECATED
- [ ] 8.3 同步 `backend/src/main/resources/sql/schema-init.sql`
- [ ] 8.4 新建 `docs/nginx.conf`
- [ ] 8.5 新建 `docs/https-automation.md`
- [ ] 8.6 新建 `docs/deployment-checklist.md`
- [ ] 8.7 新建 `.github/workflows/security-scan.yml`
- [ ] 8.8 修改 `backend/src/main/resources/application-prod.yml`（exposure.include + aes.encryption-key + app.rate-limit + app.message.signing-key）
- [ ] 阶段 8 验证：`mvn -DskipTests compile` + `npm run build` → 双双 BUILD SUCCESS

### 最终
- [ ] `cd backend; mvn clean package -DskipTests` → BUILD SUCCESS
- [ ] `cd frontend; npm run build` → BUILD SUCCESS
