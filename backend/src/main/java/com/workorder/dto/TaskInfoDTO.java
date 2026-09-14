package com.workorder.dto;

import java.io.Serializable;

/**
 * 任务信息 DTO（OPTIMIZATION 一 架构解耦）
 *
 * <p>封装 Flowable 当前任务信息，供前端展示当前审批节点/审批人。 工单表不再持久化 current_node/current_assignee 等流程运行时字段， 前端通过 GET
 * /workorder/{id}/current-task 实时获取。
 *
 * @author KLord
 */
public class TaskInfoDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 任务ID */
  private String taskId;

  /** 任务节点名称（如"部门经理审批"） */
  private String taskName;

  /** 审批人ID */
  private Long assigneeId;

  /** 审批人姓名 */
  private String assigneeName;

  /** 流程实例ID */
  private String processInstanceId;

  /** 任务创建时间（ISO-8601） */
  private String createTime;

  /** 是否为候选任务（待认领） */
  private Boolean candidateTask;

  /** 构造函数，初始化空任务信息 */
  public TaskInfoDTO() {}

  /** 构造函数，指定任务ID、节点名称、审批人ID和姓名 */
  public TaskInfoDTO(String taskId, String taskName, Long assigneeId, String assigneeName) {
    this.taskId = taskId;
    this.taskName = taskName;
    this.assigneeId = assigneeId;
    this.assigneeName = assigneeName;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  public String getTaskName() {
    return taskName;
  }

  public void setTaskName(String taskName) {
    this.taskName = taskName;
  }

  public Long getAssigneeId() {
    return assigneeId;
  }

  public void setAssigneeId(Long assigneeId) {
    this.assigneeId = assigneeId;
  }

  public String getAssigneeName() {
    return assigneeName;
  }

  public void setAssigneeName(String assigneeName) {
    this.assigneeName = assigneeName;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public String getCreateTime() {
    return createTime;
  }

  public void setCreateTime(String createTime) {
    this.createTime = createTime;
  }

  public Boolean getCandidateTask() {
    return candidateTask;
  }

  public void setCandidateTask(Boolean candidateTask) {
    this.candidateTask = candidateTask;
  }
}
