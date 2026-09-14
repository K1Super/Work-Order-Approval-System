package com.workorder.common.event;



import com.workorder.entity.WorkOrder;

/**
 * 工单驳回事件（OPTIMIZATION 一 架构解耦）
 *
 * <p>当审批人驳回工单时发布。监听器可基于此事件： - 通知申请人审批被驳回（含驳回原因） - 记录审计日志（包含 taskId、taskName） - 触发回退业务逻辑
 *
 * @author KLord
 */
public class WorkOrderRejectedEvent extends WorkOrderDomainEvent {

  /** 构造函数，初始化工单驳回事件 */
  public WorkOrderRejectedEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    super(workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  /** 构造函数，初始化工单驳回事件（含任务信息） */
  public WorkOrderRejectedEvent(
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
    return "WORK_ORDER_REJECTED";
  }
}
