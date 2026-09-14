import request from '@/utils/request'

// ============================================================
// OPTIMIZATION 三.3.1 认证 API（Token 迁移至 HttpOnly Cookie）
// ============================================================
// 改造点：
// 1. 所有请求依赖 axios withCredentials=true 自动携带 WOS_TOKEN Cookie
// 2. 移除 logout/refreshToken 的 Authorization Bearer Header（Token 在 Cookie 中）
// 3. 仅强制改密流程由 Login.vue 临时通过 request.post config.headers 传递 tempToken
//    （后端 AuthCookieUtil.extractTokenFromRequest 优先读 Authorization Header）
// ============================================================

// 用户登录
// 登录成功后后端 AuthController 调用 AuthCookieUtil.setAuthCookie 写入 WOS_TOKEN Cookie
// skipAuthError: true — 登录失败时不触发 handleUnauthorized（"登录状态已过期"），
// 而是正常返回错误信息（如"用户名或密码错误"），由 Login.vue 自行处理
export function login(data) {
  return request({
    url: '/auth/sessions',
    method: 'post',
    data,
    skipAuthError: true
  })
}

// 获取当前用户信息
// 依赖 WOS_TOKEN Cookie 鉴权（withCredentials=true 自动携带）
// skipAuthError: true — 路由守卫 fetchUserInfo 调用此接口，
// 如果 Token 过期/无效，由路由守卫自行处理跳转登录页，不触发 handleUnauthorized 弹窗
export function getCurrentUser() {
  return request({
    url: '/auth/me',
    method: 'get',
    skipAuthError: true
  })
}

// 刷新 Token
// 后端 AuthController.refreshToken 从 Cookie 读取旧 Token，刷新后写入新 Cookie
// 前端无需也无需持有 Token
export function refreshToken() {
  return request({
    url: '/auth/sessions/refresh',
    method: 'post'
  })
}

// 用户登出（skipAuthError: 退出时Token可能已过期，不触发401弹窗）
// 后端 AuthController.logout 从 Cookie 读取 Token，递增 token_version 后清除 Cookie
export function logout() {
  return request({
    url: '/auth/sessions',
    method: 'delete',
    skipAuthError: true
  })
}
