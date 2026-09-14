package com.workorder.common.exception;

/**
 * 错误码枚举 — 企业级错误码注册中心（规范 §2 错误码规范）
 *
 * <p>三段式编码：{系统码(2位)}-{模块码(3位)}-{错误编号(3位)}
 *
 * <ul>
 *   <li>系统码：01 = 工单审批系统（WOS）
 *   <li>模块码：000=公共, 001=认证, 002=工单, 003=审批, 004=员工, 005=文件, 006=系统设置, 007=密码, 998=数据库, 999=公共基础设施
 *   <li>错误编号：001起递增
 * </ul>
 *
 * <p>每个枚举值绑定：
 *
 * <ul>
 *   <li>bizCode：三段式字符串错误码
 *   <li>httpStatus：建议 HTTP 状态码
 *   <li>messageZh：中文消息模板（支持 {0}/{1} 占位符）
 *   <li>messageEn：英文翻译
 *   <li>level：错误级别（ERROR/WARN/INFO）
 *   <li>legacyCode：旧数值码（向后兼容，过渡期后移除）
 * </ul>
 *
 * <p>CI 自动化校验：启动时 ErrorCodeRegistry 自检无重复 bizCode，否则拒绝启动。
 *
 * @author KLord
 */
public enum ErrorCode {

  // ==================== 公共模块 000 ====================

  /** 操作成功 */
  SUCCESS("01-000-000", 200, "操作成功", "Success", ErrorLevel.INFO, 20000),

  /** 系统内部错误 */
  SYSTEM_ERROR("01-000-001", 500, "系统内部错误", "Internal server error", ErrorLevel.ERROR, 50001),

  /** 参数缺失或非法 */
  PARAM_INVALID("01-000-002", 400, "参数缺失或非法", "Invalid parameter", ErrorLevel.WARN, 40001),

  /** 身份认证失败 */
  AUTH_FAILED("01-000-003", 401, "身份认证失败", "Authentication failed", ErrorLevel.WARN, 40101),

  /** 无访问权限 */
  ACCESS_DENIED("01-000-004", 403, "无访问权限", "Access denied", ErrorLevel.WARN, 40301),

  /** 请求资源不存在 */
  RESOURCE_NOT_FOUND("01-000-005", 404, "请求资源不存在", "Resource not found", ErrorLevel.WARN, 40401),

  /** 请求太频繁 */
  RATE_LIMITED("01-000-006", 429, "请求太频繁", "Too many requests", ErrorLevel.WARN, null),

  /** 重复请求 / 防重复提交 */
  REPEAT_REQUEST("01-000-007", 409, "重复请求", "Duplicate request", ErrorLevel.WARN, 60001),

  // ==================== 认证模块 001 ====================

  /** 用户名或密码错误 */
  LOGIN_BAD_CREDENTIALS("01-001-001", 200, "用户名或密码错误", "Bad credentials", ErrorLevel.WARN, 40101),

  /** 账号已被锁定 */
  LOGIN_ACCOUNT_LOCKED("01-001-002", 200, "账号已被锁定", "Account locked", ErrorLevel.WARN, 40101),

  /** 账号已被禁用 */
  LOGIN_ACCOUNT_DISABLED("01-001-003", 200, "账号已被禁用", "Account disabled", ErrorLevel.WARN, 40101),

  /** 令牌已过期 */
  TOKEN_EXPIRED("01-001-004", 401, "令牌已过期", "Token expired", ErrorLevel.WARN, 40101),

  /** 令牌无效 */
  TOKEN_INVALID("01-001-005", 401, "令牌无效", "Invalid token", ErrorLevel.WARN, 40101),

  /** 密码修改失败 */
  PASSWORD_CHANGE_FAILED(
      "01-001-006", 200, "密码修改失败", "Password change failed", ErrorLevel.ERROR, null),

  /** 二次鉴权失败 */
  REAUTH_FAILED("01-001-007", 403, "二次鉴权失败", "Re-authentication failed", ErrorLevel.WARN, 40301),

  /** 注册失败 */
  REGISTER_FAILED("01-001-008", 200, "注册失败", "Registration failed", ErrorLevel.ERROR, null),

  /** 登出失败 */
  LOGOUT_FAILED("01-001-009", 200, "登出失败", "Logout failed", ErrorLevel.ERROR, null),

  // ==================== 工单模块 002 ====================

  /** 工单创建失败 */
  WORKORDER_CREATE_FAILED(
      "01-002-001", 200, "工单创建失败", "Work order creation failed", ErrorLevel.ERROR, null),

  /** 工单提交失败 */
  WORKORDER_SUBMIT_FAILED(
      "01-002-002", 200, "工单提交失败", "Work order submission failed", ErrorLevel.ERROR, null),

  /** 工单不存在 */
  WORKORDER_NOT_FOUND("01-002-003", 404, "工单不存在", "Work order not found", ErrorLevel.WARN, 40401),

  /** 工单状态冲突 */
  WORKORDER_STATUS_CONFLICT(
      "01-002-004", 409, "工单状态冲突", "Work order status conflict", ErrorLevel.WARN, null),

  /** 数据版本不匹配（乐观锁冲突） */
  WORKORDER_VERSION_MISMATCH(
      "01-002-005",
      409,
      "数据已被修改，请刷新后重试",
      "Data version mismatch, please refresh",
      ErrorLevel.WARN,
      null),

  /** 工单查询失败 */
  WORKORDER_QUERY_FAILED(
      "01-002-006", 200, "工单查询失败", "Work order query failed", ErrorLevel.ERROR, null),

