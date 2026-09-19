<template>
  <div class="page-container">
    <div class="table-card">
      <h2 class="page-title">提交新工单</h2>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        label-position="right"
        class="create-form"
      >
        <el-form-item label="工单标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入工单标题" maxlength="100" show-word-limit />
        </el-form-item>

        <el-form-item label="工单类型" prop="orderType">
          <el-select
            v-model="form.orderType"
            placeholder="请选择工单类型"
            style="width: 240px"
            @change="handleTypeChange"
          >
            <el-option
              v-for="item in orderTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="优先级" prop="priority">
          <el-select
            v-model="form.priority"
            placeholder="请选择优先级"
            style="width: 180px"
            @change="handlePriorityChange"
          >
            <el-option label="低" :value="1" />
            <el-option label="中" :value="2" />
            <el-option label="高（需填写原因）" :value="3" class="priority-option-high" />
            <el-option label="紧急（需填写原因）" :value="4" class="priority-option-urgent" />
          </el-select>
          <!-- 高/紧急优先级提示 -->
          <div v-if="form.priority >= 3 && !isManager" class="priority-hint">
          </div>
        </el-form-item>

        <!-- 高/紧急优先级时显示紧急原因输入框 -->
        <el-form-item
          v-if="form.priority >= 3"
          label="紧急原因"
          prop="emergencyReason"
          :rules="[{ required: true, message: '请说明选择此优先级的紧急原因', trigger: 'blur' }]"
        >
          <el-input
            v-model="form.emergencyReason"
            type="textarea"
            :rows="3"
            placeholder="请说明使用高/紧急优先级原因"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="申请部门" prop="department">
          <!-- 低/中优先级：锁定显示当前用户部门（纯文本） -->
          <template v-if="isDepartmentLocked">
            <span class="dept-display">{{ form.department || '加载中...' }}</span>
          </template>
          <!-- 高/紧急优先级：可选择当前部门或上级部门 -->
          <el-select
            v-else
            v-model="form.department"
            placeholder="请选择部门"
            style="width: 200px"
          >
            <el-option
              v-for="item in availableDepartmentOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>

        </el-form-item>

        <el-form-item label="工单内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            placeholder="请详细描述申请内容和原因"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="附件上传">
          <el-upload
            :auto-upload="false"
            :limit="5"
            :accept="currentAcceptFormats"
            :http-request="handleHttpUpload"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            :on-exceed="handleExceed"
            :before-upload="handleBeforeUpload"
          >
            <el-button type="default" class="upload-btn"><el-icon><Upload /></el-icon>选择文件</el-button>
            <template #tip>
              <div class="upload-tip">{{ currentUploadTip }}</div>
            </template>
          </el-upload>

          <!-- 已选文件列表 -->
          <div v-if="selectedFiles.length > 0" class="file-list">
            <div v-for="(file, index) in selectedFiles" :key="index" class="file-item">
              {{ index + 1 }}. {{ file.name }}
              <span v-if="isDangerousFile(file)" class="danger-tag">危险格式</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="可选填备注信息"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>

        <el-form-item>
          <el-space>
            <el-button type="default" class="submit-btn" :loading="submitting" @click="handleSubmit(true)">
              直接提交
            </el-button>
            <el-button class="draft-btn" :loading="submitting" @click="handleSubmit(false)">
              保存草稿
            </el-button>
            <el-button class="back-btn" @click="$router.back()">返回</el-button>
          </el-space>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { createDraft, submitWorkOrder } from '@/api/workorder'
import { getCurrentUser } from '@/api/auth'
import logger from '@/utils/logger'
import {
  ORDER_TYPE_OPTIONS,
  ORDER_TYPE_FILE_EXTENSIONS,
  DANGEROUS_FILE_EXTS,
  WORK_ORDER_TYPE
} from '@/utils/common'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)
const selectedFiles = ref([])

// 当前用户信息
const currentUser = reactive({
  department: '',
  departmentId: null,
  orgLevel: 4,
  realName: ''
})

