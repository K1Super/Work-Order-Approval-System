<template>
  <div class="reset-container">
    <div class="reset-card">
      <!-- 头部 -->
      <div class="reset-header">
        <div class="reset-header-icon">
          <el-icon><Lock /></el-icon>
        </div>
        <h1>重置密码</h1>
        <p>Set a new password</p>
      </div>

      <Transition name="fade" mode="out-in">
        <!-- 加载中 -->
        <div v-if="status === 'loading'" key="loading" class="reset-status">
          <el-icon class="status-icon is-loading"><Loading /></el-icon>
          <p class="status-text">正在验证链接...</p>
        </div>

        <!-- 验证失败 -->
        <div v-else-if="status === 'invalid'" key="invalid" class="reset-status">
          <el-icon class="status-icon status-error"><CircleCloseFilled /></el-icon>
          <h2>链接无效</h2>
          <p class="status-text">{{ errorMessage }}</p>
          <button class="reset-btn" @click="goLogin">返回登录</button>
        </div>

        <!-- 重置表单 -->
        <el-form
          v-else-if="status === 'valid'"
          key="valid"
          ref="formRef"
          :model="form"
          :rules="rules"
          class="reset-form"
        >
          <div class="target-user">
            <span class="target-label">目标账号</span>
            <span class="target-name">{{ userInfo.realName }}</span>
            <span class="target-id">{{ userInfo.username }}</span>
          </div>

          <el-form-item prop="newPassword">
            <el-input
              v-model="form.newPassword"
              type="password"
              placeholder="新密码（至少8位，含大小写+数字+特殊字符）"
              size="large"
              show-password
              maxlength="50"
            />
          </el-form-item>

          <el-form-item prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="确认新密码"
              size="large"
              show-password
              maxlength="50"
              @keyup.enter="handleConfirm"
            />
          </el-form-item>

          <el-form-item>
            <button
              class="reset-btn"
              :class="{ 'is-loading': submitting }"
              :disabled="submitting"
              @click="handleConfirm"
            >
              {{ submitting ? '提交中...' : '确认重置' }}
            </button>
          </el-form-item>

          <div class="expiry-hint">
            <el-icon><Clock /></el-icon>
            <span>有效期至 {{ expiryText }}</span>
          </div>
        </el-form>

        <!-- 重置成功 -->
        <div v-else-if="status === 'success'" key="success" class="reset-status">
          <el-icon class="status-icon status-success"><CircleCheckFilled /></el-icon>
          <h2>重置成功</h2>
          <p class="status-text">请使用新密码登录系统</p>
          <button class="reset-btn" @click="goLogin">前往登录</button>
        </div>
      </Transition>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading, CircleCloseFilled, CircleCheckFilled, Clock, Lock } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { RESULT_CODE } from '@/constants'

const route = useRoute()
const router = useRouter()

const formRef = ref(null)
const status = ref('loading') // loading | valid | invalid | success
const submitting = ref(false)
const errorMessage = ref('')
const userInfo = reactive({
  username: '',
  realName: ''
})
const expiryTime = ref(null)

const form = reactive({
  newPassword: '',
  confirmPassword: ''
})

