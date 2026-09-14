package com.workorder.util;


import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * 结构化日志工具类 — 规范条款 1/7/10/12
 *
 * <p>职责：
 *
 * <ul>
 *   <li>规范条款 1（统一出口）：提供 MDC 操作和结构化日志输出方法，业务代码通过此类补充上下文
 *   <li>规范条款 7（结构化日志字段）：MDC
 *       固定字段（action/bizNo/duration/errorMessage/errorLocation/errorCode/stackTrace）
 *   <li>规范条款 10（严格禁止行为）：强制 action+bizNo+result 格式，禁止无上下文日志
 *   <li>规范条款 12（日志文案标准）：动作+业务对象+执行结果/异常原因
 * </ul>
 *
 * <p>使用方式：
 *
 * <pre>
 * // 设置业务上下文
 * StructuredLogUtil.setBizContext("user_login", "userId=123", null);
 * logger.info("用户登录成功");
 * StructuredLogUtil.clearBizContext();
 *
 * // 结构化日志输出（推荐）
 * StructuredLogUtil.logBizAction(logger, "user_login", "用户登录", "userId=123", "登录成功");
 * StructuredLogUtil.logBizError(logger, "order_create", "工单创建", "orderId=456", exception);
 * </pre>
 *
 * @author KLord
 */
public final class StructuredLogUtil {

  private StructuredLogUtil() {}

  // ==================== MDC 键名常量 ====================

  /** 业务操作标识（如 user_login、order_create） */
  public static final String MDC_ACTION = "action";
  /** 业务单号（工单ID、订单ID等） */
  public static final String MDC_BIZ_NO = "bizNo";
  /** 操作耗时（毫秒） */
  public static final String MDC_DURATION = "duration";
  /** 错误码 */
  public static final String MDC_ERROR_CODE = "errorCode";
  /** 错误描述 */
  public static final String MDC_ERROR_MESSAGE = "errorMessage";
  /** 错误位置（类名:方法名:行号） */
  public static final String MDC_ERROR_LOCATION = "errorLocation";
  /** 异常堆栈 */
  public static final String MDC_STACK_TRACE = "stackTrace";

  // ==================== MDC 操作 ====================

  /**
   * 设置业务上下文到 MDC
   *
   * @param action 业务操作标识（如 user_login、order_create）
   * @param bizNo 业务单号（工单ID、订单ID等）
   * @param errorCode 业务错误码（可选）
   */
  public static void setBizContext(String action, String bizNo, String errorCode) {
    if (action != null) MDC.put(MDC_ACTION, action);
    if (bizNo != null) MDC.put(MDC_BIZ_NO, bizNo);
    if (errorCode != null) MDC.put(MDC_ERROR_CODE, errorCode);
  }

  /**
   * 设置错误上下文到 MDC（ERROR 级别日志必填 — 规范条款 7）
   *
   * @param errorMessage 中文错误描述
   * @param errorLocation 代码位置（类名:方法名:行号）
   * @param stackTrace 完整异常堆栈
   */
  public static void setErrorContext(String errorMessage, String errorLocation, String stackTrace) {
    if (errorMessage != null) MDC.put(MDC_ERROR_MESSAGE, errorMessage);
    if (errorLocation != null) MDC.put(MDC_ERROR_LOCATION, errorLocation);
    if (stackTrace != null) MDC.put(MDC_STACK_TRACE, stackTrace);
  }

  /**
   * 计算并设置 duration（ms）— 规范条款 7 链路追溯字段
   *
   * @param startTimeMs 请求开始时间（System.currentTimeMillis()）
   */
  public static void setDuration(long startTimeMs) {
    long duration = System.currentTimeMillis() - startTimeMs;
    MDC.put(MDC_DURATION, String.valueOf(duration));
  }

  /** 清理业务上下文（请求内多次操作时清理上一次残留） */
  public static void clearBizContext() {
    MDC.remove(MDC_ACTION);
    MDC.remove(MDC_BIZ_NO);
    MDC.remove(MDC_ERROR_CODE);
    MDC.remove(MDC_ERROR_MESSAGE);
    MDC.remove(MDC_ERROR_LOCATION);
    MDC.remove(MDC_STACK_TRACE);
    MDC.remove(MDC_DURATION);
  }

  // ==================== 结构化日志输出（规范条款 12：动作+业务对象+执行结果/异常原因） ====================

  /**
   * 记录业务操作日志（INFO 级别）— 规范条款 7/12
   *
   * <p>日志文案格式：{@code [动作] 业务对象 - 执行结果 (bizNo=xxx)}
   *
   * @param logger SLF4J Logger 实例
   * @param action 业务操作标识（如 user_login、order_create）
   * @param bizObject 业务对象描述（如 "用户登录"、"工单创建"）
   * @param bizNo 业务单号（工单ID、用户ID等）
   * @param result 执行结果（如 "登录成功"、"创建失败"）
   */
  public static void logBizAction(
      Logger logger, String action, String bizObject, String bizNo, String result) {
    MDC.put(MDC_ACTION, action);
    MDC.put(MDC_BIZ_NO, bizNo != null ? bizNo : "");
    logger.info("[{}] {} - {} (bizNo={})", action, bizObject, result, bizNo);
  }

  /**
   * 记录业务异常日志（ERROR 级别，含完整堆栈）— 规范条款 7/10/12
   *
   * <p>日志文案格式：{@code [动作] 业务对象 - 异常: 原因 (bizNo=xxx)}
   *
   * <p>强制要求：必须携带完整堆栈、错误描述、业务上下文（规范条款 10）
   *
   * @param logger SLF4J Logger 实例
   * @param action 业务操作标识
   * @param bizObject 业务对象描述
   * @param bizNo 业务单号
   * @param t 异常对象
   */
  public static void logBizError(
      Logger logger, String action, String bizObject, String bizNo, Throwable t) {
    MDC.put(MDC_ACTION, action);
    MDC.put(MDC_BIZ_NO, bizNo != null ? bizNo : "");
    MDC.put(MDC_ERROR_MESSAGE, t.getMessage());
    String errorLocation =
        t.getStackTrace() != null && t.getStackTrace().length > 0
            ? t.getStackTrace()[0].toString()
            : "unknown";
    MDC.put(MDC_ERROR_LOCATION, errorLocation);
    logger.error("[{}] {} - 异常: {} (bizNo={})", action, bizObject, t.getMessage(), bizNo, t);
  }

  /**
   * 记录安全审计日志（WARN 级别，路由到 SECURITY_AUDIT Logger）
   *
   * @param securityLogger 安全审计 Logger 实例
   * @param action 安全操作标识
   * @param description 操作描述
   * @param status 状态（SUCCESS/FAILURE/WARNING）
   */
  public static void logSecurityAudit(
      Logger securityLogger, String action, String description, String status) {
    MDC.put(MDC_ACTION, action);
    securityLogger.warn("[SECURITY] {} - {} (status={})", action, description, status);
  }
}
