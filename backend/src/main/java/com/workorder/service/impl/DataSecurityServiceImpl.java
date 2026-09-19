package com.workorder.service.impl;


import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.common.constant.RoleConstants;
import com.workorder.common.exception.BusinessException;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.service.IDataSecurityService;

/**
 * 数据安全服务实现 — 提供数据权限校验、数据脱敏等安全能力
 */
@Service
public class DataSecurityServiceImpl implements IDataSecurityService {

  private static final Logger logger = LoggerFactory.getLogger(DataSecurityServiceImpl.class);
  private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT_LOGGER");

  @Autowired private UserMapper userMapper;

  @Autowired private WorkOrderMapper workOrderMapper;

  @Autowired private ObjectMapper objectMapper;

  @Override
  public Map<String, Object> applyDataFilter(Map<String, Object> params, User currentUser) {
    if (currentUser == null) {
      logger.warn("No user context provided for data filtering");
      return params;
    }

    int orgLevel = getOrgLevel(currentUser);
    logger.info(
        "Applying data filter for user: {}, Org Level: {}", currentUser.getRealName(), orgLevel);

    switch (orgLevel) {
      case 0:
        // Super Admin - No filtering needed
        logger.debug("Super Admin access - no filter applied");
        break;

      case 1:
        // Decision Layer - Company-wide with potential desensitization
        params.put("decisionLayerAccess", true);
        logger.debug("Decision Layer access - company-wide view");
        break;

      case 2:
        // Management Layer - Department primary, cross-dept limited
        if (currentUser.getDepartmentId() != null) {
          params.put("departmentId", currentUser.getDepartmentId());
          params.put("crossDeptAccess", true); // Allow cross-dept with desensitization
          logger.debug(
              "Management Layer access - dept: {} with cross-dept overview",
              currentUser.getDepartmentId());
        }
        break;

      case 3:
        // Functional Layer - Type-specific across company
        String functionalType = getFunctionalType(currentUser);
        if (functionalType != null) {
          params.put("functionalType", functionalType);
          params.put("companyWideFunctional", true);
          logger.debug("Functional Layer access - type: {} company-wide", functionalType);
        }
        break;

      case 4:
        // Employee Layer - Own data only
        params.put("userId", currentUser.getId());
        params.put("ownDataOnly", true);
        logger.debug("Employee Layer access - own data only, userId: {}", currentUser.getId());
        break;

      case 5:
        // Dept Admin - Department management
        if (currentUser.getDepartmentId() != null) {
          params.put("departmentId", currentUser.getDepartmentId());
          params.put("deptAdminAccess", true);
          logger.debug("Dept Admin access - dept: {}", currentUser.getDepartmentId());
        }
        break;

      default:
        // Unknown level - restrict to own data only
        params.put("userId", currentUser.getId());
        params.put("ownDataOnly", true);
        logger.warn("Unknown org level: {} - defaulting to own data only", orgLevel);
    }

    return params;
  }

