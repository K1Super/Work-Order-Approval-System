package com.workorder.common.event;



import com.workorder.entity.WorkOrder;

/**
 * 工单提交事件（OPTIMIZATION 一 架构解耦）
 *
 * <p>当工单提交并启动审批流程时发布。 监听器可基于此事件： - 创建 order_process_link 关联记录（携带 processInstanceId +
 * processDefinitionId） - 发送通知给审批人 - 推送消息队列等
 *
 * @author KLord
 */
public class WorkOrderSubmittedEvent extends WorkOrderDomainEvent {

  /** 构造函数，初始化工单提交事件 */
  public WorkOrderSubmittedEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    super(workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  /** 构造函数，初始化工单提交事件（含流程定义ID） */
  public WorkOrderSubmittedEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String processDefinitionId) {
    super(
        workOrder,
        fromStatus,
        toStatus,
        operatorId,
        operatorName,
        comment,
        processInstanceId,
        processDefinitionId,
        null,
        null);
  }

  @Override
  public String getEventType() {
    return "WORK_ORDER_SUBMITTED";
  }
}
