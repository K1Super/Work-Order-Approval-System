package com.workorder.dto;

import java.io.Serializable;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 审批操作DTO
 *
 * @author KLord
 */
public class ApprovalDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 工单ID */
  @NotNull(message = "工单ID不能为空")
  private Long workOrderId;

  /** 审批操作： APPROVE-通过 REJECT-驳回 RETURN-退回 ARCHIVE-归档 TERMINATE-终止 RESUBMIT-重新提交 */
  @NotBlank(message = "审批操作不能为空")
  private String action;

  /** 审批意见 */
  private String comment;

  /** 任务ID（Flowable任务ID） */
  private String taskId;

  // Getter & Setter
  public Long getWorkOrderId() {
    return workOrderId;
  }

  public void setWorkOrderId(Long workOrderId) {
    this.workOrderId = workOrderId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }
}
