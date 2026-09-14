package com.workorder.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 离职员工实体类 存储已离职员工的完整信息，用于历史记录和审计 */
public class ResignedEmployee {

  /** 主键ID */
  private Long id;

  /** 原用户ID（关联sys_user表） */
  private Long userId;

  /** 用户名 */
  private String username;

  /** 真实姓名 */
  private String realName;

  /** 邮箱 */
  private String email;

  /** 手机号 */
  private String phone;

  /** 部门名称 */
  private String department;

  /** 部门ID */
  private Long departmentId;

  /** 职位ID */
  private Long positionId;

  /** 职位名称 */
  private String positionName;

  /** 组织层级 */
  private Integer orgLevel;

  /** 入职日期 */
  private LocalDate hireDate;

  /** 离职日期 */
  private LocalDate resignDate;

  /** 离职类型：1-主动辞职 2-被动辞退 3-合同到期 4-退休 5-其他 */
  private Integer resignType;

  /** 离职原因 */
  private String resignReason;

  /** 离职时备注 */
  private String remark;

  /** 操作人ID */
  private Long operatorId;

  /** 操作人姓名 */
  private String operatorName;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §2.7.1 审计字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §2.7.1 审计字段） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段） */
  private Long updateBy;

  // Getter 和 Setter 方法
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getRealName() {
    return realName;
  }

  public void setRealName(String realName) {
    this.realName = realName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }

  public Long getDepartmentId() {
    return departmentId;
  }

  public void setDepartmentId(Long departmentId) {
    this.departmentId = departmentId;
  }

  public Long getPositionId() {
    return positionId;
  }

  public void setPositionId(Long positionId) {
    this.positionId = positionId;
  }

  public String getPositionName() {
    return positionName;
  }

  public void setPositionName(String positionName) {
    this.positionName = positionName;
  }

  public Integer getOrgLevel() {
    return orgLevel;
  }

  public void setOrgLevel(Integer orgLevel) {
    this.orgLevel = orgLevel;
  }

  public LocalDate getHireDate() {
    return hireDate;
  }

  public void setHireDate(LocalDate hireDate) {
    this.hireDate = hireDate;
  }

  public LocalDate getResignDate() {
    return resignDate;
  }

  public void setResignDate(LocalDate resignDate) {
    this.resignDate = resignDate;
  }

  public Integer getResignType() {
    return resignType;
  }

  public void setResignType(Integer resignType) {
    this.resignType = resignType;
  }

  public String getResignReason() {
    return resignReason;
  }

  public void setResignReason(String resignReason) {
    this.resignReason = resignReason;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public Long getOperatorId() {
    return operatorId;
  }

  public void setOperatorId(Long operatorId) {
    this.operatorId = operatorId;
  }

  public String getOperatorName() {
    return operatorName;
  }

  public void setOperatorName(String operatorName) {
    this.operatorName = operatorName;
  }

  public LocalDateTime getCreateTime() {
    return createTime;
  }

  public void setCreateTime(LocalDateTime createTime) {
    this.createTime = createTime;
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
