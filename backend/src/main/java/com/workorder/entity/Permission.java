package com.workorder.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限实体类
 *
 * @author KLord
 */
public class Permission implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 权限ID */
  private Long id;

  /** 权限名称 */
  private String permissionName;

  /** 权限编码 */
  private String permissionCode;

  /** 类型：1-菜单 2-按钮 3-API接口 */
  private Integer permissionType;

  /** 父权限ID */
  private Long parentId;

  /** 菜单路径/API路径 */
  private String url;

  /** 请求方法 */
  private String method;

  /** 图标 */
  private String icon;

  /** 排序 */
  private Integer sortOrder;

  /** 状态：0-禁用 1-启用 */
  private Integer status;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §2.7.1 审计字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §2.7.1 审计字段） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段） */
  private Long updateBy;

  // Getter & Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getPermissionName() {
    return permissionName;
  }

  public void setPermissionName(String permissionName) {
    this.permissionName = permissionName;
  }

  public String getPermissionCode() {
    return permissionCode;
  }

  public void setPermissionCode(String permissionCode) {
    this.permissionCode = permissionCode;
  }

  public Integer getPermissionType() {
    return permissionType;
  }

  public void setPermissionType(Integer permissionType) {
    this.permissionType = permissionType;
  }

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getIcon() {
    return icon;
  }

  public void setIcon(String icon) {
    this.icon = icon;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer status) {
    this.status = status;
  }

  public LocalDateTime getCreateTime() {
    return createTime;
  }

  public void setCreateTime(LocalDateTime createTime) {
    this.createTime = createTime;
  }

  public LocalDateTime getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(LocalDateTime updateTime) {
    this.updateTime = updateTime;
  }

  public Integer getIsDeleted() {
    return isDeleted;
  }

  public void setIsDeleted(Integer isDeleted) {
    this.isDeleted = isDeleted;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long createBy) {
    this.createBy = createBy;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long updateBy) {
    this.updateBy = updateBy;
  }
}
