package com.workorder.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.workorder.entity.WorkOrder;

/**
 * 工单领域事件发布器（OPTIMIZATION 一 架构解耦）
 *
 * <p>集中发布工单生命周期事件，避免业务代码直接依赖 ApplicationEventPublisher。 事件发布采用 Spring 同步机制，监听器默认与发布方同事务执行。
 *
 * <p>使用方式（在 Service 层）： workOrderEventPublisher.publishSubmitted(workOrder, oldStatus, newStatus,
 * operatorId, operatorName, comment, processInstanceId, processDefinitionId);
 *
 * @author KLord
 */
@Component
public class WorkOrderEventPublisher {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderEventPublisher.class);

  @Autowired private ApplicationEventPublisher applicationEventPublisher;

  /** 发布工单提交事件（携带 processDefinitionId，供监听器创建 OrderProcessLink） */
  public void publishSubmitted(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String processDefinitionId) {
    WorkOrderSubmittedEvent event =
        new WorkOrderSubmittedEvent(
            workOrder,
            fromStatus,
            toStatus,
            operatorId,
            operatorName,
            comment,
            processInstanceId,
            processDefinitionId);
    publish(event);
  }

  /** 发布工单审批通过事件（携带 taskId/taskName，供监听器记录完整审计日志） */
  public void publishApproved(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String taskId,
      String taskName) {
    WorkOrderApprovedEvent event =
        new WorkOrderApprovedEvent(
            workOrder,
            fromStatus,
            toStatus,
            operatorId,
            operatorName,
            comment,
            processInstanceId,
            taskId,
            taskName);
    publish(event);
  }

  /** 发布工单驳回事件（携带 taskId/taskName，供监听器记录完整审计日志） */
  public void publishRejected(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String taskId,
      String taskName) {
    WorkOrderRejectedEvent event =
        new WorkOrderRejectedEvent(
            workOrder,
            fromStatus,
            toStatus,
            operatorId,
            operatorName,
            comment,
            processInstanceId,
            taskId,
            taskName);
    publish(event);
  }

  /** 发布工单终止事件 */
  public void publishTerminated(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    WorkOrderTerminatedEvent event =
        new WorkOrderTerminatedEvent(
            workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
    publish(event);
  }

  /** 发布工单撤回事件 */
  public void publishWithdrawn(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    WorkOrderWithdrawnEvent event =
        new WorkOrderWithdrawnEvent(
            workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
    publish(event);
  }

  /** 发布工单归档事件 */
  public void publishArchived(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    WorkOrderArchivedEvent event =
        new WorkOrderArchivedEvent(
            workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
    publish(event);
  }

  private void publish(WorkOrderDomainEvent event) {
    try {
      applicationEventPublisher.publishEvent(event);
      logger.debug(
          "[领域事件] 已发布 {} - 工单ID={}, 状态变更: {} -> {}",
          event.getEventType(),
          event.getWorkOrderId(),
          event.getFromStatus(),
          event.getToStatus());
    } catch (Exception e) {
      // 事件发布失败不应阻断主业务流程
      logger.error(
          "[领域事件] 发布失败 - 类型: {}, 工单ID: {}, 错误: {}",
          event.getEventType(),
          event.getWorkOrderId(),
          e.getMessage(),
          e);
    }
  }
}
