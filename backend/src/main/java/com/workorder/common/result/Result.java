package com.workorder.common.result;


import java.io.Serializable;

import org.apache.ibatis.annotations.Param;
import org.slf4j.MDC;

import com.workorder.common.exception.ErrorCode;

/**
 * 统一响应结果封装类（规范 §2.5 固定返回结构 + §2 错误码规范 + §7 异常处理返回值规范）
 *
 * <p>固定 JSON 结构：
 *
 * <pre>
 * {
 *   "code": 20000,          // 业务状态码（@Deprecated 过渡期，使用 bizCode 替代）
 *   "bizCode": "01-000-000",// 三段式字符串错误码（规范 §2 错误码规范）
 *   "msg":  "操作成功",      // 提示信息
 *   "data": { ... },         // 业务数据，可为空
 *   "traceId": "WOS-xxxx-xxxx",// 全链路追踪ID（规范 §5 结构化日志字段）
 *   "timestamp": 171...      // 服务器时间戳
 * }
 * </pre>
 *
 * <p>双码制说明（向后兼容过渡期）：
 *
 * <ul>
 *   <li>code（Integer）：旧数值码（如 20000/40001/40101），保留向后兼容，过渡期后移除
 *   <li>bizCode（String）：三段式字符串码（如 "01-000-000"），新规范推荐
 *   <li>前端优先读取 bizCode，若无则回退读取 code
 * </ul>
 *
 * @param <T> 数据类型
 * @author KLord
 */
public class Result<T> implements Serializable {

  private static final long serialVersionUID = 1L;

  // ==================== 业务码常量（@Deprecated 过渡期，使用 ErrorCode 枚举替代） ====================

  /** 操作成功（@Deprecated 使用 ErrorCode.SUCCESS） */
  @Deprecated public static final int SUCCESS = 20000;

  /** 参数非法 / 参数校验失败（@Deprecated 使用 ErrorCode.PARAM_INVALID） */
  @Deprecated public static final int PARAM_INVALID = 40001;

  /** 未登录 / 令牌过期 / 认证失败（@Deprecated 使用 ErrorCode.AUTH_FAILED） */
  @Deprecated public static final int UNAUTHORIZED = 40101;

  /** 权限不足 / 禁止访问（@Deprecated 使用 ErrorCode.ACCESS_DENIED） */
  @Deprecated public static final int FORBIDDEN = 40301;

  /** 请求资源 / 数据不存在（@Deprecated 使用 ErrorCode.RESOURCE_NOT_FOUND） */
  @Deprecated public static final int NOT_FOUND = 40401;

  /** 系统内部异常（@Deprecated 使用 ErrorCode.SYSTEM_ERROR） */
  @Deprecated public static final int SYSTEM_ERROR = 50001;

  /** 重复请求 / 防重复提交（@Deprecated 使用 ErrorCode.REPEAT_REQUEST） */
  @Deprecated public static final int REPEAT_REQUEST = 60001;

  /** 状态码（旧数值码，@Deprecated 过渡期，使用 bizCode 替代） */
  @Deprecated private Integer code;

  /** 三段式字符串错误码（规范 §2 错误码规范，如 "01-000-000"） */
  private String bizCode;

  /** 提示信息 */
  private String msg;

  /** 业务数据 */
  private T data;

  /** 全链路追踪ID（规范 §5 结构化日志字段 — 从 MDC 自动填充） */
  private String traceId;

  /** 服务器时间戳（毫秒） */
  private long timestamp;

  /** 构造函数，初始化空响应并自动填充时间戳与追踪ID */
  public Result() {
    this.timestamp = System.currentTimeMillis();
    this.traceId = MDC.get("traceId");
  }

  /** 构造函数，指定状态码、业务码、提示信息和业务数据 */
  public Result(Integer code, String bizCode, String msg, T data) {
    this.code = code;
    this.bizCode = bizCode;
    this.msg = msg;
    this.data = data;
    this.timestamp = System.currentTimeMillis();
    this.traceId = MDC.get("traceId");
  }

