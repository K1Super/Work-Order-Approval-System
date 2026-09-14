/**
 * v-permission 指令：按钮/元素级权限控制（规范 §3.3.5 按钮权限统一通过全局指令控制）
 *
 * 根据当前用户权限码列表判断是否渲染元素，无权限则从 DOM 移除。
 * 权限码与后端 sys_permission.permission_code 一一对应。
 *
 * 用法：
 *   <el-button v-permission="'workorder:approve'">审批</el-button>
 *   <el-button v-permission="['workorder:approve', 'workorder:withdraw']">审批或撤回</el-button>
 *
 * 注意：Pinia store 在指令首次求值时必须已初始化（main.js 中 app.use(pinia) 先于指令使用）。
 *
 * 关键修复（2026-07-25）：
 *   1. 修复超管快捷放行失效：原代码 `userStore.roles` 不存在（store 未暴露顶层 roles 属性），
 *      roles 实际存储在 `userStore.userInfo.roles` 中，导致快捷放行分支永远不进入。
 *   2. 修复角色代码不一致：原检查 'SUPER_ADMIN'，但后端 getCurrentUser 返回的 roles
 *      是对象数组（含 roleCode 字段），需统一提取 roleCode 后再判断。
 *   3. 兼容两种 roles 形态：login 返回字符串数组 ['SUPER_ADMIN']，
 *      getCurrentUser 返回对象数组 [{roleCode: 'SUPER_ADMIN'}]。
 */
import { useUserStore } from '../store/user'

/**
 * 从 roles 列表中提取角色代码字符串数组
 * 兼容两种形态：
 *   - 字符串数组：['SUPER_ADMIN', 'HR_DIR']
 *   - 对象数组：[{roleCode: 'SUPER_ADMIN'}, {roleCode: 'HR_DIR'}]
 */
function extractRoleCodes(roles) {
  if (!Array.isArray(roles)) return []
  return roles.map(r => {
    if (r == null) return ''
    if (typeof r === 'string') return r
    // 兼容对象形态：优先 roleCode，其次 code
    return r.roleCode || r.code || ''
  }).filter(Boolean)
}

function hasPermission(requiredCodes) {
  const codes = Array.isArray(requiredCodes) ? requiredCodes : [requiredCodes]
  if (!codes.length) return true
  const userStore = useUserStore()
  const userPermissions = userStore.permissions || []

  // 超管快捷放行（修复：从 userInfo.roles 取角色代码）
  // 后端 SUPER_ADMIN 角色拥有全部权限，无需逐项检查权限码
  const roleCodes = extractRoleCodes(userStore.userInfo?.roles)
  if (roleCodes.includes('SUPER_ADMIN')) return true

  return codes.some((code) => userPermissions.includes(code))
}

export const permission = {
  mounted: (el, binding) => {
    if (!hasPermission(binding.value)) {
      el.parentNode && el.parentNode.removeChild(el)
    }
  }
}

export default permission
