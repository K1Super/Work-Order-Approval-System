package com.workorder.listener;



import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自动归档任务委托类 流程结束时自动执行归档操作
 *
 * @author KLord
 */
public class ArchiveTaskDelegate implements JavaDelegate {

  private static final Logger logger = LoggerFactory.getLogger(ArchiveTaskDelegate.class);

  @Override
  public void execute(DelegateExecution execution) {
    String processInstanceId = execution.getProcessInstanceId();

    logger.info("【自动归档委托】开始执行归档操作 - 流程实例ID: {}", processInstanceId);

    try {
      // 1. 获取流程变量
      Long workOrderId = (Long) execution.getVariable("workOrderId");

      // 2. 更新工单状态为已归档
      // TODO: 注入WorkOrderService并调用更新方法
      // workOrderService.archiveWorkOrder(workOrderId);

      // 3. 设置流程变量
      execution.setVariable("archiveTime", new java.util.Date());
      execution.setVariable("archiveStatus", "ARCHIVED");

      logger.info("【自动归档委托】归档完成 - 工单ID: {}", workOrderId);

    } catch (Exception e) {
      logger.error("【自动归档委托】归档失败 - 错误: {}", e.getMessage(), e);
      throw new RuntimeException("归档操作失败", e);
    }
  }
}
