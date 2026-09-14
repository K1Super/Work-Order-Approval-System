<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">系统设置</h2>
      <p class="page-desc">管理系统基础配置、工单规则、通知和安全策略</p>
    </div>

    <div class="settings-wrapper">
      <!-- 左侧导航 -->
      <div class="settings-nav">
        <div
          v-for="item in navItems"
          :key="item.key"
          :class="['nav-item', { active: activeTab === item.key }]"
          @click="activeTab = item.key"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </div>
      </div>

      <!-- 右侧内容区 -->
      <div class="settings-content">
        <!-- 基本设置 -->
        <div v-show="activeTab === 'basic'" class="setting-section">
          <div class="section-header">
            <h3>基本设置</h3>
            <span>配置系统基本信息</span>
          </div>
          <el-form
            ref="basicFormRef"
            :model="basicForm"
            :rules="basicRules"
            label-width="120px"
            class="setting-form"
          >
            <el-form-item label="系统名称" prop="systemName">
              <el-input v-model="basicForm.systemName" placeholder="请输入系统名称" maxlength="50" />
            </el-form-item>
            <el-form-item>
              <el-button type="default" class="save-btn" :loading="saving.basic" @click="saveBasicSettings">保存设置</el-button>
              <el-button class="reset-btn" @click="resetBasicSettings">重置</el-button>
            </el-form-item>
          </el-form>
        </div>

        <!-- 工单设置 -->
        <div v-show="activeTab === 'workorder'" class="setting-section">
          <div class="section-header">
            <h3>工单设置</h3>
            <span>配置工单审批相关规则</span>
          </div>
          <el-form
            ref="workorderFormRef"
            :model="workorderForm"
            :rules="workorderRules"
            label-width="160px"
            class="setting-form"
          >
            <el-divider content-position="left">基本规则</el-divider>
            <el-form-item label="默认超时时间(小时)" prop="defaultTimeout">
              <el-input-number
                v-model="workorderForm.defaultTimeout"
                :min="1"
                :max="720"
                :step="1"
                controls-position="right"
              />
              <span class="form-tip">工单超过此时间未处理将标记为超时</span>
            </el-form-item>
            <el-form-item label="每日提交上限" prop="dailySubmitLimit">
              <el-input-number
                v-model="workorderForm.dailySubmitLimit"
                :min="1"
                :max="1000"
                :step="1"
                controls-position="right"
              />
              <span class="form-tip">单个用户每天最多可提交的工单数量</span>
            </el-form-item>
            
            <el-divider content-position="left">审批规则</el-divider>
            <el-form-item label="允许撤回已提交工单" prop="allowWithdraw">
              <el-switch
                v-model="workorderForm.allowWithdraw"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">开启后员工可撤回待审批的工单</span>
            </el-form-item>
            <el-form-item label="允许退回审批" prop="allowReturn">
              <el-switch
                v-model="workorderForm.allowReturn"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">开启后审批人可将工单退回给上一级或发起人</span>
            </el-form-item>
            <el-form-item label="低金额自动审批" prop="autoApproveLowAmount">
              <el-switch
                v-model="workorderForm.autoApproveLowAmount"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">低于阈值的工单自动审批通过</span>
            </el-form-item>
            <el-form-item label="低金额阈值(元)" prop="lowAmountThreshold">
              <el-input-number
                v-model="workorderForm.lowAmountThreshold"
                :min="0"
                :max="100000"
                :step="100"
                controls-position="right"
              />
              <span class="form-tip">低于此金额的工单可自动审批</span>
            </el-form-item>

            <el-divider content-position="left">附件与加急</el-divider>
            <el-form-item label="要求必须上传附件" prop="requireAttachment">
              <el-switch
                v-model="workorderForm.requireAttachment"
                active-text="是"
                inactive-text="否"
              />
              <span class="form-tip">强制要求提交工单时必须上传附件</span>
            </el-form-item>
            <el-form-item label="允许加急处理" prop="allowUrgent">
              <el-switch
                v-model="workorderForm.allowUrgent"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">允许用户标记工单为加急状态</span>
            </el-form-item>
            <el-form-item label="加急超时倍数" prop="urgentTimeoutMultiplier">
              <el-input-number
                v-model="workorderForm.urgentTimeoutMultiplier"
                :min="1"
                :max="10"
                :step="0.5"
                controls-position="right"
              />
              <span class="form-tip">加急工单的超时时间为默认的此倍数分之一</span>
            </el-form-item>

            <el-divider content-position="left">归档设置</el-divider>
            <el-form-item label="启用自动归档" prop="enableAutoArchive">
              <el-switch
                v-model="workorderForm.enableAutoArchive"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">已完成的工单在指定天数后自动归档</span>
            </el-form-item>
            <el-form-item label="归档天数(完成后)" prop="archiveDaysAfterComplete">
              <el-input-number
                v-model="workorderForm.archiveDaysAfterComplete"
                :min="7"
                :max="365"
                :step="1"
                controls-position="right"
              />
              <span class="form-tip">工单完成后多少天自动归档</span>
            </el-form-item>

            <el-form-item>
              <el-button type="default" class="save-btn" :loading="saving.workorder" @click="saveWorkorderSettings">保存设置</el-button>
              <el-button class="reset-btn" @click="resetWorkorderSettings">重置</el-button>
            </el-form-item>
          </el-form>
        </div>

        <!-- 通知设置 -->
        <div v-show="activeTab === 'notification'" class="setting-section">
          <div class="section-header">
            <h3>通知设置</h3>
            <span>配置消息推送和通知方式</span>
          </div>
          <el-form
            ref="notificationFormRef"
            :model="notificationForm"
            label-width="140px"
            class="setting-form"
          >
            <el-divider content-position="left">站内消息</el-divider>
            <el-form-item label="启用站内消息" prop="enableInternalMsg">
              <el-switch
                v-model="notificationForm.enableInternalMsg"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">开启后在系统内显示消息通知</span>
            </el-form-item>
            
            <el-divider content-position="left">消息类型</el-divider>
            <el-form-item label="新工单通知" prop="notifyNewOrder">
              <el-switch
                v-model="notificationForm.notifyNewOrder"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">有新工单提交时通知审批人</span>
            </el-form-item>
            <el-form-item label="审批结果通知" prop="notifyApprovalResult">
              <el-switch
                v-model="notificationForm.notifyApprovalResult"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">审批完成后通知工单发起人</span>
            </el-form-item>
            <el-form-item label="超时预警通知" prop="notifyTimeoutWarning">
              <el-switch
                v-model="notificationForm.notifyTimeoutWarning"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">工单即将超时时提醒相关人员</span>
            </el-form-item>
            <el-form-item label="工单退回通知" prop="notifyOrderReturn">
              <el-switch
                v-model="notificationForm.notifyOrderReturn"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">工单被退回时通知相关人员</span>
            </el-form-item>
            <el-form-item label="工单撤回通知" prop="notifyWithdraw">
              <el-switch
                v-model="notificationForm.notifyWithdraw"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">工单被撤回时通知审批人</span>
            </el-form-item>

            <el-divider content-position="left">邮件配置</el-divider>
            <el-form-item label="启用邮件通知" prop="enableEmail">
              <el-switch
                v-model="notificationForm.enableEmail"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="form-tip">开启后同时通过邮件发送通知</span>
            </el-form-item>
            <template v-if="notificationForm.enableEmail">
              <el-form-item label="SMTP服务器" prop="smtpHost" :rules="[{ required: true, message: '请输入SMTP服务器', trigger: 'blur' }]">
                <el-input v-model="notificationForm.smtpHost" placeholder="如：smtp.example.com" style="width: 300px" />
              </el-form-item>
              <el-form-item label="SMTP端口" prop="smtpPort" :rules="[{ required: true, message: '请输入端口', trigger: 'blur' }]">
                <el-input-number
                  v-model="notificationForm.smtpPort"
                  :min="1"
                  :max="65535"
                  :step="1"
                  controls-position="right"
                  style="width: 150px"
                />
                <span class="form-tip">常用端口：25(普通), 465(SSL), 587(TLS)</span>
              </el-form-item>
              <el-form-item label="SMTP用户名" prop="smtpUsername">
                <el-input v-model="notificationForm.smtpUsername" placeholder="邮箱账号或用户名" style="width: 280px" />
              </el-form-item>
              <el-form-item label="SMTP密码" prop="smtpPassword">
                <el-input 
                  v-model="notificationForm.smtpPassword" 
                  type="password" 
                  placeholder="邮箱密码或授权码" 
                  show-password
                  style="width: 280px" 
                />
              </el-form-item>
              <el-form-item label="发件人邮箱" prop="senderEmail" :rules="[{ required: true, message: '请输入发件人邮箱', trigger: 'blur' }, { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }]">
                <el-input v-model="notificationForm.senderEmail" placeholder="noreply@example.com" style="width: 280px" />
              </el-form-item>
              <el-form-item label="发件人名称" prop="senderName">
                <el-input v-model="notificationForm.senderName" placeholder="工单审批系统" style="width: 200px" />
              </el-form-item>
              <el-form-item label="邮件模板" prop="emailTemplate">
                <el-select v-model="notificationForm.emailTemplate" placeholder="选择模板" style="width: 200px">
                  <el-option label="默认模板" value="default" />
                  <el-option label="简洁模板" value="simple" />
                  <el-option label="详细模板" value="detailed" />
                </el-select>
              </el-form-item>
            </template>

            <el-form-item>
              <el-button type="default" class="save-btn" :loading="saving.notification" @click="saveNotificationSettings">保存设置</el-button>
              <el-button class="test-btn" @click="testEmailNotification" :loading="testingEmail" :disabled="!notificationForm.enableEmail">测试邮件</el-button>
              <el-button @click="resetNotificationSettings">重置</el-button>
            </el-form-item>
          </el-form>
        </div>

        <!-- 安全设置 -->
        <div v-show="activeTab === 'security'" class="setting-section">
          <div class="section-header">
            <h3>安全设置</h3>
            <span>配置密码策略和会话管理</span>
          </div>
          <el-form
            ref="securityFormRef"
            :model="securityForm"
            :rules="securityRules"
            label-width="150px"
            class="setting-form"
          >
            <el-divider content-position="left">密码策略</el-divider>
            <el-form-item label="最小密码长度" prop="minPasswordLength">
              <el-input-number
                v-model="securityForm.minPasswordLength"
                :min="6"
                :max="32"
                :step="1"
                controls-position="right"
              />
            </el-form-item>
            <el-form-item label="要求包含数字" prop="requireDigit">
              <el-switch
                v-model="securityForm.requireDigit"
                active-text="是"
                inactive-text="否"
              />
            </el-form-item>
            <el-form-item label="要求包含特殊字符" prop="requireSpecialChar">
              <el-switch
                v-model="securityForm.requireSpecialChar"
                active-text="是"
                inactive-text="否"
              />
            </el-form-item>
            <el-form-item label="密码有效期(天)" prop="passwordExpiryDays">
              <el-input-number
                v-model="securityForm.passwordExpiryDays"
                :min="0"
                :max="365"
                :step="30"
                controls-position="right"
              />
              <span class="form-tip">0表示永不过期</span>
            </el-form-item>
            <el-divider content-position="left">会话管理</el-divider>
            <el-form-item label="Token过期时间(小时)" prop="tokenExpiryHours">
              <el-input-number
                v-model="securityForm.tokenExpiryHours"
                :min="1"
                :max="168"
                :step="1"
                controls-position="right"
              />
              <span class="form-tip">用户登录后的Token有效时长</span>
            </el-form-item>
            <el-form-item label="最大登录失败次数" prop="maxLoginAttempts">
              <el-input-number
                v-model="securityForm.maxLoginAttempts"
                :min="3"
                :max="10"
                :step="1"
                controls-position="right"
              />
              <span class="form-tip">超过此次数将锁定账户</span>
            </el-form-item>
            <el-form-item label="锁定时间(分钟)" prop="lockoutDuration">
              <el-input-number
                v-model="securityForm.lockoutDuration"
                :min="5"
                :max="1440"
                :step="5"
                controls-position="right"
              />
              <span class="form-tip">账户锁定后的自动解锁时间</span>
            </el-form-item>
            <el-form-item>
              <el-button type="default" class="save-btn" :loading="saving.security" @click="saveSecuritySettings">保存设置</el-button>
              <el-button class="reset-btn" @click="resetSecuritySettings">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Setting, Document, Bell, Lock } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { RESULT_CODE } from '@/constants'
