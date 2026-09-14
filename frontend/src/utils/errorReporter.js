/**
 * 全局异常捕获 + 错误上报 — 规范条款 5/7/9
 *
 * 注册 6 种异常捕获：
 * 1. Vue errorHandler — 组件渲染/生命周期/事件处理器错误
 * 2. window.onerror — JS 运行时错误
 * 3. window.addEventListener('error', capture) — 资源加载错误（img/script/link）
 * 4. window.addEventListener('unhandledrejection') — 未捕获的 Promise 拒绝
 * 5. beforeunload — 页面卸载前强制上报
 * 6. visibilitychange — 页面切到后台时上报
 */

import logger from '@/utils/logger'

/**
 * 注册全局异常捕获
 * @param {import('vue').App} app Vue 应用实例
 */
export function setupGlobalErrorHandlers(app) {
  // 1. Vue 组件渲染错误（升级现有 errorHandler）
  app.config.errorHandler = (err, _vm, info) => {
    logger.error('VueRender', `Vue渲染异常: ${err.message}`, {
      info,
      stack: err.stack || null,
      component: _vm?.$options?.name || 'Unknown'
    })
  }

  // 2. window.onerror — 捕获 JS 运行时错误
  // 覆盖同步脚本错误、语法错误等
  window.onerror = (message, source, lineno, colno, error) => {
    logger.error('RuntimeError', `JS运行时错误: ${message}`, {
      source, lineno, colno,
      stack: error?.stack || null
    })
    return false // 不阻止默认控制台输出（开发环境保留完整堆栈）
  }

  // 3. window.addEventListener('error', capture) — 资源加载错误
  // useCapture=true 在捕获阶段拦截，才能捕获资源加载错误
  window.addEventListener('error', (event) => {
    if (event.target && event.target !== window) {
      // 资源加载错误（img/script/link 等）
      const tag = event.target.tagName?.toLowerCase()
      const src = event.target.src || event.target.href || 'unknown'
      logger.error('ResourceError', `资源加载失败: <${tag}> ${src}`)
    }
  }, true)

  // 4. unhandledrejection — 未捕获的 Promise 拒绝
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    logger.error('UnhandledRejection', `未处理的Promise拒绝: ${reason?.message || reason}`, {
      stack: reason?.stack || null
    })
    event.preventDefault() // 阻止浏览器默认的控制台报错
  })

  // 5. beforeunload — 页面卸载前强制上报
  window.addEventListener('beforeunload', () => {
    logger.forceFlush()
  })

  // 6. visibilitychange — 页面切到后台时上报
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      logger.forceFlush()
    }
  })
}
