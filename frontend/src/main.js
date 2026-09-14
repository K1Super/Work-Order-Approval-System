import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElLoading } from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router, { addDynamicRoutes } from './router'
import { useUserStore } from './store/user'
import { registerDirectives } from './directives' // XSS 净化 + 权限指令（入口规范：指令抽离独立文件）
import './utils/request' // Axios 拦截器挂载（CSRF Token + withCredentials Cookie + TraceId 透传 + 错误处理）
import './styles/index.scss'
import logger from '@/utils/logger'
import { setupGlobalErrorHandlers } from '@/utils/errorReporter'

// Element Plus 全局调用型组件样式（按需引入后需手动注入，规范 §2 第 5 步 ElementPlus 按需）
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/loading/style/css'

// ===== 1. 创建应用实例 =====
const app = createApp(App)

// ===== 2. DOMPurify 安全指令 + 权限指令（规范 §2 第 1 条 — 优先级最高，替代 v-html） =====
// 指令实现抽离至 src/directives，入口仅做注册（入口规范 §一(二)2 公共工具注入）
registerDirectives(app)

// ===== 3. 注册 Element Plus 图标（全局通用轻量组件，非重量级库） =====
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// ===== 4. 链式注册插件（Pinia → Router，规范第 5 步） =====
app.use(createPinia()).use(router)

// ===== 5. 生产环境严格降级（规范 §2 第 4 条） =====
if (import.meta.env.PROD) {
  app.config.devtools = false
  app.config.performance = false
}

// ===== 6. 全局异常捕获前置（规范 §2 第 5 条，mount 前挂载） =====
// 使用统一日志工具输出（规范条款 1：统一出口，禁止裸调用 console）
app.config.errorHandler = (err, _vm, info) => {
  logger.error('vue_render_error', 'Vue 组件渲染异常', { info, error: err?.message || String(err) })
  // 防止组件渲染错误导致白屏：不抛出异常，仅记录日志
}
window.addEventListener('unhandledrejection', (e) => {
  logger.error('unhandled_promise_rejection', '未处理的 Promise 拒绝', { reason: e.reason?.message || String(e.reason) })
  // 防止未处理的 Promise 拒绝导致页面崩溃
  e.preventDefault()
})

// ===== 7. initApp：白屏治理 + 权限预加载 + 动态路由（规范 §2 第 3 条 + 第 7 步） =====
// 流水线：开启 Loading → 并行拉取用户信息/RBAC（3 秒超时兜底）→ addRoute → router.isReady → 关闭 Loading → mount
async function initApp() {
  const loading = ElLoading.service({ fullscreen: true, lock: true, text: '正在加载系统…' })
  // 修复（2026-07-26）：原在此处 fetchUserInfo + addDynamicRoutes（HTTP macrotask），
  // 但 app.use(router) 已自动启动初始导航（microtask），先于此处执行，
  // 导致 redirect 时动态路由未注册 → /404。
  // 现改为在 router.beforeEach 异步守卫中处理 userInfo 加载 + 动态路由注册，
  // router.isReady() 会在守卫完成（含重新导航）后 resolve，再 mount。
  await router.isReady()
  loading.close()
  app.mount('#app')
}

initApp()