import { confirmReauth, REAUTH_PASSWORD_HEADER } from '@/utils/reauth'

// 当前激活的标签页
const activeTab = ref('basic')

// 导航项
const navItems = [
  { key: 'basic', label: '基本设置', icon: Setting },
  { key: 'workorder', label: '工单设置', icon: Document },
  { key: 'notification', label: '通知设置', icon: Bell },
  { key: 'security', label: '安全设置', icon: Lock }
]

// 保存状态
const saving = reactive({
  basic: false,
  workorder: false,
  notification: false,
  security: false
})
const testingEmail = ref(false)

// 表单引用
const basicFormRef = ref()
const workorderFormRef = ref()
const notificationFormRef = ref()
const securityFormRef = ref()

// ========== 基本设置 ==========
const basicForm = reactive({
  systemName: ''
})

const basicRules = {
  systemName: [
    { required: true, message: '请输入系统名称', trigger: 'blur' }
  ]
}

// 默认值备份
const defaultBasicForm = {
  systemName: '工单审批流转系统'
}

// ========== 工单设置 ==========
const workorderForm = reactive({
  defaultTimeout: 24,
  allowWithdraw: true,
  allowReturn: true,
  dailySubmitLimit: 20,
  autoApproveLowAmount: false,
  lowAmountThreshold: 1000,
  requireAttachment: false,
  allowUrgent: true,
  urgentTimeoutMultiplier: 2,
  enableAutoArchive: true,
  archiveDaysAfterComplete: 30
})

