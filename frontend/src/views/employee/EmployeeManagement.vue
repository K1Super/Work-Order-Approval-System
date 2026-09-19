<template>
  <div class="page-container">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-form :inline="true" :model="searchForm" class="filter-form">
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="姓名/用户名/手机号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="searchForm.deptId" placeholder="全部" clearable style="width: 150px">
            <el-option
              v-for="dept in departments"
              :key="dept.value"
              :label="dept.label"
              :value="dept.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="组织层级">
          <el-select v-model="searchForm.orgLevel" placeholder="全部" clearable style="width: 140px">
            <el-option label="系统级" :value="0" />
            <el-option label="高管级" :value="1" />
            <el-option label="管理级" :value="2" />
            <el-option label="专员级" :value="3" />
            <el-option label="员工级" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="default" class="search-btn" @click="handleSearch">搜索</el-button>
          <el-button @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar-btn">
        <el-button
          v-if="isSuperAdmin"
          type="default"
          icon="Plus"
          class="add-btn"
          @click="handleAdd"
        >
          新增员工
        </el-button>
        <el-button
          v-if="isSuperAdmin"
          type="default"
          icon="Download"
          class="export-btn"
          @click="handleExport"
        >
          导出数据
        </el-button>
        <el-button
          type="default"
          icon="UserFilled"
          class="resigned-btn"
          @click="$router.push('/employee/resigned')"
        >
          离职档案
        </el-button>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-card">
      <el-table
        :data="employeeList"
        border
        stripe
        v-loading="loading"
      >
        <el-table-column prop="employeeId" label="工号" width="90" align="center">
          <template #default="{ row }">
            <span class="employee-id-text">{{ row.employeeId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="realName" label="姓名" min-width="90" />
        <el-table-column prop="phone" label="手机号" min-width="120" />
        <el-table-column label="部门" min-width="100">
          <template #default="{ row }">
            {{ getDeptName(row.departmentId) }}
          </template>
        </el-table-column>
        <el-table-column label="职位" min-width="160">
          <template #default="{ row }">
            <span v-if="row.roleNames && row.roleNames.length > 0" class="position-text-list">
              <span
                v-for="(roleName, idx) in row.roleNames"
                :key="idx"
                class="position-text-item"
              >{{ roleName }}<span v-if="idx < row.roleNames.length - 1" class="pos-sep">、</span></span>
            </span>
            <span v-else-if="row.positionId" class="text-muted">
              {{ getPositionName(row.positionId) }}
            </span>
            <span v-else class="text-muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="组织层级" width="100" align="center">
          <template #default="{ row }">
            <span :class="['org-level-text', `level-${row.orgLevel}`]">
              {{ getOrgLevelText(row.orgLevel) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="70" align="center">
          <template #default="{ row }">
            <span :class="['status-text', row.status === 1 ? 'status-active' : 'status-inactive']">
              {{ getStatusText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="入职时间" width="110" align="center">
          <template #default="{ row }">
            {{ row.hireDate ? row.hireDate.substring(0, 10) : '-' }}
          </template>
        </el-table-column>
        <el-table-column
          v-if="canManageEmployee"
          label="操作"
          width="180"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="action-btns-row">
              <a class="action-link primary" @click="handleEdit(row)">编辑</a>
              <span class="action-divider">|</span>
              <a class="action-link warning" @click="handleToggleStatus(row)">
                {{ row.status === 1 ? '禁用' : '启用' }}
              </a>
              <span class="action-divider">|</span>
              <a class="action-link info" @click="handleResetPassword(row)">重置</a>
              <span class="action-divider">|</span>
              <el-dropdown trigger="click" @command="(cmd) => handleCommand(cmd, row)" class="action-dropdown">
                <a class="action-link primary">更多</a>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="roles">分配权限</el-dropdown-item>
                    <el-dropdown-item command="resign">
                      员工离职
                    </el-dropdown-item>
                    <el-dropdown-item command="delete">
                      删除员工
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div v-if="total > 0" class="pagination-container">
        <el-pagination
          v-model:current-page="searchForm.pageNum"
          v-model:page-size="searchForm.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="sizes, prev, pager, next"
          @size-change="loadEmployees"
          @current-change="loadEmployees"
        />
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      :title="dialogTitle"
      v-model="dialogVisible"
      width="650px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="12">
            <!-- 工号：系统自动生成，不可修改 -->
            <el-form-item label="工号">
              <el-input
                v-model="formData.employeeId"
                disabled
                placeholder="系统自动生成"
              >
                <template #prefix>
                  <span style="color: #409eff; font-weight: 600;">#</span>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="真实姓名" prop="realName">
              <el-input v-model="formData.realName" placeholder="请输入真实姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="formData.phone" placeholder="请输入手机号" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="部门" prop="departmentId">
              <el-select
                v-model="formData.departmentId"
                placeholder="请选择部门"
                style="width: 100%"
                @change="handleDeptChange"
              >
                <el-option
                  v-for="dept in departments"
                  :key="dept.value"
                  :label="dept.label"
                  :value="dept.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="职位" prop="positionId">
              <el-select
                v-model="formData.positionId"
                placeholder="请选择职位"
                style="width: 100%"
                @change="handlePositionChange"
              >
                <el-option-group label="【系统级】">
                  <el-option
                    v-for="role in systemLevelRoles.filter(r => !r.isPosition)"
                    :key="role.value"
                    :label="role.label"
                    :value="role.value"
                  >
                    <span style="color: #f56c6c;">{{ role.label }}</span>
                  </el-option>
                </el-option-group>
                <el-option-group label="【高管级】">
                  <el-option
                    v-for="role in executiveLevelRoles"
                    :key="role.value"
                    :label="role.label"
                    :value="role.value"
                  />
                </el-option-group>
                <el-option-group label="【管理级】">
                  <el-option
                    v-for="role in managerLevelRoles"
                    :key="role.value"
                    :label="role.label"
                    :value="role.value"
                  />
                </el-option-group>
                <el-option-group label="【专员级】">
                  <el-option
                    v-for="role in specialistLevelRoles"
                    :key="role.value"
                    :label="role.label"
                    :value="role.value"
                  />
                </el-option-group>
                <el-option-group label="【员工级】">
                  <el-option
                    v-for="role in employeeLevelRoles"
                    :key="role.value"
                    :label="role.label"
                    :value="role.value"
                  />
                </el-option-group>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="直属上级" prop="superiorId">
              <el-select
                v-model="formData.superiorId"
                placeholder="请选择上级"
                style="width: 100%"
                filterable
              >
                <el-option
                  v-for="user in superiorOptions"
                  :key="user.id"
                  :label="user.department ? (user.realName + ' - ' + user.department) : user.realName"
                  :value="user.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="组织层级" prop="orgLevel">
              <el-select v-model="formData.orgLevel" placeholder="根据职位自动计算" style="width: 100%" disabled>
                <el-option label="系统级 (0)" :value="0" />
                <el-option label="高管级 (1)" :value="1" />
                <el-option label="管理级 (2)" :value="2" />
                <el-option label="专员级 (3)" :value="3" />
                <el-option label="员工级 (4)" :value="4" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="入职时间" prop="hireDate">
              <el-date-picker
                v-model="formData.hireDate"
                type="date"
                placeholder="请选择入职日期"
                style="width: 100%"
                value-format="YYYY-MM-DD"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="formData.status">
                <el-radio :value="1">启用</el-radio>
                <el-radio :value="0">禁用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 密码管理区域（仅编辑模式显示） -->
        <div v-if="isEdit" class="password-management-section">
          <el-divider content-position="left">密码与安全设置</el-divider>

          <!-- 当前密码状态 -->
          <el-form-item label="当前状态">
            <div>
              <span :class="formData.passwordChanged === false ? 'status-text-danger' : 'status-text-normal'">
                {{ formData.passwordChanged === false ? '未修改初始密码（强制改密）' : '已修改初始密码' }}
              </span>
              <div v-if="formData.passwordChanged === false" class="simple-warning-inline">
                该员工下次登录时将被强制要求修改密码
              </div>
            </div>
          </el-form-item>

          <!-- 重置密码选项 -->
          <el-form-item label="重置密码">
            <el-checkbox v-model="formData.resetPasswordFlag" @change="handleResetPasswordChange">
              重置为随机临时密码（8位强密码）
            </el-checkbox>
            <div v-if="formData.resetPasswordFlag" class="simple-hint">
              将生成新的随机密码并要求员工下次登录时修改
            </div>
          </el-form-item>

          <!-- 强制改密选项 -->
          <el-form-item label="强制改密">
            <el-checkbox
              v-model="formData.forceChangePassword"
              :disabled="formData.passwordChanged === false || formData.resetPasswordFlag"
            >
              要求员工下次登录时修改密码
            </el-checkbox>
          </el-form-item>

          <!-- 快捷操作 -->
          <div class="quick-actions-simple">
            <el-button size="small" @click="handleQuickResetPassword" :loading="quickResetting">
              立即重置密码
            </el-button>
            <el-button size="small" type="primary" plain @click="handleForceChangeOnly" :disabled="formData.passwordChanged === false">
              仅强制改密
            </el-button>
          </div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配权限对话框 -->
    <el-dialog
      title="分配权限"
      v-model="roleDialogVisible"
      width="680px"
      :close-on-click-modal="false"
      class="position-dialog"
    >
      <!-- 员工信息头部 -->
      <div class="position-header">
        <div class="position-user-info">
          <span class="position-user-name">{{ currentUserName }}</span>
          <span class="position-user-hint">请选择要分配的权限级别和具体角色（最多2个）</span>
        </div>
        <div class="position-selected-count">
          已选 {{ selectedRoleIds.length }} / 2 个角色
        </div>
      </div>

      <!-- 权限列表 - 固定5个级别 -->
      <div class="position-list-container">
        <!-- 系统级 -->
        <div class="position-group">
          <div class="position-group-header">
            <div class="position-group-title-row">
              <span class="position-group-title system-level-title">系统级</span>
            </div>
            <div class="position-group-desc">拥有全系统配置、账号、流程、权限、审计日志全部操作权限</div>
            <div class="position-exclusive-hint">
              <el-icon><WarningFilled /></el-icon>
              <span>仅可单独选择，不可搭配其他角色</span>
            </div>
          </div>
          <div class="position-grid">
            <div
              v-for="role in systemLevelRoles"
              :key="role.value"
              class="position-card"
              :class="{
                'is-active': selectedRoleIds.includes(role.value),
                'is-disabled': !canSelectRole(role.value) && !selectedRoleIds.includes(role.value),
                'card-danger': role.danger
              }"
              @click="handleRoleClick(role.value)"
            >
              <div class="card-checkbox">
                <el-icon v-if="selectedRoleIds.includes(role.value)" class="check-icon"><Check /></el-icon>
                <div v-else class="checkbox-empty"></div>
              </div>
              <div class="card-content">
                <span class="card-name">{{ role.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 高管级 -->
        <div class="position-group">
          <div class="position-group-header">
            <div class="position-group-title-row">
              <span class="position-group-title">高管级</span>
            </div>
            <div class="position-group-desc">可查看、审批全公司所有工单，支持全量数据导出，无后台系统配置权限</div>
          </div>
          <div class="position-grid">
            <div
              v-for="role in executiveLevelRoles"
              :key="role.value"
              class="position-card"
              :class="{
                'is-active': selectedRoleIds.includes(role.value),
                'is-disabled': !canSelectRole(role.value) && !selectedRoleIds.includes(role.value)
              }"
              @click="handleRoleClick(role.value)"
            >
              <div class="card-checkbox">
                <el-icon v-if="selectedRoleIds.includes(role.value)" class="check-icon"><Check /></el-icon>
                <div v-else class="checkbox-empty"></div>
              </div>
              <div class="card-content">
                <span class="card-name">{{ role.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 管理级 -->
        <div class="position-group">
          <div class="position-group-header">
            <div class="position-group-title-row">
              <span class="position-group-title">管理级</span>
            </div>
            <div class="position-group-desc">仅管辖所属部门，可审批、导出本部门工单，无法跨部门查看其他业务数据</div>
          </div>
          <div class="position-grid">
            <div
              v-for="role in managerLevelRoles"
              :key="role.value"
              class="position-card"
              :class="{
                'is-active': selectedRoleIds.includes(role.value),
                'is-disabled': !canSelectRole(role.value) && !selectedRoleIds.includes(role.value)
              }"
              @click="handleRoleClick(role.value)"
            >
              <div class="card-checkbox">
                <el-icon v-if="selectedRoleIds.includes(role.value)" class="check-icon"><Check /></el-icon>
                <div v-else class="checkbox-empty"></div>
              </div>
              <div class="card-content">
                <span class="card-name">{{ role.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 专员级 -->
        <div class="position-group">
          <div class="position-group-header">
            <div class="position-group-title-row">
              <span class="position-group-title">专员级</span>
            </div>
            <div class="position-group-desc">可审批本部门工单，不能导出工单</div>
          </div>
          <div class="position-grid">
            <div
              v-for="role in specialistLevelRoles"
              :key="role.value"
              class="position-card"
              :class="{
                'is-active': selectedRoleIds.includes(role.value),
                'is-disabled': !canSelectRole(role.value) && !selectedRoleIds.includes(role.value)
              }"
              @click="handleRoleClick(role.value)"
            >
              <div class="card-checkbox">
                <el-icon v-if="selectedRoleIds.includes(role.value)" class="check-icon"><Check /></el-icon>
                <div v-else class="checkbox-empty"></div>
              </div>
              <div class="card-content">
                <span class="card-name">{{ role.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 员工级 -->
        <div class="position-group">
          <div class="position-group-header">
            <div class="position-group-title-row">
              <span class="position-group-title">员工级</span>
            </div>
            <div class="position-group-desc">仅能发起、查看本人相关工单，无任何批量导出、审批、后台配置权限</div>
          </div>
          <div class="position-grid">
            <div
              v-for="role in employeeLevelRoles"
              :key="role.value"
              class="position-card"
              :class="{
                'is-active': selectedRoleIds.includes(role.value),
                'is-disabled': !canSelectRole(role.value) && !selectedRoleIds.includes(role.value)
              }"
              @click="handleRoleClick(role.value)"
            >
              <div class="card-checkbox">
                <el-icon v-if="selectedRoleIds.includes(role.value)" class="check-icon"><Check /></el-icon>
                <div v-else class="checkbox-empty"></div>
              </div>
              <div class="card-content">
                <span class="card-name">{{ role.label }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部操作栏 -->
      <template #footer>
        <div class="dialog-footer-custom">
          <div class="footer-right">
            <el-button @click="roleDialogVisible = false">取消</el-button>
            <el-button
              type="primary"
              :loading="roleSubmitting"
              :disabled="selectedRoleIds.length === 0"
              @click="handleSubmitRoles"
              round
            >
              <el-icon><Check /></el-icon>
              确认分配
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 离职对话框 -->
    <el-dialog
      v-model="resignDialogVisible"
      title="员工离职处理"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form :model="resignForm" label-width="100px">
        <el-form-item label="员工姓名">
          <span class="resign-employee-name">{{ resignForm.realName }}</span>
        </el-form-item>
        <el-form-item label="离职类型" required>
          <el-select v-model="resignForm.resignType" placeholder="请选择离职类型" style="width: 100%">
            <el-option
              v-for="type in resignTypesList"
              :key="type.value"
              :label="type.label"
              :value="type.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="离职原因">
          <el-input
            v-model="resignForm.resignReason"
            type="textarea"
            :rows="3"
            placeholder="请输入离职原因（选填）"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="resignForm.remark"
            type="textarea"
            :rows="2"
            placeholder="请输入备注信息（选填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resignDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="resignSubmitting" @click="handleSubmitResign">
          确认离职
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置链接对话框 -->
    <el-dialog
      v-model="resetLinkDialogVisible"
      width="480px"
      :close-on-click-modal="false"
      align-center
      class="reset-link-dialog"
    >
      <template #header>
        <div class="rl-dialog-header">
          <div class="rl-header-icon">
            <el-icon><Key /></el-icon>
          </div>
          <div class="rl-header-text">
            <h3>密码重置链接</h3>
            <p>一次性 · {{ resetLinkData.expiryMinutes }} 分钟有效</p>
          </div>
        </div>
      </template>

      <div class="rl-body">
        <div class="rl-user-info">
          <span class="rl-user-label">目标账号</span>
          <span class="rl-user-name">{{ resetLinkData.realName }}</span>
          <span class="rl-user-id">{{ resetLinkData.username }}</span>
        </div>

        <div class="rl-link-card" @click="copyResetLink">
          <div class="rl-link-bar"></div>
          <code class="rl-link-text">{{ resetLinkData.fullLink }}</code>
          <button
            class="rl-copy-btn"
            :class="{ copied: linkCopied }"
            type="button"
            @click.stop="copyResetLink"
          >
            <el-icon class="rl-copy-icon">
              <Check v-if="linkCopied" />
              <CopyDocument v-else />
            </el-icon>
            <span>{{ linkCopied ? '已复制' : '复制' }}</span>
          </button>
        </div>

        <p class="rl-hint">员工打开链接后自行设置新密码，链接使用后即刻失效</p>
      </div>

      <template #footer>
        <button class="rl-close-btn" @click="resetLinkDialogVisible = false">我已知晓</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, WarningFilled, CopyDocument, Key } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import request from '@/utils/request'
import { RESULT_CODE } from '@/constants'
import { withReauth, confirmReauth, REAUTH_PASSWORD_HEADER } from '@/utils/reauth'

const userStore = useUserStore()

// 数据状态
const loading = ref(false)
const employeeList = ref([])
const departments = ref([])
const positions = ref([])
const roles = ref([])
const total = ref(0)
const superiorOptions = ref([])

// 搜索表单
const searchForm = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  deptId: null,
  orgLevel: null
})

// 对话框
const dialogVisible = ref(false)
const isEdit = ref(false)
const dialogTitle = ref('新增员工')
const formRef = ref(null)
const formData = reactive({
  id: null,
  employeeId: '',      // 工号（系统自动生成）
  password: '',
  realName: '',
  phone: '',
  departmentId: null,
  positionId: null,
  superiorId: null,
  orgLevel: 4,
  status: 1,
  hireDate: '',
  passwordChanged: true,   // 是否已修改初始密码
  resetPasswordFlag: false, // 是否重置密码
  forceChangePassword: false // 是否强制改密
})

// 快捷操作状态
const quickResetting = ref(false)

// 重置链接对话框状态
const resetLinkDialogVisible = ref(false)
const resetLinkData = reactive({
  fullLink: '',
  username: '',
  realName: '',
  expiryMinutes: 15
})
const linkCopied = ref(false)

// 表单校验规则
const formRules = {
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' }
  ],
  phone: [
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

// 角色对话框
const roleDialogVisible = ref(false)
const currentUserId = ref(null)
const currentUserName = ref('')
const selectedRoleIds = ref([])
const roleSubmitting = ref(false)

// 角色配置：最多分配2个职位
const maxRoles = 2

// 离职对话框相关
const resignDialogVisible = ref(false)
const resignSubmitting = ref(false)
const resignForm = reactive({
  id: null,
  realName: '',
  resignType: 1,
  resignReason: '',
  remark: ''
})
const resignTypesList = ref([
  { value: 1, label: '主动辞职' },
  { value: 2, label: '被动辞退' },
  { value: 3, label: '合同到期' },
  { value: 4, label: '退休' },
  { value: 5, label: '其他' }
])

// 固定5个权限级别的角色定义
const systemLevelRoles = ref([
  { value: 1, label: '超级管理员', orgLevel: 0, danger: true, isPosition: false },
  { value: 2, label: '安全审计管理员', orgLevel: 0, danger: true, isPosition: false }
])

const executiveLevelRoles = ref([
  { value: 10, label: '董事长', orgLevel: 1, isPosition: true },
  { value: 11, label: '总经理', orgLevel: 1, isPosition: true },
  { value: 12, label: '副总经理', orgLevel: 1, isPosition: true }
])

const managerLevelRoles = ref([
  { value: 20, label: '研发总监', orgLevel: 2, isPosition: true },
  { value: 21, label: '销售总监', orgLevel: 2, isPosition: true },
  { value: 22, label: '财务总监', orgLevel: 2, isPosition: true },
  { value: 23, label: '行政总监', orgLevel: 2, isPosition: true },
  { value: 24, label: '人事总监', orgLevel: 2, isPosition: true },
  { value: 25, label: '采购总监', orgLevel: 2, isPosition: true }
])

const specialistLevelRoles = ref([
  { value: 30, label: 'HR人事专员', orgLevel: 3, isPosition: true },
  { value: 31, label: '行政专员', orgLevel: 3, isPosition: true },
  { value: 32, label: '费用会计专员', orgLevel: 3, isPosition: true },
  { value: 33, label: '出纳专员', orgLevel: 3, isPosition: true },
  { value: 34, label: '销售专员', orgLevel: 3, isPosition: true },
  { value: 35, label: '研发工程师专员', orgLevel: 3, isPosition: true },
  { value: 36, label: '采购专员', orgLevel: 3, isPosition: true }
])

const employeeLevelRoles = ref([
  { value: 40, label: '普通员工', orgLevel: 4, isPosition: true }
])

// 所有角色的合并列表（用于API提交）
const allFixedRoles = computed(() => [
  ...systemLevelRoles.value,
  ...executiveLevelRoles.value,
  ...managerLevelRoles.value,
  ...specialistLevelRoles.value,
  ...employeeLevelRoles.value
])

// 判断是否可以选择该角色
function canSelectRole(roleId) {
  // 已选中的可以取消
  if (selectedRoleIds.value.includes(roleId)) {
    return true
  }

  // 达到数量上限（最多2个）
  if (selectedRoleIds.value.length >= maxRoles) {
    return false
  }

  // 获取当前点击的角色信息 - 使用固定角色列表
  const currentRole = allFixedRoles.value.find(r => r.value === roleId)
  if (!currentRole) return false

  const currentRoleLevel = currentRole.orgLevel

  // 系统级角色互斥：选了系统级就不能选其他，选了其他就不能选系统级
  if (currentRoleLevel === 0) {
    // 选择系统级时，必须没有选中其他角色
    return selectedRoleIds.value.length === 0
  }

  // 如果已选了系统级角色，不能再选其他
  const hasSystemLevel = selectedRoleIds.value.some(selectedId => {
    const selectedRole = allFixedRoles.value.find(r => r.value === selectedId)
    return selectedRole && selectedRole.orgLevel === 0
  })
  if (hasSystemLevel) {
    return false
  }

  return true
}

// 处理职位点击
function handleRoleClick(roleId) {
  if (!canSelectRole(roleId) && !selectedRoleIds.value.includes(roleId)) {
    const currentRole = allFixedRoles.value.find(r => r.value === roleId)
    const currentRoleLevel = currentRole ? currentRole.orgLevel : 4

    if (selectedRoleIds.value.length >= maxRoles) {
      ElMessage.warning(`最多只能选择${maxRoles}个角色`)
    } else if (currentRoleLevel === 0 && selectedRoleIds.value.length > 0) {
      ElMessage.warning('系统级权限仅可单独选择，不可搭配其他角色')
    } else {
      ElMessage.warning('系统级权限与其他角色互斥')
    }
    return
  }
  toggleRoleSelection(roleId)
}

// 部门管理员角色码（与 MainLayout.vue / 后端 RoleConstants.DEPT_ADMIN_ROLE_IDS 对齐）
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

// 当前用户的角色码列表（响应式）
const userRoleCodes = computed(() => extractRoleCodes(userStore.userInfo?.roles))

// 是否为超级管理员（基于角色码判断，与后端 CustomUserDetails.isSuperAdmin() 对齐）
// 修复（2026-07-26）：原用 permissions.includes('system:user') 判断，
// 但 system:user 是功能权限码不是身份标识，任何被分配该权限的角色都会被误判。
const isSuperAdmin = computed(() => userRoleCodes.value.includes('SUPER_ADMIN'))

// 是否可管理员工（超级管理员或部门管理员均可操作员工）
// 修复（2026-07-26）：原与 isSuperAdmin 完全等价（都只检查 system:user），
// 导致总监级角色（部门管理员）能看到员工列表但操作列被隐藏，
// 后端的部门数据隔离逻辑在前端被架空。
const canManageEmployee = computed(() =>
  isSuperAdmin.value ||
  userRoleCodes.value.some(code => DEPT_ADMIN_ROLE_CODES.includes(code))
)

// 加载数据
async function loadEmployees() {
  loading.value = true
  try {
    const response = await request.get('/employees/list', {
      params: searchForm
    })
    if (response.code === RESULT_CODE.SUCCESS) {
      employeeList.value = response.data.list || []
      total.value = response.data.total || 0
    }
  } catch (error) {
    ElMessage.error('加载员工列表失败')
  } finally {
    loading.value = false
  }
}

async function loadDepartments() {
  try {
    const response = await request.get('/employees/departments')
    if (response.code === RESULT_CODE.SUCCESS) {
      departments.value = response.data || []
    }
  } catch (error) {
    // 错误提示已由 request.js 响应拦截器统一处理，此处静默返回避免重复弹窗
  }
}

async function loadPositions() {
  try {
    const response = await request.get('/employees/positions')
    if (response.code === RESULT_CODE.SUCCESS) {
      positions.value = response.data || []
    }
  } catch (error) {
    // 错误提示已由 request.js 响应拦截器统一处理，此处静默返回避免重复弹窗
  }
}

async function loadRoles() {
  try {
    const response = await request.get('/employees/roles')
    if (response.code === RESULT_CODE.SUCCESS) {
      roles.value = response.data || []
    }
  } catch (error) {
    // 加载角色失败
  }
}

// 根据当前员工级别获取可选上级的级别范围
function getSuperiorOrgLevels(currentOrgLevel) {
  // 系统级(0): 无上级（超管不设上级）
  // 高管级(1): 无上级（已是最高业务级别，超管不作为业务上级）
  // 管理级(2): 可选高管级(1)
  // 专员级(3): 可选管理级(2)、高管级(1) — 最多跨越两级
  // 员工级(4): 可选专员级(3)、管理级(2) — 最多跨越两级
  // 规则：禁止系统级(0)作为直属上级，最多跨越两级
  if (currentOrgLevel === undefined || currentOrgLevel === null) {
    return [1, 2, 3] // 默认返回非系统级
  }
  if (currentOrgLevel <= 1) {
    return [] // 系统级和高管级无上级
  }
  const levels = []
  // 从当前级别的上一级开始，最多向上取两级
  for (let i = currentOrgLevel - 1; i >= 1 && levels.length < 2; i--) {
    levels.push(i)
  }
  return levels
}

async function loadSuperiors() {
  try {
    // 获取当前员工的组织层级
    const currentOrgLevel = formData.orgLevel

    // 系统级员工不能有上级
    if (currentOrgLevel === 0) {
      superiorOptions.value = []
      return
    }

    // 根据当前员工级别确定可选择的上级级别范围
    const allowedLevels = getSuperiorOrgLevels(currentOrgLevel)

    if (allowedLevels.length === 0) {
      superiorOptions.value = []
      return
    }

    // 构建查询参数：使用 paramsSerializer 将数组序列化为 orgLevel=1&orgLevel=2 格式
    // （Spring MVC 默认接收 List 参数的格式，非 PHP 风格的 orgLevel[]=1&orgLevel[]=2）
    const response = await request.get('/employees/superiors', {
      params: { orgLevel: allowedLevels },
      paramsSerializer: (params) => {
        // 手动序列化：orgLevel=1&orgLevel=2（重复参数名，匹配 Spring MVC @RequestParam List）
        return Object.entries(params)
          .flatMap(([key, val]) => {
            if (Array.isArray(val)) {
              return val.map((v) => `${encodeURIComponent(key)}=${encodeURIComponent(v)}`)
            }
            return [`${encodeURIComponent(key)}=${encodeURIComponent(val)}`]
          })
          .join('&')
      }
    })

    if (response.code === RESULT_CODE.SUCCESS) {
      // 排除当前编辑的员工自己
      const currentUserId = formData.id
      superiorOptions.value = (response.data || []).filter(item => item.id !== currentUserId)
    }
  } catch (error) {
    // 加载上级列表失败
    superiorOptions.value = []
  }
}

// 操作方法
function handleSearch() {
  searchForm.pageNum = 1
  loadEmployees()
}

function resetSearch() {
  searchForm.keyword = ''
  searchForm.deptId = null
  searchForm.orgLevel = null
  searchForm.pageNum = 1
  loadEmployees()
}

function handleAdd() {
  isEdit.value = false
  dialogTitle.value = '新增员工'
  Object.assign(formData, {
    id: null,
    employeeId: '',      // 新增时清空，由后端自动生成
    password: '',
    realName: '',
    phone: '',
    departmentId: null,
    positionId: null,
    superiorId: null,
    orgLevel: 4,
    status: 1,
    hireDate: ''
  })
  dialogVisible.value = true
}

function handleEdit(row) {
  isEdit.value = true
  dialogTitle.value = '编辑员工信息'
  Object.assign(formData, {
    ...row,
    password: '',
    passwordChanged: row.passwordChanged !== false, // 处理null情况
    resetPasswordFlag: false,
    forceChangePassword: false
  })
  // 重置快捷操作状态
  quickResetting.value = false
  dialogVisible.value = true
  // 加载上级列表（此时formData.id已设置，可正确排除当前用户）
  loadSuperiors()
}

// 处理重置密码复选框变化
function handleResetPasswordChange(checked) {
  if (checked) {
    // 勾选重置密码时，自动启用强制改密
    formData.forceChangePassword = true
  } else {
    // 取消重置密码时，如果员工原本是已改密状态，则取消强制改密
    if (formData.passwordChanged !== false) {
      formData.forceChangePassword = false
    }
  }
}

/**
 * 打开重置链接对话框（统一展示入口）
 * @param {string} fullLink 完整重置链接
 * @param {string} username 用户名
 * @param {string} realName 真实姓名
 * @param {number} expiryMinutes 有效期（分钟）
 */
function openResetLinkDialog(fullLink, username, realName, expiryMinutes) {
  resetLinkData.fullLink = fullLink
  resetLinkData.username = username
  resetLinkData.realName = realName
  resetLinkData.expiryMinutes = expiryMinutes || 15
  linkCopied.value = false
  resetLinkDialogVisible.value = true
}

/**
 * 一键复制重置链接
 * 优先使用 navigator.clipboard API，降级使用 execCommand
 */
async function copyResetLink() {
  const text = resetLinkData.fullLink
  if (!text) return

  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      // 降级方案：使用 textarea + execCommand
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    linkCopied.value = true
    ElMessage.success('重置链接已复制到剪贴板')
    // 3 秒后恢复按钮状态
    setTimeout(() => { linkCopied.value = false }, 3000)
  } catch (err) {
    ElMessage.error('复制失败，请手动选中链接复制')
  }
}

/**
 * 执行密码重置的核心逻辑（生成重置链接）
 * 包含超管重置自己的二次验证
 * @param {number} userId 目标用户 ID
 * @param {string} fallbackUsername 用户名（用于展示）
 * @param {string} fallbackRealName 真实姓名（用于展示）
 */
async function executePasswordReset(userId, fallbackUsername, fallbackRealName) {
  // 检查是否是超管重置自己（需要二次验证）
  const isSelfReset = userId === userStore.userInfo?.userId
  // 修复（2026-07-26）：移除遗留的 'ROLE_SUPER_ADMIN' 检查，
  // 后端角色码为 'SUPER_ADMIN'（无 ROLE_ 前缀），与 router/index.js 对齐
  const isSuperAdmin = userRoleCodes.value.includes('SUPER_ADMIN')

  let operatorPassword = null

  if (isSelfReset && isSuperAdmin) {
    // 超管重置自己密码，需要二次验证（使用专属提示语）
    try {
      const { value } = await ElMessageBox.prompt(
        '检测到您正在重置自己的超级管理员密码，需要二次验证。\n请输入您的当前密码以确认操作：',
        '二次验证',
        {
          confirmButtonText: '确认',
          cancelButtonText: '取消',
          inputType: 'password',
          inputPlaceholder: '请输入当前密码',
          inputValidator: (val) => {
            if (!val || !val.trim()) return '密码不能为空'
            return true
          }
        }
      )
      operatorPassword = value
    } catch (cancelErr) {
      // 用户取消二次验证
      return { cancelled: true }
    }
  } else {
    // 非超管重置自己：后端 @RequireReAuth 要求 X-Reauth-Password 请求头
    // 使用通用二次鉴权工具弹出密码确认对话框
    operatorPassword = await confirmReauth(`重置「${fallbackRealName}」的密码`)
    if (operatorPassword === null) {
      return { cancelled: true }
    }
  }

  // 调用重置密码接口（通过 X-Reauth-Password 请求头传递二次鉴权密码）
  const response = await request.put(
    `/employees/${userId}/reset-password`,
    { operatorPassword },
    { headers: { [REAUTH_PASSWORD_HEADER]: operatorPassword } }
  )

  if (response.code === RESULT_CODE.SUCCESS) {
    const data = response.data || {}
    const resetLink = data.resetLink || ''
    const expiryMinutes = data.expiryMinutes || 15
    const username = data.username || fallbackUsername
    const realName = data.realName || fallbackRealName

    // 构造完整 URL（用于展示和复制）
    const fullLink = `${window.location.origin}${resetLink}`

    // 打开重置链接对话框
    openResetLinkDialog(fullLink, username, realName, expiryMinutes)

    return { success: true }
  } else {
    ElMessage.error(response.msg || '密码重置失败')
    return { success: false }
  }
}

// 快捷操作：立即重置密码（企业级流程：生成重置链接）
async function handleQuickResetPassword() {
  try {
    await ElMessageBox.confirm(
      `确定要为「${formData.realName}」重置密码吗？\n\n系统将生成一次性重置链接，员工通过链接自行设置新密码。`,
      '确认重置密码',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    quickResetting.value = true

    const result = await executePasswordReset(formData.id, formData.username, formData.realName)

    if (result.success) {
      // 更新表单状态
      formData.resetPasswordFlag = false
      formData.forceChangePassword = true
      formData.passwordChanged = false
      ElMessage.success('密码重置链接已生成')
    }
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      const msg = error?.response?.data?.msg || error?.message || '密码重置失败'
      ElMessage.error(msg)
    }
  } finally {
    quickResetting.value = false
  }
}

// 快捷操作：仅强制改密（不重置密码）
async function handleForceChangeOnly() {
  if (formData.passwordChanged === false) {
    ElMessage.warning('该员工当前已是"未改密"状态，无需重复设置')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要要求「${formData.realName}」下次登录时必须修改密码吗？\n\n注意：不会重置当前密码，只是要求登录时强制修改`,
      '确认强制改密',
      {
        confirmButtonText: '确认启用',
        cancelButtonText: '取消',
        type: 'info'
      }
    )

    // 调用接口更新（修复 2026-07-26：原提交 ...formData 含非 User 字段，
    // 现仅提交需要变更的字段；updateById 动态 SQL 只更新非 null 字段）
    await request.put(`/employees/${formData.id}`, {
      passwordChanged: false
    })

    // 更新本地状态
    formData.forceChangePassword = true
    formData.passwordChanged = false

    ElMessage.success('已成功设置强制改密策略')
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('设置失败')
    }
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  formRef.value.validate(async (valid) => {
    if (!valid) return

    try {
      // 新增时自动用姓名生成用户名
      if (!isEdit.value) {
        formData.username = generateUsername(formData.realName)
      }

      if (isEdit.value) {
        // ============================================
        // 编辑模式：先保存基本信息，再处理密码逻辑
        // 修复（2026-07-26）：
        //   Bug A（致命）：原 `response = { code: 200 }` 硬编码，但 RESULT_CODE.SUCCESS = 20000，
        //     导致编辑成功后无提示、不关闭对话框、不刷新列表 —— 用户感知"编辑按钮点了没反应"。
        //   Bug B：原重置密码分支显示 `新密码：[object Object]`（后端返回 Map 不是字符串），
        //     且不弹重置链接对话框。现统一走 executePasswordReset。
        //   Bug C：原重置密码/强制改密分支不保存基本信息（姓名、手机号、部门等），
        //     用户改了姓名又勾选重置密码后，姓名修改丢失。
        //   Bug D：原直接提交 formData，含 password/resetPasswordFlag/forceChangePassword
        //     等非 User 字段。现构造纯净的更新对象。
        // ============================================
        const updateData = {
          realName: formData.realName,
          phone: formData.phone,
          departmentId: formData.departmentId,
          positionId: formData.positionId,
          superiorId: formData.superiorId,
          orgLevel: formData.orgLevel,
          hireDate: formData.hireDate,
          status: formData.status
        }
        // 如果勾选了强制改密，一并设置 passwordChanged = false
        if (formData.forceChangePassword) {
          updateData.passwordChanged = false
        }

        const updateRes = await request.put(`/employees/${formData.id}`, updateData)
        if (updateRes.code !== RESULT_CODE.SUCCESS) {
          ElMessage.error(updateRes.msg || '更新失败')
          return
        }

        // 如果勾选了重置密码，调用重置密码接口（生成重置链接，统一走 executePasswordReset）
        if (formData.resetPasswordFlag) {
          const result = await executePasswordReset(formData.id, formData.username, formData.realName)
          if (!result.success && !result.cancelled) {
            // 基本信息 已保存，但密码重置失败
            ElMessage.warning('基本信息已保存，但密码重置失败')
            dialogVisible.value = false
            loadEmployees()
            return
          }
          if (result.cancelled) {
            // 用户取消了二次验证，基本信息已保存
            ElMessage.success('基本信息已保存')
            dialogVisible.value = false
            loadEmployees()
            return
          }
          ElMessage.success('基本信息已保存，密码重置链接已生成')
        } else {
          ElMessage.success('更新成功')
        }

        dialogVisible.value = false
        loadEmployees()
      } else {
        // 新增模式
        const response = await request.post('/employees', formData)
        if (response.code === RESULT_CODE.SUCCESS) {
          ElMessage.success('创建成功')
          dialogVisible.value = false
          loadEmployees()
        } else {
          ElMessage.error(response.msg || '创建失败')
        }
      }
    } catch (error) {
      ElMessage.error('操作失败')
    }
  })
}

async function handleToggleStatus(row) {
  const currentStatus = Number(row.status)
  const newStatus = currentStatus === 1 ? 0 : 1
  const action = newStatus === 1 ? '启用' : '禁用'

  try {
    await ElMessageBox.confirm(
      `确定要${action}员工「${row.realName}」吗？`,
      '确认操作',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )

    // 修复（2026-07-25）：原 URL 拼接 `?status=${newStatus}` 会破坏请求规范化
    // （HMAC 已废弃；保留 axios params 选项以保持 URL 干净，便于后端 getRequestURI 对齐）
    const response = await request.put(`/employees/${row.id}/status`, null, {
      params: { status: newStatus }
    })

    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success(`员工已${action}`)
      loadEmployees()

      if (newStatus === 0) {
        setTimeout(() => {
          ElMessage({
            message: '该员工的活跃工单已被冻结',
            type: 'warning',
            duration: 4000
          })
        }, 300)
      }
    } else {
      ElMessage.error(response.msg || `操作失败: ${JSON.stringify(response)}`)
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(`操作失败: ${error.message || error}`)
    }
  }
}

async function handleResetPassword(row) {
  try {
    await ElMessageBox.confirm(
      `确定要为「${row.realName}」重置密码吗？\n\n系统将生成一次性重置链接，员工通过链接自行设置新密码。`,
      '确认重置密码',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    await executePasswordReset(row.id, row.username, row.realName)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      const msg = error?.response?.data?.msg || error?.message || '密码重置失败'
      ElMessage.error(msg)
    }
  }
}

function handleCommand(command, row) {
  switch (command) {
    case 'roles':
      handleAssignRoles(row)
      break
    case 'resign':
      handleResign(row)
      break
    case 'delete':
      handleDelete(row)
      break
  }
}

function handleAssignRoles(row) {
  currentUserId.value = row.id
  currentUserName.value = row.realName
  selectedRoleIds.value = row.roleIds ? [...row.roleIds] : []
  roleDialogVisible.value = true
}

// 离职处理方法
function handleResign(row) {
  resignForm.id = row.id
  resignForm.realName = row.realName
  resignForm.resignType = 1
  resignForm.resignReason = ''
  resignForm.remark = ''
  resignDialogVisible.value = true
}

async function handleSubmitResign() {
  if (!resignForm.resignType) {
    ElMessage.warning('请选择离职类型')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认员工「${resignForm.realName}」办理离职？\n\n离职后该员工将无法登录系统，且信息将转移至离职档案！`,
      '确认离职',
      { confirmButtonText: '确认离职', cancelButtonText: '取消', type: 'warning' }
    )

    resignSubmitting.value = true
    const response = await request.post(`/employees/${resignForm.id}/resign`, {
      resignType: resignForm.resignType,
      resignReason: resignForm.resignReason,
      remark: resignForm.remark
    })

    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success(`员工「${resignForm.realName}」已成功办理离职`)
      resignDialogVisible.value = false
      loadEmployees()
    } else {
      ElMessage.error(response.msg || '离职处理失败')
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('离职处理失败')
    }
  } finally {
    resignSubmitting.value = false
  }
}

function toggleRoleSelection(roleId) {
  const index = selectedRoleIds.value.indexOf(roleId)
  if (index > -1) {
    selectedRoleIds.value.splice(index, 1)
  } else {
    selectedRoleIds.value.push(roleId)
  }
}

async function handleSubmitRoles() {
  roleSubmitting.value = true
  try {
    const response = await request.put(`/employees/${currentUserId.value}/roles`, selectedRoleIds.value)

    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success(`权限分配成功：已分配 ${selectedRoleIds.value.length} 个权限，组织层级已自动更新`)
      roleDialogVisible.value = false
      loadEmployees()
    } else {
      ElMessage.error(response.msg || '分配失败')
    }
  } catch (error) {
    ElMessage.error('分配失败')
  } finally {
    roleSubmitting.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `永久删除员工「${row.realName}」？\n\n此操作不可恢复！`,
      '危险操作',
      { confirmButtonText: '永久删除', cancelButtonText: '取消', type: 'error' }
    )

    // 二次鉴权：后端 @RequireReAuth 要求 X-Reauth-Password 请求头携带当前用户密码
    const response = await withReauth(
      (password) => request.delete(`/employees/${row.id}`, {
        headers: { [REAUTH_PASSWORD_HEADER]: password }
      }),
      `删除员工「${row.realName}」`
    )

    if (response.code === RESULT_CODE.SUCCESS) {
      ElMessage.success('员工已永久删除')
      loadEmployees()
    } else {
      ElMessage.error(response.msg || '删除失败')
    }
  } catch (error) {
    if (error !== 'cancel' && error?.message !== '用户取消二次鉴权') {
      ElMessage.error('删除失败')
    }
  }
}

async function handleExport() {
  try {
    ElMessage.info('正在准备导出文件...')

    // 修复（2026-07-26）：原用原生 fetch 绕过 axios 拦截器，导致缺少
    // X-XSRF-TOKEN CSRF 头与 WOS_TOKEN Cookie，后端 Spring Security 拦截返回 403。
    // 改用 request（axios 实例），让请求拦截器自动注入 CSRF Token + 携带 Cookie。
    // 响应拦截器对 blob 响应（res.code === undefined）原样返回 Blob。
    const blob = await request.get('/employees/export', {
      responseType: 'blob'
    })

    // 创建下载链接并触发下载
    const url = window.URL.createObjectURL(new Blob([blob]))
    const link = document.createElement('a')
    link.href = url
    link.download = `员工数据_${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)

    // 释放URL对象
    window.URL.revokeObjectURL(url)

    ElMessage.success('导出完成')
  } catch (error) {
    ElMessage.error('导出失败: ' + (error.message || '未知错误'))
  }
}

// 辅助函数
function generateUsername(realName) {
  if (!realName) return ''
  // 姓名转拼音首字母小写作为用户名
  return realName.toLowerCase().replace(/\s+/g, '_')
}

function getDeptName(deptId) {
  const dept = departments.value.find(d => d.value === deptId)
  return dept ? dept.label : '-'
}

function getPositionName(positionId) {
  const pos = positions.value.find(p => p.value === positionId)
  return pos ? pos.label : '-'
}

function getOrgLevelText(level) {
  switch (level) {
    case 0: return '系统级'
    case 1: return '高管级'
    case 2: return '管理级'
    case 3: return '专员级'
    case 4: return '员工级'
    default: return '-'
  }
}

function getStatusText(status) {
  switch (status) {
    case 1: return '启用'
    case 0: return '禁用'
    case -1: return '已删除'
    default: return '未知'
  }
}

function handleDeptChange() {
  formData.positionId = null
}

// 职位选择变化时自动更新组织层级
function handlePositionChange(positionId) {
  if (positionId) {
    const role = allFixedRoles.value.find(r => r.value === positionId)
    if (role) {
      formData.orgLevel = role.orgLevel
    }
  }
}

onMounted(() => {
  loadDepartments()
  loadPositions()
  loadRoles()
  loadEmployees()
  // loadSuperiors() 移到 handleEdit 中调用，确保能正确排除当前用户
})
</script>

<style scoped>
/* 页面容器 - 与我的工单一致 */
.page-container {
  padding: var(--space-lg);
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  padding: var(--space-lg);
  background-color: var(--color-surface-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
}

.filter-form .el-form-item {
  margin-right: var(--space-lg);
  margin-bottom: 0;
}

.toolbar-btn {
  display: flex;
  gap: var(--space-sm);
  flex-shrink: 0;
}

/* 表格卡片 */
.table-card {
  background-color: var(--color-surface-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  padding: var(--space-lg);

  /* 表格表头不加粗 - 强制覆盖 */
  :deep(.el-table__header-wrapper th .cell) {
    font-weight: normal !important;
    color: var(--color-ink-muted) !important;
  }

  :deep(.el-table__header th.is-leaf .cell) {
    font-weight: normal !important;
  }

  :deep(.el-table__header th) {
    font-weight: normal !important;
  }
}

/* 操作链接样式 - 默认无颜色，悬浮时显示 */
.action-link {
  cursor: pointer;
  font-size: 13px;
  color: var(--color-ink, #303133);
  transition: color 0.2s ease;

  &:hover {
    color: #5b8ff9;
    text-decoration: none;
  }

  &.primary {
    &:hover {
      color: var(--color-primary, #409eff);
    }
  }

  &.success {
    &:hover {
      color: #67c23a;
    }
  }

  &.warning {
    &:hover {
      color: #e6a23c;
    }
  }

  &.info {
    &:hover {
      color: #909399;
    }
  }
}

/* 工号文本样式 - 等宽字体，醒目显示 */
.employee-id-text {
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
  letter-spacing: 0.05em;
}

/* 组织层级文本样式 - 无背景，仅文字颜色 */
.org-level-text {
  font-size: 13px;

  &.level-0 { color: #f56c6c; } /* 系统级 - 红色 */
  &.level-1 { color: #e6a23c; } /* 高管级 - 橙色 */
  &.level-2 { color: #409eff; } /* 管理级 - 蓝色 */
  &.level-3 { color: #67c23a; } /* 专员级 - 绿色 */
  &.level-4 { color: #909399; } /* 员工级 - 灰色 */
}

/* 状态文本样式 - 无背景，仅文字颜色 */
.status-text {
  font-size: 13px;

  &.status-active {
    color: #67c23a; /* 启用 - 绿色 */
  }

  &.status-inactive {
    color: #f56c6c; /* 禁用 - 红色 */
  }
}

.action-divider {
  color: var(--color-border);
  margin: 0 6px;
}

.action-dropdown {
  display: inline-flex;
  align-items: center;
  vertical-align: middle;
}

/* 操作按钮强制同行 */
.action-btns-row {
  display: flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
  gap: 0;
}

/* 分配职位对话框样式 */
/* 分配职位对话框 */
.position-dialog {
  .el-dialog__header {
    padding: 16px 20px;
    border-bottom: 1px solid #e4e7ed;
  }

  .el-dialog__body {
    padding: 20px 24px;
  }
}

/* 对话框头部 - 简洁版 */
.position-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.position-user-info {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.position-user-name {
  font-size: 15px;
  font-weight: 500;
  color: #303133;
}

.position-user-hint {
  font-size: 13px;
  color: #909399;
}

.position-selected-count {
  font-size: 14px;
  color: #409eff;
  font-weight: 500;
}

/* 数量限制提示 - 简洁版 */
.position-limit-tip {
  padding: 10px 14px;
  margin-bottom: 14px;
  background-color: #fdf6ec;
  border-left: 3px solid #e6a23c;
  color: #e6a23c;
  font-size: 13px;
}

/* 职位列表容器 */
.position-list-container {
  max-height: 480px;
  overflow-y: auto;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background-color: #dcdfe6;
    border-radius: 2px;
  }
}

/* 职位分组 */
.position-group {
  margin-bottom: 20px;

  &:last-child {
    margin-bottom: 0;
  }
}

.position-group-header {
  margin-bottom: 10px;
}

.position-group-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.position-exclusive-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #f56c6c;
  margin-top: 4px;
  padding-left: 4px;
}

.position-group-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;

  &.system-level-title {
    color: #f56c6c;
  }
}

.position-group-desc {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-bottom: 10px;
  padding-left: 4px;
}

/* 职位卡片网格 */
.position-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 8px;
}

/* 职位卡片 - 保留勾选样式 */
.position-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
  background-color: #fff;

  &:hover:not(.is-disabled) {
    border-color: #409eff;
  }

  &.is-active {
    border-color: #409eff;
    background-color: #ecf5ff;

    .card-checkbox .checkbox-empty {
      background-color: #409eff;
      border-color: #409eff;
    }
  }

  &.is-disabled {
    opacity: 0.5;
    cursor: not-allowed;
    background-color: #fafafa;
  }

  &.card-danger {
    border-color: #f56c6c;

    &:hover:not(.is-disabled) {
      border-color: #f56c6c;
    }

    &.is-active {
      border-color: #f56c6c;
      background-color: #fef0f0;

      .card-checkbox .checkbox-empty {
        background-color: #f56c6c;
        border-color: #f56c6c;
      }
    }
  }
}

.card-checkbox {
  flex-shrink: 0;

  .check-icon {
    font-size: 18px;
    color: #5b8ff9;
  }

  .checkbox-empty {
    width: 18px;
    height: 18px;
    border: 1.5px solid #c8ccd3;
    border-radius: 5px;
    transition: all 0.15s ease;
  }
}

.card-content {
  flex: 1;
  min-width: 0;
}

.card-name {
  font-size: 14px;
  font-weight: 500;
  color: #2c2c3a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 自定义底部栏 */
.dialog-footer-custom {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  width: 100%;
}

.footer-right {
  display: flex;
  gap: 10px;
}

/* 导出数据按钮 - 默认无色，悬浮绿色 */
.export-btn {
  color: #606b7c;
  border-color: #d0d5dd;
  background-color: #fff;

  &:hover {
    color: #52c41a;
  border-color: #95de64;
    background-color: #f6ffed;
  }
}

/* 新增员工按钮 - 默认无色，悬浮蓝色 */
.add-btn {
  color: #606b7c;
  border-color: #d0d5dd;
  background-color: #fff;

  &:hover {
    color: #4a6cf7;
    border-color: #4a6cf7;
    background-color: #f5f7ff;
  }
}

/* 离职档案按钮 - 默认无色，悬浮橙色 */
.resigned-btn {
  color: #606b7c;
  border-color: #d0d5dd;
  background-color: #fff;

  &:hover {
    color: #d48806;
    border-color: #ffc53d;
    background-color: #fff7e6;
  }
}

/* 搜索按钮 - 白底蓝字 */
.search-btn {
  color: #5b8ff9;
  border-color: #c0cee8;
  background-color: #fff;

  &:hover {
    color: #4a7ae8;
    border-color: #5b8ff9;
    background-color: #f5f8fc;
  }
}

/* 重置按钮 - 白底灰字 */
.reset-btn {
  color: #606b7c;
  border-color: #d0d5dd;
  background-color: #fff;

  &:hover {
    color: #404a58;
    border-color: #a8b0ba;
    background-color: #f7f8fa;
  }
}

/* 取消按钮 - 白底浅灰 */
.cancel-btn {
  color: #70787f;
  border-color: #d5dae0;
  background-color: #fff;

  &:hover {
    color: #50585f;
    border-color: #b8bec6;
    background-color: #f7f8fa;
  }
}

/* 提交/确定按钮 - 蓝色填充 */
.submit-btn,
.confirm-btn {
  --el-button-bg-color: #5b8ff9;
  --el-button-border-color: #5b8ff9;
  --el-button-hover-bg-color: #4a7ae8;
  --el-button-hover-border-color: #4a7ae8;
  --el-button-active-bg-color: #3d6cd4;
  --el-button-active-border-color: #3d6cd4;
}

/* 表格职位纯文本样式 */
.position-text-list {
  display: inline;
}

.position-text-item {
  color: #303133;
  font-size: 13px;
}

.pos-sep {
  margin: 0 2px;
  color: #c0c4cc;
}

.text-muted {
  color: #303133;
  font-size: 13px;
}

/* 分页容器 */
.pagination-container {
  margin-top: var(--space-lg);
  display: flex;
  justify-content: flex-end;
}

/* 离职对话框样式 */
.resign-employee-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-primary, #409eff);
}

/* 密码管理区域 - 简洁版 */
.password-management-section {
  margin-top: 20px;
  padding: 16px;
  background-color: #fafafa;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
}

.password-management-section .el-divider {
  margin-bottom: 16px;
}

.status-text-danger {
  color: #f56c6c;
  font-weight: normal;
}

.status-text-normal {
  color: #606266;
}

.simple-warning-inline {
  font-size: 12px;
  color: #f56c6c;
  line-height: 1.4;
  margin-top: 4px;
}

.simple-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}

.simple-warning {
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.4;
  margin-top: 4px;
}

.quick-actions-simple {
  display: flex;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
  margin-top: 8px;
}

.form-item-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}

/* ========== 重置链接对话框 ========== */
.reset-link-dialog {
  :deep(.el-dialog) {
    border-radius: var(--radius-md);
    overflow: hidden;
    border: 1px solid var(--color-border);
  }

  :deep(.el-dialog__header) {
    padding: 0;
    margin: 0;
    border-bottom: 1px solid var(--color-border-subtle);
  }

  :deep(.el-dialog__headerbtn) {
    top: 16px;
    right: 16px;
    z-index: 2;
  }

  :deep(.el-dialog__body) {
    padding: var(--space-xl);
  }

  :deep(.el-dialog__footer) {
    padding: var(--space-md) var(--space-xl) var(--space-xl);
    border-top: 1px solid var(--color-border-subtle);
  }
}

.rl-dialog-header {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-lg) var(--space-xl);

  .rl-header-icon {
    width: 38px;
    height: 38px;
    border-radius: var(--radius-sm);
    background: var(--color-accent-soft);
    color: var(--color-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 17px;
    flex-shrink: 0;
  }

  .rl-header-text {
    h3 {
      font-size: 15px;
      font-weight: 500;
      color: var(--color-ink);
      margin: 0 0 2px 0;
      letter-spacing: -0.01em;
      line-height: 1.4;
    }

    p {
      font-size: 12px;
      color: var(--color-ink-muted);
      margin: 0;
      letter-spacing: 0.02em;
    }
  }
}

.rl-body {
  .rl-user-info {
    display: flex;
    align-items: center;
    gap: var(--space-sm);
    margin-bottom: var(--space-lg);
    font-size: 13px;

    .rl-user-label {
      color: var(--color-ink-muted);
      font-size: 12px;
    }

    .rl-user-name {
      color: var(--color-ink);
      font-weight: 500;
    }

    .rl-user-id {
      color: var(--color-ink-muted);
      font-size: 11px;
      padding: 2px 8px;
      background: var(--color-surface-inset);
      border-radius: var(--radius-sm);
      font-family: 'SF Mono', 'Consolas', monospace;
    }
  }

  .rl-link-card {
    display: flex;
    align-items: stretch;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    overflow: hidden;
    cursor: pointer;
    transition: border-color var(--duration-fast) var(--ease-standard),
                box-shadow var(--duration-fast) var(--ease-standard);

    &:hover {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-soft);

      .rl-link-bar {
        background: var(--color-accent);
      }
    }

    .rl-link-bar {
      width: 3px;
      background: var(--color-border-divider);
      transition: background var(--duration-fast) var(--ease-standard);
      flex-shrink: 0;
    }

    .rl-link-text {
      flex: 1;
      padding: 14px var(--space-md);
      font-size: 12px;
      font-family: 'SF Mono', 'Consolas', 'Monaco', monospace;
      color: var(--color-ink-body);
      background: var(--color-surface-inset);
      word-break: break-all;
      line-height: 1.6;
      display: flex;
      align-items: center;
      min-height: 52px;
    }

    .rl-copy-btn {
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 3px;
      padding: 0 22px;
      border: none;
      border-left: 1px solid var(--color-border);
      background: var(--color-surface-card);
      color: var(--color-ink-muted);
      font-size: 11px;
      cursor: pointer;
      transition: all var(--duration-fast) var(--ease-standard);

      .rl-copy-icon {
        font-size: 17px;
        transition: transform var(--duration-normal) var(--ease-standard);
      }

      &:hover {
        background: var(--color-surface-hover);
        color: var(--color-accent);

        .rl-copy-icon {
          transform: scale(1.1);
        }
      }

      &.copied {
        color: var(--color-success);
        background: rgba(103, 194, 58, 0.06);
      }
    }
  }

  .rl-hint {
    font-size: 12px;
    color: var(--color-ink-placeholder);
    margin: var(--space-md) 0 0 0;
    line-height: 1.5;
    text-align: center;
  }
}

.rl-close-btn {
  width: 100%;
  height: 38px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-card);
  color: var(--color-ink-body);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-standard);

  &:hover {
    border-color: var(--color-accent);
    color: var(--color-accent);
    background: var(--color-accent-soft);
  }

  &:active {
    transform: scale(0.99);
  }
}
</style>
