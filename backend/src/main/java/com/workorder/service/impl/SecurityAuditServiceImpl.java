package com.workorder.service.impl;


import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.config.MetricsConfig;
import com.workorder.dao.SecurityAuditLogMapper;
import com.workorder.entity.SecurityAuditLog;
import com.workorder.service.ISecurityAuditService;

/**
 * Security Audit Log Service Implementation 安全审计日志服务实现 - 实现全链路日志追溯
 *
 * <p>Features: 1. 登录日志（IP、设备、时间、异常） 2. 工单操作（发起、修改、撤回、审批等） 3. 后台配置（流程修改、角色权限变更） 4.
 * 敏感操作（批量审批、超级管理员操作）
 *
 * @author KLord
 */
@Service
public class SecurityAuditServiceImpl implements ISecurityAuditService {

  private static final Logger logger = LoggerFactory.getLogger(SecurityAuditServiceImpl.class);

  @Autowired private SecurityAuditLogMapper auditLogMapper;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private MetricsConfig metricsConfig;

  /** Record security audit log 记录安全审计日志 */
  @Override
  public void audit(
      Long userId,
      String username,
      String actionType,
      String description,
      String ipAddress,
      String userAgent,
      String requestUrl,
      String status,
      Map<String, Object> details) {
    try {
      SecurityAuditLog auditLog = new SecurityAuditLog();
      auditLog.setUserId(userId);
      auditLog.setUsername(username);
      auditLog.setActionType(actionType);
      auditLog.setDescription(description);
      auditLog.setIpAddress(ipAddress);
      auditLog.setUserAgent(userAgent);
      auditLog.setRequestUrl(requestUrl);
      auditLog.setStatus(status);

      // Convert details map to JSON string
      if (details != null && !details.isEmpty()) {
        try {
          auditLog.setDetails(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
          logger.warn("Failed to serialize audit log details: {}", e.getMessage());
          auditLog.setDetails("{}");
        }
      }

      // 从 MDC 读取结构化日志字段（规范条款 7，TraceIdFilter 注入）
      auditLog.setTraceId(org.slf4j.MDC.get("traceId"));
      auditLog.setSpanId(org.slf4j.MDC.get("spanId"));
      auditLog.setPath(org.slf4j.MDC.get("path"));

      // Save to database
      auditLogMapper.insert(auditLog);

      logger.info(
          "[AUDIT] {} | User: {} | Action: {} | IP: {} | Status: {}",
          actionType,
          username,
          actionType,
          ipAddress,
          status);

    } catch (Exception e) {
      logger.error("Failed to record security audit log: {}", e.getMessage(), e);
    }
  }

  @Override
  public void logLoginSuccess(Long userId, String username, String ipAddress, String userAgent) {
    Map<String, Object> details = new HashMap<>();
    details.put("timestamp", LocalDateTime.now().toString());

    audit(
        userId,
        username,
        "LOGIN_SUCCESS",
        "用户登录成功",
        ipAddress,
        userAgent,
        "/api/auth/sessions",
        "SUCCESS",
        details);
  }

  @Override
  public void logLoginFailure(String username, String ipAddress, String userAgent, String reason) {
    Map<String, Object> details = new HashMap<>();
    details.put("reason", reason);
    details.put("timestamp", LocalDateTime.now().toString());

    audit(
        null,
        username,
        "LOGIN_FAILURE",
        reason,
        ipAddress,
        userAgent,
        "/api/auth/sessions",
        "FAILURE",
        details);

    logger.warn(
        "[SECURITY] Login failed for user: {} from IP: {}. Reason: {}",
        username,
        ipAddress,
        reason);
  }

  @Override
  public void logAccountLocked(
      String username, String ipAddress, int maxAttempts, int lockoutMinutes) {
    Map<String, Object> details = new HashMap<>();
    details.put("maxAttempts", maxAttempts);
    details.put("lockoutMinutes", lockoutMinutes);
    details.put("ipAddress", ipAddress);

    audit(
        null,
        username,
        "ACCOUNT_LOCKED",
        String.format("账号因连续%d次登录失败被锁定%d分钟", maxAttempts, lockoutMinutes),
        ipAddress,
        null,
        null,
        "WARNING",
        details);

    logger.error(
        "[SECURITY] Account locked: {} from IP: {}. Max attempts: {}, Lockout duration: {}min",
        username,
        ipAddress,
        maxAttempts,
        lockoutMinutes);
  }

  @Override
  public void logPasswordChange(
      Long userId, String username, String ipAddress, boolean forcedByExpiry) {
    Map<String, Object> details = new HashMap<>();
    details.put("forcedByExpiry", forcedByExpiry);
    details.put("timestamp", LocalDateTime.now().toString());

    audit(
        userId,
        username,
        "PASSWORD_CHANGE",
        forcedByExpiry ? "密码过期强制更改" : "用户主动更改密码",
        ipAddress,
        null,
        "/api/user/password",
        "SUCCESS",
        details);

    logger.info(
        "[SECURITY] Password changed for user: {} from IP: {}. Forced: {}",
        username,
        ipAddress,
        forcedByExpiry);
  }

  @Override
  public void logSensitiveOperation(
      Long userId,
      String username,
      String operationType,
      String description,
      Map<String, Object> details) {
    if (details == null) {
      details = new HashMap<>();
    }
    details.put("operationType", operationType);
    details.put("timestamp", LocalDateTime.now().toString());

    // 修复 bug：从当前 HTTP 请求上下文获取 IP 与 UA（原代码全部传 null）
    HttpServletRequest request = getCurrentRequest();
    String ipAddress = (request != null) ? getClientIpFromRequest(request) : null;
    String userAgent = (request != null) ? request.getHeader("User-Agent") : null;
    String requestUrl = (request != null) ? request.getRequestURI() : null;

    audit(
        userId,
        username,
        "SENSITIVE_OPERATION",
        description,
        ipAddress,
        userAgent,
        requestUrl,
        "WARNING",
        details);

    // Prometheus 埋点：记录敏感操作
    metricsConfig.recordSensitiveOperation(operationType);

    logger.warn(
        "[SECURITY] Sensitive operation by {}: {} - {} (IP: {})",
        username,
        operationType,
        description,
        ipAddress);
  }

  @Override
  public Map<String, Object> queryAuditLogs(Map<String, Object> params) {
    // 修复 bug：移除 try-catch 吞异常，异常向上抛由 GlobalExceptionHandler 统一处理，避免静默失败
    return auditLogMapper.queryWithPagination(params);
  }

  /** 从 Spring RequestContextHolder 获取当前 HTTP 请求 可能在异步线程或定时任务中为 null */
  private HttpServletRequest getCurrentRequest() {
    try {
      Object requestAttr =
          org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
              .resolveReference(
                  org.springframework.web.context.request.RequestAttributes.REFERENCE_REQUEST);
      return (requestAttr instanceof HttpServletRequest) ? (HttpServletRequest) requestAttr : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** 从请求中解析真实客户端 IP（支持反向代理） 优先级：X-Forwarded-For > X-Real-IP > getRemoteAddr */
  private String getClientIpFromRequest(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      // X-Forwarded-For 可能含多级代理，取第一个（最原始的客户端 IP）
      ip = ip.split(",")[0].trim();
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("X-Real-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getRemoteAddr();
    }
    return ip;
  }
}
