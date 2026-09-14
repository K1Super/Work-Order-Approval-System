package com.workorder.common.enums;

/**
 * 审批节点状态枚举（OPTIMIZATION 四.4.2 枚举字段字典化）
 *
 * <p>数据库列：approval_log.node_status SMALLINT 节点状态码：1-待处理，2-已完成，3-已驳回
 *
 * @author KLord
 */
public enum ApprovalNodeStatusEnum {

  /** 待处理 */
  PENDING(1, "待处理"),
  /** 已完成 */
  COMPLETED(2, "已完成"),
  /** 已驳回 */
  REJECTED(3, "已驳回");

  private final int code;
  private final String description;

  ApprovalNodeStatusEnum(int code, String description) {
    this.code = code;
    this.description = description;
  }

  /** 返回 Integer 类型枚举码（用于 .equals() 比较与 MyBatis 映射） */
  public Integer getCode() {
    return code;
  }

  public String getDescription() {
    return description;
  }

  /** 根据数字状态码获取枚举值 */
  public static ApprovalNodeStatusEnum fromCode(int code) {
    for (ApprovalNodeStatusEnum status : values()) {
      if (status.code == code) {
        return status;
      }
    }
    return null;
  }

  /** 根据字符串状态码或名称获取枚举值（兼容旧字符串入参） */
  public static ApprovalNodeStatusEnum fromCode(String code) {
    if (code == null) return null;
    try {
      return fromCode(Integer.parseInt(code));
    } catch (NumberFormatException e) {
      switch (code.toUpperCase()) {
        case "PENDING":
          return PENDING;
        case "COMPLETED":
          return COMPLETED;
        case "REJECTED":
          return REJECTED;
        default:
          return null;
      }
    }
  }

  /** 判断指定状态码是否合法 */
  public static boolean isValid(int code) {
    return fromCode(code) != null;
  }
}
