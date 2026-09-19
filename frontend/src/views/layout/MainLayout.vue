<template>
  <el-container class="main-layout">
    <!-- 移动端遮罩层 -->
    <div
      v-if="isMobile && sidebarVisible"
      class="mobile-overlay"
      @click="sidebarVisible = false"
    />

    <!-- 侧边栏 -->
    <el-aside
      :width="sidebarWidth"
      :class="{ 'sidebar': true, 'sidebar-mobile': isMobile, 'sidebar-visible': isMobile && sidebarVisible }"
    >
      <!-- 标题区域 -->
      <div class="header-section">
        <h1 class="system-title">工单审批系统</h1>

        <!-- 移动端关闭按钮 -->
        <button
          v-if="isMobile"
          class="mobile-close-btn"
          @click="sidebarVisible = false"
        >
          ✕
        </button>
      </div>

      <!-- 菜单区域 -->
      <nav class="menu-section">
        <ul class="menu-list">
          <!-- 我的工单 - 超级管理员无需查看 -->
          <li v-if="!isSuperAdmin" class="menu-item">
            <router-link to="/workorder/mine" class="menu-link" active-class="menu-link-active">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M14 2H6C5.46957 2 4.96086 2.21071 4.58579 2.58579C4.21071 2.96086 4 3.46957 4 4V20C4 20.5304 4.21071 21.0391 4.58579 21.4142C4.96086 21.7893 5.46957 22 6 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V8L14 2Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M14 2V8H20" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M12 18V12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M9 15L12 12L15 15" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>我的工单</span>
            </router-link>
          </li>

          <!-- 待我审批 - 管理员和经理可见 -->
          <li v-if="canAccessPending" class="menu-item">
            <router-link to="/workorder/pending" class="menu-link" active-class="menu-link-active">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5"/>
                <path d="M12 6V12L16 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <circle cx="17" cy="7" r="3" fill="var(--color-accent)" fill-opacity="0.1" stroke="var(--color-accent)" stroke-width="1.5"/>
                <path d="M17 5.5V7.5M17 8.5V8.5" stroke="var(--color-accent)" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
              <span>待我审批</span>
            </router-link>
          </li>

          <!-- 提交工单 - 超级管理员和管理层无需提交工单 -->
          <li v-if="!isSuperAdmin && canSubmitWorkOrder" class="menu-item">
            <router-link to="/workorder/create" class="menu-link" active-class="menu-link-active">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M11 4H4C3.46957 4 2.96086 4.21071 2.58579 4.58579C2.21071 4.96086 2 5.46957 2 6V20C2 20.5304 2.21071 21.0391 2.58579 21.4142C2.96086 21.7893 3.46957 22 4 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M18.5 2.50001C18.8978 2.10219 19.4374 1.87869 20 1.87869C20.5626 1.87869 21.1022 2.10219 21.5 2.50001C21.8978 2.89884 22.1213 3.43841 22.1213 4.00001C22.1213 4.56161 21.8978 5.10119 21.5 5.50001L12 15L8 16L9 12L18.5 2.50001Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>提交工单</span>
            </router-link>
          </li>

          <!-- 全部工单 - 根据角色隔离显示 -->
          <li v-if="canViewAllWorkOrders" class="menu-item">
            <router-link to="/workorder/list" class="menu-link" active-class="menu-link-active">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="3" y="3" width="7" height="7" rx="1" stroke="currentColor" stroke-width="1.5"/>
                <rect x="14" y="3" width="7" height="7" rx="1" stroke="currentColor" stroke-width="1.5"/>
                <rect x="3" y="14" width="7" height="7" rx="1" stroke="currentColor" stroke-width="1.5"/>
                <rect x="14" y="14" width="7" height="7" rx="1" stroke="currentColor" stroke-width="1.5"/>
              </svg>
              <span>全部工单</span>
            </router-link>
          </li>

          <!-- 员工管理 - 仅超级管理员和部门管理员可见 -->
          <li v-if="isSuperAdminOrDeptAdmin" class="menu-item">
            <router-link to="/employee/list" class="menu-link" active-class="menu-link-active">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M17 21V19C17 17.9391 16.5786 16.9217 15.8284 16.1716C15.0783 15.4214 14.0609 15 13 15H5C3.93913 15 2.92172 15.4214 2.17157 16.1716C1.42143 16.9217 1 17.9391 1 19V21" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M9 11C11.2091 11 13 9.20914 13 7C13 4.79086 11.2091 3 9 3C6.79086 3 5 4.79086 5 7C5 9.20914 6.79086 11 9 11Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M23 21V19C22.9993 18.1137 22.7044 17.2528 22.1614 16.5523C21.6184 15.8519 20.8581 15.3516 20 15.13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M16 3.13C16.8604 3.35031 17.623 3.85071 18.1676 4.55232C18.7122 5.25392 19.0078 6.11683 19.0078 7.005C19.0078 7.89318 18.7122 8.75608 18.1676 9.45769C17.623 10.1593 16.8604 10.6597 16 10.88" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>员工管理</span>
            </router-link>
          </li>

          <!-- 系统设置 - 仅超级管理员可见 -->
          <li v-if="isSuperAdmin" class="menu-item">
            <router-link to="/system/settings" class="menu-link" active-class="menu-link-active">
              <svg class="menu-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.5"/>
                <path d="M12 1V3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M12 21V23" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M4.22 4.22L5.64 5.64" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M18.36 18.36L19.78 19.78" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M1 12H3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M21 12H23" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M4.22 19.78L5.64 18.36" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                <path d="M18.36 5.64L19.78 4.22" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
              <span>系统设置</span>
            </router-link>
          </li>
        </ul>
      </nav>
    </el-aside>

    <!-- 主内容区 -->
    <el-container class="main-container">
      <!-- 顶部导航 -->
      <el-header class="header">
        <div class="header-left">
          <!-- 移动端菜单按钮 -->
          <button
            v-if="isMobile"
            class="menu-btn"
            @click="sidebarVisible = !sidebarVisible"
          >
            <el-icon :size="20"><Menu /></el-icon>
          </button>

          <el-breadcrumb separator="/" :class="{ 'hidden-mobile': isMobile }">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="$route.meta.title && !$route.meta.hidden">
              {{ $route.meta.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>

          <!-- 移动端页面标题 -->
          <span v-if="isMobile" class="mobile-page-title">
            {{ $route.meta.title || '首页' }}
          </span>
        </div>

        <div class="header-right">
          <span class="user-info" :class="{ 'hidden-mobile': !isMobile }">
            <el-icon><UserFilled /></el-icon>
            {{ displayName }}
          </span>
          <el-dropdown @command="handleCommand">
            <span class="el-dropdown-link">
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区域 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UserFilled, ArrowDown, Menu } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()

// 移动端检测
const isMobile = ref(false)
const sidebarVisible = ref(false)

// 检测屏幕尺寸
function checkScreenSize() {
  isMobile.value = window.innerWidth <= 768
  if (!isMobile.value) {
    sidebarVisible.value = false
  }
}

onMounted(() => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})

