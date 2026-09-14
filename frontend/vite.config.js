import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：
    // - AutoImport 自动引入 ElMessage/ElMessageBox/ElNotification/ElLoading 等 API
    // - Components 自动引入模板中使用的 <el-xxx> 组件
    // 两者配合 ElementPlusResolver 自动按需引入对应样式
    AutoImport({
      resolvers: [ElementPlusResolver()]
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  // 生产环境剔除 console/debugger（规范条款 3/11：严格分级、环境隔离）
  // 所有日志统一走 logger.js 工具类，不再直接调用 console
  esbuild: {
    drop: process.env.NODE_ENV === 'production' ? ['console', 'debugger'] : []
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 静默 Vite 4.x 使用的 Sass legacy JS API 弃用警告
        // （Vite 5.4+ 才支持 api: 'modern' 切换到 modern API；Vite 4.x 只能静默）
        // 该警告是工具链层面的，非项目代码问题
        silenceDeprecations: ['legacy-js-api']
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        // 拆分大 chunk，提升缓存命中率与首屏加载性能
        manualChunks: {
          // Vue 核心全家桶（vue + vue-router + pinia）
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          // Element Plus 图标（全量注册，独立打包）
          'element-plus-icons': ['@element-plus/icons-vue'],
          // echarts 按需引入模块（ProcessVisualize 引用）
          'echarts': ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers']
        }
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/api')
      }
    }
  }
})
