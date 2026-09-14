<template>
  <div class="page-container">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-form :inline="true" :model="queryParams" class="filter-form">
        <el-form-item label="状态">
          <el-select v-model="statusValue" placeholder="全部" style="width: 90px">
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="类型">
          <el-select v-model="typeValue" placeholder="全部" style="width: 90px">
            <el-option
              v-for="item in typeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="关键词">
          <el-input
            v-model="queryParams.keyword"
            placeholder="标题/编号/申请人"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="default" class="search-btn" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button class="reset-btn" :icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 数据表格 -->
    <div class="table-card">
      <el-table v-loading="loading" :data="orderList" border stripe>
        <el-table-column prop="orderNo" label="工单编号" width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="order-no-copy" @click="copyOrderNo(row.orderNo)" title="点击复制">{{ row.orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="工单标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="applicantName" label="申请人" width="100" align="center" show-overflow-tooltip />
        <el-table-column prop="department" label="部门" width="100" align="center" />
        <el-table-column prop="orderType" label="类型" width="80" align="center">
          <template #default="{ row }">
            {{ ORDER_TYPE_MAP[row.orderType]?.label || row.orderType }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="85" align="center">
          <template #default="{ row }">
            <span :class="`status-text status-${row.status}`">
              {{ ORDER_STATUS_MAP[row.status]?.label || row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="currentAssigneeName" label="当前审批人" width="100" align="center" />
        <el-table-column prop="submitTime" label="提交时间" width="155" align="center">
          <template #default="{ row }">{{ formatDateTime(row.submitTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 极简纯文字入口 -->
            <a class="action-link" @click="viewDetail(row.id)">查看</a>
            <span class="action-divider">/</span>
            <a class="action-link" @click="viewLog(row.id)">日志</a>
            <template v-if="row.status === WORK_ORDER_STATUS.PENDING">
              <span class="action-divider">/</span>
              <a class="action-link success-link" @click="quickApprove(row)">审批</a>
            </template>
            <template v-if="row.status === WORK_ORDER_STATUS.PENDING || row.status === WORK_ORDER_STATUS.REJECTED">
              <span class="action-divider">/</span>
              <a class="action-link danger" @click="handleTerminate(row)">终止</a>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页：数据为空时隐藏 -->
      <div v-if="total > 0" class="pagination-container">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="sizes, prev, pager, next"
          @size-change="fetchData"
          @current-change="fetchData"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getAllWorkOrders, terminateWorkOrder, handleApproval } from '@/api/workorder'
import { formatDateTime, ORDER_STATUS_MAP, ORDER_TYPE_MAP, ORDER_STATUS_OPTIONS, ORDER_TYPE_OPTIONS, WORK_ORDER_STATUS } from '@/utils/common'

const router = useRouter()
const loading = ref(false)
const orderList = ref([])
const total = ref(0)

// 状态选项列表（OPTIMIZATION：使用 Integer 枚举码）
const statusOptions = [
  { label: '全部', value: '' },
  ...ORDER_STATUS_OPTIONS.filter(opt => opt.value !== WORK_ORDER_STATUS.DRAFT)
]

// 类型选项列表（OPTIMIZATION：使用 Integer 枚举码）
const typeOptions = [
  { label: '全部', value: '' },
  ...ORDER_TYPE_OPTIONS
]

// 使用独立的ref存储选中值（确保响应式）
const statusValue = ref('')
const typeValue = ref('')

// 查询参数
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: ''
})

// 计算实际查询参数
function getQueryParams() {
  return {
    ...queryParams,
    status: statusValue.value,
    orderType: typeValue.value
  }
}

async function fetchData() {
  loading.value = true
  try {
    const res = await getAllWorkOrders(getQueryParams())
    orderList.value = res.data.list || []
    total.value = res.data.total || 0
  } catch (error) {
    // 获取工单列表失败
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  queryParams.pageNum = 1
  fetchData()
}

function resetQuery() {
  statusValue.value = ''       // 重置为"全部"
  typeValue.value = ''         // 重置为"全部"
  queryParams.keyword = ''
  queryParams.pageNum = 1
  fetchData()
}

function viewDetail(id) {
  router.push(`/workorder/detail/${id}`)
}

function viewLog(id) {
  router.push(`/process/log/${id}`)
}

async function handleTerminate(row) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `请输入终止工单「${row.title}」的原因`,
      '终止流程',
      {
        confirmButtonText: '确定终止',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入终止原因（可选）',
        type: 'warning'
      }
    )

    // 使用用户输入的原因或默认空字符串
    const terminateReason = reason && reason.trim() ? reason.trim() : ''

    await terminateWorkOrder(row.id, terminateReason)
    ElMessage.success('流程已终止')
    fetchData()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      // 显示具体的错误信息
      const errorMsg = error.response?.data?.msg || error.message || '终止失败，请重试'
      ElMessage.error(errorMsg)
    }
  }
}

// 快捷审批（从列表直接审批，跳转到详情页的审批区）
function quickApprove(row) {
  router.push(`/workorder/detail/${row.id}?action=approve`)
}

// 复制工单编号
async function copyOrderNo(orderNo) {
  if (!orderNo) return
  try {
    await navigator.clipboard.writeText(orderNo)
    ElMessage.success('编号已复制: ' + orderNo)
  } catch (err) {
    // 降级方案
    const textarea = document.createElement('textarea')
    textarea.value = orderNo
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success('编号已复制: ' + orderNo)
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped lang="scss">
:deep(.el-table) {
  table {
    table-layout: fixed;
  }

  .el-table__cell {
    .cell {
      white-space: nowrap !important;
      overflow: hidden;
      text-overflow: ellipsis;

      &:has(.action-link) {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 0;
      }
    }
  }
}

/* 按钮样式 - 白底风格 */
.search-btn {
  color: #5b8ff9;
  border-color: #c0cee8;
  background-color: #fff;

  &:hover {
    color: #4a7ae8;
    border-color: #5b8ff9;
    background-color: #f5f8fc;
  }
}

.reset-btn {
  color: #606b7c;
  border-color: #d0d5dd;
  background-color: #fff;

  &:hover {
    color: #404a58;
    border-color: #a8b0ba;
    background-color: #f7f8fa;
  }
}

/* 操作链接 - 默认灰色，悬浮变色 */
.action-link {
  color: #4e5969;
  cursor: pointer;
  font-size: 13px;
  transition: color 0.2s;

  &:hover {
    color: #5b8ff9;
  }

  &.danger {
    &:hover {
      color: #f53f3f;
    }
  }

  /* 审批按钮 - 悬浮时才显示绿色 */
  &.success-link {
    color: #4e5969;

    &:hover {
      color: #00b42a;
    }
  }
}

/* 状态文字颜色（OPTIMIZATION：使用 Integer 枚举码作为 class 后缀） */
.status-text {
  font-size: 13px;

  /* 2=PENDING(审批中) */
  &.status-2 {
    color: #1677ff;
  }

  /* 3=APPROVED(已通过), 5=ARCHIVED(已归档) */
  &.status-3,
  &.status-5 {
    color: #00b42a;
  }

  /* 4=REJECTED(已驳回), 6=TERMINATED(已终止) */
  &.status-4,
  &.status-6 {
    color: #f53f3f;
  }
}

/* 工单编号 - 可点击复制 */
.order-no-copy {
  font-family: 'Consolas', 'Monaco', monospace;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
  transition: all 0.2s;

  &:hover {
    background-color: #e8f3ff;
    color: #1677ff;
  }
}
</style>