// 部门层级定义（用于高/紧急时选择上级部门）
// 层级越高越靠前：总经办 > 各业务部门
const DEPARTMENT_HIERARCHY = [
  { label: '总经办', value: '总经办', level: 1 },
  { label: '人事部', value: '人事部', level: 2 },
  { label: '财务部', value: '财务部', level: 2 },
  { label: '研发部', value: '研发部', level: 2 },
  { label: '销售部', value: '销售部', level: 2 },
  { label: '行政部', value: '行政部', level: 2 },
  { label: '采购部', value: '采购部', level: 2 },
  { label: '运维部', value: '运维部', level: 2 }
]

// 工单类型下拉选项
const orderTypeOptions = ORDER_TYPE_OPTIONS

const form = reactive({
  title: '',
  orderType: '',
  priority: 1, // 默认低优先级
  department: '',
  content: '',
  remark: '',
  emergencyReason: '' // 紧急原因字段
})

// 计算属性：是否为管理层（orgLevel <= 2）
const isManager = computed(() => (currentUser.orgLevel || 4) <= 2)

// 计算属性：部门是否锁定
// 规则：低/中优先级 + 有部门信息 + 非管理层 时锁定显示当前用户部门
const isDepartmentLocked = computed(() => form.priority < 3 && !!currentUser.department && !isManager.value)

// 计算属性：根据优先级和用户级别返回可选的部门列表
// 管理级：可选择所有部门
// 员工级：按优先级限制选择范围
const availableDepartmentOptions = computed(() => {
  const userDept = currentUser.department

  // 如果还没获取到用户信息或用户无部门，返回全部供选择
  if (!userDept) return DEPARTMENT_HIERARCHY

  // 管理级：可选择全部部门
  if (isManager.value) {
    return DEPARTMENT_HIERARCHY
  }

  // 员工级低/中优先级：只显示当前部门
  if (form.priority < 3) {
    return DEPARTMENT_HIERARCHY.filter(d => d.value === userDept)
  }

  // 员工级高/紧急优先级：当前部门 + 更高层级的部门
  const currentDeptInfo = DEPARTMENT_HIERARCHY.find(d => d.value === userDept)
  const currentLevel = currentDeptInfo ? currentDeptInfo.level : 99

  return DEPARTMENT_HIERARCHY.filter(d => d.level <= currentLevel)
})

// 根据工单类型计算允许的文件格式（OPTIMIZATION：orderType 为 Integer 枚举码）
const currentAcceptFormats = computed(() => {
  const exts = ORDER_TYPE_FILE_EXTENSIONS[form.orderType] || ORDER_TYPE_FILE_EXTENSIONS[WORK_ORDER_TYPE.OTHER]
  return exts.map(e => '.' + e).join(',')
})

// 根据工单类型显示上传提示
const currentUploadTip = computed(() => {
  const exts = ORDER_TYPE_FILE_EXTENSIONS[form.orderType] || ORDER_TYPE_FILE_EXTENSIONS[WORK_ORDER_TYPE.OTHER]
  const allowedList = exts.map(e => e.toUpperCase()).join('、')
  return `允许格式：${allowedList} | 单文件不超过50MB | 最多5个文件 | 禁止压缩包和可执行文件`
})

// 检查是否为危险文件
function isDangerousFile(file) {
  if (!file.name) return false
  const name = file.name.toLowerCase()
  const dotIndex = name.lastIndexOf('.')
  if (dotIndex < 0) return false
  const ext = name.substring(dotIndex + 1)
  return DANGEROUS_FILE_EXTS.includes(ext)
}

// 检查文件格式是否符合当前工单类型要求
function isValidFileType(file) {
  if (!file.name) return true
  const name = file.name.toLowerCase()
  const dotIndex = name.lastIndexOf('.')
  if (dotIndex < 0) return true
  const ext = name.substring(dotIndex + 1)
  if (DANGEROUS_FILE_EXTS.includes(ext)) return false
  const allowedExts = ORDER_TYPE_FILE_EXTENSIONS[form.orderType] || ORDER_TYPE_FILE_EXTENSIONS[WORK_ORDER_TYPE.OTHER]
  return allowedExts.some(a => a === ext)
}

