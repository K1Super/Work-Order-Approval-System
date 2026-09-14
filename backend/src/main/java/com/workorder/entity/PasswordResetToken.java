package com.workorder.entity;

import java.util.Date;

/**
 * 密码重置令牌实体
 *
 * <p>用于企业级密码重置流程： 1. 管理员触发重置 → 生成一次性 token（明文返回给调用方，哈希存库） 2. 用户通过重置链接携带 token → 后端验证 token 有效性 3.
 * 用户输入新密码 → 后端消费 token 并设置新密码
 *
 * <p>安全特性： - token 为 64 字符随机十六进制串（256 位熵） - 数据库仅存 SHA-256 哈希，防数据库泄露时被重放 - 15 分钟过期 - 一次性使用（used 标志）
 *
 * @author KLord
 */
public class PasswordResetToken {

  private Long id;
  private String tokenHash;
  private Long userId;
  private Long operatorId;
  private Date expiryTime;
  private Boolean used;
  private Date usedTime;
  private Date createTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §2.7.1 审计字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §2.7.1 审计字段） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段） */
  private Long updateBy;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getOperatorId() {
    return operatorId;
  }

  public void setOperatorId(Long operatorId) {
    this.operatorId = operatorId;
  }

  public Date getExpiryTime() {
    return expiryTime;
  }

  public void setExpiryTime(Date expiryTime) {
    this.expiryTime = expiryTime;
  }

  public Boolean getUsed() {
    return used;
  }

  public void setUsed(Boolean used) {
    this.used = used;
  }

  public Date getUsedTime() {
    return usedTime;
  }

  public void setUsedTime(Date usedTime) {
    this.usedTime = usedTime;
  }

  public Date getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Date createTime) {
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