  // ==================== 工厂方法（新规范推荐入口） ====================

  /** 基于 ErrorCode 枚举的成功响应 */
  public static <T> Result<T> success(T data) {
    return new Result<>(SUCCESS, ErrorCode.SUCCESS.getBizCode(), "操作成功", data);
  }

  /** 成功响应（无数据） */
  public static <T> Result<T> success() {
    return success(null);
  }

  /** 成功响应（自定义消息） */
  public static <T> Result<T> success(String msg, T data) {
    return new Result<>(SUCCESS, ErrorCode.SUCCESS.getBizCode(), msg, data);
  }

  /**
   * 基于 ErrorCode 枚举的失败响应（规范 §2 推荐入口）
   *
   * @param errorCode 错误码枚举
   * @param args 消息占位符参数
   * @return 失败响应
   */
  public static <T> Result<T> fail(ErrorCode errorCode, Object... args) {
    Integer legacyCode =
        errorCode.getLegacyCode() != null ? errorCode.getLegacyCode() : SYSTEM_ERROR;
    String message = errorCode.renderMessage(args);
    return new Result<>(legacyCode, errorCode.getBizCode(), message, null);
  }

  // ==================== 旧工厂方法（向后兼容，内部映射到 ErrorCode） ====================

  /** 失败响应（默认系统异常） */
  public static <T> Result<T> error(String msg) {
    return new Result<>(SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getBizCode(), msg, null);
  }

  /** 失败响应（自定义业务码） */
  public static <T> Result<T> error(Integer code, String msg) {
    // 尝试通过旧码查找 ErrorCode
    ErrorCode errorCode = com.workorder.common.exception.ErrorCodeRegistry.lookupByLegacyCode(code);
    String bizCode =
        errorCode != null ? errorCode.getBizCode() : ErrorCode.SYSTEM_ERROR.getBizCode();
    return new Result<>(code, bizCode, msg, null);
  }

  /** 未授权响应（未登录 / 令牌过期 / 认证失败） */
  public static <T> Result<T> unauthorized(String msg) {
    return new Result<>(UNAUTHORIZED, ErrorCode.AUTH_FAILED.getBizCode(), msg, null);
  }

  /** 禁止访问响应（权限不足） */
  public static <T> Result<T> forbidden(String msg) {
    return new Result<>(FORBIDDEN, ErrorCode.ACCESS_DENIED.getBizCode(), msg, null);
  }

  /** 资源不存在响应 */
  public static <T> Result<T> notFound(String msg) {
    return new Result<>(NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getBizCode(), msg, null);
  }

  /** 参数非法响应（参数校验失败） */
  public static <T> Result<T> paramError(String msg) {
    return new Result<>(PARAM_INVALID, ErrorCode.PARAM_INVALID.getBizCode(), msg, null);
  }

  // ==================== 便捷判断 ====================

  /** 是否业务成功 */
  public boolean isSuccess() {
    // 优先判断 bizCode，回退判断 code
    if (bizCode != null) {
      return ErrorCode.SUCCESS.getBizCode().equals(bizCode);
    }
    return code != null && code == SUCCESS;
  }

  // ==================== Getter & Setter ====================

  /** @deprecated 使用 getBizCode() 替代 */
  @Deprecated
  public Integer getCode() {
    return code;
  }

  /** @deprecated 使用 setBizCode() 替代 */
  @Deprecated
  public void setCode(Integer code) {
    this.code = code;
  }

  public String getBizCode() {
    return bizCode;
  }

  public void setBizCode(String bizCode) {
    this.bizCode = bizCode;
  }

  public String getMsg() {
    return msg;
  }

  public void setMsg(String msg) {
    this.msg = msg;
  }

  public T getData() {
    return data;
  }

  public void setData(T data) {
    this.data = data;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }
}
