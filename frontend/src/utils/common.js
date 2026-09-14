// ============================================================
// 工单系统统一枚举常量（OPTIMIZATION 四.4.2 枚举字段字典化）
// ------------------------------------------------------------
// 与后端 Integer 枚举码完全对齐：
//   WorkOrderStatusEnum   1=DRAFT 2=PENDING 3=APPROVED 4=REJECTED 5=ARCHIVED 6=TERMINATED
//   OrderTypeEnum         1=LEAVE 2=PURCHASE 3=REIMBURSEMENT 4=REPAIR 5=SUPPLY 6=OTHER
//   ApprovalActionEnum    1=SUBMIT 2=APPROVE 3=REJECT 4=RETURN 5=ARCHIVE 6=TERMINATE 7=RESUBMIT 8=TRANSFER
// ============================================================

// 格式化日期时间
export function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 格式化日期
export function formatDate(dateStr) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

// ============================================================
// 工单状态枚举（与后端 WorkOrderStatusEnum 对齐）
// ============================================================
export const WORK_ORDER_STATUS = {
  DRAFT: 1,        // 草稿
  PENDING: 2,      // 审批中
  APPROVED: 3,     // 已通过
  REJECTED: 4,     // 已驳回
  ARCHIVED: 5,     // 已归档
  TERMINATED: 6    // 已终止
}

// 工单状态映射（key 为 Integer 枚举码）
export const ORDER_STATUS_MAP = {
  1: { label: '草稿', type: 'info' },
  2: { label: '审批中', type: 'warning' },
  3: { label: '已通过', type: 'success' },
  4: { label: '已驳回', type: 'danger' },
  5: { label: '已归档', type: 'info' },
  6: { label: '已终止', type: 'danger' }
}

// 工单状态下拉选项（不含"全部"，由调用方按需添加）
export const ORDER_STATUS_OPTIONS = [
  { value: WORK_ORDER_STATUS.DRAFT, label: '草稿' },
  { value: WORK_ORDER_STATUS.PENDING, label: '审批中' },
  { value: WORK_ORDER_STATUS.APPROVED, label: '已通过' },
  { value: WORK_ORDER_STATUS.REJECTED, label: '已驳回' },
  { value: WORK_ORDER_STATUS.ARCHIVED, label: '已归档' },
  { value: WORK_ORDER_STATUS.TERMINATED, label: '已终止' }
]

// ============================================================
// 工单类型枚举（与后端 OrderTypeEnum 对齐）
// ============================================================
export const WORK_ORDER_TYPE = {
  LEAVE: 1,         // 请假
  PURCHASE: 2,      // 采购
  REIMBURSEMENT: 3, // 报销
  REPAIR: 4,        // 维修
  SUPPLY: 5,        // 补货
  OTHER: 6          // 其他
}

// 工单类型映射（key 为 Integer 枚举码）
export const ORDER_TYPE_MAP = {
  1: { label: '请假', icon: 'Calendar' },
  2: { label: '采购', icon: 'ShoppingCart' },
  3: { label: '报销', icon: 'Money' },
  4: { label: '报修', icon: 'Tools' },
  5: { label: '物资申领', icon: 'Box' },
  6: { label: '其他', icon: 'Document' }
}

// 工单类型下拉选项（用于创建工单）
export const ORDER_TYPE_OPTIONS = [
  { value: WORK_ORDER_TYPE.LEAVE, label: '请假' },
  { value: WORK_ORDER_TYPE.PURCHASE, label: '采购' },
  { value: WORK_ORDER_TYPE.REIMBURSEMENT, label: '报销' },
  { value: WORK_ORDER_TYPE.REPAIR, label: '报修' },
  { value: WORK_ORDER_TYPE.SUPPLY, label: '物资申领' },
  { value: WORK_ORDER_TYPE.OTHER, label: '其他' }
]

// 各工单类型允许上传的文件格式（key 为 Integer 枚举码）
export const ORDER_TYPE_FILE_EXTENSIONS = {
  [WORK_ORDER_TYPE.LEAVE]: ['jpg', 'jpeg', 'png', 'gif', 'pdf'],
  [WORK_ORDER_TYPE.PURCHASE]: ['jpg', 'jpeg', 'png', 'gif', 'pdf', 'xlsx', 'xls', 'csv'],
  [WORK_ORDER_TYPE.REIMBURSEMENT]: ['jpg', 'jpeg', 'png', 'gif', 'pdf', 'xlsx', 'xls', 'csv'],
  [WORK_ORDER_TYPE.REPAIR]: ['jpg', 'jpeg', 'png', 'gif', 'pdf', 'docx', 'doc'],
  [WORK_ORDER_TYPE.SUPPLY]: ['jpg', 'jpeg', 'png', 'gif', 'pdf', 'xlsx', 'xls'],
  [WORK_ORDER_TYPE.OTHER]: ['jpg', 'jpeg', 'png', 'gif', 'pdf', 'docx', 'doc', 'xlsx', 'xls', 'txt', 'csv']
}

// 全局禁止的文件扩展名（危险格式）
export const DANGEROUS_FILE_EXTS = [
  'exe', 'bat', 'cmd', 'sh', 'ps1', 'vbs', 'js', 'jar',
  'zip', 'rar', '7z', 'tar', 'gz', 'bz2',
  'msi', 'scr', 'com', 'pif', 'hta', 'cpl'
]

// 优先级映射（高=橙色标红、紧急=深红色实心，方便审批端快速识别）
export const PRIORITY_MAP = {
  1: { label: '低', type: 'info', effect: 'plain' },
  2: { label: '中', type: '', effect: 'plain' },
  3: { label: '高', type: 'warning', effect: 'dark' },
  4: { label: '紧急', type: 'danger', effect: 'dark' }
}

// ============================================================
// 审批操作枚举（与后端 ApprovalActionEnum 对齐）
// ============================================================
export const APPROVAL_ACTION = {
  SUBMIT: 1,      // 提交
  APPROVE: 2,     // 通过
  REJECT: 3,      // 驳回
  RETURN: 4,      // 退回
  ARCHIVE: 5,     // 归档
  TERMINATE: 6,   // 终止
  RESUBMIT: 7,    // 重新提交
  TRANSFER: 8     // 转办
}

// 审批操作映射（key 为 Integer 枚举码）
export const ACTION_MAP = {
  1: { label: '提交', color: '#1677FF' },
  2: { label: '通过', color: '#67C23A' },
  3: { label: '驳回', color: '#F56C6C' },
  4: { label: '退回', color: '#E6A23C' },
  5: { label: '归档', color: '#909399' },
  6: { label: '终止', color: '#F56C6C' },
  7: { label: '重提', color: '#1677FF' },
  8: { label: '转办', color: '#909399' }
}

// ============================================================
// 工具函数：根据 Integer 枚举码获取标签
// ============================================================

/** 获取工单状态标签 */
export function getStatusLabel(statusCode) {
  return ORDER_STATUS_MAP[statusCode]?.label || '未知'
}

/** 获取工单类型标签 */
export function getOrderTypeLabel(typeCode) {
  return ORDER_TYPE_MAP[typeCode]?.label || '未知'
}

/** 获取审批操作标签 */
export function getActionLabel(actionCode) {
  return ACTION_MAP[actionCode]?.label || '未知'
}
