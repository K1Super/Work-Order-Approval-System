/**
 * v-purify-html 指令：XSS 安全的 HTML 渲染（规范 §3.1 src/directives，§3.3.6 富文本安全清洗）
 *
 * 替代 v-html，所有用户输入/富文本内容统一经 DOMPurify 净化后再写入 innerHTML，
 * 杜绝 XSS 前端漏洞。优先级最高，在 main.js 加载流水线第一步注册。
 *
 * 用法：<div v-purify-html="rawHtml"></div>
 * 别名：v-safe-html（向后兼容）
 */
import { sanitize } from '../utils/sanitize'

export const purifyHtml = {
  beforeMount: (el, binding) => {
    el.innerHTML = sanitize(binding.value)
  },
  updated: (el, binding) => {
    if (binding.oldValue !== binding.value) {
      el.innerHTML = sanitize(binding.value)
    }
  }
}

export default purifyHtml
