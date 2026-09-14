package com.workorder.service;


import java.util.List;


import com.workorder.common.result.PageRequest;
import com.workorder.common.result.PageResult;
import com.workorder.common.result.Result;
import com.workorder.dto.ApprovalDTO;
import com.workorder.dto.TaskInfoDTO;
import com.workorder.dto.WorkOrderDTO;
import com.workorder.entity.WorkOrder;

/**
 * 工单服务接口
 *
 * @author KLord
 */
public interface IWorkOrderService {

  /** 创建工单（保存为草稿） */
  Result<WorkOrder> createDraft(WorkOrderDTO dto, Long applicantId);

  /** 提交工单（启动流程） */
  Result<WorkOrder> submitWorkOrder(Long workOrderId, Long applicantId);

  /** 提交新工单（直接提交） */
  Result<WorkOrder> submitNewWorkOrder(WorkOrderDTO dto, Long applicantId);

  /** 根据ID查询工单详情 */
  Result<WorkOrder> getWorkOrderById(Long id, Long currentUserId);

  /** 根据工单编号查询 */
  Result<WorkOrder> getWorkOrderByNo(String orderNo);

  /**
   * 根据工单编号查询（带操作人 ID，用于所有权校验） 阶段 2 修复 A-11/H-07：IDOR 越权防护
   *
   * @param orderNo 工单编号
   * @param currentUserId 当前登录用户 ID
   */
  Result<WorkOrder> getWorkOrderByNo(String orderNo, Long currentUserId);

  /** 分页查询工单列表（我的工单） */
  Result<PageResult<WorkOrder>> getMyWorkOrders(
      PageRequest pageRequest, Long applicantId, String status, String orderType, String keyword);

  /** 分页查询所有工单（管理员，按部门隔离） */
  Result<PageResult<WorkOrder>> getAllWorkOrders(
      PageRequest pageRequest, String status, String orderType, String keyword, Long currentUserId);

  /** 查询待我审批的工单列表 */
  Result<List<WorkOrder>> getPendingApprovalList(Long assigneeId);

  /** 审批操作（通过/驳回/退回等） */
  Result<WorkOrder> handleApproval(ApprovalDTO approvalDTO, Long operatorId);

  /** 重新提交被驳回的工单 */
  Result<WorkOrder> resubmitWorkOrder(Long workOrderId, Long applicantId, String comment);

  /** 终止流程 */
  Result<WorkOrder> terminateWorkOrder(Long workOrderId, Long operatorId, String reason);

  /** 归档工单 */
  Result<WorkOrder> archiveWorkOrder(Long workOrderId);

  /**
   * 归档工单（带操作人 ID，用于所有权校验） 阶段 2 修复 A-11/H-07：IDOR 越权防护
   *
   * @param workOrderId 工单 ID
   * @param userId 当前操作用户 ID
   */
  Result<WorkOrder> archiveWorkOrder(Long workOrderId, Long userId);

  /** 更新工单信息 */
  Result<WorkOrder> updateWorkOrder(WorkOrder workOrder);

  /**
   * 更新工单信息（带操作人 ID，用于所有权校验） 阶段 2 修复 A-11/H-07：仅发起人可修改草稿状态工单，防 IDOR 越权
   *
   * @param workOrder 工单数据
   * @param userId 当前操作用户 ID
   */
  Result<WorkOrder> updateWorkOrder(WorkOrder workOrder, Long userId);

  /** 删除工单（仅草稿状态可删除） */
  Result<?> deleteWorkOrder(Long workOrderId, Long userId);

  /** 撤回工单（将PENDING状态改为DRAFT） */
  Result<WorkOrder> withdrawWorkOrder(Long workOrderId, Long userId);

  /**
   * 查询工单的当前任务信息（OPTIMIZATION 一 架构解耦）
   *
   * <p>工单表已移除 current_node/current_assignee 等流程运行时字段， 前端通过此接口实时获取当前审批节点和审批人。
   *
   * @param workOrderId 工单ID
   * @return 当前任务信息；流程已结束或不存在返回 null
   */
  Result<TaskInfoDTO> getCurrentTask(Long workOrderId);
}
