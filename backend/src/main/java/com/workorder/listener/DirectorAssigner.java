package com.workorder.listener;



import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 总监层审批任务分配器 负责将总监审批任务分配给总监或副总经理
 *
 * @author KLord
 */
@Component("directorAssigner")
public class DirectorAssigner implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(DirectorAssigner.class);

  @Autowired private DynamicTaskAssigner dynamicTaskAssigner;

  @Override
  public void notify(DelegateTask delegateTask) {
    logger.info("========== 总监层审批任务分配开始 ==========");
    logger.info("任务ID: {}, 流程实例: {}", delegateTask.getId(), delegateTask.getProcessInstanceId());

    // 设置当前审批层级为2（高管层）
    delegateTask.setVariable("currentApprovalLevel", 2);

    // 委托给通用动态分配器处理
    dynamicTaskAssigner.notify(delegateTask);

    logger.info("========== 总监层审批任务分配完成 ==========");
  }
}
