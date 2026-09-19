package com.workorder.service.impl;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.WorkOrder;

/**
 * 工单状态机（OPTIMIZATION 二 数据一致性保障）。
 *
 * <p>职责：作为工单状态流转的唯一入口，集中承载： 1. 审批流程结束后的终态判定（纯决策，无副作用）； 2. 基于 version 字段的乐观锁更新原语； 3.
 * 状态码到可读描述的映射（用于日志）。
 *
 * <p>并发正确性说明：所有变更工单状态的写路径（提交/审批/撤回/终止/重新提交）必须经过 {@link #updateWithVersion(WorkOrder)}
 * 委托的 {@code WorkOrderMapper#updateWithVersion}，失败时由调用方根据各自业务抛出乐观锁异常。
 *
 * @author KLord
 */
@Component
public class WorkOrderStateMachine {

  @Autowired private WorkOrderMapper workOrderMapper;

  /**
   * 基于流程是否结束与审批结论，判定工单终态（W-01/W-02 联动修复）。
   *
   * <p>状态机规则：仅当流程实例结束后才落终态——{@code approved=true} 归 APPROVED，否则归 REJECTED； 中间节点通过时流程未结束，工单保持
   * PENDING（审批中），由下一节点继续审批。
   *
   * @param isProcessFinished 流程实例是否已结束
   * @param approved 审批结论（true-通过；false-驳回/退回）
   * @return 新的工单状态码
   */
  public Integer resolveApprovalFinalStatus(boolean isProcessFinished, boolean approved) {
    if (isProcessFinished) {
      return approved
          ? WorkOrderStatusEnum.APPROVED.getCode()
          : WorkOrderStatusEnum.REJECTED.getCode();
    }
    return WorkOrderStatusEnum.PENDING.getCode();
  }

  /**
   * 执行基于 version 乐观锁的工单更新原语。
   *
   * @param workOrder 携带待更新字段与当前 version 的工单实体
   * @return 受影响行数（1=更新成功；0=版本冲突）
   */
  public int updateWithVersion(WorkOrder workOrder) {
    return workOrderMapper.updateWithVersion(workOrder);
  }

  /**
   * 获取工单状态描述（用于日志）。
   *
   * @param statusCode 工单状态码
   * @return 可读描述，未知码返回 {@code UNKNOWN(码)}
   */
  public String resolveStatusDesc(Integer statusCode) {
    if (statusCode == null) return "UNKNOWN";
    WorkOrderStatusEnum statusEnum = WorkOrderStatusEnum.fromCode(statusCode);
    return statusEnum != null
        ? statusEnum.getDescription() + "(" + statusCode + ")"
        : "UNKNOWN(" + statusCode + ")";
  }
}