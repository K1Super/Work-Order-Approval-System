package com.workorder.listener;



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

  @Autowired private IApproverResolverService approverResolverService;

  @Override
  public void notify(DelegateTask delegateTask) {
    try {
      String applicantId = (String) delegateTask.getVariable("applicantId");
      String assigneeId =
          approverResolverService.resolveAssignee(
              "Department Manager Approval", applicantId, null, null, null, null);

      if (assigneeId != null && !IApproverResolverService.SIGNAL_SKIP_NODE.equals(assigneeId)) {
        delegateTask.setAssignee(assigneeId);
        logger.info("[DeptMgr] 任务分配完成 - Task ID: {}, 审批人: {}", delegateTask.getId(), assigneeId);
      } else {
        logger.warn("[DeptMgr] 未找到部门经理 - Task ID: {}", delegateTask.getId());
      }
    } catch (Exception e) {
      logger.error("[DeptMgr] 分配异常 - 错误: {}", e.getMessage(), e);
    }
  }
}
