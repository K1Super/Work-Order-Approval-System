import axios from 'axios'
import { ElNotification } from 'element-plus'
import { useUserStore } from '@/store/user'
import { removeToken } from '@/utils/auth'
import router from '@/router'
import { RESULT_CODE, BIZ_CODE, isSuccess, isUnauthorized } from '@/constants'
import { showErrorWithDebounce } from '@/hooks/useDebouncedMessage'
import logger from '@/utils/logger'

// ============================================================
// OPTIMIZATION 三.3.1 / 三.3.2 前端请求拦截器
// ============================================================
// 改造点：
// 1. 移除 HMAC 签名（密钥易通过开发工具泄露，形同虚设）
// 2. 移除 Authorization Bearer Header（Token 已迁移至 HttpOnly Cookie，浏览器自动发送）
// 3. axios withCredentials=true：允许跨域请求携带 Cookie（HttpOnly WOS_TOKEN）
// 4. CSRF Token 注入：从 XSRF-TOKEN Cookie 读取，注入 X-XSRF-TOKEN 请求头
//    后端 Spring Security CsrfFilter 校验所有 POST/PUT/DELETE/PATCH 请求
//    登录/注册/密码重置端点已在 SecurityConfig.ignoringAntMatchers 中豁免
// ============================================================

/**
 * 从 Cookie 读取指定名称的值
 * 用于读取后端 Spring Security CookieCsrfTokenRepository 写入的 XSRF-TOKEN
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

// 创建 axios 实例
const service = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=UTF-8'
  },
  // OPTIMIZATION 三.3.1：允许跨域请求携带 Cookie
  // 后端 HttpOnly WOS_TOKEN Cookie 需要通过 withCredentials 才能被浏览器发送
  // 同源请求下也会带上 XSRF-TOKEN Cookie（用于 CSRF 防护）
  withCredentials: true,
  // OPTIMIZATION 三.3.2：CSRF Token Cookie 名称（后端 CookieCsrfTokenRepository 默认值）
  xsrfCookieName: 'XSRF-TOKEN',
  // CSRF Token 请求头名称（后端 CookieCsrfTokenRepository 默认值）
  xsrfHeaderName: 'X-XSRF-TOKEN'
})

// 请求拦截器：CSRF Token 已通过 axios xsrfCookieName/xsrfHeaderName 自动注入
// （Axios 在请求前自动从 XSRF-TOKEN Cookie 读取并设置 X-XSRF-TOKEN 头）
service.interceptors.request.use(
  config => {
    // 兜底：若 axios 自动注入失败（部分场景如 FormData），手动注入 CSRF Token
    const xsrf = getCookie('XSRF-TOKEN')
    if (xsrf && !config.headers['X-XSRF-TOKEN']) {
      config.headers['X-XSRF-TOKEN'] = xsrf
    }

    // TraceId 透传（规范条款 4：结构化全链路）
    // 每次请求生成唯一 Request-Id，便于前后端日志关联
    const requestId = 'REQ-' + Date.now().toString(36) + '-' + Math.random().toString(36).substr(2, 8)
    config.headers['X-Request-Id'] = requestId
    // 如果已有 TraceId（从后端响应头获取），透传给后端
    if (logger.getTraceId() !== 'NO_TRACE') {
      config.headers['X-Trace-Id'] = logger.getTraceId()
    }

    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器：统一处理错误
service.interceptors.response.use(
  response => {
    // 从响应头提取 TraceId 并更新到 logger（规范条款 4：结构化全链路）
    const traceId = response.headers['x-trace-id']
    if (traceId) {
      logger.setTraceId(traceId)
    }

    const res = response.data

    // 从响应体提取 traceId（规范 §5 结构化日志字段 — 双重保障：响应头+响应体）
    if (res.traceId) {
      logger.setTraceId(res.traceId)
    }

    // 业务状态码判断（双码制：优先 bizCode，回退 code）
    if (!isSuccess(res) && res.code !== undefined) {
      // 使用防抖错误提示（防止多次弹窗）
      showErrorWithDebounce(res.msg || '请求失败')

      // Token过期或无效（双码制兼容判断）
      if (isUnauthorized(res) && !response.config.skipAuthError) {
        handleUnauthorized()
      }

      return Promise.reject(new Error(res.msg || 'Error'))
    }

    return res
  },
  error => {
    // 优先使用后端返回的具体错误信息
    let message = '网络错误，请稍后重试'

    if (error.response) {
      const responseData = error.response.data
      const status = error.response.status

      // 优先显示后端返回的业务错误消息（Result.msg）
      if (responseData?.msg) {
        message = responseData.msg
      } else {
        // 根据HTTP状态码显示通用错误
        switch (status) {
          case 401:
            message = '未授权，请重新登录'
            // 排除logout请求，避免退出时弹窗干扰
            if (!error.config?.skipAuthError) {
              handleUnauthorized()
            }
            break
          case 403:
            // OPTIMIZATION 三.3.2：CSRF Token 缺失/不匹配返回 403
            // 可能是首次访问尚未获取 XSRF-TOKEN Cookie，提示用户刷新页面
            if (responseData?.error === 'Forbidden' || responseData?.error === 'access_denied') {
              message = '访问被拒绝（CSRF 校验失败），请刷新页面后重试'
            } else {
              message = '拒绝访问，权限不足'
            }
            break
          case 404:
            message = '请求的资源不存在'
            break
          case 500:
            message = '服务器内部错误，请稍后重试'
            break
          default:
            message = `请求失败 (${status})`
        }
      }
    } else if (error.code === 'ECONNABORTED' || error.message.includes('timeout')) {
      message = '请求超时，请稍后重试'
    }

    // ✅ 使用防抖错误提示（防止多次弹窗）- 排除logout请求不显示错误
    if (!error.config?.skipAuthError) {
      showErrorWithDebounce(message)
    }

    return Promise.reject(error)
  }
)

// 处理未授权（Token失效）— 非阻塞式提示 + 安全跳转
let isHandlingUnauthorized = false

function handleUnauthorized() {
  if (isHandlingUnauthorized) return
  isHandlingUnauthorized = true

  const userStore = useUserStore()

  ElNotification({
    title: '会话过期',
    message: '登录状态已过期，正在跳转登录页',
    type: 'warning',
    duration: 2000,
    position: 'top-right'
  })

  // 清除状态并跳转登录页
  userStore.resetState()
  // 使用 replace 而非 push，避免用户回退到已失效的页面
  router.replace('/login').finally(() => {
    isHandlingUnauthorized = false
  }).catch(() => {
    // 导航失败（如已在登录页），忽略错误
    isHandlingUnauthorized = false
  })
}

export default service
