/**
 * 按钮级权限校验组合式方法（规范 §3.3.5 按钮权限、页面权限统一通过全局指令控制）
 *
 * 用法：
 *   import { usePermission } from '@/hooks/usePermission'
 *   const { hasPermission, hasRole, isSuperAdmin } = usePermission()
 *   if (hasPermission('workorder:approve')) { ... }
 *   if (isSuperAdmin.value) { ... }
 *
 * @returns {{
 *   permissions: string[],
 *   roles: string[],
 *   isSuperAdmin: import('vue').ComputedRef<boolean>,
 *   hasPermission: (code: string) => boolean,
 *   hasRole: (role: string) => boolean
 * }}
 *
 * 关键修复（2026-07-25）：
 *   1. 修复 roles 取值：原 `userStore.roles` 不存在（store 未暴露顶层 roles 属性），
 *      实际存储在 `userStore.userInfo.roles` 中。
 *   2. 兼容两种 roles 形态：login 返回字符串数组，getCurrentUser 返回对象数组（含 roleCode）。
 *   3. 新增 isSuperAdmin 计算属性，并让 hasPermission 对超管快捷放行。
 */
import { computed } from 'vue'
import { useUserStore } from '../store/user'

/**
 * 从 roles 列表中提取角色代码字符串数组
 * 兼容字符串数组与对象数组两种形态
 */
function extractRoleCodes(roles) {
  if (!Array.isArray(roles)) return []
  return roles.map(r => {
    if (r == null) return ''
    if (typeof r === 'string') return r
    return r.roleCode || r.code || ''
  }).filter(Boolean)
}

export function usePermission() {
  const userStore = useUserStore()
  const permissions = userStore.permissions || []
  const roles = extractRoleCodes(userStore.userInfo?.roles)

  const isSuperAdmin = computed(() => roles.includes('SUPER_ADMIN'))

  return {
    permissions,
    roles,
    isSuperAdmin,
    // 超管拥有全部权限，无需逐项检查权限码
    hasPermission: (code) => !!code && (isSuperAdmin.value || permissions.includes(code)),
    hasRole: (role) => !!role && roles.includes(role)
  }
}

export default usePermission
