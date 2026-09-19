<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <h1>工单审批系统</h1>
        <p>Work Order Approval System</p>
      </div>

      <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" class="login-form" @submit.prevent>
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入工号"
            size="large"
            maxlength="10"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            maxlength="50"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <button
            type="button"
            class="login-btn"
            :class="{ 'is-loading': loading }"
            :disabled="loading"
            @click="handleLogin"
          >
            {{ loading ? '登录中...' : '登 录' }}
          </button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 强制修改密码弹窗 -->
    <el-dialog
      v-model="showChangePasswordDialog"
      title="首次登录 - 请修改密码"
      width="420px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      center
    >
      <div class="change-password-content">
        <el-alert
          title="为了您的账号安全，首次登录必须修改初始密码"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 20px;"
        />

        <el-form ref="changePasswordFormRef" :model="changePasswordForm" :rules="changePasswordRules" label-width="100px">
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input
              v-model="changePasswordForm.oldPassword"
              type="password"
              placeholder="请输入当前密码"
              show-password
            />
          </el-form-item>

          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="changePasswordForm.newPassword"
              type="password"
              placeholder="请输入新密码（至少8位）"
              show-password
            />
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="changePasswordForm.confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              show-password
              @keyup.enter="handleChangePassword"
            />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="handleCancelChangePassword">取消</el-button>
          <el-button
            type="primary"
            :loading="changingPassword"
            @click="handleChangePassword"
          >
            {{ changingPassword ? '修改中...' : '确认修改' }}
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'
import { addDynamicRoutes } from '@/router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { RESULT_CODE } from '@/constants'
import logger from '@/utils/logger'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loginFormRef = ref(null)
const changePasswordFormRef = ref(null)
const loading = ref(false)
const changingPassword = ref(false)

// 强制改密相关状态
const showChangePasswordDialog = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const changePasswordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const loginRules = {
  username: [
    { required: true, trigger: 'blur' },
    { min: 3, max: 50, trigger: 'blur' }
  ],
  password: [
    { required: true, trigger: 'blur' },
    { min: 3, max: 50, trigger: 'blur' }
  ]
}

