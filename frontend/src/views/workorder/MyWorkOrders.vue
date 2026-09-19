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
            placeholder="标题/编号"
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

      <!-- 新建按钮与筛选栏对齐 -->
      <div class="toolbar-btn">
        <el-button type="default" class="create-btn" :icon="Plus" @click="$router.push('/workorder/create')">
          新建工单
        </el-button>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-card">
      <el-table v-loading="loading" :data="orderList" border stripe>
        <el-table-column prop="orderNo" label="工单编号" width="180" show-overflow-tooltip />
        <el-table-column prop="title" label="工单标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="orderType" label="类型" width="90" align="center">
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
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            {{ ORDER_STATUS_MAP[row.status]?.label || row.status }}
          </template>
        </el-table-column>
        <el-table-column prop="currentAssigneeName" label="当前审批人" width="110" align="center" />
        <el-table-column prop="submitTime" label="提交时间" width="160" align="center">
          <template #default="{ row }">{{ formatDateTime(row.submitTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 草稿状态：提交 / 查看 -->
            <template v-if="row.status === WORK_ORDER_STATUS.DRAFT">
              <a class="action-link success" @click="handleSubmitDraft(row)">提交</a>
              <span class="action-divider">/</span>
              <a class="action-link" @click="viewDetail(row.id)">查看</a>
            </template>
            <!-- 审批中：撤回 / 查看（企业级标准：允许申请人主动撤回） -->
            <template v-else-if="row.status === WORK_ORDER_STATUS.PENDING">
              <a class="action-link warning" @click="handleWithdraw(row)">撤回</a>
              <span class="action-divider">/</span>
              <a class="action-link" @click="viewDetail(row.id)">查看</a>
            </template>
            <!-- 已驳回：重提 / 查看 -->
            <template v-else-if="row.status === WORK_ORDER_STATUS.REJECTED">
              <a class="action-link success" @click="handleResubmit(row)">重提</a>
              <span class="action-divider">/</span>
              <a class="action-link" @click="viewDetail(row.id)">查看</a>
            </template>
            <!-- 已通过/已归档：仅查看 -->
            <template v-else>
              <a class="action-link" @click="viewDetail(row.id)">查看</a>
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
import { Search, Refresh, Plus } from '@element-plus/icons-vue'
import { getMyWorkOrders, submitDraft, resubmitWorkOrder, withdrawWorkOrder } from '@/api/workorder'
import { formatDateTime, ORDER_STATUS_MAP, ORDER_TYPE_MAP, PRIORITY_MAP, ORDER_STATUS_OPTIONS, ORDER_TYPE_OPTIONS, WORK_ORDER_STATUS } from '@/utils/common'
import { RESULT_CODE } from '@/constants'

const router = useRouter()
const loading = ref(false)
const orderList = ref([])
const total = ref(0)

// 状态选项列表（包含"全部"作为第一个选项，OPTIMIZATION：使用 Integer 枚举码）
const statusOptions = [
  { label: '全部', value: '' },
  ...ORDER_STATUS_OPTIONS
]

// 类型选项列表（OPTIMIZATION：使用 Integer 枚举码）
const typeOptions = [
  { label: '全部', value: '' },
  ...ORDER_TYPE_OPTIONS
]

// 使用独立的ref存储选中值（确保响应式）
const statusValue = ref('')
const typeValue = ref('')

// 优先级标签自定义样式（高=橙色、紧急=红色，!important强制覆盖Element Plus默认样式）
function getPriorityStyle(priority) {
  if (priority === 3) { return { color: '#E6A23C' } }
  if (priority === 4) { return { color: '#F56C6C' } }
  return {}
}

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

// 获取数据
async function fetchData() {
  loading.value = true
  try {
    const res = await getMyWorkOrders(getQueryParams())
    orderList.value = res.data.list || []
    total.value = res.data.total || 0
  } catch (error) {
    // 获取工单列表失败
  } finally {
    loading.value = false
  }
}

// 搜索
function handleSearch() {
  queryParams.pageNum = 1
  fetchData()
}

// 重置查询条件
function resetQuery() {
  statusValue.value = ''       // 重置为"全部"
  typeValue.value = ''         // 重置为"全部"
  queryParams.keyword = ''
  queryParams.pageNum = 1
  fetchData()
}

// 查看详情
function viewDetail(id) {
  router.push(`/workorder/detail/${id}`)
}

// 提交草稿
async function handleSubmitDraft(row) {
  try {
    await ElMessageBox.confirm(
      `确定要提交工单「${row.title}」吗？`,
      '提交确认',
      {
        confirmButtonText: '确定提交',
        cancelButtonText: '取消',
        type: 'success'
      }
    )

    await submitDraft(row.id)
    ElMessage.success('工单提交成功')
    fetchData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.response?.data?.msg || '提交失败，请重试')
    }
  }
}

// 撤回工单（企业级标准：允许申请人主动撤回审批中的工单）
async function handleWithdraw(row) {
  try {
    await ElMessageBox.confirm(
      `确定要撤回工单「${row.title}」吗？\n\n撤回后该工单将终止审批流程，不可恢复。`,
      '撤回确认',
      {
        confirmButtonText: '确定撤回',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    const response = await withdrawWorkOrder(row.id)

    // 检查响应状态
    if (response.code === RESULT_CODE.SUCCESS || response.success === true || !response.code) {
      ElMessage.success('工单已成功撤回')
      fetchData()
    } else {
      throw new Error(response.msg || '撤回失败')
    }
  } catch (error) {
    if (error !== 'cancel') {
      const errorMsg = error.response?.data?.msg || error.message || '撤回失败，请重试'
      ElMessage.error(errorMsg)
    }
  }
}

// 重新提交
async function handleResubmit(row) {
  try {
    const { value: comment } = await ElMessageBox.prompt(
      '请输入重新提交的原因',
      '重新提交',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputPlaceholder: '可选填写修改说明'
      }
    )

    await resubmitWorkOrder(row.id, comment)
    ElMessage.success('重新提交成功')
    fetchData()
  } catch (error) {
    if (error !== 'cancel') {
      // 重新提交失败
    }
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
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

.create-btn {
  color: #4a6cf7;
  border-color: #c4d0f5;
  background-color: #fff;

  &:hover {
    color: #3d5ce3;
    border-color: #4a6cf7;
    background-color: #f5f7ff;
  }
}
</style>
