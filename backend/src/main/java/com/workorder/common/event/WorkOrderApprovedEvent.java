package com.workorder.common.event;



import com.workorder.entity.WorkOrder;

/**
 * 工单审批通过事件（OPTIMIZATION 一 架构解耦）
 *
 * <p>当审批人通过工单时发布。监听器可基于此事件： - 通知申请人审批通过 - 触发后续业务（如报销打款、采购下单等） - 记录审计日志（包含 taskId、taskName）
 *
 * @author KLord
 */
public class WorkOrderApprovedEvent extends WorkOrderDomainEvent {

  /** 构造函数，初始化工单审批通过事件 */
  public WorkOrderApprovedEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    super(workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  /** 构造函数，初始化工单审批通过事件（含任务信息） */
  public WorkOrderApprovedEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String taskId,
      String taskName) {
    super(
        workOrder,
        fromStatus,
        toStatus,
        operatorId,
        operatorName,
        comment,
        processInstanceId,
        null,
        taskId,
        taskName);
  }

  @Override
  public String getEventType() {
    return "WORK_ORDER_APPROVED";
  }
}
