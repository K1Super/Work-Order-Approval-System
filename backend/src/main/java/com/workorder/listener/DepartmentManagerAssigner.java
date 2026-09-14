package com.workorder.listener;



import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 部门经理审批任务分配器 负责将经理审批任务分配给申请人的部门经理或直属上级
 *
 * @author KLord
 */
@Component("departmentManagerAssigner")
public class DepartmentManagerAssigner implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(DepartmentManagerAssigner.class);

  @Autowired private DynamicTaskAssigner dynamicTaskAssigner;

  @Override
  public void notify(DelegateTask delegateTask) {
    logger.info("========== 部门经理审批任务分配开始 ==========");
    logger.info("任务ID: {}, 流程实例: {}", delegateTask.getId(), delegateTask.getProcessInstanceId());

    // 设置当前审批层级为3（部门经理层）
    delegateTask.setVariable("currentApprovalLevel", 3);

    // 委托给通用动态分配器处理
    dynamicTaskAssigner.notify(delegateTask);

    logger.info("========== 部门经理审批任务分配完成 ==========");
  }
}