const rules = {
  title: [
    { required: true, message: '请输入工单标题', trigger: 'blur' },
    { min: 2, max: 100, message: '标题长度在2-100个字符', trigger: 'blur' }
  ],
  orderType: [
    { required: true, message: '请选择工单类型', trigger: 'change' }
  ],
  priority: [
    { required: true, message: '请选择优先级', trigger: 'change' }
  ],
  department: [
    { required: true, message: '请选择申请部门', trigger: 'change' }
  ],
  content: [
    { required: true, message: '请输入工单内容', trigger: 'blur' },
    { min: 2, max: 2000, message: '内容长度在2-2000个字符', trigger: 'blur' }
  ]
}

// 页面加载时获取当前用户信息并回填部门
onMounted(async () => {
  try {
    const res = await getCurrentUser()
    const info = res.data
    currentUser.department = info.department || ''
    currentUser.departmentId = info.departmentId || null
    currentUser.orgLevel = info.orgLevel ?? 4
    currentUser.realName = info.realName || ''

    // 自动回填当前用户所属部门
    if (info.department) {
      form.department = info.department
    } else {
      // 兜底：部门为空时提示用户手动选择
      logger.warn('workorder_create_no_dept', '用户无部门信息，需手动选择')
    }

    logger.debug('workorder_create_user_info', '当前用户部门信息', { department: info.department, orgLevel: info.orgLevel, isManager: isManager.value })
  } catch (e) {
    logger.error('workorder_create_user_info_failure', '获取用户信息失败', { error: e?.message })
    ElMessage.warning('获取用户信息失败，请手动选择部门')
  }
})

// 监听 currentUser.department 变化，自动同步到 form.department
watch(() => currentUser.department, (newVal) => {
  if (newVal && !form.department) {
    form.department = newVal
  }
})

// 优先级变更处理
function handlePriorityChange(val) {
  // 从高/紧急切回低/中时，重置部门为用户所属部门
  if (val < 3 && currentUser.department) {
    form.department = currentUser.department
  }
}

// 工单类型变更时清空已选的不合规文件
function handleTypeChange() {
  selectedFiles.value = selectedFiles.value.filter(f => isValidFileType(f))
}

// 文件选择变化
function handleFileChange(file) {
  if (!isValidFileType(file)) {
    ElMessage.error(`"${file.name}" 格式不符合该工单类型的上传要求或为禁止的危险格式`)
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error(`"${file.name}" 超过50MB限制`)
    return
  }
  if (!selectedFiles.value.find(f => f.uid === file.uid)) {
    selectedFiles.value.push(file)
  }
}

// 上传前拦截
function handleBeforeUpload(file) {
  if (!isValidFileType(file)) {
    ElMessage.error(`"${file.name}" 格式不允许上传`)
    return false
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过50MB')
    return false
  }
  return true
}

// 文件移除
function handleFileRemove(file) {
  const index = selectedFiles.value.findIndex(f => f.uid === file.uid)
  if (index > -1) {
    selectedFiles.value.splice(index, 1)
  }
}

// 文件数量超限
function handleExceed(files) {
  ElMessage.warning(`当前限制选择 5 个文件，本次选择了 ${files.length} 个文件`)
}

