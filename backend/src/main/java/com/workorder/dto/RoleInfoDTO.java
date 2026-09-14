package com.workorder.dto;

import java.util.List;

/**
 * 角色信息DTO 用于查询用户角色列表的返回对象
 *
 * @author KLord
 */
public class RoleInfoDTO {
  private Long id;
  private String roleName;
  private String roleCode;
  private List<String> permissions;

  /** 构造函数，初始化空角色信息 */
  public RoleInfoDTO() {}

  /** 构造函数，指定角色ID、名称和编码 */
  public RoleInfoDTO(Long id, String roleName, String roleCode) {
    this.id = id;
    this.roleName = roleName;
    this.roleCode = roleCode;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getRoleName() {
    return roleName;
  }

  public void setRoleName(String roleName) {
    this.roleName = roleName;
  }

  public String getRoleCode() {
    return roleCode;
  }

  public void setRoleCode(String roleCode) {
    this.roleCode = roleCode;
  }

  public List<String> getPermissions() {
    return permissions;
  }

  public void setPermissions(List<String> permissions) {
    this.permissions = permissions;
  }
}
