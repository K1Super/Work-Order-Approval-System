// ============================================================
// OPTIMIZATION 三.3.1 前端 Token 存储迁移
// ============================================================
// 改造点：
// 1. 移除 localStorage 存储 JWT Token（XSS 可窃取）
// 2. Token 改由后端 HttpOnly; Secure; SameSite=Strict Cookie 传递（WOS_TOKEN）
//    前端 JS 无法读取，浏览器自动在 withCredentials=true 时发送
// 3. 保留 sessionStorage 缓存 userInfo（不含 Token，仅用户基本信息 + 权限列表）
//    用于刷新页面时快速恢复用户状态（无需立即调用 /auth/current-user）
// 4. isLoggedIn 判断改为 userStore.isLoggedIn（基于 userInfo 是否存在）
// ============================================================

const USER_INFO_KEY = 'work_order_user_info'

/**
 * 获取 Token（OPTIMIZATION 三.3.1：已废弃，Token 由 HttpOnly Cookie 传递）
 *
 * 兼容历史调用方：
 * - router/index.js 的 getToken() 判断：返回 null → 触发 userStore.isLoggedIn 路径
 * - 部分旧代码（如 api/auth.js 中 refreshToken/logout 的 Authorization Header）：
 *   返回 null → 不再注入 Authorization Header，依赖 Cookie 鉴权
 *
 * @returns {string|null} 始终返回 null（Token 已迁移至 HttpOnly Cookie）
 * @deprecated 不再使用，保留仅为向后兼容
 */
export function getToken() {
  return null
}

/**
 * 设置 Token（OPTIMIZATION 三.3.1：已废弃）
 *
 * Token 现由后端 AuthController.login 调用 AuthCookieUtil.setAuthCookie 写入 HttpOnly Cookie。
 * 前端无法也无需设置 Token。此函数保留为空函数仅为向后兼容历史调用方。
 *
 * @param {string} _token 忽略
 * @deprecated 不再使用，保留仅为向后兼容
 */
export function setToken(_token) {
  // no-op：Token 由后端 HttpOnly Cookie 设置
  void _token
}

/**
 * 移除 Token（OPTIMIZATION 三.3.1：已废弃）
 *
 * 注销时由后端 AuthController.logout 调用 AuthCookieUtil.clearAuthCookie 清除 Cookie。
 * 此函数仅清除本地缓存的 userInfo，确保前端状态与后端 Cookie 同步清除。
 *
 * @deprecated 不再使用 Token 操作；保留 userInfo 清除逻辑
 */
export function removeToken() {
  // 清除本地 userInfo 缓存（Token 由后端 Cookie 管理）
  sessionStorage.removeItem(USER_INFO_KEY)
}

/**
 * 获取缓存的用户信息（不含 Token，仅用户基本信息 + 权限列表）
 *
 * 用于刷新页面时快速恢复用户状态，避免立即调用 /auth/current-user。
 * 路由守卫会在首次导航时通过 userStore.fetchUserInfo() 重新校验 Token 有效性。
 *
 * @returns {object|null} 用户信息对象；不存在返回 null
 */
export function getCachedUserInfo() {
  try {
    const data = sessionStorage.getItem(USER_INFO_KEY)
    return data ? JSON.parse(data) : null
  } catch {
    return null
  }
}

/**
 * 缓存用户信息（不含 Token）
 *
 * @param {object} info 用户信息对象
 */
export function setCachedUserInfo(info) {
  if (info) {
    sessionStorage.setItem(USER_INFO_KEY, JSON.stringify(info))
  }
}