const workorderRules = {
  defaultTimeout: [
    { required: true, message: '请输入超时时间', trigger: 'blur' }
  ],
  dailySubmitLimit: [
    { required: true, message: '请输入每日提交上限', trigger: 'blur' }
  ]
}

const defaultWorkorderForm = {
  defaultTimeout: 24,
  allowWithdraw: true,
  allowReturn: true,
  dailySubmitLimit: 20,
  autoApproveLowAmount: false,
  lowAmountThreshold: 1000,
  requireAttachment: false,
  allowUrgent: true,
  urgentTimeoutMultiplier: 2,
  enableAutoArchive: true,
  archiveDaysAfterComplete: 30
}

// ========== 通知设置 ==========
const notificationForm = reactive({
  enableInternalMsg: true,
  notifyNewOrder: true,
  notifyApprovalResult: true,
  notifyTimeoutWarning: true,
  notifyOrderReturn: true,
  notifyWithdraw: false,
  enableEmail: false,
  smtpHost: '',
  smtpPort: 465,
  smtpUsername: '',
  smtpPassword: '',
  senderEmail: '',
  senderName: '工单审批系统',
  emailTemplate: 'default'
})

const defaultNotificationForm = {
  enableInternalMsg: true,
  notifyNewOrder: true,
  notifyApprovalResult: true,
  notifyTimeoutWarning: true,
  notifyOrderReturn: true,
  notifyWithdraw: false,
  enableEmail: false,
  smtpHost: '',
  smtpPort: 465,
  smtpUsername: '',
  smtpPassword: '',
  senderEmail: '',
  senderName: '工单审批系统',
  emailTemplate: 'default'
}

