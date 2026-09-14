/**
 * 自定义全局指令统一注册出口（规范 §3.1 src/directives）
 *
 * 在 main.js 加载流水线中调用 registerDirectives(app) 一次性注册全部指令，
 * 入口文件不直接编写指令实现，仅引入初始化（入口规范 §一(二)2）。
 */
import { purifyHtml } from './purifyHtml'
import { permission } from './permission'

/**
 * 在 Vue 应用实例上注册全部自定义指令
 * @param {import('vue').App} app Vue 应用实例
 */
export function registerDirectives(app) {
  // XSS 净化指令（优先级最高，替代 v-html）
  app.directive('purify-html', purifyHtml)
  app.directive('safe-html', purifyHtml) // 向后兼容别名
  // 按钮级权限指令
  app.directive('permission', permission)
}

export { purifyHtml, permission }
export default { registerDirectives }
