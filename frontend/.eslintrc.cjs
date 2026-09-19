// ============================================================
// ESLint 配置（经典 .eslintrc 方案）
// - ESLint 8 + eslint-plugin-vue 9，Node 18+ 兼容，规避 ESLint 9 flat config 迁移成本
// - 规则收紧度与后端 checkstyle 对齐：仅对确有合理的 Vue3 模板规则做项目级调整
// - 注意：本项目使用 unplugin-auto-import（ElementPlusResolver）自动注入
//   ElMessage/ElMessageBox/ElNotification/ElLoading 等 API，源码中无显式 import，
//   故在 globals 中显式放行，避免 no-undef 误报（非全局组件，仅对应自动导入 API）。
// ============================================================
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true
  },
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended'
  ],
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module'
  },
  globals: {
    // Element Plus 按需自动导入 API（见 vite.config.js AutoImport 配置）
    ElMessage: 'readonly',
    ElMessageBox: 'readonly',
    ElNotification: 'readonly',
    ElLoading: 'readonly'
  },
  rules: {
    // 现有组件命名以单单词为主（如 Login、App），且路由已固定、重命名会波及路由表与跳转
    // 属于存量命名惯例，非质量问题；此处项目级关闭以避免大规模无业务价值的重构。
    'vue/multi-word-component-names': 'off',

    // ---- 以下为纯模板格式化规则（非正确性规则），项目级关闭 ----
    // vue3-recommended 这些规则强制"每属性一行 / 标签内容换行 / 属性排序 / 自闭合"等排版风格，
    // 与既有代码紧凑写法冲突，若强制会触发 900+ 处无业务价值的模板重排。
    // 保留 vue3-recommended 的全部正确性规则（valid-v-* / require-v-for-key / no-mutating-props 等），
    // 仅关闭格式化类规则，收紧度与后端 checkstyle（聚焦真实问题）对齐。
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/multiline-html-element-content-newline': 'off',
    'vue/html-self-closing': 'off',
    'vue/html-closing-bracket-spacing': 'off',
    'vue/attributes-order': 'off'
  }
}