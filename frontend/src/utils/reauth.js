/**
 * 二次鉴权工具（规范 §8 安全开发规范）
 *
 * 后端 SensitiveOperationAspect 拦截标注 @RequireReAuth 的敏感操作方法，
 * 要求请求头 X-Reauth-Password 携带当前用户密码进行二次确认。
 *
 * 本工具提供：
 * 1. confirmReauth() — 弹出密码确认对话框，返回用户输入的密码
 * 2. withReauth(requestFn, action) — 封装二次鉴权流程，自动添加请求头
 *
 * 使用示例：
 *   import { withReauth } from '@/utils/reauth'
 *   await withReauth(
 *     (password) => request.delete('/employees/1', { headers: { 'X-Reauth-Password': password } }),
 *     '删除员工'
 *   )
 */
import { ElMessageBox } from 'element-plus'

/** 二次鉴权密码请求头名称（与后端 SensitiveOperationAspect.REAUTH_PASSWORD_HEADER 对齐） */
export const REAUTH_PASSWORD_HEADER = 'X-Reauth-Password'

/**
 * 弹出二次鉴权密码确认对话框
 *
 * @param {string} actionDescription 操作描述（如"删除员工"、"重置密码"）
 * @returns {Promise<string|null>} 用户输入的密码；用户取消返回 null
 */
export async function confirmReauth(actionDescription = '敏感操作') {
  try {
    const { value } = await ElMessageBox.prompt(
      `为了保障账户安全，请输入您的登录密码以确认${actionDescription}操作。`,
      '二次鉴权确认',
      {
        confirmButtonText: '确认操作',
        cancelButtonText: '取消',
        inputType: 'password',
        inputPlaceholder: '请输入登录密码',
        inputValidator: (val) => {
          if (!val || val.trim().length === 0) {
            return '密码不能为空'
          }
          return true
        },
        inputErrorMessage: '密码不能为空',
        type: 'warning',
        center: true,
        customClass: 'reauth-dialog',
        beforeClose: (action, instance, done) => {
          if (action === 'confirm') {
            instance.confirmButtonLoading = true
            instance.confirmButtonText = '验证中...'
            setTimeout(() => {
              instance.confirmButtonLoading = false
              instance.confirmButtonText = '确认操作'
              done()
            }, 300)
          } else {
            done()
          }
        }
      }
    )
    return value || null
  } catch {
    // 用户点击取消
    return null
  }
}

/**
 * 封装二次鉴权流程
 *
 * 流程：
 * 1. 弹出密码确认对话框
 * 2. 用户输入密码后，将密码传递给请求函数
 * 3. 请求函数通过返回的密码设置 X-Reauth-Password 请求头
 *
 * @param {(password: string) => Promise} requestFn 接收密码参数的请求函数
 * @param {string} actionDescription 操作描述（用于对话框提示）
 * @returns {Promise} 请求函数的返回值；用户取消则 reject
 */
export async function withReauth(requestFn, actionDescription = '敏感操作') {
  const password = await confirmReauth(actionDescription)
  if (password === null) {
    throw new Error('用户取消二次鉴权')
  }
  return requestFn(password)
}

export default { confirmReauth, withReauth, REAUTH_PASSWORD_HEADER }
