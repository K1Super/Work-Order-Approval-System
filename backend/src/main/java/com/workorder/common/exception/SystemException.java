package com.workorder.common.exception;

/**
 * 系统异常类（规范 §7 异常处理与返回值规范 — 系统异常分类）
 *
 * <p>用于数据库连接失败、Redis 不可用、第三方服务超时等非业务异常。 不暴露内部细节给前端，统一返回 {@link ErrorCode#SYSTEM_ERROR} 或 {@link
 * ErrorCode#DB_CONNECTION_FAILED}。
 *
 * <p>与 {@link BusinessException} 的区别：
 *
 * <ul>
 *   <li>BusinessException：业务逻辑错误（如库存不足、用户已存在），需传递对应错误码
 *   <li>SystemException：基础设施/中间件故障，前端仅显示友好提示，堆栈留存日志
 * </ul>
 *
 * @author KLord
 */
public class SystemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 错误码枚举 */
  private final ErrorCode errorCode;

  /**
   * 使用 ErrorCode 枚举构造（推荐）
   *
   * @param errorCode 错误码枚举
   */
  public SystemException(ErrorCode errorCode) {
    super(errorCode.getMessageZh());
    this.errorCode = errorCode;
  }

  /**
   * 使用 ErrorCode 枚举 + 原始异常构造
   *
   * @param errorCode 错误码枚举
   * @param cause 原始异常
   */
  public SystemException(ErrorCode errorCode, Throwable cause) {
    super(errorCode.getMessageZh(), cause);
    this.errorCode = errorCode;
  }

  /**
   * 使用 ErrorCode 枚举 + 自定义消息构造
   *
   * @param errorCode 错误码枚举
   * @param message 自定义错误消息
   */
  public SystemException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  /**
   * 使用 ErrorCode 枚举 + 自定义消息 + 原始异常构造
   *
   * @param errorCode 错误码枚举
   * @param message 自定义错误消息
   * @param cause 原始异常
   */
  public SystemException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
