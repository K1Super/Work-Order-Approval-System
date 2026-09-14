package com.workorder.task;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.dao.ApprovalLogMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.WorkOrder;

/**
 * 数据完整性对账定时任务（OPTIMIZATION 二 数据一致性保障）
 *
 * <p>每 30 分钟扫描一次 work_order.applicant_id / approval_log.operator_id 等引用字段， 与 sys_user
 * 比对，发现指向已逻辑删除用户的记录时： - 自动记录异常并追加 remark "[系统提示：申请人已离职]"
 *
 * <p>与 CDC 机制互补： - CDC（SysUserDeleteListener）准实时捕获软删除事件 - 本任务作为兜底，处理 CDC 漏接的场景（如监听器宕机期间的事件）
 *
 * @author KLord
 */
@Component
public class DataIntegrityReconciliationTask {

  private static final Logger logger =
      LoggerFactory.getLogger(DataIntegrityReconciliationTask.class);
  private static final Logger reconciliationLogger =
      LoggerFactory.getLogger("DATA_RECONCILIATION_LOGGER");

  @Autowired private WorkOrderMapper workOrderMapper;

  @Autowired private ApprovalLogMapper approvalLogMapper;

  /**
   * 每 30 分钟执行一次数据完整性对账 fixedRate = 30 * 60 * 1000 = 1800000 ms initialDelay = 60000（启动后 1
   * 分钟首次执行，避免与启动冲突）
   */
  @Scheduled(fixedRate = 1800000, initialDelay = 60000)
  @Transactional(rollbackFor = Exception.class)
  public void reconcileDataIntegrity() {
    Instant startTime = Instant.now();
    reconciliationLogger.info("[对账任务] 开始执行 - 启动时间: {}", startTime);

    try {
      // 1. 扫描 work_order.applicant_id 悬空引用
      int workOrderFixed = scanOrphanWorkOrderApplicants();

      // 2. 扫描 approval_log.operator_id 悬空引用（仅记录日志，不修改审计轨迹）
      int orphanApprovalLogs = scanOrphanApprovalLogOperators();

      Instant endTime = Instant.now();
      long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
      reconciliationLogger.info(
          "[对账任务] 执行完成 - 耗时: {}ms, 工单修复: {}, 审计日志悬空: {}",
          durationMs,
          workOrderFixed,
          orphanApprovalLogs);
    } catch (Exception e) {
      reconciliationLogger.error("[对账任务] 执行失败: {}", e.getMessage(), e);
      logger.error("[对账任务] 执行失败", e);
    }
  }

  /**
   * 扫描 work_order.applicant_id 指向已逻辑删除用户的记录 仅处理 PENDING 状态工单，追加 remark 标记
   *
   * @return 修复的工单数量
   */
  private int scanOrphanWorkOrderApplicants() {
    try {
      List<WorkOrder> orphanOrders = workOrderMapper.selectOrphanApplicantWorkOrders();
      if (orphanOrders == null || orphanOrders.isEmpty()) {
        reconciliationLogger.info("[对账任务] 无 applicant_id 悬空引用工单");
        return 0;
      }

      reconciliationLogger.warn("[对账任务] 发现 {} 条 applicant_id 悬空引用工单", orphanOrders.size());
      int fixed = 0;
      for (WorkOrder wo : orphanOrders) {
        try {
          // 检查 remark 是否已包含标记（避免重复追加）
          String existingRemark = wo.getRemark();
          if (existingRemark != null && existingRemark.contains("[系统提示：申请人已离职")) {
            reconciliationLogger.debug("[对账任务] 工单 {} 已有离职标记，跳过", wo.getOrderNo());
            continue;
          }

          String remarkAppend =
              String.format(
                  "\n[系统提示：申请人已离职（用户ID=%d, 申请时姓名=%s），请重新指派或终止流程]",
                  wo.getApplicantId(), wo.getApplicantName());

          int rows = workOrderMapper.appendRemarkForOrphanWorkOrder(wo.getId(), remarkAppend);
          if (rows > 0) {
            fixed++;
            reconciliationLogger.warn(
                "[对账任务] 已标记工单 - 编号={}, 标题={}, 申请人={}",
                wo.getOrderNo(),
                wo.getTitle(),
                wo.getApplicantName());
          }
        } catch (Exception e) {
          reconciliationLogger.error(
              "[对账任务] 标记工单失败 - workOrderId={}, 错误: {}", wo.getId(), e.getMessage(), e);
        }
      }
      return fixed;
    } catch (Exception e) {
      reconciliationLogger.error("[对账任务] 扫描 applicant_id 悬空引用失败: {}", e.getMessage(), e);
      return 0;
    }
  }

  /**
   * 扫描 approval_log.operator_id 指向已逻辑删除用户的记录 审计日志为纯追加写，仅记录异常不修改
   *
   * @return 悬空引用数量
   */
  private int scanOrphanApprovalLogOperators() {
    try {
      // 查询近 30 天的审批日志中 operator_id 指向已删除用户的记录
      // 审计日志不修改，仅记录告警
      Instant since = Instant.now().minusSeconds(30 * 24 * 60 * 60L);
      // approval_logMapper 暂未提供 selectOrphanOperatorLogs 方法
      // 此处仅记录日志，后续可扩展 Mapper 方法
      reconciliationLogger.info(
          "[对账任务] approval_log.operator_id 悬空扫描（待扩展 Mapper 方法，since={}）", since);
      return 0;
    } catch (Exception e) {
      reconciliationLogger.error("[对账任务] 扫描 operator_id 悬空引用失败: {}", e.getMessage(), e);
      return 0;
    }
  }
}