const validateConfirmPassword = (rule, value, callback) => {
  if (value !== form.newPassword) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 50, message: '密码长度在8-50个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

const expiryText = computed(() => {
  if (!expiryTime.value) return ''
  const date = new Date(expiryTime.value)
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const ss = String(date.getSeconds()).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
})

onMounted(async () => {
  const token = route.query.token
  if (!token) {
    status.value = 'invalid'
    errorMessage.value = '重置链接缺少必要的参数，请确认链接完整'
    return
  }

  try {
    const res = await request.post('/password-resets/validate', { token })
    if (res.code === RESULT_CODE.SUCCESS) {
      userInfo.username = res.data.username
      userInfo.realName = res.data.realName
      expiryTime.value = res.data.expiryTime
      status.value = 'valid'
    } else {
      status.value = 'invalid'
      errorMessage.value = res.msg || '重置链接无效'
    }
  } catch (err) {
    status.value = 'invalid'
    errorMessage.value = '验证重置链接失败，请稍后重试'
  }
})

async function handleConfirm() {
  if (!formRef.value || submitting.value) return

  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const token = route.query.token
    const res = await request.post('/password-resets/confirm', {
      token,
      newPassword: form.newPassword
    })

    if (res.code === RESULT_CODE.SUCCESS) {
      status.value = 'success'
      ElMessage.success('密码重置成功')
    } else {
      ElMessage.error(res.msg || '密码重置失败')
    }
  } catch (err) {
    const msg = err?.response?.data?.msg || err?.message || '密码重置失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}

function goLogin() {
  router.push('/login')
}
</script>

<style scoped lang="scss">
.reset-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: var(--color-surface-page);
  padding: var(--space-lg);
}

.reset-card {
  width: 100%;
  max-width: 400px;
  padding: 44px 36px 36px;
  background-color: var(--color-surface-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

/* ========== 头部 ========== */
.reset-header {
  margin-bottom: 32px;
  text-align: center;

  .reset-header-icon {
    width: 44px;
    height: 44px;
    margin: 0 auto 16px;
    border-radius: var(--radius-sm);
    background: var(--color-accent-soft);
    color: var(--color-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 20px;
  }

  h1 {
    font-size: 22px;
    font-weight: 500;
    color: var(--color-ink);
    letter-spacing: -0.02em;
    margin: 0 0 6px 0;
  }

  p {
    font-size: 13px;
    color: var(--color-ink-muted);
    letter-spacing: 0.03em;
    margin: 0;
  }
}

/* ========== 状态展示（loading / invalid / success） ========== */
.reset-status {
  text-align: center;
  padding: 16px 0;

  .status-icon {
    font-size: 44px;
    margin-bottom: 16px;

    &.is-loading {
      color: var(--color-accent);
      animation: spin 0.8s linear infinite;
    }

    &.status-error {
      color: var(--color-danger);
      animation: pop-in var(--duration-normal) var(--ease-standard);
    }

    &.status-success {
      color: var(--color-success);
      animation: pop-in var(--duration-normal) var(--ease-standard);
    }
  }

  h2 {
    font-size: 17px;
    font-weight: 500;
    color: var(--color-ink);
    margin: 0 0 8px 0;
    letter-spacing: -0.01em;
  }

  .status-text {
    font-size: 13px;
    color: var(--color-ink-muted);
    margin: 0 0 24px 0;
    line-height: 1.6;
  }
}

/* ========== 表单 ========== */
.reset-form {
  .target-user {
    display: flex;
    align-items: center;
    gap: var(--space-sm);
    margin-bottom: var(--space-lg);
    padding: 10px var(--space-md);
    background: var(--color-surface-inset);
    border-radius: var(--radius-sm);
    font-size: 13px;

    .target-label {
      color: var(--color-ink-muted);
      font-size: 12px;
    }

    .target-name {
      color: var(--color-ink);
      font-weight: 500;
    }

    .target-id {
      color: var(--color-ink-muted);
      font-size: 11px;
      padding: 2px 8px;
      background: var(--color-surface-card);
      border-radius: var(--radius-sm);
      font-family: 'SF Mono', 'Consolas', monospace;
      margin-left: auto;
    }
  }

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
      font-size: 13px;
    }
  }

  :deep(.el-form-item) {
    margin-bottom: var(--space-lg);
  }
}

/* ========== 按钮 ========== */
.reset-btn {
  width: 100%;
  height: 44px;
  background-color: var(--color-accent);
  color: #ffffff;
  border: none;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-standard),
              transform var(--duration-fast) var(--ease-standard);

  &:hover:not(:disabled):not(.is-loading) {
    background-color: var(--color-accent-hover);
  }

  &:active:not(:disabled):not(.is-loading) {
    transform: scale(0.99);
  }

  &:disabled,
  &.is-loading {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

/* ========== 有效期提示 ========== */
.expiry-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-ink-placeholder);

  .el-icon {
    font-size: 13px;
  }
}

/* ========== 过渡动画 ========== */
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--duration-fast) var(--ease-standard);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@keyframes pop-in {
  0% { transform: scale(0); opacity: 0; }
  60% { transform: scale(1.1); }
  100% { transform: scale(1); opacity: 1; }
}
</style>
