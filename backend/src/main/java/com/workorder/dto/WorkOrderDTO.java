package com.workorder.dto;

import java.io.Serializable;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * Work Order Submission/Creation DTO
 *
 * @author KLord
 */
public class WorkOrderDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Work Order Title */
  @NotBlank(message = "Work order title cannot be empty")
  private String title;

  /** Work Order Content */
  @NotBlank(message = "Work order content cannot be empty")
  private String content;

  /** Work Order Type: leave-leave purchase-purchase reimbursement-reimbursement other-other */
  @NotBlank(message = "Work order type cannot be empty")
  private String orderType;

  /** Priority Level: 1-low 2-medium 3-high 4-urgent */
  @NotNull(message = "Priority level cannot be null")
  private Integer priority;

  /** Application Department */
  private String department;

  /** Application Department ID */
  private Long departmentId;

  /** Attachment URL */
  private String attachmentUrl;

  /** Remark */
  private String remark;

  /** Process Definition Key (used to start process) */
  private String processDefinitionKey;

  // Getter & Setter
  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getOrderType() {
    return orderType;
  }

  public void setOrderType(String orderType) {
    this.orderType = orderType;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }

  public Long getDepartmentId() {
    return departmentId;
  }

  public void setDepartmentId(Long departmentId) {
    this.departmentId = departmentId;
  }

  public String getAttachmentUrl() {
    return attachmentUrl;
  }

  public void setAttachmentUrl(String attachmentUrl) {
    this.attachmentUrl = attachmentUrl;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getProcessDefinitionKey() {
    return processDefinitionKey;
  }

  public void setProcessDefinitionKey(String processDefinitionKey) {
    this.processDefinitionKey = processDefinitionKey;
  }
}
