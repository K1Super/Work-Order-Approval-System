package com.workorder.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * Password History Entity 密码历史记录实体 - 用于禁止复用历史密码
 *
 * @author KLord
 */
public class PasswordHistory implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key ID */
  private Long id;

  /** User ID (foreign key to sys_user) */
  private Long userId;

  /** Encoded password hash (BCrypt) */
  private String passwordHash;

  /** Password change timestamp */
  private Date changeTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §2.7.1 审计字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §2.7.1 审计字段） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段） */
  private Long updateBy;

  /** 构造函数，初始化空密码历史记录 */
  public PasswordHistory() {}

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

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public Date getChangeTime() {
    return changeTime;
  }

  public void setChangeTime(Date changeTime) {
    this.changeTime = changeTime;
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
