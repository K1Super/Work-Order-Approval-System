package com.workorder.common.exception;

/**
 * 错误级别枚举（规范 §2 错误码规范 — 每个错误码必须绑定错误级别）
 *
 * @author KLord
 */
public enum ErrorLevel {
  /** 错误级 — 业务失败、系统异常、流程阻断 */
  ERROR,
  /** 警告级 — 非中断性异常、降级处理、参数兼容 */
  WARN,
  /** 信息级 — 正常业务操作（如 SUCCESS） */
  INFO
}
