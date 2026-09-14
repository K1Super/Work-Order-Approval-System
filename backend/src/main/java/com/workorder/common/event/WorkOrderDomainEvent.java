package com.workorder.common.event;


import java.time.Instant;

import org.springframework.transaction.event.TransactionalEventListener;

import com.workorder.entity.WorkOrder;

/**
 * 工单领域事件基类（OPTIMIZATION 一 架构解耦 - 领域事件驱动）
 *
 * <p>流程引擎状态变更通过领域事件通知工单上下文更新业务状态， 而不是直接修改工单表的冗余列。事件采用同步发布机制
 * （@TransactionalEventListener），确保事件处理与业务事务一致。
 *
 * @author KLord
 */
public abstract class WorkOrderDomainEvent {

  private final Long workOrderId;
  private final String orderNo;
  private final Long applicantId;
  private final String applicantName;
  private final Integer fromStatus;
  private final Integer toStatus;
  private final Long operatorId;
  private final String operatorName;
  private final String comment;
  private final Instant occurredAt;
  private final String processInstanceId;
  /** 流程定义ID（提交事件用于创建 OrderProcessLink） */
  private final String processDefinitionId;
  /** 任务ID（审批事件用于审计日志） */
  private final String taskId;
  /** 任务节点名称（审批事件用于审计日志） */
  private final String taskName;

  protected WorkOrderDomainEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    this(
        workOrder,
        fromStatus,
        toStatus,
        operatorId,
        operatorName,
        comment,
        processInstanceId,
        null,
        null,
        null);
  }

  protected WorkOrderDomainEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String processDefinitionId,
      String taskId,
      String taskName) {
    this.workOrderId = workOrder.getId();
    this.orderNo = workOrder.getOrderNo();
    this.applicantId = workOrder.getApplicantId();
    this.applicantName = workOrder.getApplicantName();
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.operatorId = operatorId;
    this.operatorName = operatorName;
    this.comment = comment;
    this.processInstanceId = processInstanceId;
    this.processDefinitionId = processDefinitionId;
    this.taskId = taskId;
    this.taskName = taskName;
    this.occurredAt = Instant.now();
  }

  public Long getWorkOrderId() {
    return workOrderId;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public Long getApplicantId() {
    return applicantId;
  }

  public String getApplicantName() {
    return applicantName;
  }

  public Integer getFromStatus() {
    return fromStatus;
  }

  public Integer getToStatus() {
    return toStatus;
  }

  public Long getOperatorId() {
    return operatorId;
  }

  public String getOperatorName() {
    return operatorName;
  }

  public String getComment() {
    return comment;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public String getProcessDefinitionId() {
    return processDefinitionId;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getTaskName() {
    return taskName;
  }

  /** 事件类型描述（用于日志/审计） */
  public abstract String getEventType();
}
