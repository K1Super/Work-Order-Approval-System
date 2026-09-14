package com.workorder.common.enums;

/**
 * 工单类型枚举（OPTIMIZATION 四.4.2 枚举字段字典化）
 *
 * <p>数据库列：work_order.order_type SMALLINT 类型码：1-请假，2-采购，3-报销，4-维修，5-补货，6-其他
 *
 * @author KLord
 */
public enum OrderTypeEnum {

  /** 请假 */
  LEAVE(1, "请假"),
  /** 采购 */
  PURCHASE(2, "采购"),
  /** 报销 */
  REIMBURSEMENT(3, "报销"),
  /** 维修 */
  REPAIR(4, "维修"),
  /** 补货 */
  SUPPLY(5, "补货"),
  /** 其他 */
  OTHER(6, "其他");

  private final int code;
  private final String description;

  OrderTypeEnum(int code, String description) {
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

  /** 根据数字类型码获取枚举值 */
  public static OrderTypeEnum fromCode(int code) {
    for (OrderTypeEnum type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    return null;
  }

  /** 根据字符串类型码或名称获取枚举值（兼容旧字符串入参） */
  public static OrderTypeEnum fromCode(String code) {
    if (code == null) return null;
    try {
      return fromCode(Integer.parseInt(code));
    } catch (NumberFormatException e) {
      switch (code.toLowerCase()) {
        case "leave":
          return LEAVE;
        case "purchase":
          return PURCHASE;
        case "reimbursement":
          return REIMBURSEMENT;
        case "repair":
          return REPAIR;
        case "supply":
          return SUPPLY;
        default:
          return OTHER;
      }
    }
  }

  /** 判断指定类型码是否合法 */
  public static boolean isValid(int code) {
    return fromCode(code) != null;
  }
}
