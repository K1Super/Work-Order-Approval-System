package com.workorder.entity;

import java.io.Serializable;
import java.time.Instant;

/**
 * 审批日志实体类（流程记录核心）
 *
 * <p>OPTIMIZATION 四.4.1（2026-07-26）： - 移除 is_deleted 列（日志表为纯追加写，删除审计轨迹是合规风险） - 已改为声明式分区表（按月
 * RANGE(create_time)）
 *
 * <p>OPTIMIZATION 四.4.2（2026-07-26）： - action/node_status/before_status/after_status 从 VARCHAR 改为
 * SMALLINT
 *
 * <p>OPTIMIZATION 四.4.4（2026-07-26）： - 时间字段从 LocalDateTime 改为 Instant（对应 TIMESTAMPTZ）
 *
 * @author KLord
 */
public class ApprovalLog implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 日志ID */
  private Long id;

  /** 工单ID */
  private Long workOrderId;

  /** 工单编号 */
  private String orderNo;

  /** 流程实例ID */
  private String processInstanceId;

  /** 任务ID */
  private String taskId;

  /** 任务节点名称 */
  private String taskName;

  /** 操作人ID */
  private Long operatorId;

  /** 操作人姓名 */
  private String operatorName;

  /** 操作类型（SMALLINT，对应 ApprovalActionEnum）： 1-提交 2-通过 3-驳回 4-退回 5-归档 6-终止 7-重新提交 8-转办 */
  private Integer action;

  /** 审批意见 */
  private String comment;

  /** 节点状态（SMALLINT，对应 ApprovalNodeStatusEnum）： 1-待处理 2-已完成 3-已驳回 */
  private Integer nodeStatus;

  /** 操作前状态（SMALLINT，对应 WorkOrderStatusEnum） */
  private Integer beforeStatus;

  /** 操作后状态（SMALLINT，对应 WorkOrderStatusEnum） */
  private Integer afterStatus;

  /** 处理时长（毫秒） */
  private Long duration;

  /** 创建时间（分区键，TIMESTAMPTZ） */
  private Instant createTime;

  /** 创建人ID */
  private Long createBy;

  // Getter & Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getWorkOrderId() {
    return workOrderId;
  }

  public void setWorkOrderId(Long workOrderId) {
    this.workOrderId = workOrderId;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String orderNo) {
    this.orderNo = orderNo;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
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

  public Long getOperatorId() {
    return operatorId;
  }

  public void setOperatorId(Long operatorId) {
    this.operatorId = operatorId;
  }

  public String getOperatorName() {
    return operatorName;
  }

  public void setOperatorName(String operatorName) {
    this.operatorName = operatorName;
  }

  public Integer getAction() {
    return action;
  }

  public void setAction(Integer action) {
    this.action = action;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public Integer getNodeStatus() {
    return nodeStatus;
  }

  public void setNodeStatus(Integer nodeStatus) {
    this.nodeStatus = nodeStatus;
  }

  public Integer getBeforeStatus() {
    return beforeStatus;
  }

  public void setBeforeStatus(Integer beforeStatus) {
    this.beforeStatus = beforeStatus;
  }

  public Integer getAfterStatus() {
    return afterStatus;
  }

  public void setAfterStatus(Integer afterStatus) {
    this.afterStatus = afterStatus;
  }

  public Long getDuration() {
    return duration;
  }

  public void setDuration(Long duration) {
    this.duration = duration;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant createTime) {
    this.createTime = createTime;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long createBy) {
    this.createBy = createBy;
  }
}
