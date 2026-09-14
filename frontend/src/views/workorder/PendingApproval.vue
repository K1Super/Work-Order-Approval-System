<template>
  <div class="page-container">
    <div class="table-card">
      <el-alert
        title="以下是需要您审批的工单，请及时处理"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />

      <el-table v-loading="loading" :data="pendingList" border stripe>
        <el-table-column prop="orderNo" label="工单编号" width="200" show-overflow-tooltip />
        <el-table-column prop="title" label="工单标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="applicantName" label="申请人" width="120" align="center" />
        <el-table-column prop="department" label="部门" width="120" align="center" />
        <el-table-column prop="orderType" label="类型" width="100" align="center">
          <template #default="{ row }">
            {{ ORDER_TYPE_MAP[row.orderType]?.label || row.orderType }}
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" align="center">
          <template #default="{ row }">
            <span :style="getPriorityStyle(row.priority)">
              {{ PRIORITY_MAP[row.priority]?.label || row.priority }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="currentNode" label="当前节点" width="140" align="center" />
        <el-table-column prop="submitTime" label="提交时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.submitTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <a class="action-link" @click="viewDetail(row.id)">查看</a>
            <span class="action-divider">/</span>
            <a class="action-link success" @click="handleApprove(row)">通过</a>
            <span class="action-divider">/</span>
            <a class="action-link danger" @click="handleReject(row)">驳回</a>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && pendingList.length === 0" description="暂无待审批工单" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPendingApprovalList, quickApprove, quickReject } from '@/api/workorder'
import { formatDateTime, ORDER_TYPE_MAP, PRIORITY_MAP } from '@/utils/common'

const router = useRouter()
const loading = ref(false)

// 优先级文字颜色（高=橙色、紧急=红色）
function getPriorityStyle(priority) {
  if (priority === 3) { return { color: '#E6A23C' } }
  if (priority === 4) { return { color: '#F56C6C' } }
  return {}
}

const pendingList = ref([])

// 获取待审批列表
async function fetchPendingList() {
  loading.value = true
  try {
    const res = await getPendingApprovalList()

    // 兼容多种数据格式：数组、对象、分页对象等
    if (Array.isArray(res.data)) {
      pendingList.value = res.data
    } else if (res.data && Array.isArray(res.data.list)) {
      pendingList.value = res.data.list
    } else if (res.data && typeof res.data === 'object') {
      // 如果是单个对象，包装成数组
      pendingList.value = [res.data]
    } else {
      pendingList.value = []
    }
  } catch (error) {
    ElMessage.error('获取待审批列表失败: ' + (error.message || '未知错误'))
    pendingList.value = []
  } finally {
    loading.value = false
  }
}

// 查看详情
function viewDetail(id) {
  router.push(`/workorder/detail/${id}`)
}

// 审批通过
async function handleApprove(row) {
  try {
    await ElMessageBox.confirm(`确定要通过工单「${row.title}」吗？`, '审批确认', {
      confirmButtonText: '确定通过',
      cancelButtonText: '取消',
      type: 'success'
    })

    loading.value = true
    try {
      await quickApprove(row.id, '')
      ElMessage.success('审批通过')
      fetchPendingList()
    } catch (error) {
      ElMessage.error(error.response?.data?.msg || '审批失败，请重试')
    } finally {
      loading.value = false
    }
  } catch (error) {
    if (error !== 'cancel') {
      // 用户取消确认
    }
  }
}

// 审批驳回
async function handleReject(row) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `请输入驳回工单「${row.title}」的原因`,
      '驳回原因',
      {
        confirmButtonText: '确定驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入驳回原因（可选）',
        type: 'warning'
      }
    )

    // 使用用户输入的原因或默认空字符串
    const rejectReason = reason && reason.trim() ? reason.trim() : ''

    loading.value = true
    try {
      await quickReject(row.id, rejectReason)
      ElMessage.success('已驳回')
      fetchPendingList()
    } catch (error) {
      // 显示具体的错误信息
      const errorMsg = error.response?.data?.msg || error.message || '驳回失败，请重试'
      ElMessage.error(errorMsg)
    } finally {
      loading.value = false
    }
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      // 用户取消确认
    }
  }
}

onMounted(() => {
  fetchPendingList()
})
</script>