// 确认密码验证规则
const validateConfirmPassword = (rule, value, callback) => {
  if (value !== changePasswordForm.newPassword) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const changePasswordRules = {
  oldPassword: [
    { required: true, message: '请输入旧密码', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 50, message: '密码长度在8-50个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

async function handleLogin() {
  if (!loginFormRef.value || loading.value) return

  try {
    await loginFormRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    // OPTIMIZATION 三.3.1：登录请求由后端 AuthController 设置 HttpOnly Cookie (WOS_TOKEN)
    // W-47：后端不再下发 tempToken，强制改密流程改凭 HttpOnly Cookie 完成
    const res = await request.post('/auth/sessions', loginForm, { skipAuthError: true })

    if (res.code === RESULT_CODE.SUCCESS) {
      // 检查是否需要强制改密
      if (res.data.needChangePassword) {
        ElMessage.warning('首次登录，请修改初始密码')
        // 显示强制改密弹窗
        showChangePasswordDialog.value = true
        // 预填旧密码
        changePasswordForm.oldPassword = loginForm.password
      } else {
        // 不需要改密，直接保存登录结果（避免重复调用登录 API）
        userStore.setLoginResult(res.data)
        // 注册动态权限路由
        addDynamicRoutes(userStore)

        // 登录成功提示与页面跳转
        const redirect = route.query.redirect || '/'
        // 使用 nextTick 确保 store 状态已完全更新后再导航
        // 防止 beforeEach 守卫读到不一致的状态
        ElMessage.success('登录成功')
        try {
          await router.push(redirect)
        } catch (navError) {
          // Vue Router 导航失败（如重复导航、重定向循环等）
          // 不影响用户使用，仅记录日志
          logger.warn('user_login_navigation', '导航结果: ' + (navError?.message || navError))
          // 如果当前不在主页面，强制跳转
          if (router.currentRoute.value.path === '/login') {
            try { await router.replace('/') } catch (_) { /* ignore */ }
          }
        }
      }
    }
  } catch (error) {
    logger.warn('user_login_failure', '登录失败: ' + (error?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function handleChangePassword() {
  if (!changePasswordFormRef.value || changingPassword.value) return

  try {
    await changePasswordFormRef.value.validate()
  } catch {
    return
  }

  changingPassword.value = true
  try {
    // W-47：强制改密凭 HttpOnly Cookie 鉴权（request 实例已开启 withCredentials，
    // 后端登录响应写入的 HttpOnly Cookie 会自动随请求携带），不再传递 tempToken
    const res = await request.put('/auth/password', {
      oldPassword: changePasswordForm.oldPassword,
      newPassword: changePasswordForm.newPassword
    })

    if (res.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('密码修改成功！正在重新登录...')

      // 关闭弹窗
      showChangePasswordDialog.value = false

      // 使用新密码重新登录
      loginForm.password = changePasswordForm.newPassword
      setTimeout(async () => {
        try {
          await userStore.login(loginForm)
          addDynamicRoutes(userStore)
          const redirect = route.query.redirect || '/'
          await router.push(redirect)
          ElMessage.success('登录成功')
        } catch (error) {
          logger.error('user_relogin_failure', '重新登录失败', { error: error?.message })
          // 重新登录失败不影响主流程，用户可以手动登录
        }
      }, 500)
    }
  } catch (error) {
    logger.warn('user_change_password_failure', '改密失败: ' + (error?.message || '未知错误'))
  } finally {
    changingPassword.value = false
  }
}

function handleCancelChangePassword() {
  ElMessageBox.confirm(
    '您尚未修改初始密码，确定要退出吗？',
    '提示',
    {
      confirmButtonText: '确定退出',
      cancelButtonText: '继续修改',
      type: 'warning'
    }
  ).then(() => {
    // 用户选择退出，关闭弹窗并清除表单数据
    showChangePasswordDialog.value = false
    changePasswordForm.oldPassword = ''
    changePasswordForm.newPassword = ''
    changePasswordForm.confirmPassword = ''
  }).catch(() => {
    // 用户选择继续修改，不做任何操作
  })
}
</script>

<style scoped lang="scss">
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: var(--color-surface-page);
  padding: var(--space-lg);
}

.login-card {
  width: 100%;
  max-width: 380px;
  padding: 48px 40px 40px;
  background-color: var(--color-surface-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.login-header {
  margin-bottom: 40px;

  h1 {
    font-size: 24px;
    font-weight: 500;
    color: var(--color-ink);
    letter-spacing: -0.02em;
    margin: 0 0 8px 0;
    text-wrap: balance;
  }

  p {
    font-size: 13px;
    color: var(--color-ink-muted);
    letter-spacing: 0.03em;
    margin: 0;
  }
}

.login-form {
  :deep(.el-input__wrapper) {
    box-shadow: 0 0 0 1px var(--color-border-input) inset;
    border-radius: var(--radius-md);
    padding: 4px 12px;
    transition: box-shadow var(--duration-fast) var(--ease-standard);

    &:hover {
      box-shadow: 0 0 0 1px var(--color-ink-muted) inset;
    }

    &.is-focus {
      box-shadow: 0 0 0 1px var(--color-accent) inset;
    }
  }

  :deep(.el-input__inner) {
    height: 42px;
    line-height: 42px;
    font-size: 14px;
    color: var(--color-ink);

    &::placeholder {
      color: var(--color-ink-placeholder);
    }
  }

  :deep(.el-form-item) {
    margin-bottom: var(--space-lg);
  }

  :deep(.el-form-item__error) {
    color: var(--color-ink-muted);
    font-size: 12px;
    padding-top: 4px;
  }
}

.login-btn {
  width: 100%;
  height: 44px;
  background-color: #409EFF;
  color: #ffffff;
  border: none;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-standard);

  &:hover:not(:disabled) {
    background-color: #66b1ff;
  }

  &:active:not(:disabled) {
    background-color: #3a8ee6;
  }

  &:focus-visible {
    outline: 2px solid #409EFF;
    outline-offset: 2px;
  }

  &:disabled,
  &.is-loading {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.change-password-content {
  .el-alert {
    margin-bottom: 20px;
  }
}
</style>