  @Override
  public <T> List<T> desensitizeData(List<T> data, User currentUser, String dataType) {
    if (data == null || data.isEmpty()) {
      return data;
    }

    int orgLevel = getOrgLevel(currentUser);

    // No desensitization needed for super admin or own data
    if (orgLevel == 0 || canViewSensitiveData(currentUser)) {
      return data;
    }

    logger.info(
        "Desensitizing data for user: {}, type: {}, count: {}",
        currentUser.getRealName(),
        dataType,
        data.size());

    try {
      List<Map<String, Object>> result = new ArrayList<>();

      for (T item : data) {
        Map<String, Object> itemMap = objectMapper.convertValue(item, Map.class);

        // Apply desensitization rules based on data type
        switch (dataType.toLowerCase()) {
          case "employee":
            itemMap = desensitizeEmployeeData(itemMap, currentUser, orgLevel);
            break;
          case "workorder":
            itemMap = desensitizeWorkOrderData(itemMap, currentUser, orgLevel);
            break;
          case "salary":
            itemMap = desensitizeSalaryData(itemMap, currentUser, orgLevel);
            break;
          default:
            // Apply basic desensitization
            itemMap = applyBasicDesensitization(itemMap, orgLevel);
        }

        result.add(itemMap);
      }

      @SuppressWarnings("unchecked")
      List<T> typedResult = (List<T>) result;
      return typedResult;

    } catch (Exception e) {
      // 阶段 2 修复 C-04：fail-closed — 脱敏异常时返回脱敏占位，不再返回原始数据
      logger.error(
          "Failed to deserialize data for desensitization, returning masked placeholder (fail-closed)",
          e);
      List<T> masked = new ArrayList<>();
      for (int i = 0; i < data.size(); i++) {
        @SuppressWarnings("unchecked")
        T placeholder = (T) "***";
        masked.add(placeholder);
      }
      return masked;
    }
  }

  /** Desensitize employee data based on access level */
  private Map<String, Object> desensitizeEmployeeData(
      Map<String, Object> data, User currentUser, int orgLevel) {

    Long currentUserId = currentUser.getId();

    // Always show own data fully
    if (data.get("id") != null && Long.parseLong(data.get("id").toString()) == currentUserId) {
      return data;
    }

    // Management layer can see own department fully
    if (orgLevel == 2 && data.get("department_id") != null) {
      Long dataDeptId = Long.parseLong(data.get("department_id").toString());
      if (Objects.equals(dataDeptId, currentUser.getDepartmentId())) {
        return data; // Same department - full access
      }
    }

    // Apply desensitization for other cases
    if (data.containsKey("phone")) {
      data.put("phone", maskPhone(data.get("phone") != null ? data.get("phone").toString() : ""));
    }

    if (data.containsKey("email")) {
      data.put("email", maskEmail(data.get("email") != null ? data.get("email").toString() : ""));
    }

    if (data.containsKey("id_number")) {
      data.put("id_number", "***");
    }

    if (data.containsKey("salary")) {
      data.put("salary", "****");
    }

    return data;
  }

  /**
   * Desensitize work order data based on access level
   *
   * <p>阶段 2 修复 C-05 / H-05：修正逻辑反转 原逻辑 `if (orgLevel != 3 || !isFinanceRole(currentUser))` 会对
   * orgLevel 0/1/2/4/5 移除金额， 导致管理层（orgLevel 0/1/2）反而看不到金额。 正确逻辑：orgLevel > 2（专员/普通员工）且非财务角色时移除金额。
   */
  private Map<String, Object> desensitizeWorkOrderData(
      Map<String, Object> data, User currentUser, int orgLevel) {

    // 仅 orgLevel > 2 的非财务用户需脱敏金额（orgLevel 0/1/2 管理层可见）
    if (orgLevel > 2 && !isFinanceRole(currentUser)) {
      if (data.containsKey("amount")) {
        data.put("amount_masked", true);
        data.remove("amount"); // Remove actual amount
      }

      if (data.containsKey("bank_account")) {
        data.put("bank_account", "***");
      }

      if (data.containsKey("invoice_image")) {
        data.remove("invoice_image");
      }
    }

    return data;
  }

  /** Desensitize salary data - only accessible by finance and decision layers */
  private Map<String, Object> desensitizeSalaryData(
      Map<String, Object> data, User currentUser, int orgLevel) {

    if (!canViewSensitiveData(currentUser)) {
      data.clear();
      data.put("access_denied", true);
      data.put("message", "Salary data requires special permission");
    }

    return data;
  }

  /** Basic desensitization for unknown data types */
  private Map<String, Object> applyBasicDesensitization(Map<String, Object> data, int orgLevel) {
    // Mask phone numbers
    if (data.containsKey("phone")) {
      data.put("phone", maskPhone(data.get("phone") != null ? data.get("phone").toString() : ""));
    }

    // Mask emails
    if (data.containsKey("email")) {
      data.put("email", maskEmail(data.get("email") != null ? data.get("email").toString() : ""));
    }

    return data;
  }

