package com.workorder.listener;



import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 决策层审批任务分配器 负责将重大事项审批任务分配给总经理或董事长
 *
 * @author KLord
 */
@Component("executiveAssigner")
public class ExecutiveAssigner implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(ExecutiveAssigner.class);

  @Autowired private DynamicTaskAssigner dynamicTaskAssigner;

  @Override
  public void notify(DelegateTask delegateTask) {
    logger.info("========== 决策层审批任务分配开始 ==========");
    logger.info("任务ID: {}, 流程实例: {}", delegateTask.getId(), delegateTask.getProcessInstanceId());

    // 设置当前审批层级为1（决策层）
    delegateTask.setVariable("currentApprovalLevel", 1);

    // 委托给通用动态分配器处理
    dynamicTaskAssigner.notify(delegateTask);

    logger.info("========== 决策层审批任务分配完成 ==========");
  }
}
