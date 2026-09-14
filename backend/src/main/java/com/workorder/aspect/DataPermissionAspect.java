package com.workorder.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.workorder.annotation.DataPermission;
import com.workorder.common.constant.RoleConstants;
import com.workorder.common.exception.BusinessException;
import com.workorder.config.MetricsConfig;
import com.workorder.dao.ApprovalLogMapper;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.ApprovalLog;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.security.CustomUserDetails;

/**
 * Data Permission Isolation Aspect 数据权限隔离切面 — 拦截带有 @DataPermission 注解的方法，强制执行权限检查
 *
 * <p>阶段 2 修复 A-09 / H-02： 原 checkDataOwnership 为 TODO 桩（仅 logger.warn 后 return 允许通过），
 * 现已实现真实的所有权校验，资源 ID 提取失败时 fail-closed 抛异常。
 *
 * <p>Features: 1. 基于角色的 RBAC 权限验证 2. 数据权限隔离（部门、个人）— dataScope 字段 3. 防止水平越权（IDOR）— checkOwnership 字段
 * 4. 防止垂直越权 — requirePermission 字段
 *
 * @author KLord
 */
@Aspect
@Component
public class DataPermissionAspect {

  private static final Logger logger = LoggerFactory.getLogger(DataPermissionAspect.class);

  @Autowired private WorkOrderMapper workOrderMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private ApprovalLogMapper approvalLogMapper;

  @Autowired private MetricsConfig metricsConfig;

  /** Intercept methods annotated with @DataPermission */
  @Around("@annotation(dataPermission)")
  public Object checkDataPermission(ProceedingJoinPoint joinPoint, DataPermission dataPermission)
      throws Throwable {

    // 1. Get current authenticated user
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new BusinessException("未登录或会话已过期");
    }

    CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
    Long currentUserId = userDetails.getUserId();
    String username = userDetails.getUsername();
    boolean isSuperAdmin = userDetails.isSuperAdmin();

    // 2. Check if super admin bypass is allowed and user is super admin
    if (dataPermission.allowSuperAdminBypass() && isSuperAdmin) {
      logger.debug("超级管理员 {} 绕过数据权限检查", username);
      return joinPoint.proceed();
    }

    // 数据权限校验（捕获 BusinessException 时埋点 Prometheus 指标，便于告警与审计）
    try {
      // 3. Check required permission code
      if (!dataPermission.requirePermission().isEmpty()) {
        boolean hasPermission = userDetails.hasPermission(dataPermission.requirePermission());
        if (!hasPermission) {
          logger.warn("用户 {} 尝试访问需要权限 {} 的资源，但无此权限", username, dataPermission.requirePermission());
          throw new BusinessException("您没有执行此操作的权限");
        }
      }

      // 4. Check ownership if required (防水平越权 IDOR)
      if (dataPermission.checkOwnership()) {
        checkDataOwnership(joinPoint, dataPermission, userDetails, currentUserId, username);
      }

      // 5. Check department match if required
      if (dataPermission.requireDepartmentMatch() && !isDeptAdminOrHr(userDetails)) {
        checkDepartmentMatch(joinPoint, dataPermission, userDetails, username);
      }
    } catch (BusinessException be) {
      // Prometheus 埋点：记录数据权限拒绝（水平/垂直越权、IDOR 等）
      metricsConfig.recordAuthorizationDenial("data_denied");
      throw be;
    }

    // 6. Log the authorized access
    logger.debug(
        "数据权限验证通过: 用户={}, 操作={}",
        username,
        ((MethodSignature) joinPoint.getSignature()).getMethod().getName());