// ========== 安全设置 ==========
const securityForm = reactive({
  minPasswordLength: 8,
  requireDigit: false,
  requireSpecialChar: false,
  passwordExpiryDays: 0,
  tokenExpiryHours: 24,
  maxLoginAttempts: 5,
  lockoutDuration: 30
})

const securityRules = {
  minPasswordLength: [
    { required: true, message: '请输入最小密码长度', trigger: 'blur' }
  ],
  tokenExpiryHours: [
    { required: true, message: '请输入Token过期时间', trigger: 'blur' }
  ]
}

const defaultSecurityForm = {
  minPasswordLength: 8,
  requireDigit: false,
  requireSpecialChar: false,
  passwordExpiryDays: 0,
  tokenExpiryHours: 24,
  maxLoginAttempts: 5,
  lockoutDuration: 30
}

// ========== 加载设置数据 ==========
async function loadAllSettings() {
  try {
    const response = await request.get('/system/settings/all')
    if (response.code === RESULT_CODE.SUCCESS && response.data) {
      const data = response.data

      // 基本设置
      if (data.basic) {
        Object.assign(basicForm, data.basic)
      }

      // 工单设置
      if (data.workorder) {
        Object.assign(workorderForm, data.workorder)
      }

      // 通知设置
      if (data.notification) {
        Object.assign(notificationForm, data.notification)
      }

      // 安全设置
      if (data.security) {
        Object.assign(securityForm, data.security)
      }
    }
  } catch (error) {
    ElMessage.warning('加载设置失败，使用默认值')
  }
}