  @Override
  public boolean canAccessData(Long dataId, String dataType, User currentUser) {
    if (currentUser == null || dataId == null) {
      return false;
    }

    int orgLevel = getOrgLevel(currentUser);

    // Super admin has full access
    if (orgLevel == 0) {
      logDataAccess(currentUser, "VIEW", dataType, dataId, true);
      return true;
    }

    // Check specific data type access rules
    switch (dataType.toLowerCase()) {
      case "employee":
        return canAccessEmployeeData(dataId, currentUser, orgLevel);
      case "workorder":
        return canAccessWorkOrderData(dataId, currentUser, orgLevel);
      case "sensitive":
        return canViewSensitiveData(currentUser);
      default:
        return false;
    }
  }

  private boolean canAccessEmployeeData(Long employeeId, User currentUser, int orgLevel) {
    // Can always access own data
    if (Objects.equals(employeeId, currentUser.getId())) {
      logDataAccess(currentUser, "VIEW_OWN_EMPLOYEE", "employee", employeeId, true);
      return true;
    }

    // Decision layer can access all
    if (orgLevel == 1) {
      logDataAccess(currentUser, "VIEW_EMPLOYEE_DECISION", "employee", employeeId, true);
      return true;
    }

    // Management layer can access same department
    if (orgLevel == 2) {
      User targetUser = userMapper.selectById(employeeId);
      if (targetUser != null
          && Objects.equals(targetUser.getDepartmentId(), currentUser.getDepartmentId())) {
        logDataAccess(currentUser, "VIEW_DEPT_EMPLOYEE", "employee", employeeId, true);
        return true;
      }
    }

    // Functional layer based on role
    if (orgLevel == 3) {
      logDataAccess(currentUser, "VIEW_FUNC_EMPLOYEE", "employee", employeeId, true);
      return true; // HR/Finance need access to process work orders
    }

    // Dept admin can access department members
    if (orgLevel == 5) {
      User targetUser = userMapper.selectById(employeeId);
      if (targetUser != null
          && Objects.equals(targetUser.getDepartmentId(), currentUser.getDepartmentId())) {
        logDataAccess(currentUser, "VIEW_DEPT_ADMIN_EMPLOYEE", "employee", employeeId, true);
        return true;
      }
    }

    logDataAccess(currentUser, "DENIED_VIEW_EMPLOYEE", "employee", employeeId, false);
    return false;
  }

  /**
   * 阶段 2 修复 C-06 / H-06：canAccessWorkOrderData 真实所有权校验 原 orgLevel 2/3/4/5 无条件 return true（注释"由 SQL
   * 过滤"），SQL 未过滤则 fail-open。 现改为调用 WorkOrderMapper 查询工单后做真实所有权校验。
   */
  private boolean canAccessWorkOrderData(Long workOrderId, User currentUser, int orgLevel) {
    // 决策层（orgLevel <= 1）可访问全部
    if (orgLevel <= 1) {
      return true;
    }

    // 其他层级需校验具体工单所有权
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      return false; // fail-closed：工单不存在时拒绝
    }

    // 本人发起的工单可访问
    if (workOrder.getApplicantId() != null
        && workOrder.getApplicantId().equals(currentUser.getId())) {
      return true;
    }

    // 管理层（orgLevel == 2）：仅可访问本部门工单
    if (orgLevel == 2) {
      if (currentUser.getDepartmentId() != null && workOrder.getDepartmentId() != null) {
        return currentUser.getDepartmentId().equals(workOrder.getDepartmentId());
      }
      return false; // fail-closed：无法判断部门时拒绝
    }

