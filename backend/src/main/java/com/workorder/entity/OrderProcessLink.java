package com.workorder.entity;

import java.io.Serializable;
import java.time.Instant;

/**
 * 工单-流程实例关联实体（OPTIMIZATION 一 架构解耦）
 *
 * <p>轻量级关联表，仅维护 work_order_id ↔ process_instance_id 的映射。 work_order 表不再承载任何 Flowable 运行时状态，
 * 流程引擎状态变更通过领域事件通知工单上下文更新业务状态。
 *
 * @author KLord
 */
public class OrderProcessLink implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键 */
  private Long id;

  /** 工单ID */
  private Long workOrderId;

  /** 流程实例ID（Flowable） */
  private String processInstanceId;

  /** 流程定义ID（Flowable） */
  private String processDefinitionId;

  /** 创建时间 */
  private Instant createTime;

  /** 更新时间 */
  private Instant updateTime;

  /** 逻辑删除标识：0-未删除 1-已删除 */
  private Integer isDeleted;

  /** 创建人ID */
  private Long createBy;

  /** 更新人ID */
  private Long updateBy;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getWorkOrderId() {
    return workOrderId;
  }

  public void setWorkOrderId(Long workOrderId) {
    this.workOrderId = workOrderId;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public String getProcessDefinitionId() {
    return processDefinitionId;
  }

  public void setProcessDefinitionId(String processDefinitionId) {
    this.processDefinitionId = processDefinitionId;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant createTime) {
    this.createTime = createTime;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant updateTime) {
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
