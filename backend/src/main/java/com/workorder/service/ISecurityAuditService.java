package com.workorder.service;


import java.util.Map;


/**
 * Security Audit Log Service Interface 安全审计日志服务接口 - 用于记录所有安全相关操作
 *
 * @author KLord
 */
public interface ISecurityAuditService {

  /**
   * Record security audit log 记录安全审计日志
   *
   * @param userId User ID (can be null for anonymous operations)
   * @param username Username
   * @param actionType Action type (LOGIN_SUCCESS, LOGIN_FAILURE, ACCOUNT_LOCKED, PASSWORD_CHANGE,
   *     etc.)
   * @param description Description of the action
   * @param ipAddress Client IP address
   * @param userAgent User agent string
   * @param requestUrl Request URL
   * @param status Status: SUCCESS, FAILURE, WARNING
   * @param details Additional context data as JSON
   */
  void audit(
      Long userId,
      String username,
      String actionType,
      String description,
      String ipAddress,
      String userAgent,
      String requestUrl,
      String status,
      Map<String, Object> details);

  /** Convenience method for login success event 登录成功事件便捷方法 */
  void logLoginSuccess(Long userId, String username, String ipAddress, String userAgent);

  /** Convenience method for login failure event 登录失败事件便捷方法 */
  void logLoginFailure(String username, String ipAddress, String userAgent, String reason);

  /** Convenience method for account lockout event 账号锁定事件便捷方法 */
  void logAccountLocked(String username, String ipAddress, int maxAttempts, int lockoutMinutes);

  /** Convenience method for password change event 密码变更事件便捷方法 */
  void logPasswordChange(Long userId, String username, String ipAddress, boolean forcedByExpiry);

  /** Convenience method for sensitive operation event 敏感操作事件便捷方法（批量审批、流程变更等） */
  void logSensitiveOperation(
      Long userId,
      String username,
      String operationType,
      String description,
      Map<String, Object> details);

  /**
   * Query audit logs with pagination and filters 分页查询审计日志
   *
   * @param params Query parameters (userId, actionType, startDate, endDate, status, etc.)
   * @return Paginated result
   */
  Map<String, Object> queryAuditLogs(Map<String, Object> params);
}