// 侧边栏宽度（桌面端固定，移动端动态）
const sidebarWidth = computed(() => {
  return isMobile.value ? '260px' : '240px'
})

// 显示名称
const displayName = computed(() => {
  return userStore.userInfo.realName || '访客用户'
})

// 部门管理员角色码（与后端 RoleConstants.DEPT_ADMIN_ROLE_IDS 对齐）
// 包含高管级（董事长/总经理/副总经理）和管理级（各部门总监）
const DEPT_ADMIN_ROLE_CODES = [
  'CHAIRMAN', 'GM', 'VP',
  'RD_DIR', 'SALES_DIR', 'FIN_DIR', 'ADMIN_DIR', 'HR_DIR', 'PROCUREMENT_DIR'
]

// 从 roles 提取角色码字符串数组（兼容字符串数组与对象数组两种形态）
function extractRoleCodes(roles) {
  if (!Array.isArray(roles)) return []
  return roles.map(r => {
    if (r == null) return ''
    if (typeof r === 'string') return r
    return r.roleCode || r.code || ''
  }).filter(Boolean)
}

// 当前用户的角色码列表（响应式，userInfo 变化时自动更新）
const userRoleCodes = computed(() => extractRoleCodes(userStore.userInfo?.roles))

// 是否为超级管理员（基于角色码判断，与后端 CustomUserDetails.isSuperAdmin() 对齐）
// 修复（2026-07-26）：原用 permissions.includes('system:user') 判断，但 system:user
// 是功能权限码不是身份标识，任何被分配该权限的角色都会被误判为超管。
const isSuperAdmin = computed(() => userRoleCodes.value.includes('SUPER_ADMIN'))

// 是否为超级管理员或部门管理员（可管理员工）
// 修复（2026-07-26）：原与 isSuperAdmin 完全等价（都只检查 system:user），
// 导致总监级角色（无 system:user 权限）看不到"员工管理"菜单，
// 后端的部门数据隔离逻辑在前端被架空。
const isSuperAdminOrDeptAdmin = computed(() =>
  isSuperAdmin.value ||
  userRoleCodes.value.some(code => DEPT_ADMIN_ROLE_CODES.includes(code))
)

