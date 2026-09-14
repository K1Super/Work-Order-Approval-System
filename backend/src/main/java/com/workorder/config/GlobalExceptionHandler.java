package com.workorder.config;


import java.util.stream.Collectors;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.common.exception.SystemException;
import com.workorder.common.exception.ValidationException;
import com.workorder.common.result.Result;

/**
 * 全局统一异常处理器（规范 §7 异常处理与返回值规范 + §2 错误码规范）
 *
 * <p>接管所有接口异常，Controller 层禁止手写 try-catch；生产环境仅向前端返回友好提示， 完整异常堆栈仅留存日志。所有响应体统一携带 bizCode（三段式错误码）+
 * traceId（全链路追踪）。
 *
 * <p>异常分类处理（规范 §7）：
 *
 * <ul>
 *   <li>{@link BusinessException}：业务异常 → HTTP 200 + 业务错误码
 *   <li>{@link SystemException}：系统异常 → HTTP 500 + 系统错误码
 *   <li>{@link ValidationException}：校验异常 → HTTP 400 + 字段级错误
 *   <li>{@link AuthenticationException}：认证异常 → HTTP 200 + 认证错误码
 *   <li>其他未捕获异常 → HTTP 500 + 系统错误码
 * </ul>
 *
 * @author KLord
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 处理业务异常 — 返回 HTTP 200 + 业务错误码（规范 §7 异常分类） */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Result<?>> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    logger.warn("业务异常: bizCode={}, msg={}", errorCode.getBizCode(), e.getMessage());
    return ResponseEntity.ok(Result.fail(errorCode));
  }

  /** 处理系统异常 — 返回 HTTP 500 + 系统错误码（规范 §7 异常分类） 不暴露内部细节给前端，堆栈留存日志 */
  @ExceptionHandler(SystemException.class)
  public ResponseEntity<Result<?>> handleSystemException(SystemException e) {
    ErrorCode errorCode = e.getErrorCode();
    logger.error("系统异常: bizCode={}, msg={}", errorCode.getBizCode(), e.getMessage(), e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(errorCode));
  }

  /** 处理校验异常 — 返回 HTTP 400 + 字段级错误（规范 §7 异常分类 + §2 详细错误字段） */
  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<Result<?>> handleValidationException(ValidationException e) {
    ErrorCode errorCode = e.getErrorCode();
    logger.warn(
        "校验异常: bizCode={}, violations={}",
        errorCode.getBizCode(),
        e.getViolations().stream()
            .map(v -> v.getField() + ":" + v.getMessage())
            .collect(Collectors.joining(", ")));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(errorCode));
  }

  /** 处理 Spring Security 认证异常（用户名或密码错误 / 锁定 / 禁用） 返回 HTTP 200 + 业务码 01-001-xxx，避免 500 系统错误误导前端 */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<Result<?>> handleAuthenticationException(AuthenticationException e) {
    logger.warn("认证失败: {}", e.getClass().getSimpleName());
    ErrorCode errorCode;
    if (e instanceof BadCredentialsException) {
      errorCode = ErrorCode.LOGIN_BAD_CREDENTIALS;
    } else if (e instanceof LockedException) {
      errorCode = ErrorCode.LOGIN_ACCOUNT_LOCKED;
    } else if (e instanceof DisabledException) {
      errorCode = ErrorCode.LOGIN_ACCOUNT_DISABLED;
    } else {
      errorCode = ErrorCode.LOGIN_BAD_CREDENTIALS;
    }
    return ResponseEntity.ok(Result.fail(errorCode));
  }

  /** 处理参数校验异常（@Validated）— 转换为 ValidationException 格式返回 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Result<?>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException e) {
    java.util.List<ValidationException.FieldViolation> violations =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                fe -> new ValidationException.FieldViolation(fe.getField(), fe.getDefaultMessage()))
            .collect(Collectors.toList());
    logger.warn(
        "参数校验失败: fields={}",
        violations.stream().map(v -> v.getField()).collect(Collectors.joining(",")));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ErrorCode.PARAM_INVALID));
  }

  /** 处理绑定异常 — 业务码 01-000-002 */
  @ExceptionHandler(BindException.class)
  public ResponseEntity<Result<?>> handleBindException(BindException e) {
    String msg =
        e.getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
    logger.warn("参数绑定失败: {}", msg);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ErrorCode.PARAM_INVALID));
  }

  /** 处理约束违反异常 — 业务码 01-000-002 */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Result<?>> handleConstraintViolationException(
      ConstraintViolationException e) {
    String msg =
        e.getConstraintViolations().stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining(", "));
    logger.warn("约束违反: {}", msg);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ErrorCode.PARAM_INVALID));
  }

  /** 处理权限不足异常 — 业务码 01-000-004 */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Result<?>> handleAccessDeniedException(AccessDeniedException e) {
    logger.warn("权限不足: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ErrorCode.ACCESS_DENIED));
  }

  /** 处理请求体不可读异常（如 JSON 格式错误）— 业务码 01-000-002 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Result<?>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
    logger.warn("请求体解析失败: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ErrorCode.PARAM_INVALID));
  }

  /** 处理不支持的媒体类型 — 业务码 01-000-002 */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Result<?>> handleHttpMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e) {
    logger.warn("不支持的媒体类型: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(Result.fail(ErrorCode.PARAM_INVALID));
  }

  /** 处理缺少必填参数异常 — 业务码 01-000-002 */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Result<?>> handleMissingParam(MissingServletRequestParameterException e) {
    logger.warn("缺少请求参数: {}", e.getParameterName());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ErrorCode.PARAM_INVALID));
  }

  /** 处理其他未捕获异常 — 转为 SystemException 处理，业务码 01-000-001 仅返回友好提示，堆栈留存日志 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<?>> handleException(Exception e) {
    logger.error("系统异常", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Result.fail(ErrorCode.SYSTEM_ERROR));
  }
}
