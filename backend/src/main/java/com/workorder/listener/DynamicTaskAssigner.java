package com.workorder.listener;



import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.service.IApproverResolverService;

/**
 * 动态任务分配器（轻量适配器）
 *
 * <p>重构后：仅作为 Flowable TaskListener 与 IApproverResolverService 之间的桥梁， 不再包含任何业务逻辑。所有审批人查找逻辑已提取至
 * ApproverResolverServiceImpl。
 */
@Component("dynamicTaskAssigner")
public class DynamicTaskAssigner implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(DynamicTaskAssigner.class);

  @Autowired private IApproverResolverService approverResolverService;

  @Autowired private TaskService taskService;

  @Override
  public void notify(DelegateTask delegateTask) {
    String taskName = delegateTask.getName();
    String processInstanceId = delegateTask.getProcessInstanceId();

    logger.info("[DynamicTaskAssigner] 任务: {}, 流程实例: {}", taskName, processInstanceId);

    try {
      // 获取流程变量
      String applicantId = (String) delegateTask.getVariable("applicantId");
      String orderType = (String) delegateTask.getVariable("orderType");
      Double amount = (Double) delegateTask.getVariable("amount");
      Integer leaveDays = (Integer) delegateTask.getVariable("leaveDays");
      Integer priority = getPriorityFromVariable(delegateTask.getVariable("priority"));

      // 委托给 Service 解析审批人
      String assigneeId =
          approverResolverService.resolveAssignee(
              taskName, applicantId, orderType, amount, leaveDays, priority);

      // 处理跳过节点的信号
      if (IApproverResolverService.SIGNAL_SKIP_NODE.equals(assigneeId)) {
        logger.info("[跳过节点] 任务 '{}' 被自动跳过", taskName);
        try {
          taskService.complete(delegateTask.getId());
          logger.info("[自动完成] 已完成被跳过的任务 '{}'", taskName);
        } catch (Exception e) {
          logger.warn("[自动完成失败] 无法完成任务 '{}': {}", taskName, e.getMessage());
        }
        return;
      }

      if (assigneeId != null) {
        delegateTask.setAssignee(assigneeId);
        logger.info("[分配成功] 任务: {} → 审批人ID: {}", taskName, assigneeId);
      } else {
        logger.error("[分配失败] 未找到审批人 - 任务: {}", taskName);
      }
    } catch (Exception e) {
      logger.error("[分配异常] 任务: {}, 错误: {}", taskName, e.getMessage(), e);
    }
  }

  private Integer getPriorityFromVariable(Object priorityVar) {
    if (priorityVar == null) return 2;
    if (priorityVar instanceof Integer) return (Integer) priorityVar;
    if (priorityVar instanceof Number) return ((Number) priorityVar).intValue();
    try {
      return Integer.parseInt(priorityVar.toString());
    } catch (Exception e) {
      return 2;
    }
  }
}