  /** 工单更新失败 */
  WORKORDER_UPDATE_FAILED(
      "01-002-007", 200, "工单更新失败", "Work order update failed", ErrorLevel.ERROR, null),

  /** 工单删除失败 */
  WORKORDER_DELETE_FAILED(
      "01-002-008", 200, "工单删除失败", "Work order deletion failed", ErrorLevel.ERROR, null),

  // ==================== 审批模块 003 ====================

  /** 审批动作无效 */
  APPROVAL_ACTION_INVALID(
      "01-003-001", 400, "审批动作无效", "Invalid approval action", ErrorLevel.WARN, null),

  /** 非当前审批人 */
  APPROVAL_NOT_ASSIGNEE(
      "01-003-002", 403, "非当前审批人", "Not current assignee", ErrorLevel.WARN, 40301),

  /** 审批操作失败 */
  APPROVAL_OPERATION_FAILED(
      "01-003-003", 200, "审批操作失败", "Approval operation failed", ErrorLevel.ERROR, null),

  // ==================== 员工模块 004 ====================

  /** 员工不存在 */
  EMPLOYEE_NOT_FOUND("01-004-001", 404, "员工不存在", "Employee not found", ErrorLevel.WARN, 40401),

  /** 用户名已存在 */
  EMPLOYEE_USERNAME_EXISTS(
      "01-004-002", 409, "用户名已存在", "Username already exists", ErrorLevel.WARN, null),

  /** 员工创建失败 */
  EMPLOYEE_CREATE_FAILED(
      "01-004-003", 200, "员工创建失败", "Employee creation failed", ErrorLevel.ERROR, null),

  /** 员工更新失败 */
  EMPLOYEE_UPDATE_FAILED(
      "01-004-004", 200, "员工更新失败", "Employee update failed", ErrorLevel.ERROR, null),

  /** 员工删除失败 */
  EMPLOYEE_DELETE_FAILED(
      "01-004-005", 200, "员工删除失败", "Employee deletion failed", ErrorLevel.ERROR, null),

  // ==================== 文件模块 005 ====================

  /** 文件上传失败 */
  FILE_UPLOAD_FAILED("01-005-001", 200, "文件上传失败", "File upload failed", ErrorLevel.ERROR, null),

  /** 文件类型不允许 */
  FILE_TYPE_NOT_ALLOWED(
      "01-005-002", 400, "文件类型不允许", "File type not allowed", ErrorLevel.WARN, null),

  /** 文件大小超限 */
  FILE_SIZE_EXCEEDED("01-005-003", 400, "文件大小超限", "File size exceeded", ErrorLevel.WARN, null),

  // ==================== 系统设置模块 006 ====================

  /** 设置保存失败 */
  SETTINGS_SAVE_FAILED("01-006-001", 200, "设置保存失败", "Settings save failed", ErrorLevel.ERROR, null),

  // ==================== 密码模块 007 ====================

  /** 重置令牌无效或已过期 */
  PASSWORD_RESET_TOKEN_INVALID(
      "01-007-001", 400, "重置令牌无效或已过期", "Reset token invalid or expired", ErrorLevel.WARN, null),

  /** 密码不符合策略要求 */
  PASSWORD_POLICY_VIOLATION(
      "01-007-002", 400, "密码不符合策略要求", "Password policy violation", ErrorLevel.WARN, null),

  // ==================== 数据库模块 998 ====================

  /** 数据库连接失败 */
  DB_CONNECTION_FAILED(
      "01-998-001", 500, "数据库连接失败", "Database connection failed", ErrorLevel.ERROR, 50001),

  /** 数据库查询超时 */
  DB_QUERY_TIMEOUT("01-998-002", 500, "数据库查询超时", "Database query timeout", ErrorLevel.ERROR, 50001),

  // ==================== 公共基础设施模块 999 ====================

  /** 第三方服务调用失败 */
  EXTERNAL_SERVICE_FAILED(
      "01-999-001", 500, "第三方服务调用失败", "External service call failed", ErrorLevel.ERROR, null),

  /** Redis 不可用 */
  REDIS_UNAVAILABLE(
      "01-999-002", 500, "缓存服务不可用", "Cache service unavailable", ErrorLevel.ERROR, null),
  ;

  /** 三段式字符串错误码 */
  private final String bizCode;

  /** 建议 HTTP 状态码 */
  private final int httpStatus;

  /** 中文消息模板（支持 {0}/{1} 占位符） */
  private final String messageZh;

  /** 英文翻译 */
  private final String messageEn;

  /** 错误级别 */
  private final ErrorLevel level;

  /** 旧数值码（向后兼容，过渡期后移除） */
  private final Integer legacyCode;

  ErrorCode(
      String bizCode,
      int httpStatus,
      String messageZh,
      String messageEn,
      ErrorLevel level,
      Integer legacyCode) {
    this.bizCode = bizCode;
    this.httpStatus = httpStatus;
    this.messageZh = messageZh;
    this.messageEn = messageEn;
    this.level = level;
    this.legacyCode = legacyCode;
  }

  public String getBizCode() {
    return bizCode;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getMessageZh() {
    return messageZh;
  }

  public String getMessageEn() {
    return messageEn;
  }

  public ErrorLevel getLevel() {
    return level;
  }

  public Integer getLegacyCode() {
    return legacyCode;
  }

  /**
   * 渲染带占位符的中文消息
   *
   * @param args 占位符参数
   * @return 渲染后的消息
   */
  public String renderMessage(Object... args) {
    String msg = this.messageZh;
    if (args != null) {
      for (int i = 0; i < args.length; i++) {
        msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
      }
    }
    return msg;
  }
}
