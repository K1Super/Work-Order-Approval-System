package com.workorder.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统设置实体类
 *
 * @author KLord
 */
public class SystemSetting implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键ID */
  private Long id;

  /** 设置分组：basic-基本设置 workorder-工单设置 notification-通知设置 security-安全设置 */
  private String groupKey;

  /** 设置键 */
  private String settingKey;

  /** 设置值 */
  private String settingValue;

  /** 设置描述 */
  private String description;

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

  /** 构造函数，初始化空系统设置 */
  public SystemSetting() {}

  /** 构造函数，指定设置分组、键、值和描述 */
  public SystemSetting(
      String groupKey, String settingKey, String settingValue, String description) {
    this.groupKey = groupKey;
    this.settingKey = settingKey;
    this.settingValue = settingValue;
    this.description = description;
  }

  // Getter & Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public String getSettingKey() {
    return settingKey;
  }

  public void setSettingKey(String settingKey) {
    this.settingKey = settingKey;
  }

  public String getSettingValue() {
    return settingValue;
  }

  public void setSettingValue(String settingValue) {
    this.settingValue = settingValue;
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
