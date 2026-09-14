package com.workorder.common.event;



import com.workorder.entity.WorkOrder;

/**
 * 工单归档事件（OPTIMIZATION 一 架构解耦）
 *
 * <p>当工单归档时发布。监听器可基于此事件： - 触发数据归档存储 - 记录审计日志 - 释放相关资源
 *
 * @author KLord
 */
public class WorkOrderArchivedEvent extends WorkOrderDomainEvent {

  /** 构造函数，初始化工单归档事件 */
  public WorkOrderArchivedEvent(
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
    return "WORK_ORDER_ARCHIVED";
  }
}
