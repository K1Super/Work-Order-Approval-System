package com.workorder.common.event;



import com.workorder.entity.WorkOrder;

/**
 * 工单终止事件（OPTIMIZATION 一 架构解耦）
 *
 * <p>当管理员终止审批流程时发布。监听器可基于此事件： - 通知所有相关方流程已终止 - 记录审计日志 - 清理流程引擎残留数据
 *
 * @author KLord
 */
public class WorkOrderTerminatedEvent extends WorkOrderDomainEvent {

  /** 构造函数，初始化工单终止事件 */
  public WorkOrderTerminatedEvent(
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
    return "WORK_ORDER_TERMINATED";
  }
}
