import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, getCurrentUser, logout as apiLogout, refreshToken } from '@/api/auth'
import { getCachedUserInfo, setCachedUserInfo, removeToken } from '@/utils/auth'
import { resetRouter } from '@/router'

// ============================================================
// OPTIMIZATION 三.3.1 用户状态管理（Token 迁移至 HttpOnly Cookie）
// ============================================================
// 改造点：
// 1. 移除 token 字段（Token 由后端 HttpOnly Cookie 管理，前端 JS 无法读取）
// 2. isLoggedIn 改为基于 userInfo.userId 判断（而非 token 是否存在）
// 3. userInfo 缓存从 localStorage 迁移至 sessionStorage（防 XSS 持久窃取）
// 4. login()/setLoginResult 不再调用 setToken（后端 Set-Cookie 自动写入）
// 5. logout() 调用后端 /auth/logout 由 AuthController.clearAuthCookie 清除 Cookie
// 6. refreshToken() 由后端 /auth/refresh-token 自动更新 Cookie，无需前端操作
// ============================================================

export const useUserStore = defineStore('user', () => {
  // 状态 - 从 sessionStorage 恢复（不再持有 Token）
  const userInfo = ref(getCachedUserInfo() || {})
  const permissions = ref((getCachedUserInfo()?.permissions) || [])
  // 会话级标记：本次页面加载是否已向后端验证过Token。
  // ref 在模块重新加载（刷新页面）时会重置为 false，因此每次打开页面都会强制校验。
  const isUserInfoLoaded = ref(false)

  // 计算属性：基于 userInfo.userId 判断登录状态（替代原 token 检查）
  // 兼容 Login.vue setLoginResult 后立即判断的场景
  const isLoggedIn = computed(() => !!userInfo.value?.userId)

  // 登录
  async function login(loginForm) {
    const res = await apiLogin(loginForm)
    setLoginResult(res.data)
    return res
  }

  // 直接保存登录结果（避免重复调用登录 API）
  // Login.vue 先调用 /auth/login 检查是否需要改密，确认无需改密后直接传入结果
  function setLoginResult(data) {
    // OPTIMIZATION 三.3.1：不再前端持有 Token
    // Token 由后端 AuthController.login 调用 AuthCookieUtil.setAuthCookie 写入 HttpOnly Cookie
    // （withCredentials=true 的 Axios 请求会自动携带该 Cookie）

    // 保存用户信息（包含权限，不含 Token）
    userInfo.value = {
      userId: data.userId,
      username: data.username,
      realName: data.realName,
      roles: data.roles,
      permissions: data.permissions || []
    }
    // 独立权限列表
    permissions.value = data.permissions || (data.roles?.flatMap(r => r.permissions) || [])

    // 缓存到 sessionStorage（刷新后恢复；Token 不缓存，由 Cookie 管理）
    setCachedUserInfo(userInfo.value)
    // 登录成功后本次会话已验证
    isUserInfoLoaded.value = true
  }

  // 获取用户信息
  async function fetchUserInfo() {
    try {
      const res = await getCurrentUser()
      userInfo.value = res.data
      permissions.value = res.data.permissions || []
      setCachedUserInfo(userInfo.value)
      // 后端校验通过，标记本次会话已加载
      isUserInfoLoaded.value = true
      return res
    } catch (error) {
      // 校验失败，确保标记为未加载
      isUserInfoLoaded.value = false
      throw error
    }
  }

  // 登出
  async function logout() {
    try {
      // 调用后端 /auth/logout：
      // 1. AuthService.logout 递增 token_version（旧 Token 立即失效）
      // 2. AuthController.logout 调用 AuthCookieUtil.clearAuthCookie 清除 Cookie
      await apiLogout()
    } finally {
      // 无论API是否成功，都清除本地状态
      resetState()
    }
  }

  // 重置状态
  function resetState() {
    userInfo.value = {}
    permissions.value = []
    isUserInfoLoaded.value = false
    removeToken()
    // W-45：回收动态权限路由，防止同标签换账号后残留上一个账号的越权路由
    resetRouter()
  }

  // 刷新 Token（OPTIMIZATION 三.3.1：后端自动更新 Cookie）
  async function refreshAccessToken() {
    try {
      // 后端 /auth/refresh-token 由 AuthController 调用 setAuthCookie 自动更新 Cookie
      // 前端无需也无需持有 Token
      const res = await refreshToken()
      // 更新 userInfo（如有）
      if (res.data) {
        userInfo.value = { ...userInfo.value, ...res.data }
        setCachedUserInfo(userInfo.value)
      }
      return res.data
    } catch (error) {
      resetState()
      throw error
    }
  }

  return {
    userInfo,
    permissions,
    isLoggedIn,
    isUserInfoLoaded,
    login,
    setLoginResult,
    fetchUserInfo,
    logout,
    resetState,
    refreshAccessToken
  }
})
