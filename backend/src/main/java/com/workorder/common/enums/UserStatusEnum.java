package com.workorder.common.enums;

/**
 * 用户状态枚举（规范 §2.4.1 禁止魔法数字）
 *
 * <p>替代代码中硬编码的 0、1 状态数字，统一枚举管理。
 *
 * @author KLord
 */
public enum UserStatusEnum {

  /** 禁用 */
  DISABLED(0, "禁用"),
  /** 启用 */
  ACTIVE(1, "启用");

  private final int code;
  private final String description;

  UserStatusEnum(int code, String description) {
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
  public static UserStatusEnum fromCode(Integer code) {
    if (code == null) {
      return null;
    }
    for (UserStatusEnum status : values()) {
      if (status.code == code) {
        return status;
      }
    }
    return null;
  }

  /** 判断是否为启用状态 */
  public static boolean isActive(Integer code) {
    return code != null && code == ACTIVE.code;
  }
}