// 是否可以访问待我审批（有 workorder:approve 权限，但系统级用户无需审批）
const canAccessPending = computed(() => {
  // 系统级用户(超级管理员)无需审批工单，隐藏待我审批菜单
  if (isSuperAdmin.value) return false
  const permissions = userStore.userInfo?.permissions || []
  return permissions.includes('workorder:approve')
})

// 是否可以提交工单（普通员工和基层员工需要）
const canSubmitWorkOrder = computed(() => {
  const permissions = userStore.userInfo?.permissions || []
  return permissions.includes('workorder:submit')
})

// 是否可以查看全部工单（高管级有 view-all，管理级有 approve）
const canViewAllWorkOrders = computed(() => {
  const permissions = userStore.userInfo?.permissions || []
  return permissions.includes('workorder:view-all') || permissions.includes('workorder:approve')
})

// 下拉菜单操作
async function handleCommand(command) {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      })

      await userStore.logout()
      ElMessage.success('已退出登录')
      // 使用 replace 防止回退到已注销的页面
      router.replace('/login').catch(() => { /* 忽略导航错误 */ })
    } catch (error) {
      // 用户取消或退出失败
    }
  } else if (command === 'profile') {
    ElMessage.info('功能开发中...')
  }
}
</script>

<style scoped lang="scss">
/* ========================================
   主布局容器
   ======================================== */
.main-layout {
  height: 100vh;
}

/* ========================================
   移动端遮罩层
   ======================================== */
.mobile-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.45);
  z-index: var(--z-overlay);
  backdrop-filter: blur(2px);
  transition: opacity var(--duration-normal) var(--ease-standard);
}

/* ========================================
   侧边栏 - 现代商务风格
   纯净通透，无莫兰迪灰调
   ======================================== */
.sidebar {
  background-color: var(--color-surface-card);
  overflow-y: auto;
  transition: transform var(--duration-sidebar) var(--ease-standard);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;

  /* ====== 标题区域 ====== */
  .header-section {
    background-color: var(--color-surface-sidebar-header);
    padding: var(--space-xl) var(--space-lg);
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-sm);
    border-bottom: 1px solid var(--color-border);
    position: relative;

    .system-title {
      color: var(--color-ink);
      font-size: 16px;
      font-weight: 600;
      margin: 0;
      letter-spacing: 0.5px;
      line-height: 1.3;
    }

    .mobile-close-btn {
      display: none; /* 桌面端隐藏 */
    }
  }

  /* ====== 菜单区域 ====== */
  .menu-section {
    flex: 1;
    padding: 12px 0;

    .menu-list {
      list-style: none;
      margin: 0;
      padding: 0 12px;

      .menu-item {
        margin-bottom: 2px;

        &:last-child {
          margin-bottom: 0;
        }
      }

      /* ====== 菜单链接样式 ====== */
      .menu-link {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 12px 16px;
        color: var(--color-ink-muted);
        font-size: 14px;
        font-weight: 400;
        text-decoration: none;
        border-radius: var(--radius-md);
        transition: all 0.3s ease;
        cursor: pointer;
        position: relative;
        overflow: hidden;

        /* 悬浮时的背景渐变效果 - 从左到右展开 */
        &::before {
          content: '';
          position: absolute;
          left: 0;
          top: 0;
          height: 100%;
          width: 0;
          background: linear-gradient(90deg, rgba(91, 143, 249, 0.08) 0%, transparent 100%);
          transition: width 0.3s ease;
          z-index: 0;
        }

        /* 图标样式 */
        .menu-icon {
          width: 20px;
          height: 20px;
          flex-shrink: 0;
          transition: all 0.2s ease;
          position: relative;
          z-index: 1;
        }

        /* 文字层级 */
        span {
          position: relative;
          z-index: 1;
        }

        /* ====== Hover状态 - 从左到右渐变展开 ====== */
        &:hover:not(.menu-link-active) {
          color: var(--color-accent, #5b8ff9);

          &::before {
            width: 100%;
          }

          .menu-icon {
            stroke-width: 2; /* 图标线条变粗 */
          }
        }

        /* ====== 点击反馈 ====== */
        &:active {
          transform: scale(0.99); /* 轻微缩放 */
          transition-duration: 0.1s;
        }

        /* ====== 选中状态 - 全宽背景色 + 渐变 ====== */
        &.menu-link-active {
          background-color: var(--color-accent-soft, rgba(91, 143, 249, 0.1));
          color: var(--color-accent, #5b8ff9);
          font-weight: 500;

          &::before {
            width: 100%;
            background: linear-gradient(90deg, rgba(91, 143, 249, 0.15) 0%, transparent 100%);
          }

          .menu-icon {
            stroke-width: 1.75;
          }
        }
      }
    }
  }
}

/* ========================================
   移动端侧边栏样式
   ======================================== */
.sidebar-mobile {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: var(--z-sidebar);
  transform: translateX(-100%);
  box-shadow: 4px 0 16px rgba(0, 0, 0, 0.08);

  &.sidebar-visible {
    transform: translateX(0);
  }

  .header-section {
    padding: 20px 16px;
    justify-content: center;

    .system-title {
      font-size: 15px;
    }

    .mobile-close-btn {
      position: absolute;
      right: 16px;
      top: 50%;
      transform: translateY(-50%);
      width: 32px;
      height: 32px;
      border: none;
      background: transparent;
      color: var(--color-ink-muted);
      font-size: 18px;
      cursor: pointer;
      border-radius: var(--radius-sm);
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background-color var(--duration-normal) var(--ease-standard),
        color var(--duration-normal) var(--ease-standard);

      &:hover {
        background-color: var(--color-surface-hover);
        color: var(--color-ink);
      }
    }
  }

  .menu-section .menu-list {
    padding: 0 8px;

    .menu-link {
      padding: 14px 16px;
      font-size: 15px;
    }
  }
}

/* ========================================
   顶部导航 - 商务简约风格
   ======================================== */
.header {
  background: var(--color-surface-card);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 var(--space-xl);
  height: 64px;
  border-bottom: 1px solid var(--color-border);

  .header-left {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 16px;

    .user-info {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--color-ink-body);
      font-size: 14px;
      font-weight: 400;
    }

    .el-dropdown-link {
      cursor: pointer;
      color: var(--color-ink-muted);
      display: flex;
      align-items: center;
      padding: 6px;
      border-radius: var(--radius-sm);
      transition: background-color var(--duration-normal) var(--ease-standard),
        color var(--duration-normal) var(--ease-standard);

      &:hover {
        background-color: var(--color-surface-hover);
        color: var(--color-ink);
      }
    }
  }
}