    // 专员层（orgLevel == 3）：仅可访问本部门工单（HR/财务等专员跨部门处理）
    if (orgLevel == 3) {
      // HR/财务专员可跨部门处理（由角色判断）
      if (isHRRole(currentUser) || isFinanceRole(currentUser)) {
        return true;
      }
      if (currentUser.getDepartmentId() != null && workOrder.getDepartmentId() != null) {
        return currentUser.getDepartmentId().equals(workOrder.getDepartmentId());
      }
      return false;
    }

    // 普通员工（orgLevel == 4）：仅本人发起的工单（已在上方校验）
    if (orgLevel == 4) {
      return false;
    }

    // 部门管理员（orgLevel == 5）：仅本部门工单
    if (orgLevel == 5) {
      if (currentUser.getDepartmentId() != null && workOrder.getDepartmentId() != null) {
        return currentUser.getDepartmentId().equals(workOrder.getDepartmentId());
      }
      return false;
    }

    return false; // fail-closed：未知层级拒绝
  }

  @Override
  public boolean canViewSensitiveData(User currentUser) {
    if (currentUser == null) {
      return false;
    }

    int orgLevel = getOrgLevel(currentUser);

    // Super Admin and Decision Layer can view sensitive data
    if (orgLevel <= 1) {
      logger.debug(
          "User {} can view sensitive data (level: {})", currentUser.getRealName(), orgLevel);
      return true;
    }

    // Finance roles can view financial sensitive data
    if (isFinanceRole(currentUser)) {
      logger.debug("User {} can view sensitive data (finance role)", currentUser.getRealName());
      return true;
    }

    // HR roles can view HR sensitive data
    if (isHRRole(currentUser)) {
      logger.debug("User {} can view sensitive data (HR role)", currentUser.getRealName());
      return true;
    }

    logger.debug(
        "User {} cannot view sensitive data (level: {})", currentUser.getRealName(), orgLevel);
    return false;
  }

  @Override
  public void logDataAccess(
      User currentUser, String action, String dataType, Long dataId, boolean success) {
    try {
      Map<String, Object> auditLog = new HashMap<>();
      auditLog.put("timestamp", new Date());
      auditLog.put("userId", currentUser.getId());
      auditLog.put("username", currentUser.getUsername());
      auditLog.put("realName", currentUser.getRealName());
      auditLog.put("action", action);
      auditLog.put("dataType", dataType);
      auditLog.put("dataId", dataId);
      auditLog.put("success", success);
      auditLog.put("ipAddress", getClientIp());

      String logMessage = objectMapper.writeValueAsString(auditLog);

      if (success) {
        auditLogger.info("DATA_ACCESS: {}", logMessage);
      } else {
        auditLogger.warn("DATA_ACCESS_DENIED: {}", logMessage);
      }

    } catch (Exception e) {
      logger.error("Failed to log data access audit", e);
    }
  }

  @Override
  public String getAccessibleScope(User currentUser) {
    if (currentUser == null) {
      return "NONE";
    }

    int orgLevel = getOrgLevel(currentUser);

    switch (orgLevel) {
      case 0:
        return "FULL_ACCESS";
      case 1:
        return "COMPANY_WIDE_WITH_SENSITIVE";
      case 2:
        return "DEPARTMENT_FULL_CROSS_DEPT_LIMITED";
      case 3:
        return "TYPE_SPECIFIC_COMPANY_WIDE";
      case 4:
        return "OWN_DATA_ONLY";
      case 5:
        return "DEPARTMENT_MANAGEMENT";
      default:
        return "UNKNOWN";
    }
  }

  // ==================== Helper Methods ====================

  /**
   * 阶段 2 修复 C-09 / H-09：统一使用 RoleConstants 原 getOrgLevel 使用 1-14 旧角色 ID，与新 RoleConstants（1-40）不一致。
   */
  private int getOrgLevel(User user) {
    if (user == null) {
      return 4; // Default to most restrictive
    }

    // Query actual org_level from database
    try {
      List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
      if (roleIds == null || roleIds.isEmpty()) {
        return 4;
      }
      // 取所有角色中最高层级（最小 orgLevel 值）
      int minLevel = 4;
      for (Long roleId : roleIds) {
        int level = RoleConstants.getOrgLevelByRoleId(roleId);
        if (level < minLevel) {
          minLevel = level;
        }
      }
      return minLevel;
    } catch (Exception e) {
      logger.error("Failed to determine org level for user: {}", user.getId(), e);
      return 4; // fail-closed：异常时返回最严格层级
    }
  }

  private String getFunctionalType(User user) {
    try {
      List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
      if (roleIds == null) return null;

      if (roleIds.contains(RoleConstants.ROLE_HR_SPEC)) return "HR";
      if (roleIds.contains(RoleConstants.ROLE_ACCOUNTANT_SPEC)
          || roleIds.contains(RoleConstants.ROLE_CASHIER_SPEC)) return "FINANCE";
      if (roleIds.contains(RoleConstants.ROLE_RD_ENGINEER_SPEC)) return "IT";
      if (roleIds.contains(RoleConstants.ROLE_ADMIN_SPEC)) return "ADMIN";

      return null;
    } catch (Exception e) {
      logger.error("Failed to determine functional type for user: {}", user.getId(), e);
      // W-29：fail-close，角色查询异常时禁止放弃职能隔离，避免列表越过部门/数据范围条件
      throw new BusinessException("数据权限校验失败，无法加载列表");
    }
  }

  /** 阶段 2 修复 C-09 / H-09：使用 RoleConstants 统一角色判断 */
  private boolean isFinanceRole(User user) {
    try {
      List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
      if (roleIds == null) return false;
      for (Long roleId : roleIds) {
        if (RoleConstants.isFinanceRole(roleId)) return true;
      }
      return false;
    } catch (Exception e) {
      return false; // fail-closed
    }
  }

  /** 阶段 2 修复 C-09 / H-09：使用 RoleConstants 统一角色判断 */
  private boolean isHRRole(User user) {
    try {
      List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
      if (roleIds == null) return false;
      for (Long roleId : roleIds) {
        if (RoleConstants.isHrRole(roleId)) return true;
      }
      return false;
    } catch (Exception e) {
      return false; // fail-closed
    }
  }

  private String maskPhone(String phone) {
    if (phone == null || phone.length() < 7) {
      return phone;
    }
    return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }

  private String maskEmail(String email) {
    if (email == null || !email.contains("@")) {
      return email;
    }
    int atIndex = email.indexOf("@");
    if (atIndex <= 2) {
      return "***" + email.substring(atIndex);
    }
    return email.substring(0, 2) + "***" + email.substring(atIndex);
  }

  /**
   * 阶段 2 修复 C-07：从 RequestContextHolder 获取真实 IP 原 getClientIp 返回硬编码 "SYSTEM_INTERNAL"，导致审计日志 IP
   * 字段无意义。 现解析 X-Forwarded-For（代理场景取首项），否则取 RemoteAddr。
   */
  private String getClientIp() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attrs == null) {
        return "SYSTEM_INTERNAL"; // 非请求上下文（如定时任务）
      }
      HttpServletRequest req = attrs.getRequest();
      String ip = req.getHeader("X-Forwarded-For");
      if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
        // X-Forwarded-For 可能为 "client, proxy1, proxy2"，取第一个
        return ip.split(",")[0].trim();
      }
      ip = req.getHeader("X-Real-IP");
      if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
        return ip.trim();
      }
      return req.getRemoteAddr();
    } catch (Exception e) {
      logger.warn("Failed to get client IP, falling back to SYSTEM_INTERNAL: {}", e.getMessage());
      return "SYSTEM_INTERNAL";
    }
  }
}
