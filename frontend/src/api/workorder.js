import request from '@/utils/request'

// 创建工单草稿
export function createDraft(data) {
  return request({
    url: '/work-orders/draft',
    method: 'post',
    data
  })
}

// 提交新工单
export function submitWorkOrder(data) {
  return request({
    url: '/work-orders',
    method: 'post',
    data
  })
}

// 提交草稿工单
export function submitDraft(id) {
  return request({
    url: `/work-orders/${id}/submit`,
    method: 'post'
  })
}

// 查询工单详情
export function getWorkOrderDetail(id) {
  return request({
    url: `/work-orders/${id}`,
    method: 'get'
  })
}

// 分页查询我的工单
export function getMyWorkOrders(params) {
  return request({
    url: '/work-orders/my',
    method: 'get',
    params
  })
}

// 查询所有工单（管理员）
export function getAllWorkOrders(params) {
  return request({
    url: '/work-orders/list',
    method: 'get',
    params
  })
}

// 查询待我审批的工单
export function getPendingApprovalList() {
  return request({
    url: '/work-orders/pending',
    method: 'get'
  })
}

// 审批操作（通过/驳回/退回）
export function handleApproval(data) {
  return request({
    url: '/approvals',
    method: 'post',
    data
  })
}

// 快捷审批通过
export function quickApprove(id, comment = '') {
  return request({
    url: `/approvals/${id}/approve`,
    method: 'post',
    params: { comment }
  })
}

// 快捷驳回
export function quickReject(id, reason) {
  return request({
    url: `/approvals/${id}/reject`,
    method: 'post',
    params: { reason }
  })
}

// 查询审批日志
export function getApprovalLog(workOrderId) {
  return request({
    url: `/approvals/${workOrderId}/logs`,
    method: 'get'
  })
}

// 重新提交工单
export function resubmitWorkOrder(id, comment) {
  return request({
    url: `/work-orders/${id}/resubmit`,
    method: 'post',
    params: { comment }
  })
}

// 终止流程
export function terminateWorkOrder(id, reason) {
  return request({
    url: `/work-orders/${id}/terminate`,
    method: 'post',
    params: { reason }
  })
}

// 撤回工单（删除）
export function withdrawWorkOrder(id) {
  return request({
    url: `/work-orders/${id}/withdraw`,
    method: 'post'
  })
}

// 查询工单当前任务信息（OPTIMIZATION 一 架构解耦）
// 工单表已移除 current_node/current_assignee 等流程运行时字段，
// 通过此接口实时获取当前审批节点和审批人
export function getWorkOrderCurrentTask(id) {
  return request({
    url: `/work-orders/${id}/current-task`,
    method: 'get'
  })
}
