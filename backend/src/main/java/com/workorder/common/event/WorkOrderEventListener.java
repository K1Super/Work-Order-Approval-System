package com.workorder.common.event;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.workorder.common.enums.ApprovalActionEnum;
import com.workorder.config.MetricsConfig;
import com.workorder.dao.ApprovalLogMapper;
import com.workorder.entity.ApprovalLog;
import com.workorder.service.IOrderProcessLinkService;

/**
 * 工单领域事件监听器（OPTIMIZATION 一 架构解耦）
 *
 * <p>监听工单生命周期事件并触发相应副作用： 1. 处理 SubmittedEvent → 创建 OrderProcessLink 关联记录（架构解耦核心） 2. 异步记录审计日志到
 * approval_log 表（统一审计入口，包含 taskId/taskName） 3. 异步发送通知（邮件/IM/短信，留作后续扩展）
 *
 * <p>使用 @TransactionalEventListener(AFTER_COMMIT) 确保事件处理在事务提交后执行， 避免主业务回滚后审计日志却已写入的不一致问题。 使用 @Async
 * 确保不阻断主业务流程。
 *
 * <p>注意：若 OrderProcessLink 创建失败，主流程（work_order 状态更新）已提交， 关联记录缺失由 P4 对账任务定期修复（最终一致性）。
 *
 * @author KLord
 */
