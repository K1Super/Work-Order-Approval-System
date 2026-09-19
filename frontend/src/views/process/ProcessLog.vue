<template>
  <div class="page-container">
    <div class="table-card" v-loading="loading">
      <!-- 返回按钮 -->
      <div class="page-header">
        <a class="back-link" @click="$router.back()">← 返回</a>
      </div>

      <h2 class="page-title">审批流程日志</h2>

      <!-- 时间线展示 -->
      <el-timeline v-if="logList.length > 0">
        <el-timeline-item
          v-for="log in logList"
          :key="log.id"
          :timestamp="formatDateTime(log.createTime)"
          placement="top"
          :type="getTimelineType(log.action)"
          :hollow="log.action === 'SUBMIT'"
        >
          <el-card shadow="never" class="flat-card">
            <div class="log-header">
              <el-tag :color="ACTION_MAP[log.action]?.color" effect="dark" size="small">
                {{ ACTION_MAP[log.action]?.label || log.action }}
              </el-tag>
              <span class="operator-name">{{ log.operatorName }}</span>
              <span class="task-name" v-if="log.taskName">【{{ log.taskName }}】</span>
            </div>

            <div class="log-content" v-if="log.comment">
              <strong>审批意见：</strong>{{ log.comment }}
            </div>

            <div class="log-meta">
              <el-row :gutter="20">
                <el-col :span="8">
                  <span class="meta-label">操作前状态：</span>
                  <el-tag size="small" type="info">{{ log.beforeStatus || '-' }}</el-tag>
                </el-col>
                <el-col :span="8">
                  <span class="meta-label">操作后状态：</span>
                  <el-tag size="small" :type="getStatusType(log.afterStatus)">
                    {{ log.afterStatus || '-' }}
                  </el-tag>
                </el-col>
                <el-col :span="8">
                  <span class="meta-label">节点状态：</span>
                  <el-tag size="small">{{ log.nodeStatus || '-' }}</el-tag>
                </el-col>
              </el-row>
            </div>
          </el-card>
        </el-timeline-item>
      </el-timeline>

      <el-empty v-else description="暂无审批日志" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getApprovalLog } from '@/api/workorder'
import { formatDateTime, ACTION_MAP, ORDER_STATUS_MAP } from '@/utils/common'

const route = useRoute()
const loading = ref(false)
const logList = ref([])

// 根据操作类型获取时间线样式
function getTimelineType(action) {
  switch (action) {
    case 'SUBMIT':
      return 'primary'
    case 'APPROVE':
      return 'success'
    case 'REJECT':
    case 'TERMINATE':
      return 'danger'
    case 'RETURN':
    case 'RESUBMIT':
      return 'warning'
    case 'ARCHIVE':
      return 'info'
    default:
      return ''
  }
}

// 获取状态标签类型
function getStatusType(status) {
  return ORDER_STATUS_MAP[status]?.type || 'info'
}

// 获取日志数据
async function fetchLogs() {
  loading.value = true
  try {
    const res = await getApprovalLog(route.params.id)
    logList.value = res.data || []
  } catch (error) {
    // 获取审批日志失败
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchLogs()
})
</script>

<style scoped lang="scss">
.log-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;

  .operator-name {
    font-weight: 600;
    color: var(--color-ink);
  }

  .task-name {
    color: var(--color-info);
    font-size: 13px;
  }
}

.log-content {
  margin-bottom: var(--space-sm);
  line-height: 1.6;
  color: var(--color-ink-secondary);
  background-color: var(--color-surface-muted);
  padding: var(--space-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-subtle);
}

.log-meta {
  .meta-label {
    color: var(--color-info);
    font-size: 13px;
    margin-right: 6px;
  }
}
</style>
