import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'
import logger from '@/utils/logger'

// ============================================================
// 路由架构 —— 动态权限路由
// ============================================================
//
// 拆分为两部分：
// 1. constantRoutes —— 基础路由（静态，始终注册）：登录/重置密码/主布局壳
// 2. dynamicRoutes  —— 权限路由（按 RBAC 动态 addRoute）：主布局下的业务子路由
//
// 关键防白屏设计：
// - Layout 下始终有一个无权限要求的 Dashboard 子路由（path: ''），
//   确保认证用户访问 '/' 时永远有可渲染的组件
// - 所有 redirect 函数对认证用户永远不会返回 /login（避免重定向循环）
// - catch-all 路由对认证用户重定向到 '/' 而非 /login
// ============================================================

// ============================================================
// 基础路由（静态，始终注册）
// ============================================================
export const constantRoutes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/reset-password',
    name: 'ResetPassword',
    component: () => import('@/views/ResetPassword.vue'),
    meta: { title: '重置密码', requiresAuth: false }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/views/layout/MainLayout.vue'),
    redirect: () => {
      // 根据用户角色+权限决定默认页面
      // 核心原则：认证用户永远不返回 /login，防止重定向循环导致白屏
      const userStore = useUserStore()
      const permissions = userStore.permissions || []
      const isSuperAdmin = isSuperAdminUser(userStore)

      // 未登录 → 返回 /login
      if (!userStore.isLoggedIn) {
        return '/login'
      }

      // 部门管理员角色码（与 MainLayout.vue / 后端 RoleConstants 对齐）
      const DEPT_ADMIN_ROLE_CODES = [
        'CHAIRMAN', 'GM', 'VP',
        'RD_DIR', 'SALES_DIR', 'FIN_DIR', 'ADMIN_DIR', 'HR_DIR', 'PROCUREMENT_DIR'
      ]
      const userRoles = (userStore.userInfo?.roles || []).map(r =>
        r?.roleCode || r?.code || r
      )
      const isDeptAdmin = userRoles.some(code => DEPT_ADMIN_ROLE_CODES.includes(code))

      // 按优先级降级：优先返回有权限且已注册的路由
      if ((isSuperAdmin || isDeptAdmin) && permissions.includes('employee:view') && router.hasRoute('EmployeeManagement')) {
        return '/employee/list'
      }
      if ((permissions.includes('workorder:view-all') || permissions.includes('workorder:approve')) && router.hasRoute('WorkOrderList')) {
        return '/workorder/list'
      }
      if (permissions.includes('workorder:mine') && router.hasRoute('MyWorkOrders')) {
        return '/workorder/mine'
      }
      if (permissions.includes('workorder:submit') && router.hasRoute('CreateWorkOrder')) {
        return '/workorder/create'
      }
      // 超管兜底
      if (isSuperAdmin) {
        if (router.hasRoute('EmployeeManagement')) return '/employee/list'
        if (router.hasRoute('WorkOrderList')) return '/workorder/list'
        if (router.hasRoute('MyWorkOrders')) return '/workorder/mine'
      }

      // 权限兜底：即使路由未注册也返回路径（Dashboard 兜底路由会匹配）
      if (permissions.includes('workorder:mine')) return '/workorder/mine'
      if (permissions.includes('workorder:submit')) return '/workorder/create'
      if (permissions.includes('workorder:view-all')) return '/workorder/list'
      if (permissions.includes('employee:view')) return '/employee/list'

      // 最终兜底：返回 '/'（Dashboard 子路由 path='' 会匹配，渲染欢迎页）
      // 绝不返回 /login — 认证用户返回 /login 会触发 beforeEach 重定向到 /，形成死循环导致白屏
      return '/'
    },
    meta: { requiresAuth: true },
    children: [
      // 兜底子路由：path='' 匹配 '/' 精确路径
      // 无权限要求，所有认证用户都能看到
      // 这确保了无论动态路由是否注册，Layout 下永远有可渲染的组件
      {
        path: '',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/Dashboard.vue'),
        meta: { title: '首页', icon: 'HomeFilled' }
      }
    ]
  },
  {
    // 未匹配路由：未登录 → 登录页；已登录 → 首页
    // 关键：认证用户绝不重定向到 /login，避免与 beforeEach 形成死循环
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    redirect: (to) => {
      const userStore = useUserStore()
      if (!userStore.isLoggedIn) {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
      // 已登录但路由不存在 → 首页（Dashboard 兜底）
      return '/'
    }
  }
]

