/**
 * 全局常量、枚举、状态映射（规范 §3.1 src/constants）
 *
 * 集中托管前端硬编码常量，消除魔法值（对齐后端 Result.java 业务码）。
 */

/**
 * 后端统一业务码 — 旧数值码（@Deprecated 过渡期，使用 BIZ_CODE 替代）
 * 前端拦截器/视图层判断业务成功或异常时统一引用，禁止散落魔法数字。
 */
export const RESULT_CODE = Object.freeze({
  /** 操作成功 */
  SUCCESS: 20000,
  /** 参数非法 / 参数校验失败 */
  PARAM_INVALID: 40001,
  /** 未登录 / 令牌过期 / 认证失败 */
  UNAUTHORIZED: 40101,
  /** 权限不足 / 禁止访问 */
  FORBIDDEN: 40301,
  /** 请求资源 / 数据不存在 */
  NOT_FOUND: 40401,
  /** 系统内部异常 */
  SYSTEM_ERROR: 50001,
  /** 重复请求 / 防重复提交 */
  REPEAT_REQUEST: 60001
})

/**
 * 三段式字符串错误码（规范 §2 错误码规范 — 前端优先使用 bizCode 判断）
 * 与后端 ErrorCode 枚举对齐
 */
export const BIZ_CODE = Object.freeze({
  // ===== 公共模块 000 =====
  SUCCESS: '01-000-000',
  SYSTEM_ERROR: '01-000-001',
  PARAM_INVALID: '01-000-002',
  AUTH_FAILED: '01-000-003',
  ACCESS_DENIED: '01-000-004',
  RESOURCE_NOT_FOUND: '01-000-005',
  RATE_LIMITED: '01-000-006',
  REPEAT_REQUEST: '01-000-007',

  // ===== 认证模块 001 =====
  LOGIN_BAD_CREDENTIALS: '01-001-001',
  LOGIN_ACCOUNT_LOCKED: '01-001-002',
  LOGIN_ACCOUNT_DISABLED: '01-001-003',
  TOKEN_EXPIRED: '01-001-004',
  TOKEN_INVALID: '01-001-005',
  PASSWORD_CHANGE_FAILED: '01-001-006',
  REAUTH_FAILED: '01-001-007',

  // ===== 工单模块 002 =====
  WORKORDER_CREATE_FAILED: '01-002-001',
  WORKORDER_SUBMIT_FAILED: '01-002-002',
  WORKORDER_NOT_FOUND: '01-002-003',
  WORKORDER_STATUS_CONFLICT: '01-002-004',
  WORKORDER_VERSION_MISMATCH: '01-002-005',

  // ===== 审批模块 003 =====
  APPROVAL_ACTION_INVALID: '01-003-001',
  APPROVAL_NOT_ASSIGNEE: '01-003-002',

  // ===== 员工模块 004 =====
  EMPLOYEE_NOT_FOUND: '01-004-001',
  EMPLOYEE_USERNAME_EXISTS: '01-004-002',

  // ===== 密码模块 007 =====
  PASSWORD_RESET_TOKEN_INVALID: '01-007-001',
  PASSWORD_POLICY_VIOLATION: '01-007-002'
})

/**
 * 辅助函数：判断响应是否成功（双码制兼容）
 * 优先读取 bizCode，若无则回退读取 code
 *
 * @param {object} res 响应数据
 * @returns {boolean}
 */
export function isSuccess(res) {
  if (!res) return false
  if (res.bizCode !== undefined) {
    return res.bizCode === BIZ_CODE.SUCCESS
  }
  return res.code === RESULT_CODE.SUCCESS
}

/**
 * 辅助函数：判断是否未授权（双码制兼容）
 *
 * @param {object} res 响应数据
 * @returns {boolean}
 */
export function isUnauthorized(res) {
  if (!res) return false
  if (res.bizCode !== undefined) {
    return res.bizCode === BIZ_CODE.AUTH_FAILED
  }
  return res.code === RESULT_CODE.UNAUTHORIZED
}

/**
 * 工单状态映射（与后端 WorkOrderStatus 对齐）
 */
export const WORK_ORDER_STATUS = Object.freeze({
  DRAFT: 'DRAFT',
  PENDING: 'PENDING',
  APPROVING: 'APPROVING',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  RETURNED: 'RETURNED',
  TERMINATED: 'TERMINATED',
  WITHDRAWN: 'WITHDRAWN',
  ARCHIVED: 'ARCHIVED',
  COMPLETED: 'COMPLETED'
})

/**
 * 通用分页默认值
 */
export const PAGINATION = Object.freeze({
  DEFAULT_PAGE: 1,
  DEFAULT_PAGE_SIZE: 10,
  PAGE_SIZES: [10, 20, 50, 100]
})

export default { RESULT_CODE, BIZ_CODE, WORK_ORDER_STATUS, PAGINATION, isSuccess, isUnauthorized }
