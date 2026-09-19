package com.workorder.common.event;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.WorkOrder;

/**
 * sys_user 软删除事件处理器（OPTIMIZATION 二 CDC 模拟）
 *
 * <p>监听 SysUserLogicallyDeletedEvent，标记该用户所有 PENDING 工单 remark， 实现"准实时补偿"，而不仅依赖定时对账任务。
 *
 * <p>处理逻辑： - 查询该用户作为 applicant 的所有 PENDING 工单 - 在 remark 中追加 "[系统提示：申请人已离职，请重新指派或终止流程]" -
 * 不修改工单状态，由管理员后续处理
 *
 * @author KLord
 */
@Component
public class SysUserDeleteEventHandler {

  private static final Logger logger = LoggerFactory.getLogger(SysUserDeleteEventHandler.class);

  @Autowired private WorkOrderMapper workOrderMapper;

  /** 异步处理 sys_user 软删除事件 标记该用户作为申请人的所有 PENDING 工单 */
  @Async
  @EventListener
  @Transactional(rollbackFor = Exception.class)
  public void handleSysUserDeleted(SysUserLogicallyDeletedEvent event) {
    Long userId = event.getUserId();
    String username = event.getUsername();
    logger.info("[CDC] 处理 sys_user 软删除事件 - userId={}, username={}", userId, username);

    try {
      // 查询该用户作为申请人的所有 PENDING 工单
      List<WorkOrder> pendingOrders =
          workOrderMapper.selectPage(
              userId,
              WorkOrderStatusEnum.PENDING.getCode(),
              null,
              null,
              null,
              0,
              Integer.MAX_VALUE,
              null,
              null);

      if (pendingOrders == null || pendingOrders.isEmpty()) {
        logger.info("[CDC] 用户 {} ({}) 无 PENDING 工单，无需处理", userId, username);
        return;
      }

      String remarkAppend =
          String.format("\n[系统提示：申请人已离职（用户ID=%d, 用户名=%s），请重新指派或终止流程]", userId, username);

      int marked = 0;
      for (WorkOrder wo : pendingOrders) {
        try {
          int rows =
              workOrderMapper.appendRemarkForOrphanWorkOrder(
                  wo.getId(), wo.getVersion(), remarkAppend);
          if (rows > 0) {
            marked++;
            logger.info(
                "[CDC] 已标记工单 {} (申请人={}, 标题={})",
                wo.getOrderNo(),
                wo.getApplicantName(),
                wo.getTitle());
          }
        } catch (Exception e) {
          logger.error("[CDC] 标记工单失败 - workOrderId={}, 错误: {}", wo.getId(), e.getMessage(), e);
        }
      }

      logger.info(
          "[CDC] sys_user 软删除处理完成 - userId={}, username={}, 标记工单数={}/{}",
          userId,
          username,
          marked,
          pendingOrders.size());
    } catch (Exception e) {
      logger.error("[CDC] 处理 sys_user 软删除事件失败 - userId={}, 错误: {}", userId, e.getMessage(), e);
    }
  }
}