// ============================================================
// 动态权限路由（主布局子路由，按 RBAC addRoute）
// ============================================================
export const dynamicRoutes = [
  {
    path: 'workorder/mine',
    name: 'MyWorkOrders',
    component: () => import('@/views/workorder/MyWorkOrders.vue'),
    meta: { title: '我的工单', icon: 'Document', permission: 'workorder:mine' }
  },
  {
    path: 'workorder/pending',
    name: 'PendingApproval',
    component: () => import('@/views/workorder/PendingApproval.vue'),
    meta: { title: '待我审批', icon: 'Clock', permission: 'workorder:pending' }
  },
  {
    path: 'workorder/create',
    name: 'CreateWorkOrder',
    component: () => import('@/views/workorder/CreateWorkOrder.vue'),
    meta: { title: '提交工单', icon: 'EditPen', permission: 'workorder:submit' }
  },
  {
    path: 'workorder/list',
    name: 'WorkOrderList',
    component: () => import('@/views/workorder/WorkOrderList.vue'),
    meta: { title: '全部工单', icon: 'Files', permission: 'workorder:view-all' }
  },
  {
    path: 'workorder/detail/:id',
    name: 'WorkOrderDetail',
    component: () => import('@/views/workorder/WorkOrderDetail.vue'),
    meta: { title: '工单详情', hidden: true }
  },
  {
    path: 'process/log/:id',
    name: 'ProcessLog',
    component: () => import('@/views/process/ProcessLog.vue'),
    meta: { title: '流程日志', hidden: true }
  },
  {
    path: 'process/visualize/:id',
    name: 'ProcessVisualize',
    component: () => import('@/views/process/ProcessVisualize.vue'),
    meta: { title: '流程可视化', hidden: true }
  },
  {
    path: 'employee/list',
    name: 'EmployeeManagement',
    component: () => import('@/views/employee/EmployeeManagement.vue'),
    meta: { title: '员工管理', icon: 'UserFilled', permission: 'employee:view' }
  },
  {
    path: 'employee/resigned',
    name: 'ResignedEmployees',
    component: () => import('@/views/employee/ResignedEmployees.vue'),
    meta: { title: '离职员工', icon: 'UserFilled', permission: 'employee:view' }
  },
  {
    path: 'system/settings',
    name: 'SystemSettings',
    component: () => import('@/views/system/SystemSettings.vue'),
    meta: { title: '系统设置', icon: 'Setting', permission: 'system:user' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes
})

// 白名单路由（无需登录即可访问）
const whiteList = ['/login', '/reset-password']

/**
 * 判断用户是否为超级管理员
 */
function isSuperAdminUser(userStore) {
  const userRoles = (userStore.userInfo?.roles || []).map(r =>
    r?.roleCode || r?.code || r
  )
  return userRoles.includes('SUPER_ADMIN')
}

/**
 * 根据当前用户 RBAC 权限动态添加业务路由
 *
 * 规则：
 * - 超级管理员：添加全部动态路由
 * - 普通用户：仅添加有对应 permission 的路由
 * - 基础路由（我的工单/提交工单）：所有已登录用户都可访问
 * - 无 permission 的路由（详情页等）：始终添加
 *
 * 幂等：重复调用不会重复注册（通过 router.hasRoute 判断）
 */
export function addDynamicRoutes(userStore) {
  const permissions = userStore.permissions || []
  const isSuperAdmin = isSuperAdminUser(userStore)
  const isLoggedIn = userStore.isLoggedIn

  dynamicRoutes.forEach(route => {
    // 幂等：已注册则跳过
    if (route.name && router.hasRoute(route.name)) return

    const requiredPermission = route.meta?.permission

    // 基础路由：所有已登录用户都可访问（我的工单、提交工单）
    const isBasicRoute = ['workorder:mine', 'workorder:submit'].includes(requiredPermission)
    const hasPermission = isSuperAdmin || !requiredPermission || permissions.includes(requiredPermission) || (isLoggedIn && isBasicRoute)

    if (hasPermission) {
      router.addRoute('Layout', route)
    }
  })
}

// ============================================================
// 全局路由守卫
// ============================================================
router.beforeEach(async (to, from, next) => {
  // 设置页面标题
  document.title = to.meta.title ? `${to.meta.title} - 工单审批系统` : '工单审批系统'

  const userStore = useUserStore()
  const isLoggedIn = userStore.isLoggedIn

  if (isLoggedIn) {
    // 已登录状态
    if (to.path === '/login') {
      // 已登录用户访问登录页 → 重定向到首页
      return next({ path: '/', replace: true })
    }

    // 首次加载或刷新页面时，需要从后端验证 Token 并加载用户信息
    if (!userStore.isUserInfoLoaded) {
      try {
        await Promise.race([
          userStore.fetchUserInfo(),
          new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 5000))
        ])
        addDynamicRoutes(userStore)
        // 重新导航到同一目标，让新注册的路由生效
        return next({ ...to, replace: true })
      } catch (e) {
        // Token 过期/无效/超时 → 清除凭证，跳转登录
        userStore.resetState()
        return next({ path: '/login', query: { redirect: to.fullPath } })
      }
    }

    // 确保动态路由已注册（幂等）
    addDynamicRoutes(userStore)

    return next()
  } else {
    // 未登录状态
    if (whiteList.includes(to.path)) {
      next()
    } else {
      next({ path: '/login', query: { redirect: to.fullPath } })
    }
  }
})

// 全局路由错误处理：防止导航失败导致白屏
router.onError((error) => {
  logger.error('router_navigation_error', '路由导航错误', { error: error?.message })
})

export default router
