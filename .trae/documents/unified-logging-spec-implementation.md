# 企业级全栈统一日志治理规范 — 落地执行方案

## Context
项目日志存在严重治理缺陷：后端无 TraceId 链路追踪、命名 Logger 路由失效（logback-audit.xml 未被加载）、脱敏覆盖不全、前端无统一日志工具和生产 console 剔除、无错误上报机制。需按《企业级前后端统一日志治理规范》13 条款严格落地。

---

## Phase 1：基础设施搭建（后端 TraceId + Logback + 前端 Logger）

### Step 1: TraceIdFilter — 全链路追踪核心
- **新建** `backend/src/main/java/com/workorder/filter/TraceIdFilter.java`
  - `@Component @Order(Ordered.HIGHEST_PRECEDENCE + 2)`（XssFilter 之后、EnterpriseSecurityFilter 之后）
  - 生成 TraceId 格式 `WOS-<8hex>-<8hex>`，SpanId `<16hex>`
  - 从请求头读 `X-Trace-Id`（前端传入），不存在则生成
  - 注入 MDC：traceId, spanId, userId, clientIp, path, requestStartTime
  - 响应头回传 `X-Trace-Id` + `X-Request-Id`
  - finally 中 `MDC.clear()`（防线程池复用串链）

### Step 2: SecurityConfig 注册 TraceIdFilter
- **修改** `backend/src/main/java/com/workorder/config/SecurityConfig.java`
  - `addFilterBefore(traceIdFilter, CsrfFilter.class)` 确保 TraceId 在所有业务过滤器之前
  - `/client-log/report` 加入 permitAll
  - `/client-log/report` 加入 CSRF ignoringAntMatches

### Step 3: logback-spring.xml 全面改造
- **修改** `backend/src/main/resources/logback-spring.xml`
  - 合并 logback-audit.xml 全部内容（appender + logger 定义）
  - 所有 pattern 增加 MDC 占位符：`[%X{traceId:-NO_TRACE}] [%X{spanId:-NO_SPAN}]`
  - 新增 `JSON_FILE` appender（logback-jackson + logback-json-classic，ELK/Loki 采集源）
  - 新增 `ERROR_FILE` appender（仅 ERROR 级别，独立文件，保留 90 天）
  - 新增命名 Logger 路由：AUDIT_LOGGER、SECURITY_VIOLATION_LOGGER、SECURITY_AUDIT、WORK_ORDER_AUDIT_LOGGER、DATA_RECONCILIATION_LOGGER、DEK_MIGRATION_LOGGER、CLIENT_ERROR_LOGGER
  - prod profile root level 从 WARN 调为 INFO，框架噪音降级
  - 新增 test profile（DEBUG 级别）
  - 用 ASYNC 包装 JSON_FILE 和 ERROR_FILE
- **删除** `backend/src/main/resources/logback-audit.xml`（已合并，避免配置歧义）

### Step 4: pom.xml 新增依赖
- **修改** `backend/pom.xml`
  - `ch.qos.logback.contrib:logback-jackson:0.1.5`
  - `ch.qos.logback.contrib:logback-json-classic:0.1.5`

### Step 5: SensitiveDataConverter 增强
- **修改** `backend/src/main/java/com/workorder/util/SensitiveDataConverter.java`
  - 新增 key=value 格式：phone, idcard, bankcard, email, cookie, sessionId, privateKey, signingKey（8 种）
  - 新增 JSON 格式：password, token, secret, phone, email, idcard, bankcard, sessionId, cookie, privateKey, signingKey（11 种 `"key":"value"` 模式）
  - convert() 分两轮处理：先 key=value，再 JSON

### Step 6: StructuredLogUtil 结构化日志工具
- **新建** `backend/src/main/java/com/workorder/util/StructuredLogUtil.java`
  - MDC 操作：setBizContext(action, bizNo, errorCode)、setErrorContext(errorMessage, errorLocation, stackTrace)、clearBizContext()
  - 结构化输出：logBizAction（INFO）、logBizError（ERROR 含堆栈）、logSecurityAudit（WARN）
  - setDuration(startTimeMs) 计算耗时写入 MDC
  - 日志文案强制格式：`[动作] 业务对象 - 执行结果/异常原因 (bizNo=xxx)`

---

## Phase 2：前端日志基础设施

### Step 7: 统一日志工具 logger.js
- **新建** `frontend/src/utils/logger.js`
  - 五级日志：trace/debug/info/warn/error
  - 环境隔离：dev 全量输出，prod 仅 INFO+ 且 WARN/ERROR 自动上报
  - 敏感数据脱敏：9 种正则（password/token/secret/phone/email/idcard/cookie/sessionId/privateKey）
  - 结构化日志字段：timestamp/env/service/level/action/traceId/userId/message/data
  - TraceId 管理：setTraceId/getTraceId
  - 批量上报：队列最大 20 条，5 秒间隔，sendBeacon 优先 + fetch 降级
  - forceFlush() 供 beforeunload/visibilitychange 调用

