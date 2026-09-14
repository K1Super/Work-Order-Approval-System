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

  @Autowired private IApproverResolverService approverResolverService;

  @Override
  public void notify(DelegateTask delegateTask) {
    try {
      String applicantId = (String) delegateTask.getVariable("applicantId");
      String assigneeId =
          approverResolverService.resolveAssignee(
              "Finance Review", applicantId, null, null, null, null);

      if (assigneeId != null && !IApproverResolverService.SIGNAL_SKIP_NODE.equals(assigneeId)) {
        delegateTask.setAssignee(assigneeId);
        logger.info("[Finance] 任务分配完成 - Task ID: {}, 审批人: {}", delegateTask.getId(), assigneeId);
      } else {
        logger.warn("[Finance] 未找到财务审批人 - Task ID: {}", delegateTask.getId());
      }
    } catch (Exception e) {
      logger.error("[Finance] 分配异常 - 错误: {}", e.getMessage(), e);
    }
  }
}