    // 7. Proceed with the original method execution
    return joinPoint.proceed();
  }

  /**
   * Check if user has permission to access the specific resource 检查用户是否有权访问特定资源（防水平越权 IDOR）
   *
   * <p>阶段 2 修复 A-09 / H-02：从 TODO 桩改为真实实现。 资源 ID 提取失败时 fail-closed 抛异常（不再允许通过）。
   */
  private void checkDataOwnership(
      ProceedingJoinPoint joinPoint,
      DataPermission dataPermission,
      CustomUserDetails currentUser,
      Long currentUserId,
      String username) {
    // Extract resource ID from method parameters
    Object[] args = joinPoint.getArgs();
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    String[] paramNames = signature.getParameterNames();

    Object resourceIdRaw = null;
    for (int i = 0; i < paramNames.length; i++) {
      if (paramNames[i].equals(dataPermission.resourceIdParam())) {
        resourceIdRaw = args[i];
        break;
      }
    }

    if (resourceIdRaw == null) {
      // fail-closed: 资源 ID 未找到，拒绝访问
      logger.error(
          "数据权限校验失败: 用户={}, 无法从参数中提取资源ID, 参数名={}", username, dataPermission.resourceIdParam());
      throw new BusinessException("无效的资源标识，拒绝访问");
    }

    String entityType = dataPermission.entityType();
    String resourceIdStr = String.valueOf(resourceIdRaw);

    try {
      switch (entityType) {
        case "WORK_ORDER":
          checkWorkOrderOwnership(resourceIdRaw, currentUser, currentUserId, username);
          break;
        case "APPROVAL_LOG":
          checkApprovalLogOwnership(resourceIdRaw, currentUser, currentUserId, username);
          break;
        case "EMPLOYEE":
          checkEmployeeOwnership(resourceIdRaw, currentUser, currentUserId, username);
          break;
        case "USER":
          checkUserOwnership(resourceIdRaw, currentUserId, username);
          break;
        default:
          // 未知实体类型，fail-closed
          logger.error("数据权限校验失败: 未知实体类型 {}", entityType);
          throw new BusinessException("无效的资源类型，拒绝访问");
      }
    } catch (NumberFormatException e) {
      logger.error("解析资源ID失败: {} (用户={})", resourceIdStr, username);
      throw new BusinessException("无效的资源ID格式");
    }
  }

  /** 工单所有权校验 规则：发起人 == 当前用户 OR 当前用户是当前审批人 OR 同部门管理层 */
  private void checkWorkOrderOwnership(
      Object resourceId, CustomUserDetails currentUser, Long currentUserId, String username) {
    WorkOrder workOrder = resolveWorkOrder(resourceId);
    if (workOrder == null) {
      logger.warn("工单不存在: resourceId={} (用户={})", resourceId, username);
      throw new BusinessException("工单不存在或已删除");
    }

    // 1. 发起人是当前用户
    if (workOrder.getApplicantId() != null && workOrder.getApplicantId().equals(currentUserId)) {
      return;
    }

    // 2. 当前用户是该工单的当前审批人（按 username 匹配 current_assignee）
    if (workOrder.getCurrentAssignee() != null
        && workOrder.getCurrentAssignee().equals(currentUser.getUsername())) {
      return;
    }

    // 3. 管理层（orgLevel <= 2）可查看本部门工单
    Integer orgLevel = currentUser.getOrgLevel();
    if (orgLevel != null && orgLevel <= 2 && workOrder.getApplicantId() != null) {
      User applicant = userMapper.selectById(workOrder.getApplicantId());
      if (applicant != null
          && applicant.getDepartmentId() != null
          && applicant.getDepartmentId().equals(currentUser.getDepartmentId())) {
        return;
      }
    }

    // 4. 拥有 view-all 权限的用户可查看
    if (currentUser.hasPermission("workorder:view-all")) {
      return;
    }

    logger.warn(
        "水平越权拦截: 用户={} (id={}) 尝试访问不属于自己的工单 id={}, applicant={}",
        username,
        currentUserId,
        workOrder.getId(),
        workOrder.getApplicantId());
    throw new BusinessException("您没有权限访问此工单");
  }

  /** 审批日志所有权校验 — 复用对应工单的所有权校验 */
  private void checkApprovalLogOwnership(
      Object resourceId, CustomUserDetails currentUser, Long currentUserId, String username) {
    Long logId = toLong(resourceId);
    // resourceId 可能是 workOrderId（ApprovalController.getApprovalLog 传的就是 workOrderId）
    // 先尝试作为 workOrderId 直接校验工单所有权
    WorkOrder workOrder = workOrderMapper.selectById(logId);
    if (workOrder != null) {
      checkWorkOrderOwnership(logId, currentUser, currentUserId, username);
      return;
    }

    // 若不是 workOrderId，尝试作为 approval_log 的 id 查询
    ApprovalLog log = approvalLogMapper.selectById(logId);
    if (log == null) {
      logger.warn("审批日志不存在: logId={} (用户={})", logId, username);
      throw new BusinessException("审批日志不存在");
    }

    // 复用工单所有权校验
    if (log.getWorkOrderId() != null) {
      checkWorkOrderOwnership(log.getWorkOrderId(), currentUser, currentUserId, username);
    } else {
      logger.warn("审批日志缺少 workOrderId: logId={} (用户={})", logId, username);
      throw new BusinessException("审批日志数据异常");
    }
  }

  /** 员工所有权校验 — 同部门或 HR/超管 */
  private void checkEmployeeOwnership(
      Object resourceId, CustomUserDetails currentUser, Long currentUserId, String username) {
    Long employeeId = toLong(resourceId);
    User employee = userMapper.selectById(employeeId);
    if (employee == null) {
      logger.warn("员工不存在: employeeId={} (用户={})", employeeId, username);
      throw new BusinessException("员工不存在");
    }

    // 本人
    if (employeeId.equals(currentUserId)) {
      return;
    }

    // HR 角色可跨部门查看
    if (isHrRole(currentUser)) {
      return;
    }

    // 同部门
    if (employee.getDepartmentId() != null
        && employee.getDepartmentId().equals(currentUser.getDepartmentId())) {
      // 同部门且为部门管理员
      if (isDeptAdminOrHr(currentUser)) {
        return;
      }
    }

    logger.warn(
        "水平越权拦截: 用户={} (id={}) 尝试访问其他部门员工 id={}, dept={}/{}",
        username,
        currentUserId,
        employeeId,
        currentUser.getDepartmentId(),
        employee.getDepartmentId());
    throw new BusinessException("您没有权限访问此员工信息");
  }

  /** 用户所有权校验 — 仅本人或超管 */
  private void checkUserOwnership(Object resourceId, Long currentUserId, String username) {
    Long targetUserId = toLong(resourceId);
    if (targetUserId.equals(currentUserId)) {
      return;
    }
    logger.warn("水平越权拦截: 用户={} (id={}) 尝试访问其他用户 id={}", username, currentUserId, targetUserId);
    throw new BusinessException("您没有权限访问此用户信息");
  }

  /** 部门匹配校验 — 用于列表查询场景 */
  private void checkDepartmentMatch(
      ProceedingJoinPoint joinPoint,
      DataPermission dataPermission,
      CustomUserDetails currentUser,
      String username) {
    // 列表查询场景的部门过滤由 Service 层 SQL 实现（见 WorkOrderMapper.xml dataScope 条件）
    // 此处仅做日志记录，实际过滤在 SQL 层
    logger.debug(
        "部门匹配校验: 用户={}, 部门={}, dataScope={}",
        username,
        currentUser.getDepartmentId(),
        dataPermission.dataScope());
  }

  // ========== 辅助方法 ==========

  private WorkOrder resolveWorkOrder(Object resourceId) {
    // resourceId 可能是 Long（工单主键）或 String（工单编号 orderNo）
    if (resourceId instanceof Long) {
      return workOrderMapper.selectById((Long) resourceId);
    } else if (resourceId instanceof Integer) {
      return workOrderMapper.selectById(((Integer) resourceId).longValue());
    } else if (resourceId instanceof String) {
      // 尝试作为数字 ID
      try {
        return workOrderMapper.selectById(Long.parseLong((String) resourceId));
      } catch (NumberFormatException e) {
        // 作为工单编号查询
        return workOrderMapper.selectByOrderNo((String) resourceId);
      }
    }
    return null;
  }

  private Long toLong(Object value) {
    if (value instanceof Long) return (Long) value;
    if (value instanceof Integer) return ((Integer) value).longValue();
    if (value instanceof String) return Long.parseLong((String) value);
    throw new NumberFormatException("无法转换为 Long: " + value);
  }

  private boolean isDeptAdminOrHr(CustomUserDetails user) {
    if (user.isSuperAdmin()) return true;
    Integer orgLevel = user.getOrgLevel();
    if (orgLevel != null && orgLevel <= 2) return true;
    return isHrRole(user);
  }

  private boolean isHrRole(CustomUserDetails user) {
    if (user.getRoles() == null) return false;
    return user.getRoles().contains(RoleConstants.CODE_HR_DIR)
        || user.getRoles().contains(RoleConstants.CODE_HR_SPEC);
  }
}