### Step 8: 全局异常捕获 errorReporter.js
- **新建** `frontend/src/utils/errorReporter.js`
  - setupGlobalErrorHandlers(app)：注册 6 种异常捕获
    - Vue errorHandler（渲染异常）
    - window.onerror（JS 运行时错误）
    - window.addEventListener('error', capture)（资源加载错误）
    - window.addEventListener('unhandledrejection')（Promise 拒绝）
    - beforeunload（页面卸载上报）
    - visibilitychange（页面切后台上报）

### Step 9: 错误上报 API
- **新建** `frontend/src/api/log.js`
  - reportError(logData) — POST /client-log/report

### Step 10: vite.config.js 生产 console 剔除
- **修改** `frontend/vite.config.js`
  - esbuild.drop: `process.env.NODE_ENV === 'production' ? ['console', 'debugger'] : []`

### Step 11: request.js TraceId 透传
- **修改** `frontend/src/utils/request.js`
  - 请求拦截器：注入 X-Trace-Id + X-Request-Id
  - 响应拦截器：提取响应头 x-trace-id 更新 logger.setTraceId()
  - 导入 logger 替代 console

---

## Phase 3：结构化字段增强

### Step 12: SecurityAuditLog 实体增强
- **修改** `backend/src/main/java/com/workorder/entity/SecurityAuditLog.java`
  - 新增字段：traceId, spanId, action, path, duration, errorCode, errorLocation

### Step 13: SecurityAuditLogMapper.xml 更新
- **修改** `backend/src/main/resources/mapper/SecurityAuditLogMapper.xml`
  - INSERT 语句增加新字段
  - 查询结果映射增加新字段

### Step 14: SecurityAuditServiceImpl MDC 集成
- **修改** `backend/src/main/java/com/workorder/service/impl/SecurityAuditServiceImpl.java`
  - audit() 方法从 MDC 读取 traceId/spanId/path/clientIp 填充审计日志

### Step 15: 前端错误上报后端接口
- **新建** `backend/src/main/java/com/workorder/controller/ClientLogController.java`
  - POST /client-log/report，不要求认证
  - 使用 CLIENT_ERROR_LOGGER 输出日志
  - Prometheus 埋点：wos_client_errors_total
- **新建** `backend/src/main/java/com/workorder/entity/ClientLog.java`（可选，先用 Map 接收）
- **新建** `backend/src/main/resources/db/migration/V8__add_audit_log_trace_fields.sql`
  - ALTER TABLE sys_security_audit_log ADD COLUMN trace_id VARCHAR(40), span_id VARCHAR(40), action VARCHAR(50), path VARCHAR(200), duration BIGINT, error_code VARCHAR(20), error_location VARCHAR(200)

### Step 16: MetricsConfig 前端错误指标
- **修改** `backend/src/main/java/com/workorder/config/MetricsConfig.java`
  - 新增 recordClientError(level) 方法
  - Counter: wos_client_errors_total{level}

### Step 17: main.js 集成
- **修改** `frontend/src/main.js`
  - 导入 logger 和 errorReporter
  - 替换现有 errorHandler + unhandledrejection 为 setupGlobalErrorHandlers(app)
  - initApp 中登录成功后 logger.setUserId()
  - beforeunload 时 logger.forceFlush()

---

## Phase 4：存量代码整改

### Step 18: 后端 Logger 变量名统一
- **修改** 4 个文件：`log` → `logger`
  - `GlobalExceptionHandler.java`
  - `JwtAuthenticationFilter.java`
  - `MyBatisTypeHandlerConfig.java`
  - `WorkOrderController.java`

### Step 19: 前端 console 替换为 logger
- **修改** `frontend/src/views/Login.vue`：4 处 console → logger
- **修改** `frontend/src/router/index.js`：1 处 console → logger
- **修改** `frontend/src/views/workorder/CreateWorkOrder.vue`：3 处 console → logger（其中用户信息日志删除）

---

## 验证方案

### 后端验证
1. TraceId：请求任意 API → 响应头含 X-Trace-Id，日志含 `[WOS-xxxx-xxxx]`
2. 命名 Logger 路由：触发 XSS 拦截 → security-violations.log 有输出
3. JSON 结构化日志：检查 .json 文件每行是合法 JSON 含 traceId/spanId
4. ERROR 专用文件：触发 500 → .error 文件有独立输出
5. SensitiveDataConverter：触发含 `"password":"xxx"` 的日志 → 输出 `"password":"******"`
6. Logger 变量名统一：grep 零结果
7. MDC 清理：并发 100 请求 → TraceId 不串链

### 前端验证
1. 统一日志工具：logger.info() → 控制台含 [traceId] [INFO] [action]
2. 敏感数据脱敏：logger.info('Test', 'password=secret') → password=******
3. 生产 console 剔除：npm run build 后 dist 无 console.*
4. TraceId 透传：请求头含 X-Trace-Id/X-Request-Id
5. 全局异常捕获：throw Error → logger 有 ERROR 输出 + 上报请求
6. 存量 console 替换：grep console. 零结果

### 端到端链路验证
1. 前端登录 → 请求头 X-Request-Id → 后端生成 TraceId → 响应头 X-Trace-Id
2. 前端 logger 提取 TraceId → 后续请求自动携带
3. 后端全链路日志搜索同一 TraceId → 串联前后端
4. 前端错误上报 → CLIENT_ERROR_LOGGER 输出 → Prometheus 指标递增
