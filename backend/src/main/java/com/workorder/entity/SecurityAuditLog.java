package com.workorder.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Security Audit Log Entity 安全审计日志实体 - 用于记录所有安全相关操作
 *
 * @author KLord
 */
public class SecurityAuditLog implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key ID */
  private Long id;

  /** User ID (can be null for anonymous operations) */
  private Long userId;

  /** Username */
  private String username;

  /** Action type: LOGIN_SUCCESS, LOGIN_FAILURE, ACCOUNT_LOCKED, PASSWORD_CHANGE, etc. */
  private String actionType;

  /** Description of the action */
  private String description;

  /** Client IP address (supports IPv6) */
  private String ipAddress;

  /** User agent string */
  private String userAgent;

  /** Request URL */
  private String requestUrl;

  /** Status: SUCCESS, FAILURE, WARNING */
  private String status;

  /** Additional context data as JSON string */
  private String details;

  /** Create timestamp */
  private LocalDateTime createTime;

  /** 创建人ID（规范 §2.7.1 审计字段） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段） */
  private Long updateBy;

  // ==================== 规范条款 7：结构化日志字段增强 ====================

  /** 全链路追踪 ID（从 MDC 获取，TraceIdFilter 生成） */
  private String traceId;

  /** Span ID（从 MDC 获取，标识当前请求分段） */
  private String spanId;

  /** 操作动作标识（如 LOGIN/APPROVE/REJECT/DELETE，对齐 StructuredLogUtil.MDC_ACTION） */
  private String action;

  /** 请求路径（从 MDC 获取） */
  private String path;

  /** 操作耗时（毫秒，从 MDC 获取） */
  private Long duration;

  /** 业务错误码（如 40101/40301/50001） */
  private String errorCode;

  /** 错误位置（类名:方法名:行号） */
  private String errorLocation;

  /** 构造函数，初始化空安全审计日志 */
  public SecurityAuditLog() {}

  // Getters and Setters
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public String getRequestUrl() {
    return requestUrl;
  }

  public void setRequestUrl(String requestUrl) {
    this.requestUrl = requestUrl;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public LocalDateTime getCreateTime() {
    return createTime;
  }

  public void setCreateTime(LocalDateTime createTime) {
    this.createTime = createTime;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long createBy) {
    this.createBy = createBy;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long updateBy) {
    this.updateBy = updateBy;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public void setSpanId(String spanId) {
    this.spanId = spanId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public Long getDuration() {
    return duration;
  }

  public void setDuration(Long duration) {
    this.duration = duration;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getErrorLocation() {
    return errorLocation;
  }

  public void setErrorLocation(String errorLocation) {
    this.errorLocation = errorLocation;
  }
}
