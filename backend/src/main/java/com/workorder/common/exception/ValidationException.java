package com.workorder.common.exception;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * 校验异常类（规范 §7 异常处理与返回值规范 — 校验异常分类）
 *
 * <p>用于参数校验失败场景，携带字段级错误列表，精确到具体字段和错误原因。 替代直接暴露 Spring 原生 MethodArgumentNotValidException。
 *
 * <p>与 {@link BusinessException} 的区别：
 *
 * <ul>
 *   <li>BusinessException：业务逻辑错误（如库存不足、权限不够）
 *   <li>ValidationException：参数校验错误（如字段缺失、格式非法），携带字段级详情
 * </ul>
 *
 * @author KLord
 */
public class ValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 错误码枚举 */
  private final ErrorCode errorCode;

  /** 字段级错误列表 */
  private final List<FieldViolation> violations;

  /**
   * 使用字段级错误列表构造
   *
   * @param violations 字段级错误列表
   */
  public ValidationException(List<FieldViolation> violations) {
    super(ErrorCode.PARAM_INVALID.getMessageZh());
    this.errorCode = ErrorCode.PARAM_INVALID;
    this.violations = violations != null ? violations : Collections.emptyList();
  }

  /**
   * 使用单个字段错误构造
   *
   * @param field 字段名
   * @param message 错误消息
   */
  public ValidationException(String field, String message) {
    super(ErrorCode.PARAM_INVALID.getMessageZh());
    this.errorCode = ErrorCode.PARAM_INVALID;
    this.violations = Collections.singletonList(new FieldViolation(field, message));
  }

  /**
   * 使用 ErrorCode 枚举构造
   *
   * @param errorCode 错误码枚举
   * @param violations 字段级错误列表
   */
  public ValidationException(ErrorCode errorCode, List<FieldViolation> violations) {
    super(errorCode.getMessageZh());
    this.errorCode = errorCode;
    this.violations = violations != null ? violations : Collections.emptyList();
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public List<FieldViolation> getViolations() {
    return violations;
  }

  /** 字段级错误详情（规范 §7 异常处理 — 详细错误字段可增加 errors 数组，精确到字段级别） */
  public static class FieldViolation {
    /** 字段名 */
    private final String field;
    /** 错误消息 */
    private final String message;

    /** 构造函数，初始化字段级错误详情 */
    public FieldViolation(String field, String message) {
      this.field = field;
      this.message = message;
    }

    public String getField() {
      return field;
    }

    public String getMessage() {
      return message;
    }
  }

  /** 构建器 — 方便逐步添加字段错误 */
  public static class Builder {
    private final List<FieldViolation> violations = new ArrayList<>();

    /** 添加字段错误并返回当前构建器 */
    public Builder add(String field, String message) {
      violations.add(new FieldViolation(field, message));
      return this;
    }

    /** 构建校验异常对象 */
    public ValidationException build() {
      return new ValidationException(violations);
    }
  }
}
