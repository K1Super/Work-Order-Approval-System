<template>
  <div class="welcome-container">
    <div class="welcome-card">
      <div class="welcome-icon">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M9 11L12 14L22 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M21 12V19C21 19.5304 20.7893 20.0391 20.4142 20.4142C20.0391 20.7893 19.5304 21 19 21H5C4.46957 21 3.96086 20.7893 3.58579 20.4142C3.21071 20.0391 3 19.5304 3 19V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H16" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <h2 class="welcome-title">欢迎使用工单审批系统</h2>
      <p class="welcome-desc">{{ welcomeMessage }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()

const welcomeMessage = computed(() => {
  const name = userStore.userInfo?.realName || '用户'
  return `${name}，请从左侧菜单选择功能开始使用`
})
</script>

<style scoped lang="scss">
.welcome-container {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: var(--space-xl);
}

.welcome-card {
  text-align: center;
  max-width: 400px;
}

.welcome-icon {
  margin-bottom: 24px;

  svg {
    width: 56px;
    height: 56px;
    color: var(--color-accent, #5b8ff9);
    opacity: 0.6;
  }
}

.welcome-title {
  font-size: 20px;
  font-weight: 500;
  color: var(--color-ink);
  margin: 0 0 12px 0;
}

.welcome-desc {
  font-size: 14px;
  color: var(--color-ink-muted);
  margin: 0;
  line-height: 1.6;
}
</style>
