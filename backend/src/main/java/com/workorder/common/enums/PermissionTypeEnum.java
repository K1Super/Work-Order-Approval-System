package com.workorder.common.enums;

/**
 * 权限类型枚举（OPTIMIZATION 四.4.2 枚举字段字典化）
 *
 * <p>数据库列：sys_permission.permission_type SMALLINT 类型码：1-菜单，2-按钮，3-API
 *
 * @author KLord
 */
public enum PermissionTypeEnum {

  /** 菜单 */
  MENU(1, "菜单"),
  /** 按钮 */
  BUTTON(2, "按钮"),
  /** API */
  API(3, "API");

  private final int code;
  private final String description;

  PermissionTypeEnum(int code, String description) {
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
  public static PermissionTypeEnum fromCode(int code) {
    for (PermissionTypeEnum type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    return null;
  }

  /** 根据字符串类型码或名称获取枚举值（兼容旧字符串入参） */
  public static PermissionTypeEnum fromCode(String code) {
    if (code == null) return null;
    try {
      return fromCode(Integer.parseInt(code));
    } catch (NumberFormatException e) {
      switch (code.toLowerCase()) {
        case "menu":
          return MENU;
        case "button":
          return BUTTON;
        case "api":
          return API;
        default:
          return null;
      }
    }
  }

  /** 判断指定类型码是否合法 */
  public static boolean isValid(int code) {
    return fromCode(code) != null;
  }
}
