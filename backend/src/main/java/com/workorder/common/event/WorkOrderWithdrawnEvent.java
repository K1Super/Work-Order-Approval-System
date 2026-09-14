package com.workorder.common.event;



import com.workorder.entity.WorkOrder;

/**
 * 工单撤回事件（OPTIMIZATION 一 架构解耦）
 *
 * <p>当申请人主动撤回工单时发布。监听器可基于此事件： - 通知当前审批人工单已撤回 - 记录审计日志 - 清理流程引擎任务
 *
 * @author KLord
 */
public class WorkOrderWithdrawnEvent extends WorkOrderDomainEvent {

  /** 构造函数，初始化工单撤回事件 */
  public WorkOrderWithdrawnEvent(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    super(workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  @Override
  public String getEventType() {
    return "WORK_ORDER_WITHDRAWN";
  }
}