@Component
public class WorkOrderEventListener {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderEventListener.class);
  private static final Logger auditLogger = LoggerFactory.getLogger("WORK_ORDER_AUDIT_LOGGER");

  @Autowired private ApprovalLogMapper approvalLogMapper;

  @Autowired private IOrderProcessLinkService orderProcessLinkService;

  /** 安全告警指标（审计修复 P2-5：审计写库失败必须可采集可告警，而非仅记日志） */
  @Autowired private MetricsConfig metricsConfig;

  /** 工单提交事件处理 - W-13 修复：order_process_link 已由主事务同步持久化，此处不再重复创建 - 记录 SUBMIT 审计日志 - 通知申请人 */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleWorkOrderSubmitted(WorkOrderSubmittedEvent event) {
    // 1. 记录审计日志
    logAuditEvent(event, ApprovalActionEnum.SUBMIT);

    // 2. 通知申请人
    notifyApplicant(event, "您的工单已提交，等待审批");
  }

  /** 工单审批通过事件处理 - 记录 APPROVE 审计日志（含 taskId/taskName） - 通知申请人审批通过 */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleWorkOrderApproved(WorkOrderApprovedEvent event) {
    logAuditEvent(event, ApprovalActionEnum.APPROVE);
    notifyApplicant(event, "您的工单已审批通过");
    // TODO: 触发后续业务联动（如报销打款、采购下单）
  }

  /** 工单驳回事件处理 - 记录 REJECT 审计日志（含 taskId/taskName） - 通知申请人审批被驳回（含原因） */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleWorkOrderRejected(WorkOrderRejectedEvent event) {
    logAuditEvent(event, ApprovalActionEnum.REJECT);
    String message = "您的工单已被驳回";
    if (event.getComment() != null && !event.getComment().isEmpty()) {
      message += "，原因：" + event.getComment();
    }
    notifyApplicant(event, message);
  }

  /** 工单终止事件处理 - 逻辑删除 order_process_link 关联记录 - 记录 TERMINATE 审计日志 - 通知所有相关方流程已终止 */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleWorkOrderTerminated(WorkOrderTerminatedEvent event) {
    // 逻辑删除关联记录（终止流程时清理）
    if (event.getProcessInstanceId() != null) {
      orderProcessLinkService.removeLink(event.getWorkOrderId());
    }
    logAuditEvent(event, ApprovalActionEnum.TERMINATE);
    notifyApplicant(event, "您的工单已被管理员终止");
  }

  /**
   * 工单撤回事件处理 - 逻辑删除 order_process_link 关联记录 - 记录 TERMINATE 审计日志（撤回使用 TERMINATE 动作） - 通知当前审批人工单已撤回
   */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleWorkOrderWithdrawn(WorkOrderWithdrawnEvent event) {
    if (event.getProcessInstanceId() != null) {
      orderProcessLinkService.removeLink(event.getWorkOrderId());
    }
    logAuditEvent(event, ApprovalActionEnum.TERMINATE);
    notifyCurrentApprover(event, "工单已被申请人撤回");
  }

  /** 工单归档事件处理 - 记录 ARCHIVE 审计日志 */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleWorkOrderArchived(WorkOrderArchivedEvent event) {
    logAuditEvent(event, ApprovalActionEnum.ARCHIVE);
  }

  // ============================================================
  // 内部辅助方法
  // ============================================================

  /** 异步记录审计日志到 approval_log 表 包含 taskId、taskName（来自事件），确保审计日志完整性 */
  private void logAuditEvent(WorkOrderDomainEvent event, ApprovalActionEnum action) {
    try {
      ApprovalLog log = new ApprovalLog();
      log.setWorkOrderId(event.getWorkOrderId());
      log.setOrderNo(event.getOrderNo());
      log.setProcessInstanceId(event.getProcessInstanceId());
      log.setTaskId(event.getTaskId());
      log.setTaskName(event.getTaskName());
      log.setAction(action.getNumericCode());
      log.setComment(event.getComment());
      log.setBeforeStatus(event.getFromStatus());
      log.setAfterStatus(event.getToStatus());
      log.setOperatorId(event.getOperatorId());
      log.setOperatorName(event.getOperatorName());
      log.setCreateTime(Instant.now());
      approvalLogMapper.insert(log);

      auditLogger.info(
          "[AUDIT] 工单ID={}, 编号={}, 事件={}, 操作人={}({}), 状态: {} -> {}, 任务: {}({})",
          event.getWorkOrderId(),
          event.getOrderNo(),
          event.getEventType(),
          event.getOperatorName(),
          event.getOperatorId(),
          event.getFromStatus(),
          event.getToStatus(),
          event.getTaskName(),
          event.getTaskId());
    } catch (Exception e) {
      // 审计修复 P2-5：主事务已提交无法回滚（设计使然），但审计静默丢失不可接受 ——
      // 除 error 日志外记 Prometheus 告警指标（wos_audit_persistence_failures_total）供运维告警，
      // 极端情况由 P4 对账任务兜底。
      metricsConfig.recordAuditPersistenceFailure(event.getEventType());
      logger.error(
          "[AUDIT] 审计日志记录失败 - 工单ID: {}, 事件: {}, 错误: {}",
          event.getWorkOrderId(),
          event.getEventType(),
          e.getMessage(),
          e);
    }
  }

  /** 异步通知申请人（邮件/IM/短信，当前为日志占位） TODO: 接入实际的通知服务（如 EmailService, IMService） */
  private void notifyApplicant(WorkOrderDomainEvent event, String message) {
    try {
      // 当前仅记录日志，后续接入实际通知服务
      logger.info(
          "[NOTIFY] 通知申请人 - 用户ID={}, 工单={}, 消息={}",
          event.getApplicantId(),
          event.getOrderNo(),
          message);
    } catch (Exception e) {
      logger.warn("[NOTIFY] 通知发送失败 - 申请人ID: {}, 错误: {}", event.getApplicantId(), e.getMessage());
    }
  }

  /** 异步通知当前审批人（撤回场景） */
  private void notifyCurrentApprover(WorkOrderDomainEvent event, String message) {
    try {
      logger.info("[NOTIFY] 通知审批人 - 工单={}, 消息={}", event.getOrderNo(), message);
    } catch (Exception e) {
      logger.warn("[NOTIFY] 通知发送失败 - 工单: {}, 错误: {}", event.getOrderNo(), e.getMessage());
    }
  }
}
