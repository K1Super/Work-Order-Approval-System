/**
 * 错误消息防抖 Hook（规范 §3.1 src/hooks/composables，§3.3.8 通用逻辑统一封装）
 *
 * 短时间内相同错误消息只弹出一次，防止接口批量失败时反复弹窗刷屏。
 * 从原 request.js 内联逻辑抽离，便于在拦截器与视图层复用。
 */
import { ElMessage } from 'element-plus'

const ERROR_DEBOUNCE_MS = 1000
let lastErrorTime = 0
let lastErrorMessage = ''

/**
 * 显示错误消息（带 1 秒防抖）
 * @param {string} message 错误消息
 * @param {object} [options] ElMessage 选项
 */
export function showErrorWithDebounce(message, options = {}) {
  if (!message) return
  const now = Date.now()
  // 1 秒内相同错误只显示一次
  if (message === lastErrorMessage && (now - lastErrorTime) < ERROR_DEBOUNCE_MS) {
    return
  }
  lastErrorTime = now
  lastErrorMessage = message
  ElMessage({
    message,
    type: 'error',
    duration: 5000,
    ...options
  })
}

/** 重置防抖状态（测试/切换场景使用） */
export function resetDebouncedMessage() {
  lastErrorTime = 0
  lastErrorMessage = ''
}

export default { showErrorWithDebounce, resetDebouncedMessage }
