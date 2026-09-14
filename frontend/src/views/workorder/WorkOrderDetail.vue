<template>
  <div class="page-container">
    <!-- 页面标题栏 -->
    <div class="detail-header">
      <el-button text @click="$router.back()" class="back-btn">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <span class="header-title">工单详情</span>
    </div>

    <div class="table-card" v-loading="loading">
      <template v-if="workOrder">
        <!-- 基本信息表格 -->
        <div class="info-section">
          <div class="section-label">基本信息</div>
          <table class="info-table">
            <tbody>
              <tr>
                <td class="label-cell">工单编号</td>
                <td class="value-cell">
                  <span class="order-no" @click="copyOrderNo" title="点击复制">{{ workOrder.orderNo }}</span>
                </td>
                <td class="label-cell">当前状态</td>
                <td class="value-cell">
                  <span :class="['status-tag', `status-${workOrder.status}`]">
                    {{ ORDER_STATUS_MAP[workOrder.status]?.label || workOrder.status }}
                  </span>
                </td>
              </tr>
              <tr>
                <td class="label-cell">工单标题</td>
                <td class="value-cell" colspan="3" style="font-weight:500;">{{ workOrder.title }}</td>
              </tr>
              <tr>
                <td class="label-cell">工单类型</td>
                <td class="value-cell">{{ ORDER_TYPE_MAP[workOrder.orderType]?.label || workOrder.orderType }}</td>
                <td class="label-cell">优先级</td>
                <td class="value-cell">
                  <span :class="`priority-${workOrder.priority}`">{{ PRIORITY_MAP[workOrder.priority]?.label || workOrder.priority }}</span>
                </td>
              </tr>
              <tr>
                <td class="label-cell">申请人</td>
                <td class="value-cell">{{ workOrder.applicantName }}</td>
                <td class="label-cell">申请部门</td>
                <td class="value-cell">{{ workOrder.department }}</td>
              </tr>
              <tr>
                <td class="label-cell">当前审批人</td>
                <td class="value-cell">{{ workOrder.currentAssigneeName || '-' }}</td>
                <td class="label-cell">当前节点</td>
                <td class="value-cell node-name">{{ workOrder.currentNode || '-' }}</td>
              </tr>
              <tr>
                <td class="label-cell">提交时间</td>
                <td class="value-cell">{{ formatDateTime(workOrder.submitTime) }}</td>
                <td class="label-cell">完成时间</td>
                <td class="value-cell">{{ formatDateTime(workOrder.completeTime) || '-' }}</td>
              </tr>
              <tr v-if="workOrder.amount != null">
                <td class="label-cell">金额(元)</td>
                <td class="value-cell amount-value">{{ Number(workOrder.amount).toLocaleString() }}</td>
                <td class="label-cell"></td>
                <td class="value-cell"></td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 工单内容 -->
        <div class="info-section">
          <div class="section-label">工单内容</div>
          <div class="content-area">{{ workOrder.content || '无' }}</div>
        </div>

        <!-- 备注 -->
        <div class="info-section" v-if="workOrder.remark">
          <div class="section-label">备注信息</div>
          <div class="content-area remark-area">{{ workOrder.remark }}</div>
        </div>

        <!-- 审批操作区 -->
        <template v-if="showApprovalActions">
          <div class="info-section approval-box">
            <div class="section-label">审批操作</div>
            <el-input
              v-model="approvalComment"
              type="textarea"
              :rows="3"
              placeholder="请输入审批意见"
              maxlength="500"
              show-word-limit
            />
            <div class="btn-group">
              <button class="op-btn btn-pass" @click="handleApprove">通过</button>
              <button class="op-btn btn-reject" @click="handleReject">驳回</button>
              <button class="op-btn btn-return" @click="handleReturn">退回</button>
            </div>
          </div>
        </template>

        <!-- 重新提交 -->
        <template v-if="workOrder.status === WORK_ORDER_STATUS.REJECTED && isApplicant">
          <div class="info-section resubmit-box">
            <button class="op-btn btn-primary" @click="handleResubmit">重新提交</button>
          </div>
        </template>

        <!-- 相关操作链接 -->
        <div class="info-section link-box">
          <a @click="$router.push(`/process/log/${workOrder.id}`)">审批日志</a>
          <span class="sep">|</span>
          <a @click="$router.push(`/process/visualize/${workOrder.id}`)">流程可视化</a>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getWorkOrderDetail, handleApproval, resubmitWorkOrder } from '@/api/workorder'
