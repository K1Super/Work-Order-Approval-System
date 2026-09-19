<template>
  <div class="page-container">
    <div class="table-card" v-loading="loading">
      <!-- 返回按钮 -->
      <div class="page-header">
        <a class="back-link" @click="$router.back()">← 返回</a>
      </div>

      <h2 class="page-title">流程可视化</h2>

      <!-- 流程图容器 -->
      <div id="process-chart" class="chart-container"></div>

      <!-- 图例说明 -->
      <div class="legend-container">
        <div class="legend-title">图例说明：</div>
        <div class="legend-items">
          <span class="legend-item"><i class="dot completed"></i>已完成节点</span>
          <span class="legend-item"><i class="dot current"></i>当前节点</span>
          <span class="legend-item"><i class="dot pending"></i>待处理节点</span>
          <span class="legend-item"><i class="dot rejected"></i>已拒绝/终止</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
// echarts 按需引入（避免全量打包 ~1MB，仅引入 graph 图表所需模块）
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getApprovalLog } from '@/api/workorder'

// 注册 echarts 模块（按需引入后必须显式注册）
echarts.use([
  GraphChart,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer
])

const route = useRoute()
const loading = ref(false)
let chartInstance = null

/**
 * 初始化流程可视化图表
 * 使用ECharts绘制流程流转路径图
 */
function initChart() {
  const chartDom = document.getElementById('process-chart')
  if (!chartDom) return

  chartInstance = echarts.init(chartDom)

  // 构建流程节点和连线数据（示例数据，实际应从后端API获取）
  const option = {
    title: {
      text: '通用审批流程',
      left: 'center',
      top: 10,
      textStyle: {
        fontSize: 16,
        fontWeight: 'bold'
      }
    },
    tooltip: {
      trigger: 'item',
      formatter: function(params) {
        if (params.dataType === 'node') {
          return `<strong>${params.name}</strong><br/>状态：${params.data.status}<br/>${params.data.info || ''}`
        }
        return params.name
      }
    },
    series: [
      {
        type: 'graph',
        layout: 'none',
        symbolSize: [120, 50],
        roam: true,
        draggable: true,
        edgeSymbol: ['circle', 'arrow'],
        edgeSymbolSize: [4, 10],
        edgeLabel: {
          fontSize: 12
        },
        label: {
          show: true,
          position: 'inside',
          fontSize: 13,
          fontWeight: 'bold',
          color: '#fff'
        },
        lineStyle: {
          width: 2,
          curveness: 0.15,
          color: '#1677FF'
        },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 2
        },
        data: [
          {
            name: '开始',
            x: 400,
            y: 50,
            category: 0,
            status: 'completed',
            info: '流程启动时间：2026-06-07 09:00:00',
            itemStyle: { color: '#67C23A' }
          },
          {
            name: '提交申请',
            x: 400,
            y: 150,
            category: 1,
            status: 'completed',
            info: '操作人：张三<br/>时间：2026-06-07 09:05:00',
            itemStyle: { color: '#67C23A' }
          },
          {
            name: '金额判断',
            x: 400,
            y: 250,
            category: 2,
            status: 'completed',
            info: '条件：金额 >= 10000',
            itemStyle: { color: '#E6A23C', shape: 'diamond' }
          },
          {
            name: '部门经理审批',
            x: 400,
            y: 360,
            category: 1,
            status: 'current',
            info: '当前处理人：李四<br/>等待审批中...',
            itemStyle: { color: '#1677FF' }
          },
          {
            name: 'HR审批',
            x: 200,
            y: 480,
            category: 1,
            status: 'pending',
            info: '待分配处理人',
            itemStyle: { color: '#909399' }
          },
          {
            name: '财务审批',
            x: 600,
            y: 480,
            category: 1,
            status: 'pending',
            info: '待分配处理人',
            itemStyle: { color: '#909399' }
          },
          {
            name: '自动归档',
            x: 400,
            y: 600,
            category: 3,
            status: 'pending',
            info: '系统自动执行',
            itemStyle: { color: '#909399' }
          },
          {
            name: '结束',
            x: 400,
            y: 700,
            category: 0,
            status: 'pending',
            itemStyle: { color: '#909399' }
          }
        ],
        links: [
          { source: '开始', target: '提交申请', label: { show: false } },
          { source: '提交申请', target: '金额判断', label: { show: false } },
          { source: '金额判断', target: '部门经理审批', label: { formatter: '金额>=10000' } },
          { source: '部门经理审批', target: 'HR审批', label: { show: false }, lineStyle: { type: 'dashed' } },
          { source: '部门经理审批', target: '财务审批', label: { show: false }, lineStyle: { type: 'dashed' } },
          { source: 'HR审批', target: '自动归档', label: { show: false } },
          { source: '财务审批', target: '自动归档', label: { show: false } },
          { source: '自动归档', target: '结束', label: { show: false } }
        ],
        categories: [
          { name: '开始/结束' },
          { name: '任务节点' },
          { name: '网关节点' },
          { name: '服务节点' }
        ]
      }
    ],
    animationDuration: 1500,
    animationEasingUpdate: 'quinticInOut'
  }

  chartInstance.setOption(option)

  // 响应式调整
  window.addEventListener('resize', handleResize)
}

function handleResize() {
  chartInstance?.resize()
}

// 获取实际流程数据并更新图表
async function fetchProcessData() {
  loading.value = true
  try {
    // 获取审批日志用于构建流程图数据
    await getApprovalLog(route.params.id)

    // TODO: 根据实际日志数据动态构建流程图的节点和连线

    // 等待DOM渲染完成后初始化图表
    await nextTick()
    initChart()
  } catch (error) {
    await nextTick()
    initChart() // 使用默认数据初始化
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchProcessData()
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chartInstance?.dispose()
})
</script>

<style scoped lang="scss">
.chart-container {
  width: 100%;
  height: 800px;
  background-color: var(--color-surface-muted);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-subtle);
}

.legend-container {
  margin-top: var(--space-lg);
  padding: var(--space-md);
  background-color: var(--color-surface-inset);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-subtle);

  .legend-title {
    font-weight: 600;
    color: var(--color-ink-secondary);
    margin-bottom: 10px;
  }

  .legend-items {
    display: flex;
    gap: var(--space-xl);
    flex-wrap: wrap;

    .legend-item {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 14px;
      color: var(--color-ink-secondary);

      .dot {
        display: inline-block;
        width: 12px;
        height: 12px;
        border-radius: 50%;

        &.completed { background-color: var(--color-success); }
        &.current { background-color: var(--color-accent); }
        &.pending { background-color: var(--color-info); }
        &.rejected { background-color: var(--color-danger); }
      }
    }
  }
}

@media screen and (max-width: 768px) {
  .chart-container {
    height: 480px;
  }

  .legend-items {
    flex-direction: column;
    gap: var(--space-xs) !important;
  }
}
</style>
