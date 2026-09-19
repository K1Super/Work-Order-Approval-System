package com.workorder.listener;



import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.service.IApproverResolverService;

/** 财务审核任务分配器 使用 IApproverResolverService 动态查找财务会计，不再硬编码用户ID */
@Component("financeTaskListener")
public class FinanceTaskListener implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(FinanceTaskListener.class);

  /** W-27：独立工作流告警 logger，用于分配失败等关键告警。 */
  private static final Logger ALERT = LoggerFactory.getLogger("WORKFLOW_ALERT_LOGGER");

  @Autowired private IApproverResolverService approverResolverService;

  @Override
  public void notify(DelegateTask delegateTask) {
    String applicantId = (String) delegateTask.getVariable("applicantId");

    String assigneeId;
    try {
      assigneeId =
          approverResolverService.resolveAssignee(
              "Finance Review", applicantId, null, null, null, null);
    } catch (Exception e) {
      ALERT.error(
          "[WORKFLOW_ALERT] 财务审批人解析异常 - 任务ID: {}, 流程实例: {}, 原因: {}",
          delegateTask.getId(), delegateTask.getProcessInstanceId(), e.getMessage(), e);
      throw new RuntimeException("财务审批人解析异常", e);
    }

    // 财务节点无“跳过”语义：跳过信号或 assignee 为空均视为分配失败，禁止静默落空 assignee 卡死
    if (assigneeId != null
        && !assigneeId.isEmpty()
        && !IApproverResolverService.SIGNAL_SKIP_NODE.equals(assigneeId)) {
      delegateTask.setAssignee(assigneeId);
      logger.info("[Finance] 任务分配完成 - Task ID: {}, 审批人: {}", delegateTask.getId(), assigneeId);
    } else {
      ALERT.error(
          "[WORKFLOW_ALERT] 财务审批人分配失败（候选人为空） - 任务ID: {}, 流程实例: {}",
          delegateTask.getId(), delegateTask.getProcessInstanceId());
      throw new RuntimeException("财务审批人分配失败");
    }
  }
}