import { formatDateTime, ORDER_STATUS_MAP, ORDER_TYPE_MAP, PRIORITY_MAP, WORK_ORDER_STATUS } from '@/utils/common'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const workOrder = ref(null)
const approvalComment = ref('')

// 是否为申请人
const isApplicant = computed(() => {
  return workOrder.value?.applicantId === userStore.userInfo.userId
})

// 是否显示审批操作（OPTIMIZATION：status 改为 Integer 枚举码）
const showApprovalActions = computed(() => {
  if (!workOrder.value) return false
  if (workOrder.value.status !== WORK_ORDER_STATUS.PENDING) return false
  if (isApplicant.value) return false
  const permissions = userStore.userInfo.permissions || []
  // 修复（2026-07-26）：移除 system:user 检查 — 该权限是系统管理权限，
  // 不是审批权限。超管已拥有 workorder:approve，无需额外兜底。
  return permissions.includes('workorder:approve') ||
         permissions.includes('workorder:pending')
})

// 获取工单详情
async function fetchDetail() {
  loading.value = true
  try {
    const res = await getWorkOrderDetail(route.params.id)
    workOrder.value = res.data
    if (route.query.action === 'approve') {
      setTimeout(() => {
        document.querySelector('.approval-box')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }, 300)
    }
  } catch (error) {
    ElMessage.error(error.response?.data?.msg || '获取工单详情失败')
  } finally {
    loading.value = false
  }
}

// 复制编号
async function copyOrderNo() {
  if (!workOrder.value?.orderNo) return
  try {
    await navigator.clipboard.writeText(workOrder.value.orderNo)
    ElMessage.success('已复制: ' + workOrder.value.orderNo)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = workOrder.value.orderNo
    ta.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    ElMessage.success('已复制: ' + workOrder.value.orderNo)
  }
}

// 通过
async function handleApprove() {
  try {
    await ElMessageBox.confirm('确定通过该工单？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'info'
    })
    await handleApproval({
      workOrderId: workOrder.value.id,
      action: 'APPROVE',
      comment: approvalComment.value
    })
    ElMessage.success('已通过')
    approvalComment.value = ''
    fetchDetail()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.msg || e.message || '操作失败')
  }
}

// 驳回 - 必须填写原因
async function handleReject() {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '请输入驳回原因（必填）',
      '驳回工单',
      {
        confirmButtonText: '确定驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '请详细说明驳回原因...',
        inputPattern: /\S+/,
        inputErrorMessage: '驳回原因不能为空',
        type: 'warning'
      }
    )
    if (!reason || !reason.trim()) {
      ElMessage.warning('请输入驳回原因')
      return
    }
    await handleApproval({
      workOrderId: workOrder.value.id,
      action: 'REJECT',
      comment: reason.trim()
    })
    ElMessage.success('已驳回')
    approvalComment.value = ''
    fetchDetail()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e.response?.data?.msg || e.message || '驳回失败')
  }
}

// 退回
async function handleReturn() {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '请输入退回原因',
      '退回工单',
      {
        confirmButtonText: '确定退回',
        cancelButtonText: '取消',
        inputPlaceholder: '请说明退回原因',
        type: 'warning'
      }
    )
    await handleApproval({
      workOrderId: workOrder.value.id,
      action: 'RETURN',
      comment: reason
    })
    ElMessage.success('已退回')
    approvalComment.value = ''
    fetchDetail()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.msg || e.message || '退回失败')
  }
}

// 重新提交
async function handleResubmit() {
  try {
    const { value: comment } = await ElMessageBox.prompt(
      '请输入修改说明',
      '重新提交',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputPlaceholder: '请说明修改了哪些内容'
      }
    )
    await resubmitWorkOrder(workOrder.value.id, comment)
    ElMessage.success('已重新提交')
    fetchDetail()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.msg || e.message || '操作失败')
  }
}

