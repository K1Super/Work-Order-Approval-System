<template>
  <!-- el-config-provider 注入 Element Plus 中文 locale（按需引入后不再通过 app.use 注册 locale） -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
// 工单审批流转系统根组件
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<style>
#app {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>
