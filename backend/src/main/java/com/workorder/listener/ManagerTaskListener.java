package com.workorder.listener;



import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.service.IApproverResolverService;

/** 部门经理审批任务分配器（独立节点） 使用 IApproverResolverService 动态查找部门经理，不再硬编码用户ID */
@Component("managerTaskListener")
public class ManagerTaskListener implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(ManagerTaskListener.class);

  /** W-27：独立工作流告警 logger，用于分配/跳过失败等关键告警。 */
  private static final Logger ALERT = LoggerFactory.getLogger("WORKFLOW_ALERT_LOGGER");

  @Autowired private IApproverResolverService approverResolverService;

  @Autowired private TaskService taskService;

  @Override
  public void notify(DelegateTask delegateTask) {
    String applicantId = (String) delegateTask.getVariable("applicantId");

    String assigneeId;
    try {
      assigneeId =
          approverResolverService.resolveAssignee(
              "Department Manager Approval", applicantId, null, null, null, null);
    } catch (Exception e) {
      ALERT.error(
          "[WORKFLOW_ALERT] 部门经理审批人解析异常 - 任务ID: {}, 流程实例: {}, 原因: {}",
          delegateTask.getId(), delegateTask.getProcessInstanceId(), e.getMessage(), e);
      throw new RuntimeException("部门经理审批人解析异常", e);
    }

    // 节点跳过：高层级申请人无需部门经理审批，自动完成该节点作为补偿，避免工序卡死
    if (IApproverResolverService.SIGNAL_SKIP_NODE.equals(assigneeId)) {
      logger.info("[DeptMgr] 任务自动跳过 - Task ID: {}", delegateTask.getId());
      try {
        taskService.complete(delegateTask.getId());
      } catch (Exception e) {
        ALERT.error(
            "[WORKFLOW_ALERT] 部门经理节点跳过自动完成失败 - 任务ID: {}, 流程实例: {}, 原因: {}",
            delegateTask.getId(), delegateTask.getProcessInstanceId(), e.getMessage(), e);
        throw new RuntimeException("部门经理节点跳过自动完成失败", e);
      }
      return;
    }

    if (assigneeId == null || assigneeId.isEmpty()) {
      ALERT.error(
          "[WORKFLOW_ALERT] 部门经理审批人分配失败（候选人为空） - 任务ID: {}, 流程实例: {}",
          delegateTask.getId(), delegateTask.getProcessInstanceId());
      throw new RuntimeException("部门经理审批人分配失败");
    }

    delegateTask.setAssignee(assigneeId);
    logger.info("[DeptMgr] 任务分配完成 - Task ID: {}, 审批人: {}", delegateTask.getId(), assigneeId);
  }
}