// ========== 保存方法 ==========
async function saveBasicSettings() {
  if (!basicFormRef.value) return

  try {
    await basicFormRef.value.validate()
  } catch {
    return
  }

  saving.basic = true
  try {
    const response = await request.put('/system/settings/basic', basicForm)
    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('基本设置保存成功')

      // 实时更新应用标题
      if (basicForm.systemName) {
        document.title = basicForm.systemName

        // 更新localStorage存储的系统名称，供其他组件使用
        localStorage.setItem('systemName', basicForm.systemName)

        // 触发自定义事件通知其他组件更新
        window.dispatchEvent(new CustomEvent('systemNameChanged', {
          detail: { systemName: basicForm.systemName }
        }))
      }
    } else {
      ElMessage.error(response.msg || '保存失败')
    }
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    saving.basic = false
  }
}

async function saveWorkorderSettings() {
  if (!workorderFormRef.value) return

  try {
    await workorderFormRef.value.validate()
  } catch {
    return
  }

  saving.workorder = true
  try {
    const response = await request.put('/system/settings/workorder', workorderForm)
    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('工单设置保存成功')
    } else {
      ElMessage.error(response.msg || '保存失败')
    }
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    saving.workorder = false
  }
}

async function saveNotificationSettings() {
  saving.notification = true
  try {
    const response = await request.put('/system/settings/notification', notificationForm)
    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('通知设置保存成功')
    } else {
      ElMessage.error(response.msg || '保存失败')
    }
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    saving.notification = false
  }
}

async function saveSecuritySettings() {
  if (!securityFormRef.value) return

  try {
    await securityFormRef.value.validate()
  } catch {
    return
  }

  saving.security = true
  try {
    // 二次鉴权：后端 @RequireReAuth 要求 X-Reauth-Password 请求头
    const password = await confirmReauth('修改安全设置')
    if (password === null) {
      // 用户取消
      return
    }

    const response = await request.put('/system/settings/security', securityForm, {
      headers: { [REAUTH_PASSWORD_HEADER]: password }
    })
    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('安全设置保存成功')
    } else {
      ElMessage.error(response.msg || '保存失败')
    }
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    saving.security = false
  }
}

// ========== 测试邮件 ==========
async function testEmailNotification() {
  if (!notificationForm.smtpHost || !notificationForm.senderEmail) {
    ElMessage.warning('请先填写SMTP服务器和发件人邮箱')
    return
  }

  testingEmail.value = true
  try {
    const response = await request.post('/system/settings/test-email', {
      smtpHost: notificationForm.smtpHost,
      smtpPort: notificationForm.smtpPort,
      smtpUsername: notificationForm.smtpUsername,
      smtpPassword: notificationForm.smtpPassword,
      senderEmail: notificationForm.senderEmail,
      senderName: notificationForm.senderName
    })
    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('测试邮件发送成功，请检查收件箱')
    } else {
      ElMessage.error(response.msg || '发送失败')
    }
  } catch (error) {
    ElMessage.error('发送失败，请检查SMTP配置')
  } finally {
    testingEmail.value = false
  }
}

// ========== 重置方法 ==========
function resetBasicSettings() {
  Object.assign(basicForm, { ...defaultBasicForm })
}

function resetWorkorderSettings() {
  Object.assign(workorderForm, { ...defaultWorkorderForm })
}

function resetNotificationSettings() {
  Object.assign(notificationForm, { ...defaultNotificationForm })
}

function resetSecuritySettings() {
  Object.assign(securityForm, { ...defaultSecurityForm })
}

onMounted(() => {
  loadAllSettings()

  // 初始化时从localStorage读取系统名称
  const savedSystemName = localStorage.getItem('systemName')
  if (savedSystemName) {
    document.title = savedSystemName
  }

  // 监听系统名称变化事件
  window.addEventListener('systemNameChanged', (event) => {
    // 系统名称已更新
  })
})
</script>

<style scoped>
/* 页面容器 */
.page-container {
  padding: var(--space-lg);
}

/* 页面标题 */
.page-header {
  margin-bottom: var(--space-lg);
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-ink-primary, #1F2937);
  margin: 0 0 6px 0;
}

