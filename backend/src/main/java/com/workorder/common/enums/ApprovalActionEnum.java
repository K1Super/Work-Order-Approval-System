package com.workorder.common.enums;

/**
 * 审批操作类型枚举（规范 §8 安全开发规范 — 枚举字段字典化）
 *
 * <p>使用字符串编码（String code），便于 API 层传参与可读性：
 *
 * <ul>
 *   <li>SUBMIT — 提交
 *   <li>APPROVE — 通过
 *   <li>REJECT — 驳回
 *   <li>RETURN — 退回
 *   <li>ARCHIVE — 归档
 *   <li>TERMINATE — 终止
 *   <li>RESUBMIT — 重新提交
 *   <li>TRANSFER — 转办
 * </ul>
 *
 * <p>数值编码（numericCode）对应数据库 approval_log.action SMALLINT 列： 1-提交 2-通过 3-驳回 4-退回 5-归档 6-终止 7-重新提交
 * 8-转办
 *
 * @author KLord
 */
public enum ApprovalActionEnum {

  /** 提交 */
  SUBMIT("SUBMIT", "提交", 1),

  /** 通过 */
  APPROVE("APPROVE", "通过", 2),

  /** 驳回 */
  REJECT("REJECT", "驳回", 3),

  /** 退回 */
  RETURN("RETURN", "退回", 4),

  /** 归档 */
  ARCHIVE("ARCHIVE", "归档", 5),

  /** 终止 */
  TERMINATE("TERMINATE", "终止", 6),

  /** 重新提交 */
  RESUBMIT("RESUBMIT", "重新提交", 7),

  /** 转办 */
  TRANSFER("TRANSFER", "转办", 8);

  /** 字符串编码 */
  private final String code;

  /** 中文描述 */
  private final String description;

  /** 数值编码（兼容数据库 SMALLINT 列） */
  private final int numericCode;

  ApprovalActionEnum(String code, String description, int numericCode) {
    this.code = code;
    this.description = description;
    this.numericCode = numericCode;
  }

  /** 返回字符串编码（用于 API 层传参与 JSON 序列化） */
  public String getCode() {
    return code;
  }

  /** 返回中文描述 */
  public String getDescription() {
    return description;
  }

  /** 返回数值编码（用于数据库 SMALLINT 列） */
  public int getNumericCode() {
    return numericCode;
  }

  /**
   * 根据 code 查找枚举值
   *
   * @param code 字符串编码（如 "APPROVE"）
   * @return 对应的枚举值，未找到返回 null
   */
  public static ApprovalActionEnum fromCode(String code) {
    if (code == null || code.isEmpty()) {
      return null;
    }
    for (ApprovalActionEnum action : values()) {
      if (action.code.equals(code)) {
        return action;
      }
    }
    // 兼容旧数值码字符串
    try {
      int numericCode = Integer.parseInt(code);
      return fromNumericCode(numericCode);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 根据数值编码查找枚举值
   *
   * @param numericCode 数值编码（如 2）
   * @return 对应的枚举值，未找到返回 null
   */
  public static ApprovalActionEnum fromNumericCode(int numericCode) {
    for (ApprovalActionEnum action : values()) {
      if (action.numericCode == numericCode) {
        return action;
      }
    }
    return null;
  }

  /** 判断 code 是否有效 */
  public static boolean isValid(String code) {
    return fromCode(code) != null;
  }
}
