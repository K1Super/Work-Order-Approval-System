package com.workorder.service.impl;


import java.util.List;

import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.OrderProcessLink;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;

/**
 * 工单展示字段组装器（OPTIMIZATION 一 架构解耦）。
 *
 * <p>职责：集中组装工单的非持久化显示字段——当前节点/当前审批人/流程实例 ID。 这些字段不写入数据库，仅在 API 响应前通过查询 order_process_link
 * 与 Flowable 动态填充， 供门面查询方法与审批编排复用。
 *
 * @author KLord
 */
@Component
public class WorkOrderDisplayAssembler {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderDisplayAssembler.class);

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Autowired private TaskService taskService;

  @Autowired private UserMapper userMapper;

  /**
   * 批量填充工单列表的当前审批人信息（OPTIMIZATION 一：非持久化显示字段）。
   *
   * @param list 工单列表
   */
  public void populateProcessDisplayFields(List<WorkOrder> list) {
    if (list == null || list.isEmpty()) return;

    for (WorkOrder wo : list) {
      populateProcessDisplayFields(wo);
    }
  }

  /**
   * 填充单个工单的当前审批人信息（OPTIMIZATION 一：非持久化显示字段）。
   *
   * @param wo 工单实体
   */
  public void populateProcessDisplayFields(WorkOrder wo) {
    if (wo == null) return;

    // 非 PENDING 状态无需查询当前审批人
    if (!WorkOrderStatusEnum.PENDING.getCode().equals(wo.getStatus())) {
      wo.setCurrentAssigneeName(null);
      wo.setCurrentNode(null);
      wo.setCurrentAssignee(null);
      wo.setProcessInstanceId(null);
      return;
    }

    try {
      // 从 order_process_link 查询 process_instance_id
      OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(wo.getId());
      if (link == null || link.getProcessInstanceId() == null) {
        logger.debug("OrderProcessLink not found for workOrderId={}", wo.getId());
        return;
      }

      String processInstanceId = link.getProcessInstanceId();
      wo.setProcessInstanceId(processInstanceId);

      List<Task> activeTasks =
          taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();

      if (!activeTasks.isEmpty()) {
        Task currentTask = activeTasks.get(0);
        wo.setCurrentNode(currentTask.getName());
        String assignee = currentTask.getAssignee();
        if (assignee != null && !assignee.isEmpty()) {
          Long assigneeId = Long.parseLong(assignee);
          User assigneeUser = userMapper.selectById(assigneeId);
          if (assigneeUser != null) {
            wo.setCurrentAssignee(assigneeId);
            wo.setCurrentAssigneeName(assigneeUser.getRealName());
          }
        }
      }
    } catch (Exception e) {
      logger.warn("查询工单 {} 审批人失败: {}", wo.getId(), e.getMessage());
    }
  }

  /**
   * 组装审批完成后的下一节点显示信息（非持久化）。
   *
   * <p>查询流程实例的活跃任务，解析负责人或候选组，返回节点名/审批人 ID/审批人显示名。
   *
   * @param processInstanceId 流程实例 ID
   * @return 下一节点信息，无活跃任务时三个字段均为 null
   */
  public NextNodeInfo buildNextNodeInfo(String processInstanceId) {
    String currentNode = null;
    Long currentAssigneeId = null;
    String currentAssigneeName = null;

    try {
      List<Task> activeTasks =
          taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();

      if (!activeTasks.isEmpty()) {
        Task nextTask = activeTasks.get(0);
        currentNode = nextTask.getName();

        if (nextTask.getAssignee() != null) {
          currentAssigneeId = Long.parseLong(nextTask.getAssignee());
          User assigneeUser = userMapper.selectById(currentAssigneeId);
          if (assigneeUser != null) {
            currentAssigneeName = assigneeUser.getRealName();
          }
        } else {
          try {
            List<IdentityLink> identityLinks =
                taskService.getIdentityLinksForTask(nextTask.getId());
            if (!identityLinks.isEmpty()) {
              StringBuilder candidateInfo = new StringBuilder();
              for (IdentityLink link : identityLinks) {
                if ("candidate".equals(link.getType())) {
                  if (link.getGroupId() != null) {
                    if (candidateInfo.length() > 0) candidateInfo.append(", ");
                    candidateInfo.append(link.getGroupId());
                  } else if (link.getUserId() != null) {
                    if (candidateInfo.length() > 0) candidateInfo.append(", ");
                    User candUser = userMapper.selectById(Long.parseLong(link.getUserId()));
                    if (candUser != null) {
                      candidateInfo.append(candUser.getRealName());
                    }
                  }
                }
              }
              if (candidateInfo.length() > 0) {
                currentAssigneeName = "待认领(" + candidateInfo + ")";
              } else {
                currentAssigneeName = "待认领";
              }
            } else {
              currentAssigneeName = "待认领";
            }
          } catch (Exception ex) {
            logger.warn("获取候选组信息失败: {}", ex.getMessage());
            currentAssigneeName = "待处理";
          }
        }
      }
    } catch (Exception e) {
      logger.warn("Failed to get next node info", e);
    }

    return new NextNodeInfo(currentNode, currentAssigneeId, currentAssigneeName);
  }

  /**
   * 获取审批层级名称。
   *
   * @param task Flowable 任务
   * @return 审批层级英文名，无法解析返回 {@code Unknown}
   */
  public String getApprovalLevelName(Task task) {
    try {
      Integer level = (Integer) taskService.getVariable(task.getId(), "currentApprovalLevel");
      if (level == null) return "Unknown";

      switch (level) {
        case 1:
          return "Decision Layer";
        case 2:
          return "Director Level";
        case 3:
          return "Dept Manager Level";
        case 4:
          return "Professional Capability";
        default:
          return "Unknown Level";
      }
    } catch (Exception e) {
      return "Unknown";
    }
  }

  /** 下一审批节点信息（非持久化显示字段载体） */
  public static class NextNodeInfo {

    private final String nodeName;
    private final Long assigneeId;
    private final String assigneeName;

    public NextNodeInfo(String nodeName, Long assigneeId, String assigneeName) {
      this.nodeName = nodeName;
      this.assigneeId = assigneeId;
      this.assigneeName = assigneeName;
    }

    public String getNodeName() {
      return nodeName;
    }

    public Long getAssigneeId() {
      return assigneeId;
    }

    public String getAssigneeName() {
      return assigneeName;
    }
  }
}