.page-desc {
  font-size: 13px;
  color: var(--color-ink-muted, #6B7280);
  margin: 0;
}

/* 设置区域布局 */
.settings-wrapper {
  display: flex;
  gap: var(--space-lg);
  align-items: flex-start;
}

/* 左侧导航 */
.settings-nav {
  width: 180px;
  flex-shrink: 0;
  background-color: var(--color-surface-card, #fff);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border, #E5E7EB);
  padding: var(--space-md) 0;
  position: sticky;
  top: calc(var(--header-height, 64px) + var(--space-lg));
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  cursor: pointer;
  font-size: 14px;
  color: var(--color-ink-body, #374151);
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;

  /* 悬浮时的背景渐变效果 */
  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    height: 100%;
    width: 0;
    background: linear-gradient(90deg, var(--color-primary-soft, #E6F4FF) 0%, transparent 100%);
    transition: width 0.3s ease;
    z-index: 0;
  }

  .el-icon {
    font-size: 16px;
    color: inherit;
    position: relative;
    z-index: 1;
  }

  & span {
    position: relative;
    z-index: 1;
  }

  &:hover {
    color: var(--color-primary, #1677FF);

    &::before {
      width: 100%;
    }
  }

  &.active {
    background-color: var(--color-primary-soft, #E6F4FF);
    color: var(--color-primary, #1677FF);
    font-weight: 500;

    &::before {
      width: 100%;
      background: linear-gradient(90deg, rgba(22, 119, 255, 0.15) 0%, transparent 100%);
    }
  }
}

/* 右侧内容区 */
.settings-content {
  flex: 1;
  min-width: 0;
}

.setting-section {
  background-color: var(--color-surface-card, #fff);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border, #E5E7EB);
  padding: var(--space-xl);
}

.section-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: var(--space-xl);
  padding-bottom: var(--space-md);
  border-bottom: 1px solid var(--color-border-light, #f0f0f0);

  & h3 {
    font-size: 16px;
    font-weight: 600;
    color: var(--color-ink-primary, #1F2937);
    margin: 0;
  }

  & span {
    font-size: 13px;
    color: var(--color-ink-muted, #6B7280);
  }
}

/* 表单样式 */
.setting-form {
  max-width: 700px;

  :deep(.el-form-item__label) {
    color: var(--color-ink-body, #374151);
    font-weight: 500;
  }

  :deep(.el-input__wrapper),
  :deep(.el-textarea__inner) {
    border-radius: 6px;
  }

  :deep(.el-switch__label) {
    font-size: 13px;
  }

  :deep(.el-divider__text) {
    font-size: 14px;
    font-weight: 500;
    color: var(--color-ink-muted, #6B7280);
  }
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--color-ink-placeholder, #A3A3A3);
}

/* 按钮样式 */
:deep(.el-button--primary) {
  background-color: var(--color-primary, #1677FF);
  border-color: var(--color-primary, #1677FF);

  &:hover {
    background-color: var(--color-primary-hover, #0958D9);
    border-color: var(--color-primary-hover, #0958D9);
  }
}

/* 移动端适配 */
@media (max-width: 768px) {
  .settings-wrapper {
    flex-direction: column;
  }

  .settings-nav {
    width: 100%;
    position: static;
    display: flex;
    overflow-x: auto;
    padding: var(--space-sm);

    .nav-item {
      white-space: nowrap;

      &::before {
        background: linear-gradient(180deg, var(--color-primary-soft, #E6F4FF) 0%, transparent 100%);
        width: 0;
        height: 0;
        left: 0;
        top: auto;
        bottom: 0;
        transition: height 0.3s ease;
      }

      &:hover::before,
      &.active::before {
        width: 100%;
        height: 100%;
      }
    }
  }

  .setting-form {
    max-width: 100%;
  }
}

/* 系统设置按钮样式 - 统一白底风格 */
.save-btn {
  color: #4a6cf7;
  border-color: #c4d0f5;
  background-color: #fff;

  &:hover {
    color: #3d5ce3;
    border-color: #4a6cf7;
    background-color: #f5f7ff;
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

.test-btn {
  color: #6b8fd4;
  border-color: #c8d4eb;
  background-color: #fff;

  &:hover {
    color: #5578c0;
    border-color: #6b8fd4;
    background-color: #f5f7fc;
  }
}
</style>
