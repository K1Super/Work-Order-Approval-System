package com.workorder.entity;

import java.io.Serializable;
import java.time.Instant;

/**
 * 工单实体类（核心业务表）
 *
 * <p>OPTIMIZATION 一 架构解耦（2026-07-26）： - 移除 5 个 Flowable
 * 流程运行时字段（process_instance_id/process_definition_id/
 * current_node/current_assignee/current_assignee_name），改由 order_process_link 表承载 -
 * 流程引擎状态变更通过领域事件通知，工单表只保留业务状态
 *
 * <p>OPTIMIZATION 四 数据库优化（2026-07-26）： - status/order_type 从 VARCHAR 改为 SMALLINT（CHECK 约束） - 时间字段从
 * LocalDateTime 改为 Instant（对应 TIMESTAMPTZ） - 新增 version 字段（乐观锁）
 *
 * @author KLord
 */
public class WorkOrder implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 工单ID */
  private Long id;

  /** 工单编号 */
  private String orderNo;

  /** 工单标题 */
  private String title;

  /** 工单内容 */
  private String content;

  /** 工单类型：1-请假 2-采购 3-报销 4-维修 5-补货 6-其他（SMALLINT，对应 OrderTypeEnum） */
  private Integer orderType;

  /** 申请人ID */
  private Long applicantId;

  /** 申请人姓名 */
  private String applicantName;

  /** 申请部门 */
  private String department;

  /** 申请部门ID */
  private Long departmentId;

  /** 优先级：1-低 2-中 3-高 4-紧急 */
  private Integer priority;

  /** 状态：1-草稿 2-审批中 3-已通过 4-已驳回 5-已归档 6-已终止（SMALLINT，对应 WorkOrderStatusEnum） */
  private Integer status;

  /** 附件URL */
  private String attachmentUrl;

  /** 备注 */
  private String remark;

  /** 提交时间 */
  private Instant submitTime;

  /** 完成时间 */
  private Instant completeTime;

  /** 创建时间 */
  private Instant createTime;

  /** 更新时间 */
  private Instant updateTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §2.7.1 审计字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §2.7.1 审计字段，由 AuditFieldInterceptor 自动填充） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段，由 AuditFieldInterceptor 自动填充） */
  private Long updateBy;

  /** 乐观锁版本号（OPTIMIZATION 二 数据一致性保障） */
  private Long version;

  // ============================================================
  // 非持久化显示字段（OPTIMIZATION 一 架构解耦）
  // ------------------------------------------------------------
  // 以下字段不映射到 work_order 表的任何列（V7 已删除 process_instance_id 等 5 个
  // Flowable 运行时字段）。这些字段由服务层在读取时通过查询 order_process_link
  // + Flowable TaskService 动态填充，仅用于 API 响应展示，不参与 INSERT/UPDATE。
  // Mapper.xml 的 resultMap 不包含这些字段，确保 MyBatis 不会尝试从结果集读取。
  // ============================================================

  /** 流程实例ID（非持久化，从 order_process_link 表动态查询填充） */
  private String processInstanceId;

  /** 当前流程节点名称（非持久化，从 Flowable TaskService 动态查询填充） */
  private String currentNode;

  /** 当前审批人ID（非持久化，从 Flowable TaskService 动态查询填充） */
  private Long currentAssignee;

  /** 当前审批人姓名（非持久化，从 Flowable TaskService 动态查询填充） */
  private String currentAssigneeName;

  // Getter & Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String orderNo) {
    this.orderNo = orderNo;
  }

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

  public Integer getOrderType() {
    return orderType;
  }

  public void setOrderType(Integer orderType) {
    this.orderType = orderType;
  }

  public Long getApplicantId() {
    return applicantId;
  }

  public void setApplicantId(Long applicantId) {
    this.applicantId = applicantId;
  }

  public String getApplicantName() {
    return applicantName;
  }

  public void setApplicantName(String applicantName) {
    this.applicantName = applicantName;
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

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer status) {
    this.status = status;
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

  public Instant getSubmitTime() {
    return submitTime;
  }

  public void setSubmitTime(Instant submitTime) {
    this.submitTime = submitTime;
  }

  public Instant getCompleteTime() {
    return completeTime;
  }

  public void setCompleteTime(Instant completeTime) {
    this.completeTime = completeTime;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant createTime) {
    this.createTime = createTime;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant updateTime) {
    this.updateTime = updateTime;
  }

  public Integer getIsDeleted() {
    return isDeleted;
  }

  public void setIsDeleted(Integer isDeleted) {
    this.isDeleted = isDeleted;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long createBy) {
    this.createBy = createBy;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long updateBy) {
    this.updateBy = updateBy;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  // ============================================================
  // 非持久化显示字段 Getter/Setter（OPTIMIZATION 一 架构解耦）
  // ============================================================

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public String getCurrentNode() {
    return currentNode;
  }

  public void setCurrentNode(String currentNode) {
    this.currentNode = currentNode;
  }

  public Long getCurrentAssignee() {
    return currentAssignee;
  }

  public void setCurrentAssignee(Long currentAssignee) {
    this.currentAssignee = currentAssignee;
  }

  public String getCurrentAssigneeName() {
    return currentAssigneeName;
  }

  public void setCurrentAssigneeName(String currentAssigneeName) {
    this.currentAssigneeName = currentAssigneeName;
  }
}
