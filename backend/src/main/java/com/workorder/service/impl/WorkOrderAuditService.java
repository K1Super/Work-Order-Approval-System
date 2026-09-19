package com.workorder.service.impl;


import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.common.enums.ApprovalActionEnum;
import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.event.WorkOrderEventPublisher;
import com.workorder.dao.ApprovalLogMapper;
import com.workorder.entity.ApprovalLog;
import com.workorder.entity.WorkOrder;

/**
 * 工单审计服务。
 *
 * <p>职责：集中承载工单生命周期中的审计日志落库与领域事件发布。 绝大多数审计日志由领域事件监听器在事务提交后异步写入； 唯有 RESUBMIT（重新提交）因无对应事件类型，由本服务直接落库。
 *
 * @author KLord
 */
@Component
public class WorkOrderAuditService {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderAuditService.class);

  @Autowired private ApprovalLogMapper approvalLogMapper;

  @Autowired private WorkOrderEventPublisher workOrderEventPublisher;

  /**
   * 记录 RESUBMIT 审计日志（此处直接插入，因为事件类未涵盖 RESUBMIT）。
   *
   * <p>后续 submitWorkOrder 调用会自动触发 SubmittedEvent，记录 SUBMIT 审计日志。
   *
   * @param workOrder 工单实体
   * @param sanitizedComment 净化后的备注
   * @param applicantId 申请人 ID
   */
  public void recordResubmitLog(WorkOrder workOrder, String sanitizedComment, Long applicantId) {
    try {
      ApprovalLog log =
          buildApprovalLog(
              workOrder,
              ApprovalActionEnum.RESUBMIT,
              sanitizedComment,
              WorkOrderStatusEnum.REJECTED.getCode(),
              WorkOrderStatusEnum.DRAFT.getCode());
      log.setOperatorId(applicantId);
      log.setOperatorName(workOrder.getApplicantName());
      approvalLogMapper.insert(log);
    } catch (Exception e) {
      logger.warn("Failed to record resubmit log", e);
    }
  }

  /**
   * 发布工单提交事件。
   *
   * @param workOrder 工单实体
   * @param fromStatus 变更前状态
   * @param toStatus 变更后状态
   * @param operatorId 操作人 ID
   * @param operatorName 操作人姓名
   * @param comment 备注
   * @param processInstanceId 流程实例 ID
   * @param processDefinitionId 流程定义 ID
   */
  public void publishSubmitted(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId,
      String processDefinitionId) {
    workOrderEventPublisher.publishSubmitted(
        workOrder,
        fromStatus,
        toStatus,
        operatorId,
        operatorName,
        comment,
        processInstanceId,
        processDefinitionId);
  }

  /**
   * 发布工单审批通过事件。
   *
   * @param workOrder 工单实体
   * @param fromStatus 变更前状态
   * @param toStatus 变更后状态
   * @param operatorId 操作人 ID
   * @param operatorName 操作人姓名
   * @param comment 审批意见
   * @param processInstanceId 流程实例 ID
   * @param taskId 任务 ID
   * @param taskName 任务节点名称
   */
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
    workOrderEventPublisher.publishApproved(
        workOrder,
        fromStatus,
        toStatus,
        operatorId,
        operatorName,
        comment,
        processInstanceId,
        taskId,
        taskName);
  }

  /**
   * 发布工单驳回事件。
   *
   * @param workOrder 工单实体
   * @param fromStatus 变更前状态
   * @param toStatus 变更后状态
   * @param operatorId 操作人 ID
   * @param operatorName 操作人姓名
   * @param comment 审批意见
   * @param processInstanceId 流程实例 ID
   * @param taskId 任务 ID
   * @param taskName 任务节点名称
   */
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
    workOrderEventPublisher.publishRejected(
        workOrder,
        fromStatus,
        toStatus,
        operatorId,
        operatorName,
        comment,
        processInstanceId,
        taskId,
        taskName);
  }

  /**
   * 发布工单终止事件。
   *
   * @param workOrder 工单实体
   * @param fromStatus 变更前状态
   * @param toStatus 变更后状态
   * @param operatorId 操作人 ID
   * @param operatorName 操作人姓名
   * @param comment 备注
   * @param processInstanceId 流程实例 ID
   */
  public void publishTerminated(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    workOrderEventPublisher.publishTerminated(
        workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  /**
   * 发布工单撤回事件。
   *
   * @param workOrder 工单实体
   * @param fromStatus 变更前状态
   * @param toStatus 变更后状态
   * @param operatorId 操作人 ID
   * @param operatorName 操作人姓名
   * @param comment 备注
   * @param processInstanceId 流程实例 ID
   */
  public void publishWithdrawn(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    workOrderEventPublisher.publishWithdrawn(
        workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  /**
   * 发布工单归档事件。
   *
   * @param workOrder 工单实体
   * @param fromStatus 变更前状态
   * @param toStatus 变更后状态
   * @param operatorId 操作人 ID
   * @param operatorName 操作人姓名
   * @param comment 备注
   * @param processInstanceId 流程实例 ID
   */
  public void publishArchived(
      WorkOrder workOrder,
      Integer fromStatus,
      Integer toStatus,
      Long operatorId,
      String operatorName,
      String comment,
      String processInstanceId) {
    workOrderEventPublisher.publishArchived(
        workOrder, fromStatus, toStatus, operatorId, operatorName, comment, processInstanceId);
  }

  /** 构建审批日志对象（OPTIMIZATION：action 改为 ApprovalActionEnum，status 改为 Integer） */
  private ApprovalLog buildApprovalLog(
      WorkOrder workOrder,
      ApprovalActionEnum actionEnum,
      String comment,
      Integer oldStatus,
      Integer newStatus) {
    ApprovalLog log = new ApprovalLog();
    log.setWorkOrderId(workOrder.getId());
    log.setOrderNo(workOrder.getOrderNo());
    log.setAction(actionEnum.getNumericCode());
    log.setComment(comment);
    log.setBeforeStatus(oldStatus);
    log.setAfterStatus(newStatus);
    log.setCreateTime(Instant.now());
    return log;
  }
}