package com.workorder.common.exception;



import org.apache.ibatis.annotations.Param;

import com.workorder.common.result.Result;

/**
 * 业务异常类（规范 §7 异常处理与返回值规范 — 业务异常分类 + §2 错误码规范）
 *
 * <p>用于业务逻辑错误，如库存不足、用户已存在、工单状态冲突等。 携带 ErrorCode 枚举，由 GlobalExceptionHandler 统一捕获并返回标准 Result。
 *
 * <p>与 {@link SystemException} 的区别：
 *
 * <ul>
 *   <li>BusinessException：业务逻辑错误，需传递对应错误码给前端
 *   <li>SystemException：基础设施/中间件故障，不暴露内部细节
 * </ul>
 *
 * @author KLord
 */
public class BusinessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 错误码枚举（规范 §2 — 每个异常必须携带 ErrorCode） */
  private ErrorCode errorCode;

  /** 旧数值错误码（@Deprecated 过渡期，使用 errorCode 替代） */
  @Deprecated private Integer code;

  /**
   * 使用 ErrorCode 枚举构造（规范推荐入口）
   *
   * @param errorCode 错误码枚举
   * @param args 消息占位符参数
   */
  public BusinessException(ErrorCode errorCode, Object... args) {
    super(errorCode.renderMessage(args));
    this.errorCode = errorCode;
    this.code = errorCode.getLegacyCode() != null ? errorCode.getLegacyCode() : Result.SYSTEM_ERROR;
  }

  /** 使用自定义消息构造（@Deprecated 旧入口，建议使用 ErrorCode 枚举） */
  @Deprecated
  public BusinessException(String message) {
    super(message);
    this.errorCode = ErrorCode.SYSTEM_ERROR;
    this.code = Result.SYSTEM_ERROR;
  }

  /** 使用旧数值码 + 消息构造（@Deprecated 旧入口，建议使用 ErrorCode 枚举） */
  @Deprecated
  public BusinessException(Integer code, String message) {
    super(message);
    this.code = code;
    // 尝试通过旧码映射到 ErrorCode
    ErrorCode mapped = ErrorCodeRegistry.lookupByLegacyCode(code);
    this.errorCode = mapped != null ? mapped : ErrorCode.SYSTEM_ERROR;
  }

  /** 使用自定义消息 + 原始异常构造（@Deprecated 旧入口） */
  @Deprecated
  public BusinessException(String message, Throwable cause) {
    super(message, cause);
    this.errorCode = ErrorCode.SYSTEM_ERROR;
    this.code = Result.SYSTEM_ERROR;
  }

  /** 使用旧数值码 + 消息 + 原始异常构造（@Deprecated 旧入口） */
  @Deprecated
  public BusinessException(Integer code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    ErrorCode mapped = ErrorCodeRegistry.lookupByLegacyCode(code);
    this.errorCode = mapped != null ? mapped : ErrorCode.SYSTEM_ERROR;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(ErrorCode errorCode) {
    this.errorCode = errorCode;
  }

  /** @deprecated 使用 getErrorCode() 替代 */
  @Deprecated
  public Integer getCode() {
    return code;
  }

  /** @deprecated 使用 setErrorCode() 替代 */
  @Deprecated
  public void setCode(Integer code) {
    this.code = code;
  }
}
