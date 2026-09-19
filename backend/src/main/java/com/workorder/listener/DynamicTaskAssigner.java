package com.workorder.listener;



import java.util.List;

import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.identitylink.api.IdentityLink;
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
 *
 * <p>W-12：职能节点优先按流程定义中的 assignee/candidate 判定 —— 若任务已配置审批人或候选用户， 直接沿用（跨部门职能岗可在 BPMN
 * 或流程变量中指定，不受申请人部门限制）；仅在未配置时才委托 ApproverResolverServiceImpl 按角色/职能解析。
 */
@Component("dynamicTaskAssigner")
public class DynamicTaskAssigner implements TaskListener {

  private static final Logger logger = LoggerFactory.getLogger(DynamicTaskAssigner.class);

  /** W-27：独立工作流告警 logger，用于分配/跳过失败等关键告警，便于运营配置独立告警路由。 */
  private static final Logger ALERT = LoggerFactory.getLogger("WORKFLOW_ALERT_LOGGER");

  @Autowired private IApproverResolverService approverResolverService;

  @Autowired private TaskService taskService;

  @Override
  public void notify(DelegateTask delegateTask) {
    String taskName = delegateTask.getName();
    String processInstanceId = delegateTask.getProcessInstanceId();

    logger.info("[DynamicTaskAssigner] 任务: {}, 流程实例: {}", taskName, processInstanceId);

    // W-12：优先尊重流程定义中已配置的 assignee（审批人不限定申请人同部门）
    if (delegateTask.getAssignee() != null && !delegateTask.getAssignee().isEmpty()) {
      logger.info(
          "[DynamicTaskAssigner] 任务 '{}' 已配置审批人 assignee={}，跳过动态分配",
          taskName,
          delegateTask.getAssignee());
      return;
    }

    // W-12：若已配置候选用户（candidateUsers），保留认领语义，跳过动态分配。
    // 可降级：候选人查询失败时不阻断流程，继续走动态分配兜底。
    try {
      List<IdentityLink> links = taskService.getIdentityLinksForTask(delegateTask.getId());
      if (links != null) {
        for (IdentityLink link : links) {
          if ("candidate".equals(link.getType())
              && link.getUserId() != null
              && !link.getUserId().isEmpty()) {
            logger.info("[DynamicTaskAssigner] 任务 '{}' 已配置候选用户，跳过动态分配", taskName);
            return;
          }
        }
      }
    } catch (Exception e) {
      logger.warn(
          "[DynamicTaskAssigner] 查询任务候选人失败，继续动态分配: 任务={}, 错误={}", taskName, e.getMessage());
    }

    // 获取流程变量
    String applicantId = (String) delegateTask.getVariable("applicantId");
    String orderType = (String) delegateTask.getVariable("orderType");
    Double amount = (Double) delegateTask.getVariable("amount");
    Integer leaveDays = (Integer) delegateTask.getVariable("leaveDays");
    Integer priority = getPriorityFromVariable(delegateTask.getVariable("priority"));
    Object workOrderId = delegateTask.getVariable("workOrderId");

    // 委托给 Service 解析审批人：禁止吞异常后返回 null 导致空 assignee 落库卡死
    String assigneeId;
    try {
      assigneeId =
          approverResolverService.resolveAssignee(
              taskName, applicantId, orderType, amount, leaveDays, priority);
    } catch (Exception e) {
      ALERT.error(
          "[WORKFLOW_ALERT] 审批人解析异常 - 节点: {}, 任务ID: {}, 流程实例: {}, 工单: {}, 原因: {}",
          taskName, delegateTask.getId(), processInstanceId, workOrderId, e.getMessage(), e);
      throw new RuntimeException("审批人解析异常: " + taskName, e);
    }

    // 处理跳过节点的信号：自动完成即补偿；完成失败必须告警并抛出，否则任务无 assignee 静默卡死
    if (IApproverResolverService.SIGNAL_SKIP_NODE.equals(assigneeId)) {
      logger.info("[跳过节点] 任务 '{}' 被自动跳过", taskName);
      try {
        taskService.complete(delegateTask.getId());
        logger.info("[自动完成] 已完成被跳过的任务 '{}'", taskName);
      } catch (Exception e) {
        ALERT.error(
            "[WORKFLOW_ALERT] 跳过节点自动完成失败 - 节点: {}, 任务ID: {}, 流程实例: {}, 工单: {}, 原因: {}",
            taskName, delegateTask.getId(), processInstanceId, workOrderId, e.getMessage(), e);
        throw new RuntimeException("跳过节点自动完成失败: " + taskName, e);
      }
      return;
    }

    if (assigneeId == null || assigneeId.isEmpty()) {
      ALERT.error(
          "[WORKFLOW_ALERT] 审批人分配失败（候选人为空） - 节点: {}, 任务ID: {}, 流程实例: {}, 工单: {}, 申请人ID: {}",
          taskName, delegateTask.getId(), processInstanceId, workOrderId, applicantId);
      throw new RuntimeException("审批人分配失败: " + taskName);
    }

    delegateTask.setAssignee(assigneeId);
    logger.info("[分配成功] 任务: {} → 审批人ID: {}", taskName, assigneeId);
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
