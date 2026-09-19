<template>
  <div class="page-container">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-form :inline="true" :model="searchForm" class="filter-form">
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="姓名/用户名/手机号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="searchForm.departmentId" placeholder="全部" clearable style="width: 150px">
            <el-option
              v-for="dept in departments"
              :key="dept.value"
              :label="dept.label"
              :value="dept.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="离职类型">
          <el-select v-model="searchForm.resignType" placeholder="全部" clearable style="width: 140px">
            <el-option
              v-for="type in resignTypes"
              :key="type.value"
              :label="type.label"
              :value="type.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="default" class="search-btn" @click="handleSearch">搜索</el-button>
          <el-button class="reset-btn" @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar-btn">
        <el-button
          type="default"
          icon="Back"
          class="back-btn"
          @click="$router.push('/employee/list')"
        >
          返回员工管理
        </el-button>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-card">
      <el-table
        :data="resignedList"
        border
        stripe
        v-loading="loading"
      >
        <el-table-column prop="id" label="ID" width="60" align="center" />
        <el-table-column prop="realName" label="姓名" min-width="90" />
        <el-table-column prop="username" label="用户名" min-width="100" />
        <el-table-column prop="phone" label="手机号" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="160" />
        <el-table-column prop="department" label="部门" min-width="100" />
        <el-table-column prop="positionName" label="职位" min-width="100" />
        <el-table-column label="组织层级" width="90" align="center">
          <template #default="{ row }">
            {{ getOrgLevelText(row.orgLevel) }}
          </template>
        </el-table-column>
        <el-table-column prop="hireDate" label="入职日期" width="110" align="center">
          <template #default="{ row }">
            {{ formatDate(row.hireDate) }}
          </template>
        </el-table-column>
        <el-table-column prop="resignDate" label="离职日期" width="110" align="center">
          <template #default="{ row }">
            {{ formatDate(row.resignDate) }}
          </template>
        </el-table-column>
        <el-table-column label="离职类型" width="100" align="center">
          <template #default="{ row }">
            {{ getResignTypeText(row.resignType) }}
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="80" align="center" />
        <el-table-column prop="createTime" label="记录时间" width="160" align="center">
          <template #default="{ row }">
            {{ formatDateTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleViewDetail(row)">
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 详情对话框 -->
    <el-dialog
      v-model="detailDialogVisible"
      title="离职员工详情"
      width="700px"
      :close-on-click-modal="false"
    >
      <el-descriptions :column="2" border v-if="currentResigned">
        <el-descriptions-item label="姓名">{{ currentResigned.realName }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ currentResigned.username }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ currentResigned.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ currentResigned.email || '-' }}</el-descriptions-item>
        <el-descriptions-item label="部门">{{ currentResigned.department || '-' }}</el-descriptions-item>
        <el-descriptions-item label="职位">{{ currentResigned.positionName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="组织层级">{{ getOrgLevelText(currentResigned.orgLevel) }}</el-descriptions-item>
        <el-descriptions-item label="入职日期">{{ formatDate(currentResigned.hireDate) }}</el-descriptions-item>
        <el-descriptions-item label="离职日期">{{ formatDate(currentResigned.resignDate) }}</el-descriptions-item>
        <el-descriptions-item label="离职类型">{{ getResignTypeText(currentResigned.resignType) }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ currentResigned.operatorName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="离职原因" :span="2">
          {{ currentResigned.resignReason || '未填写' }}
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">
          {{ currentResigned.remark || '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="记录时间" :span="2">
          {{ formatDateTime(currentResigned.createTime) }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { RESULT_CODE } from '@/constants'

// 响应式数据
const loading = ref(false)
const resignedList = ref([])
const departments = ref([])
const resignTypes = ref([])
const detailDialogVisible = ref(false)
const currentResigned = ref(null)

// 搜索表单
const searchForm = reactive({
  keyword: '',
  departmentId: null,
  resignType: null
})

// 分页
const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

// 加载离职员工列表
const loadResignedList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      ...searchForm
    }
    // 移除空值参数
    Object.keys(params).forEach(key => {
      if (params[key] === '' || params[key] === null || params[key] === undefined) {
        delete params[key]
      }
    })

    const res = await request.get('/employees/resigned/list', { params })
    if (res.code === RESULT_CODE.SUCCESS) {
      resignedList.value = res.data.list || []
      pagination.total = res.data.total || 0
    }
  } catch (error) {
    ElMessage.error('加载离职员工列表失败')
  } finally {
    loading.value = false
  }
}

// 加载部门列表
const loadDepartments = async () => {
  try {
    const res = await request.get('/employees/departments')
    if (res.code === RESULT_CODE.SUCCESS) {
      departments.value = res.data || []
    }
  } catch (error) {
    // 加载部门列表失败
  }
}

// 加载离职类型列表
const loadResignTypes = async () => {
  try {
    const res = await request.get('/employees/resign-types')
    if (res.code === RESULT_CODE.SUCCESS) {
      resignTypes.value = res.data || []
    }
  } catch (error) {
    // 加载离职类型失败
  }
}

// 搜索
const handleSearch = () => {
  pagination.pageNum = 1
  loadResignedList()
}

// 重置搜索
const resetSearch = () => {
  searchForm.keyword = ''
  searchForm.departmentId = null
  searchForm.resignType = null
  handleSearch()
}

// 分页变化
const handleSizeChange = (val) => {
  pagination.pageSize = val
  pagination.pageNum = 1
  loadResignedList()
}

const handlePageChange = (val) => {
  pagination.pageNum = val
  loadResignedList()
}

// 查看详情
const handleViewDetail = async (row) => {
  try {
    const res = await request.get(`/employees/resigned/${row.id}`)
    if (res.code === RESULT_CODE.SUCCESS) {
      currentResigned.value = res.data
      detailDialogVisible.value = true
    }
  } catch (error) {
    ElMessage.error('获取详情失败')
  }
}

// 格式化日期
const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  return dateStr.split('T')[0]
}

// 格式化日期时间
const formatDateTime = (dateTimeStr) => {
  if (!dateTimeStr) return '-'
  return dateTimeStr.replace('T', ' ').substring(0, 19)
}

// 获取组织层级文本
const getOrgLevelText = (orgLevel) => {
  const levelMap = {
    0: '超级管理层',
    1: '决策层',
    2: '管理层',
    3: '职能层',
    4: '执行层'
  }
  return levelMap[orgLevel] || '-'
}

// 获取离职类型文本
const getResignTypeText = (type) => {
  const typeMap = {
    1: '主动辞职',
    2: '被动辞退',
    3: '合同到期',
    4: '退休',
    5: '其他'
  }
  return typeMap[type] || '-'
}

// 初始化
onMounted(() => {
  loadDepartments()
  loadResignTypes()
  loadResignedList()
})
</script>

<style scoped>
.page-container {
  padding: 20px;
}

.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding: 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.filter-form {
  flex: 1;
}

.toolbar-btn {
  display: flex;
  gap: 10px;
}

/* 搜索按钮 - 白底蓝字（与员工管理一致） */
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

/* 重置按钮 - 白底灰字（与员工管理一致） */
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

.add-btn {
  background-color: #67c23a;
  color: white;
  border-color: #67c23a;
}

.add-btn:hover {
  background-color: #85ce61;
  border-color: #85ce61;
}

.export-btn {
  background-color: #e6a23c;
  color: white;
  border-color: #e6a23c;
}

.export-btn:hover {
  background-color: #ebb563;
  border-color: #ebb563;
}

.back-btn {
  color: #606266;
  border-color: #dcdfe6;
}

.back-btn:hover {
  color: #409eff;
  border-color: #409eff;
}

.table-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
