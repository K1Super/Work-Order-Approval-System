import DOMPurify from 'dompurify'

/**
 * 安全 HTML 净化工具 — 基于 DOMPurify
 *
 * 安全策略（§3 前端 XSS 防御）：
 * 1. 所有 v-html 渲染必须改用 v-safe-html 指令（main.js 全局注册）
 * 2. 默认白名单仅允许基础格式化标签，禁用 script/style/iframe 等
 * 3. 提供严格模式（纯文本）和工单内容模式（允许段落/列表/强调）
 */

// 默认配置：允许基础格式化标签
const DEFAULT_CONFIG = {
  ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'br', 'p', 'ul', 'ol', 'li', 'span'],
  ALLOWED_ATTR: ['class'],
  FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'textarea'],
  FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'onblur']
}

/**
 * 净化 HTML 字符串（默认白名单）
 * @param {string} dirty 待净化的 HTML
 * @param {object} config DOMPurify 配置（可选，覆盖默认）
 * @returns {string} 净化后的安全 HTML
 */
export function sanitize(dirty, config = DEFAULT_CONFIG) {
  if (!dirty) return ''
  return DOMPurify.sanitize(dirty, config)
}

/**
 * 严格净化：移除所有 HTML 标签（纯文本）
 * 用于标题、备注等仅需文本的字段
 * @param {string} dirty 待净化的字符串
 * @returns {string} 纯文本
 */
export function sanitizeStrict(dirty) {
  if (!dirty) return ''
  return DOMPurify.sanitize(dirty, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] })
}

/**
 * 净化工单内容（允许段落、列表、强调）
 * 用于工单正文等富文本字段
 * @param {string} dirty 待净化的 HTML
 * @returns {string} 净化后的安全 HTML
 */
export function sanitizeWorkOrderContent(dirty) {
  if (!dirty) return ''
  return DOMPurify.sanitize(dirty, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'ul', 'ol', 'li', 'span', 'h4', 'h5', 'h6'],
    ALLOWED_ATTR: ['class'],
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover']
  })
}

/**
 * 净化 URL（防 javascript: 协议注入）
 * @param {string} url 待净化的 URL
 * @returns {string} 安全的 URL（非法协议返回空字符串）
 */
export function sanitizeUrl(url) {
  if (!url) return ''
  const cleaned = String(url).trim()
  // 仅允许 http/https/mailto/tel/相对路径
  if (/^(https?:|mailto:|tel:|\/|\.\/|\.\.\/|#)/i.test(cleaned)) {
    return cleaned
  }
  return ''
}

export default DOMPurify