onMounted(() => fetchDetail())
</script>

<style scoped lang="scss">
/* 头部 */
.detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
}

.back-btn {
  font-size: 14px;
  color: #4e5969;

  &:hover {
    color: #1677ff;
  }
}

/* 信息区块 */
.info-section {
  margin-bottom: 16px;
}

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: #1d2129;
  padding: 8px 0;
  border-bottom: 1px solid #e5e6eb;
  margin-bottom: 12px;
}

/* 信息表格 */
.info-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;

  td {
    padding: 10px 12px;
    border: 1px solid #e5e6eb;
    font-size: 13px;
    line-height: 1.6;
    vertical-align: middle;
  }

  .label-cell {
    width: 110px;
    background-color: #fafafa;
    color: #86909c;
    font-weight: normal;
  }

  .value-cell {
    background-color: #fff;
    color: #1d2129;
    word-break: break-all;
  }
}

/* 工单编号可点击 */
.order-no {
  font-family: Consolas, Monaco, monospace;
  cursor: pointer;
  color: #1677ff;

  &:hover {
    text-decoration: underline;
  }
}

/* 状态标签 - 简洁文字色 */
.status-tag {
  display: inline-block;
  padding: 2px 0;
  font-weight: 500;

  /* OPTIMIZATION：使用 Integer 枚举码作为 class 后缀 */
  &.status-2 { color: #1677ff; } /* PENDING(审批中) */
  &.status-3,
  &.status-5 { color: #00b42a; } /* APPROVED, ARCHIVED */
  &.status-4,
  &.status-6 { color: #f53f3f; } /* REJECTED, TERMINATED */
}

/* 优先级颜色 */
.priority-1 { color: #86909c; }
.priority-2 { color: #4e5969; }
.priority-3 { color: #ff7d00; font-weight: 500; }
.priority-4 { color: #f53f3f; font-weight: 500; }

/* 节点名称 */
.node-name { color: #1677ff; }

/* 金额 */
.amount-value {
  color: #f53f3f;
  font-weight: 600;
  font-family: DIN, Consolas, monospace;
}

/* 内容区域 */
.content-area {
  padding: 12px 16px;
  background-color: #fafafa;
  border: 1px solid #e5e6eb;
  line-height: 1.8;
  color: #4e5969;
  font-size: 13px;
  min-height: 48px;
  white-space: pre-wrap;
  word-break: break-all;
}

.remark-area {
  background-color: #fffbe6;
  border-color: #ffe58f;
  color: #ad6800;
}

/* 审批操作区 */
.approval-box {
  .section-label {
    color: #1677ff;
  }
}

.btn-group {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

/* 操作按钮 - 白底边框 */
.op-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 80px;
  height: 32px;
  padding: 0 20px;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid;
  background: #fff;

  &:hover {
    opacity: 0.85;
  }
}

.btn-pass {
  color: #00b42a;
  border-color: #7ce0a1;
  &:hover { background: #f0fdf4; border-color: #00b42a; }
}

.btn-reject {
  color: #f53f3f;
  border-color: #fc9797;
  &:hover { background: #fff2f0; border-color: #f53f3f; }
}

.btn-return {
  color: #ff7d00;
  border-color: #ffc069;
  &:hover { background: #fff7e8; border-color: #ff7d00; }
}

.btn-primary {
  color: #1677ff;
  border-color: #80adff;
  &:hover { background: #f2f7fe; border-color: #1677ff; }
}

/* 重新提交区 */
.resubmit-box {
  text-align: center;
  padding: 20px 0;
}

/* 链接区 */
.link-box {
  text-align: center;
  padding: 16px 0;
  border-top: 1px solid #e5e6eb;

  a {
    color: #1677ff;
    cursor: pointer;
    font-size: 13px;

    &:hover {
      text-decoration: underline;
    }
  }

  .sep {
    color: #c9cdd4;
    margin: 0 12px;
  }
}
</style>