/* ========================================
   菜单按钮 - 简约风格
   ======================================== */
.menu-btn {
  display: none;
  width: 44px;
  height: 44px;
  padding: 8px;
  background: transparent;
  border: none;
  cursor: pointer;
  border-radius: var(--radius-md);
  transition: background-color var(--duration-normal) var(--ease-standard),
    color var(--duration-normal) var(--ease-standard);
  color: var(--color-ink-body);
  align-items: center;
  justify-content: center;

  &:hover {
    background-color: var(--color-surface-hover);
    color: var(--color-accent);
  }

  &:active {
    transform: scale(0.96);
  }
}

/* ========================================
   移动端页面标题
   ======================================== */
.mobile-page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ========================================
   主内容区 - 清爽干净
   ======================================== */
.main-container {
  overflow: hidden;
  background-color: var(--color-surface-page);
}

.main-content {
  background-color: var(--color-surface-page);
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/* 隐藏元素工具类 */
.hidden-mobile {
  display: contents;
}

/* ========================================
   桌面端侧边栏顶部圆角效果
   仅桌面端显示圆角，移动端不需要
   ======================================== */
@media screen and (min-width: 769px) {
  .sidebar:not(.sidebar-mobile) {
    border-top-left-radius: var(--radius-sidebar);
    border-top-right-radius: 0;
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;

    .header-section {
      border-top-left-radius: var(--radius-sidebar);
    }
  }
}

/* ========================================
   移动端响应式样式
   ======================================== */
@media screen and (max-width: 768px) {
  .header {
    padding: 0 var(--space-md);
    height: 56px;
  }

  .menu-btn {
    display: flex; /* 移动端显示 */
  }

  .hidden-mobile {
    display: none !important;
  }

  .mobile-page-title {
    display: block;
  }

  .header-right {
    gap: 8px;

    .user-info {
      font-size: 13px;
      color: var(--color-ink-body);
    }
  }
}

/* 平板端微调 */
@media screen and (min-width: 769px) and (max-width: 992px) {
  .header {
    padding: 0 20px;
  }

  .sidebar {
    .header-section {
      padding: 20px 16px;
    }
  }
}
</style>
