/**
 * 通用组合式工具方法统一出口（规范 §3.1 src/hooks/composables）
 *
 * 注意：request.js 直接从 ./useDebouncedMessage 引入防抖方法，避免经此 barrel
 * 触发 hooks → store/user → request → hooks 的循环依赖。
 */
export { showErrorWithDebounce, resetDebouncedMessage } from './useDebouncedMessage'
export { usePermission, default as usePermissionDefault } from './usePermission'
