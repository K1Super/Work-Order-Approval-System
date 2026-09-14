package com.workorder.service.impl;


import java.util.ArrayList;
import java.util.List;

import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.dto.TaskInfoDTO;
import com.workorder.entity.OrderProcessLink;
import com.workorder.entity.User;
import com.workorder.service.IFlowableQueryService;

/**
 * Flowable 流程查询服务实现（OPTIMIZATION 一 架构解耦）
 *
 * <p>封装 Flowable TaskService/HistoryService 的查询调用， 工单业务层通过此接口获取流程运行时数据，避免直接依赖 Flowable API。
 *
 * <p>通过 order_process_link 表反查 process_instance_id，再查 Flowable 任务。
 *
 * @author KLord
 */
@Service
public class FlowableQueryServiceImpl implements IFlowableQueryService {

  private static final Logger logger = LoggerFactory.getLogger(FlowableQueryServiceImpl.class);

  @Autowired private TaskService taskService;

  @Autowired private HistoryService historyService;

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Autowired private UserMapper userMapper;

  @Override
  public TaskInfoDTO getCurrentTask(Long workOrderId) {
    try {
      // 通过 order_process_link 反查 process_instance_id
      OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrderId);
      if (link == null || link.getProcessInstanceId() == null) {
        logger.debug("[FlowableQuery] 工单 {} 无关联流程实例", workOrderId);
        return null;
      }
      return getFirstActiveTask(link.getProcessInstanceId());
    } catch (Exception e) {
      logger.error("[FlowableQuery] 查询工单 {} 当前任务失败: {}", workOrderId, e.getMessage(), e);
      return null;
    }
  }

  @Override
  public List<TaskInfoDTO> getActiveTasks(String processInstanceId) {
    List<TaskInfoDTO> result = new ArrayList<>();
    if (processInstanceId == null || processInstanceId.isEmpty()) {
      return result;
    }
    try {
      List<Task> tasks =
          taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();
      for (Task task : tasks) {
        result.add(convertToDTO(task));
      }
    } catch (Exception e) {
      logger.error(
          "[FlowableQuery] 查询活跃任务失败 - processInstanceId: {}, 错误: {}",
          processInstanceId,
          e.getMessage(),
          e);
    }
    return result;
  }

  @Override
  public boolean isProcessFinished(String processInstanceId) {
    if (processInstanceId == null || processInstanceId.isEmpty()) {
      return true;
    }
    try {
      long count =
          historyService
              .createHistoricProcessInstanceQuery()
              .processInstanceId(processInstanceId)
              .finished()
              .count();
      return count > 0;
    } catch (Exception e) {
      logger.error(
          "[FlowableQuery] 查询流程状态失败 - processInstanceId: {}, 错误: {}",
          processInstanceId,
          e.getMessage(),
          e);
      return false;
    }
  }

  @Override
  public TaskInfoDTO getFirstActiveTask(String processInstanceId) {
    if (processInstanceId == null || processInstanceId.isEmpty()) {
      return null;
    }
    try {
      List<Task> tasks =
          taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();
      if (tasks.isEmpty()) {
        return null;
      }
      Task firstTask = tasks.get(0);
      return convertToDTO(firstTask);
    } catch (Exception e) {
      logger.error(
          "[FlowableQuery] 查询首个活跃任务失败 - processInstanceId: {}, 错误: {}",
          processInstanceId,
          e.getMessage(),
          e);
      return null;
    }
  }

  /** Flowable Task → TaskInfoDTO 转换 查询审批人姓名（如果 assignee 不为空） */
  private TaskInfoDTO convertToDTO(Task task) {
    TaskInfoDTO dto = new TaskInfoDTO();
    dto.setTaskId(task.getId());
    dto.setTaskName(task.getName());
    dto.setProcessInstanceId(task.getProcessInstanceId());

    if (task.getAssignee() != null && !task.getAssignee().isEmpty()) {
      try {
        Long assigneeId = Long.parseLong(task.getAssignee());
        dto.setAssigneeId(assigneeId);
        User assigneeUser = userMapper.selectById(assigneeId);
        if (assigneeUser != null) {
          dto.setAssigneeName(assigneeUser.getRealName());
        }
        dto.setCandidateTask(false);
      } catch (NumberFormatException e) {
        logger.warn("[FlowableQuery] 任务 assignee 不是有效数字: {}", task.getAssignee());
      }
    } else {
      dto.setAssigneeName("待认领");
      dto.setCandidateTask(true);
    }

    if (task.getCreateTime() != null) {
      dto.setCreateTime(task.getCreateTime().toString());
    }
    return dto;
  }
}
