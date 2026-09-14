package com.workorder.common.enums;

/**
 * 工单状态枚举（OPTIMIZATION 四.4.2 枚举字段字典化）
 *
 * <p>数据库列：work_order.status SMALLINT 状态码：1-草稿，2-审批中，3-已通过，4-已驳回，5-已归档，6-已终止
 *
 * @author KLord
 */
public enum WorkOrderStatusEnum {

  /** 草稿 */
  DRAFT(1, "草稿"),
  /** 审批中 */
  PENDING(2, "审批中"),
  /** 已通过 */
  APPROVED(3, "已通过"),
  /** 已驳回 */
  REJECTED(4, "已驳回"),
  /** 已归档 */
  ARCHIVED(5, "已归档"),
  /** 已终止 */
  TERMINATED(6, "已终止");

  private final int code;
  private final String description;

  WorkOrderStatusEnum(int code, String description) {
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

  /** 根据 int code 获取枚举值 */
  public static WorkOrderStatusEnum fromCode(int code) {
    for (WorkOrderStatusEnum status : values()) {
      if (status.code == code) {
        return status;
      }
    }
    return null;
  }

  /** 兼容旧字符串入参（过渡期） */
  public static WorkOrderStatusEnum fromCode(String code) {
    if (code == null) return null;
    try {
      return fromCode(Integer.parseInt(code));
    } catch (NumberFormatException e) {
      // 兼容旧字符串值
      switch (code.toUpperCase()) {
        case "DRAFT":
          return DRAFT;
        case "PENDING":
        case "APPROVING":
          return PENDING;
        case "APPROVED":
          return APPROVED;
        case "REJECTED":
        case "RETURNED":
          return REJECTED;
        case "ARCHIVED":
          return ARCHIVED;
        case "TERMINATED":
          return TERMINATED;
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
