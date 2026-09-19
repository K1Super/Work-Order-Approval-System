/**
 * 企业级统一日志工具 — 规范条款 1/2/3/4/6/7/10/11/12
 *
 * 规范条款 1（统一出口）：禁止裸调用 console.*，必须通过此工具输出
 * 规范条款 2（零明文敏感数据）：内置脱敏正则，自动替换敏感字段
 * 规范条款 3（严格分级、环境隔离）：dev 全量输出，prod 仅 INFO+ 且 WARN/ERROR 自动上报
 * 规范条款 4（结构化全链路）：固定字段 + TraceId
 * 规范条款 7（结构化日志字段）：timestamp/env/service/level/action/traceId/userId/message/data
 * 规范条款 11（环境输出策略）：dev: ALL, test: DEBUG+, prod: INFO+
 * 规范条款 12（日志文案标准）：动作+业务对象+执行结果/异常原因
 */

// ==================== 敏感数据脱敏（规范条款 2/6） ====================

/**
 * 从 Cookie 读取指定名称的值
 * 用于读取后端 Spring Security CookieCsrfTokenRepository 写入的 XSRF-TOKEN，
 * 手动注入 X-XSRF-TOKEN 请求头（logger 保持独立，不复用 axios 实例，避免循环依赖）
 *
 * @param {string} name Cookie 名称
 * @returns {string|null} Cookie 值；不存在返回 null
 */
function getCookie(name) {
  if (typeof document === 'undefined' || !name) return null
  const match = document.cookie.match(
    new RegExp('(?:^|; )' + name.replace(/([.$?*|{}()[\]\\/+^])/g, '\\$1') + '=([^;]*)')
  )
  return match ? decodeURIComponent(match[1]) : null
}

const SENSITIVE_PATTERNS = [
  // JSON 格式 "key":"value" → "key":"******"
  { pattern: /"password"\s*:\s*"[^"]+"/g, replacement: '"password":"******"' },
  { pattern: /"token"\s*:\s*"[^"]+"/g, replacement: '"token":"******"' },
  { pattern: /"secret"\s*:\s*"[^"]+"/g, replacement: '"secret":"******"' },
  { pattern: /"phone"\s*:\s*"[^"]+"/g, replacement: '"phone":"******"' },
  { pattern: /"email"\s*:\s*"[^"]+"/g, replacement: '"email":"******"' },
  { pattern: /"idcard"\s*:\s*"[^"]+"/g, replacement: '"idcard":"******"' },
  { pattern: /"cookie"\s*:\s*"[^"]+"/g, replacement: '"cookie":"******"' },
  { pattern: /"sessionId"\s*:\s*"[^"]+"/g, replacement: '"sessionId":"******"' },
  { pattern: /"privateKey"\s*:\s*"[^"]+"/g, replacement: '"privateKey":"******"' },
  // key=value / key: value 格式 → 保留前缀，替换值
  { pattern: /(password\s*[=:]\s*)\S+/gi, replacement: '$1******' },
  { pattern: /(token\s*[=:]\s*)\S+/gi, replacement: '$1******' },
  { pattern: /(secret\s*[=:]\s*)\S+/gi, replacement: '$1******' },
  { pattern: /(phone\s*[=:]\s*)\d{7,}/gi, replacement: '$1******' },
  { pattern: /(email\s*[=:]\s*)[^\s,;}]+/gi, replacement: '$1******' },
]

/**
 * 脱敏处理：对日志消息中的敏感数据进行替换
 */
function sanitize(message) {
  if (typeof message !== 'string') {
    try { message = JSON.stringify(message) } catch { return String(message) }
  }
  let result = message
  for (const { pattern, replacement } of SENSITIVE_PATTERNS) {
    result = result.replace(pattern, replacement)
  }
  return result
}

// ==================== 日志级别定义（规范条款 4：五级日志体系） ====================

const LOG_LEVELS = {
  TRACE: 0,
  DEBUG: 1,
  INFO: 2,
  WARN: 3,
  ERROR: 4
}

/**
 * 根据环境确定最低日志级别（规范条款 11）
 * - dev: TRACE（全量输出）
 * - test: DEBUG
 * - pre/prod: INFO
 */
function getMinLevel() {
  const mode = import.meta.env.MODE
  if (mode === 'development') return LOG_LEVELS.TRACE
  if (mode === 'test') return LOG_LEVELS.DEBUG
  return LOG_LEVELS.INFO
}

// ==================== Logger 类 ====================

class Logger {
  constructor() {
    this._traceId = null
    this._userId = null
    this._env = import.meta.env.MODE || 'unknown'
    this._service = 'work-order-frontend'
    this._reportQueue = []
    this._reportTimer = null
    this._isReporting = false
    this._maxQueueSize = 20
    this._flushInterval = 5000
    this._minLevel = getMinLevel()
  }

