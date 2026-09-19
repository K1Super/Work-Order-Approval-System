/**
 * 前端错误上报 API — 规范条款 5/13
 * POST /api/client-logs/report
 */

import request from '@/utils/request'

/**
 * 上报前端错误日志
 * @param {Object} logData 日志数据
 * @param {string} logData.level 日志级别
 * @param {string} logData.message 错误消息
 * @param {string} [logData.stack] 异常堆栈
 * @param {string} [logData.url] 发生错误的页面 URL
 * @param {string} [logData.traceId] 链路追踪 ID
 * @param {string} [logData.action] 操作标识
 */
export function reportError(logData) {
  // W-48：删除 skipCsrf 死配置；统一由 request 实例自动注入 X-XSRF-TOKEN 与凭证
  return request.post('/client-logs/report', logData).catch(() => {
    // 上报失败静默处理，不影响业务
  })
}
