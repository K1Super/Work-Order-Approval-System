package com.workorder.security;


import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 自定义UserDetails实现 扩展标准UserDetails，添加业务字段
 *
 * <p>安全策略（阶段 1 修复 A-08 / 阶段 2 修复 H-03）： 新增 departmentId / orgLevel / roles / positionId 字段， 使
 * DataPermissionAspect 能执行部门级数据隔离与水平越权校验。
 *
 * @author KLord
 */
public class CustomUserDetails implements UserDetails {

  private static final long serialVersionUID = 1L;

  /** 用户ID */
  private Long userId;

  /** 用户名 */
  private String username;

  /** 密码 */
  private String password;

  /** 真实姓名 */
  private String realName;

  /** 状态：0-禁用 1-启用 */
  private Integer status;

  /** 部门ID（用于数据权限部门级过滤） */
  private Long departmentId;

  /** 组织层级：0-超管/安全审计 1-董事长/总经理/副总 2-总监 3-专员 4-普通员工 */
  private Integer orgLevel;

  /** 职位ID */
  private Long positionId;

  /** 角色代码列表（如 SUPER_ADMIN, HR_DIR） */
  private List<String> roles;

  /** 权限列表 */
  private Collection<? extends GrantedAuthority> authorities;

  /** 构造函数，初始化基本用户信息（不含数据权限字段） */
  public CustomUserDetails(
      Long userId,
      String username,
      String password,
      String realName,
      Integer status,
      Collection<? extends GrantedAuthority> authorities) {
    this.userId = userId;
    this.username = username;
    this.password = password;
    this.realName = realName;
    this.status = status;
    this.authorities = authorities;
    // 默认值，由 UserDetailsServiceImpl 填充
    this.departmentId = null;
    this.orgLevel = 4;
    this.positionId = null;
    this.roles = Collections.emptyList();
  }

  /** 完整构造函数（含数据权限字段） */
  public CustomUserDetails(
      Long userId,
      String username,
      String password,
      String realName,
      Integer status,
      Long departmentId,
      Integer orgLevel,
      Long positionId,
      List<String> roles,
      Collection<? extends GrantedAuthority> authorities) {
    this.userId = userId;
    this.username = username;
    this.password = password;
    this.realName = realName;
    this.status = status;
    this.departmentId = departmentId;
    this.orgLevel = orgLevel != null ? orgLevel : 4;
    this.positionId = positionId;
    this.roles = roles != null ? roles : Collections.emptyList();
    this.authorities = authorities;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities;
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return status == 1;
  }

  // Getter方法

  public Long getUserId() {
    return userId;
  }

  public String getRealName() {
    return realName;
  }

  public Long getDepartmentId() {
    return departmentId;
  }

  public Integer getOrgLevel() {
    return orgLevel;
  }

  public Long getPositionId() {
    return positionId;
  }

  public List<String> getRoles() {
    return roles;
  }

  /** 判断是否为超级管理员 */
  public boolean isSuperAdmin() {
    if (roles == null) return false;
    return roles.contains("SUPER_ADMIN");
  }

  /** 判断是否拥有指定角色代码 */
  public boolean hasRole(String roleCode) {
    if (roles == null) return false;
    return roles.contains(roleCode);
  }

  /** 判断是否拥有指定权限代码 */
  public boolean hasPermission(String permissionCode) {
    if (authorities == null) return false;
    return authorities.stream().anyMatch(auth -> permissionCode.equals(auth.getAuthority()));
  }

  public void setDepartmentId(Long departmentId) {
    this.departmentId = departmentId;
  }

  public void setOrgLevel(Integer orgLevel) {
    this.orgLevel = orgLevel;
  }

  public void setPositionId(Long positionId) {
    this.positionId = positionId;
  }

  public void setRoles(List<String> roles) {
    this.roles = roles;
  }
}