  // ====== TraceId 管理（规范条款 4：结构化全链路） ======

  /** 设置 TraceId（从后端响应头获取） */
  setTraceId(traceId) { this._traceId = traceId }

  /** 获取当前 TraceId */
  getTraceId() { return this._traceId || 'NO_TRACE' }

  // ====== 用户上下文 ======

  /** 设置用户 ID（登录成功后调用） */
  setUserId(userId) { this._userId = userId }

  // ====== 五级日志体系（规范条款 4/6） ======

  /** TRACE 级别：代码级执行细节，仅本地调试 */
  trace(action, message, data = null) { this._log('TRACE', action, message, data) }

  /** DEBUG 级别：业务调试信息，测试环境可见 */
  debug(action, message, data = null) { this._log('DEBUG', action, message, data) }

  /** INFO 级别：核心正常业务节点（全环境保留） */
  info(action, message, data = null) { this._log('INFO', action, message, data) }

  /** WARN 级别：非中断性异常、降级处理（全环境保留） */
  warn(action, message, data = null) { this._log('WARN', action, message, data) }

  /** ERROR 级别：业务失败、系统异常（全环境保留，生产强制采集） */
  error(action, message, data = null) { this._log('ERROR', action, message, data) }

  // ====== 核心日志方法 ======

  _log(level, action, message, data) {
    // 环境隔离（规范条款 11）
    if (LOG_LEVELS[level] < this._minLevel) return

    // 构建结构化日志条目（规范条款 7）
    const entry = this._buildEntry(level, action, message, data)

    // 开发环境：控制台友好输出
    if (import.meta.env.DEV) {
      this._consoleOutput(level, entry)
    }

    // 生产环境：WARN/ERROR 自动上报（规范条款 5：可采集可告警）
    if (import.meta.env.PROD && (level === 'WARN' || level === 'ERROR')) {
      this._enqueueReport(entry)
    }
  }

  /**
   * 构建结构化日志条目（规范条款 7）
   */
  _buildEntry(level, action, message, data) {
    return {
      timestamp: new Date().toISOString(),
      env: this._env,
      service: this._service,
      level,
      action,
      traceId: this.getTraceId(),
      userId: this._userId || null,
      message: sanitize(message),
      data: data ? sanitize(JSON.stringify(data)) : null
    }
  }

  /**
   * 控制台输出（开发环境友好格式）
   */
  _consoleOutput(level, entry) {
    const prefix = `[${entry.timestamp}] [${entry.traceId}] [${level}] [${entry.action}]`
    const consoleMethod = level === 'TRACE' ? console.debug
      : level === 'DEBUG' ? console.debug
      : level === 'INFO' ? console.info
      : level === 'WARN' ? console.warn
      : console.error

    if (entry.data) {
      consoleMethod(prefix, entry.message, entry.data)
    } else {
      consoleMethod(prefix, entry.message)
    }
  }

  // ====== 批量上报机制（规范条款 5/13） ======

  /**
   * 加入上报队列
   */
  _enqueueReport(entry) {
    this._reportQueue.push(entry)
    if (this._reportQueue.length >= this._maxQueueSize) {
      this._flush()
    } else if (!this._reportTimer) {
      this._reportTimer = setTimeout(() => this._flush(), this._flushInterval)
    }
  }

  /**
   * 批量上报（sendBeacon 优先，fetch 降级）
   */
  async _flush() {
    if (this._isReporting || this._reportQueue.length === 0) return
    this._isReporting = true
    clearTimeout(this._reportTimer)
    this._reportTimer = null

    const batch = this._reportQueue.splice(0)
    try {
      const payload = JSON.stringify({ logs: batch })
      // W-48：统一上报路径为 /api/v1/client-logs/report（对齐后端 context-path /api/v1，
      // 与 api/log.js 通过 request 实例 baseURL 拼接出的结果一致）
      // 上报需带 CSRF token（后端 POST 统一校验 XSRF；permitAll 仅豁免认证、不豁免 CSRF）
      // logger 保持裸 fetch 以规避 request.js ↔ logger.js 循环依赖，
      // 这里手动从 XSRF-TOKEN Cookie 读取并注入 X-XSRF-TOKEN 请求头
      const headers = { 'Content-Type': 'application/json' }
      const xsrf = getCookie('XSRF-TOKEN')
      if (xsrf) {
        headers['X-XSRF-TOKEN'] = xsrf
      }
      await fetch('/api/v1/client-logs/report', {
        method: 'POST',
        headers,
        body: payload,
        credentials: 'include',
        keepalive: true
      })
    } catch (_) {
      // 上报失败静默处理，不影响业务
    } finally {
      this._isReporting = false
    }
  }

  /**
   * 强制刷新上报队列（beforeunload/visibilitychange 时调用）
   */
  forceFlush() { this._flush() }
}

// 单例导出
const logger = new Logger()
export default logger