// W-42：真实上传单个文件到后端，返回文件标识（fileId）
async function uploadFile(file) {
  const fd = new FormData()
  // el-upload 传入的 UploadFile，其 .raw 为原生 File 对象
  fd.append('file', file.raw || file)
  // 显式 multipart（覆盖 axios 实例默认 application/json，避免 FormData 被 JSON 化）
  const res = await request.post('/files/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
  return res.data?.fileId || null
}

// ElUpload 的 http-request 自定义上传（真实调用后端 /files/upload）
async function handleHttpUpload(options) {
  try {
    const fileId = await uploadFile(options.file)
    options.onSuccess({ fileId })
  } catch (e) {
    options.onError(e)
  }
}

async function handleSubmit(isSubmit) {
  if (!formRef.value) return

  await formRef.value.validate(async (valid) => {
    if (valid) {
      // 前端校验：选高/紧急必须填写紧急原因
      if (form.priority >= 3) {
        if (!form.emergencyReason || form.emergencyReason.trim().length < 20) {
          ElMessage.warning('选择「高」或「紧急」优先级时，请在紧急原因栏详细说明（至少20字）')
          return
        }
      }

      // 将紧急原因合并到内容中
      let finalContent = form.content
      if (form.emergencyReason && form.emergencyReason.trim()) {
        finalContent += '\n\n【紧急原因】\n' + form.emergencyReason.trim()
      }

      submitting.value = true
      try {
        // W-42：真实上传已选附件，收集文件标识随工单一起提交
        const attachmentFileIds = []
        for (const f of selectedFiles.value) {
          if (f.fileId) {
            attachmentFileIds.push(f.fileId)
            continue
          }
          try {
            const fileId = await uploadFile(f)
            if (!fileId) {
              ElMessage.error(`文件「${f.name}」上传失败，未返回文件标识`)
              return
            }
            f.fileId = fileId
            attachmentFileIds.push(fileId)
          } catch (e) {
            ElMessage.error(`文件「${f.name}」上传失败，请重试`)
            return
          }
        }

        const submitData = {
          ...form,
          content: finalContent,
          attachmentUrl: attachmentFileIds.join(',')
        }

        if (isSubmit) {
          await submitWorkOrder(submitData)
          ElMessage.success('工单提交成功')
        } else {
          await createDraft(submitData)
          ElMessage.success('草稿保存成功')
        }

        router.push('/workorder/mine')
      } catch (error) {
        const errorMsg = error.response?.data?.msg || error.message || '提交失败，请检查输入数据'
        ElMessage.error(errorMsg)
      } finally {
        submitting.value = false
      }
    }
  })
}
</script>

<style scoped lang="scss">
.create-form {
  max-width: 700px;
}

.upload-tip {
  color: var(--color-info);
  font-size: 12px;
  margin-top: 4px;
  line-height: 1.5;
}

.file-list {
  margin-top: var(--space-xs);

  .file-item {
    color: var(--color-ink-secondary);
    font-size: 14px;
    line-height: 1.8;

    .danger-tag {
      margin-left: 8px;
      color: #F56C6C;
      font-size: 12px;
      font-weight: bold;
    }
  }
}

.dept-hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;

  &-warn {
    color: #E6A23C;
  }
}

.dept-display {
  display: inline-block;
  padding: 0 11px;
  height: 32px;
  line-height: 30px;
  font-size: 14px;
  color: #303133;
  background-color: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  min-width: 160px;
}

.priority-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  color: #409EFF;
  font-size: 13px;
  line-height: 1.4;
}

// 优先级下拉选项颜色
:deep(.priority-option-high),
:deep(.el-select-dropdown__item:nth-child(3)) {
  color: #E6A23C !important;
}

:deep(.priority-option-urgent),
:deep(.el-select-dropdown__item:nth-child(4)) {
  color: #F56C6C !important;
}

@media screen and (max-width: 768px) {
  .create-form {
    max-width: 100% !important;

    :deep(.el-form-item) {
      margin-bottom: var(--space-md);
    }

    :deep(.el-input),
    :deep(.el-select) {
      width: 100% !important;
    }

    :deep(.el-textarea__inner) {
      min-height: 120px;
    }
  }

  :deep(.el-space) {
    display: flex;
    width: 100%;
    gap: 10px;

    .el-button {
      flex: 1;
      min-height: 44px;
    }
  }
}

/* 按钮样式 - 白底风格 */
.upload-btn {
  color: #6b8fd4;
  border-color: #c8d4eb;
  background-color: #fff;

  &:hover {
    color: #5578c0;
    border-color: #6b8fd4;
    background-color: #f5f7fc;
  }
}

.submit-btn {
  color: #4a6cf7;
  border-color: #c4d0f5;
  background-color: #fff;

  &:hover {
    color: #3d5ce3;
    border-color: #4a6cf7;
    background-color: #f5f7ff;
  }
}

.draft-btn {
  color: #606b7c;
  border-color: #d0d5dd;
  background-color: #fff;

  &:hover {
    color: #404a58;
    border-color: #a8b0ba;
    background-color: #f7f8fa;
  }
}

.back-btn {
  color: #70787f;
  border-color: #d5dae0;
  background-color: #fff;

  &:hover {
    color: #50585f;
    border-color: #b8bec6;
    background-color: #f7f8fa;
  }
}
</style>
