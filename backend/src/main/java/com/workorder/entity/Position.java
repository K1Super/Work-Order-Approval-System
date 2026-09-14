package com.workorder.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 职位实体类（规范 §3 数据库设计规范 — 审计字段完整性）
 *
 * <p>对应数据库表 sys_position，V9 迁移脚本已补充 is_deleted/create_by/update_by 字段。
 *
 * @author KLord
 */
public class Position implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 职位ID */
  private Long id;

  /** 职位名称 */
  private String positionName;

  /** 职位编码（唯一） */
  private String positionCode;

  /** 所属部门ID */
  private Long deptId;

  /** 组织层级 */
  private Integer orgLevel;

  /** 排序号 */
  private Integer sortOrder;

  /** 状态：0-禁用 1-启用 */
  private Integer status;

  /** 描述 */
  private String description;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §3 必备基础字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §3 审计字段） */
  private Long createBy;

  /** 更新人ID（规范 §3 审计字段） */
  private Long updateBy;

  // Getter & Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getPositionName() {
    return positionName;
  }

  public void setPositionName(String positionName) {
    this.positionName = positionName;
  }

  public String getPositionCode() {
    return positionCode;
  }

  public void setPositionCode(String positionCode) {
    this.positionCode = positionCode;
  }

  public Long getDeptId() {
    return deptId;
  }

  public void setDeptId(Long deptId) {
    this.deptId = deptId;
  }

  public Integer getOrgLevel() {
    return orgLevel;
  }

  public void setOrgLevel(Integer orgLevel) {
    this.orgLevel = orgLevel;
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

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
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
