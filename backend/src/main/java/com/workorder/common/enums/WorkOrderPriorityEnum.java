package com.workorder.common.enums;

/**
 * 工单优先级枚举（规范 §2.4.1 禁止魔法数字）
 *
 * <p>替代代码中硬编码的 1、2、3、4 优先级数字，统一枚举管理。
 *
 * @author KLord
 */
public enum WorkOrderPriorityEnum {

  /** 低优先级 */
  LOW(1, "低"),
  /** 中优先级 */
  MEDIUM(2, "中"),
  /** 高优先级 */
  HIGH(3, "高"),
  /** 紧急 */
  URGENT(4, "紧急");

  private final int code;
  private final String description;

  WorkOrderPriorityEnum(int code, String description) {
    this.code = code;
    this.description = description;
  }

  public int getCode() {
    return code;
  }

  public String getDescription() {
    return description;
  }

  /** 根据 code 获取枚举值 */
  public static WorkOrderPriorityEnum fromCode(Integer code) {
    if (code == null) {
      return null;
    }
    for (WorkOrderPriorityEnum priority : values()) {
      if (priority.code == code) {
        return priority;
      }
    }
    return null;
  }

  /** 判断 code 是否有效 */
  public static boolean isValid(Integer code) {
    return fromCode(code) != null;
  }
}
