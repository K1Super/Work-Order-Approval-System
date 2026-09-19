package com.workorder.service.impl;


import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.enums.ApprovalActionEnum;
import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.OptimisticLockException;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.dto.ApprovalDTO;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.service.IFlowableQueryService;
import com.workorder.util.XssCleanUtil;

/**
 * 工单审批编排器。
 *
 * <p>职责：编排工单审批整体流程（入口校验、候选人校验、任务完成、终态判定、状态更新、事件发布）。 内部改用工单状态机、访问控制服务、展示组装器与审计服务，依赖收敛为组件。
 *
 * <p>事务边界说明：handleApproval 保留 {@code @Transactional}（与拆分前 WorkOrderServiceImpl 一致）， 咨询锁 + 乐观锁的并发正确性由此事务边界保证。
 *
 * @author KLord
 */
@Component
public class WorkOrderApprovalOrchestrator {

  private static final Logger logger =
      LoggerFactory.getLogger(WorkOrderApprovalOrchestrator.class);

  @Autowired private WorkOrderMapper workOrderMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private TaskService taskService;

  @Autowired private IFlowableQueryService flowableQueryService;

  @Autowired private WorkOrderStateMachine workOrderStateMachine;

  @Autowired private WorkOrderAccessService accessService;

  @Autowired private WorkOrderDisplayAssembler displayAssembler;

  @Autowired private WorkOrderAuditService auditService;

  /**
   * 审批操作（通过/驳回/退回等）。
   *
   * @param dto 审批 DTO
   * @param operatorId 操作人 ID
   * @return 审批后的工单
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> handleApproval(ApprovalDTO dto, Long operatorId) {
    // 0. XSS 净化（阶段 3 §3）：审批意见
    if (dto.getComment() != null) {
      dto.setComment(XssCleanUtil.cleanHtml(dto.getComment()));
    }

    // ========== 0.1 获取事务级咨询锁（OPTIMIZATION 二 数据一致性保障） ==========
    // 防止并发审批同一工单导致状态混乱
    workOrderMapper.acquireAdvisoryLock(dto.getWorkOrderId());

    // ========== 1. 数据验证与权限校验 ==========
    WorkOrder workOrder = workOrderMapper.selectById(dto.getWorkOrderId());
    if (workOrder == null) {
      logger.warn("[审批拒绝] 工单不存在 - ID: {}, 操作人ID: {}", dto.getWorkOrderId(), operatorId);
      throw new BusinessException("工单不存在");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较（仅 PENDING 允许审批）
    if (!WorkOrderStatusEnum.PENDING.getCode().equals(workOrder.getStatus())) {
      logger.warn(
          "[审批拒绝] 状态不匹配 - ID: {}, 当前状态: {}, 动作: {}",
          dto.getWorkOrderId(),
          workOrder.getStatus(),
          dto.getAction());
      throw new BusinessException("当前状态下无法审批，仅允许审批中的工单操作");
    }

    User operator = userMapper.selectById(operatorId);
    if (operator == null) {
      logger.warn("[审批拒绝] 操作人不存在 - ID: {}", operatorId);
      throw new BusinessException("审批人信息不存在");
    }

    // 数据级权限校验
    if (!accessService.canUserApproveWorkOrder(workOrder, operator)) {
      logger.warn(
          "[审批越权拦截] 用户 {} ({}, orgLevel={}, dept={}) 尝试审批非授权工单 ID={} (工单部门={})",
          operator.getRealName(),
          operator.getUsername(),
          operator.getOrgLevel(),
          operator.getDepartmentId(),
          workOrder.getId(),
          workOrder.getDepartmentId());
      throw new BusinessException("无权审批该工单（跨部门或权限不足）");
    }

    // ========== 2. 查找当前任务 ==========
    String taskId = accessService.findActiveTaskForUser(workOrder, operatorId);
    if (taskId == null) {
      logger.warn(
          "[审批拒绝] 未找到待办任务 - 工单ID: {}, 操作人ID: {}, 操作人: {}",
          dto.getWorkOrderId(),
          operatorId,
          operator.getRealName());
      throw new BusinessException("未找到当前待办任务或无权操作");
    }

    Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
    if (task == null) {
      throw new BusinessException("任务状态异常，请刷新页面重试");
    }

    // 候选人双重校验（§9 防任务哄抢）
    accessService.validateTaskCandidate(task, operatorId);

    // ========== 3. 解析审批动作（OPTIMIZATION：使用 ApprovalActionEnum） ==========
    ApprovalActionEnum actionEnum = ApprovalActionEnum.fromCode(dto.getAction());
    if (actionEnum == null) {
      logger.warn("[审批拒绝] 无效动作 - 工单ID: {}, 动作: {}", dto.getWorkOrderId(), dto.getAction());
      throw new BusinessException("无效的审批动作");
    }

    // 驳回原因必填校验
    if (actionEnum == ApprovalActionEnum.REJECT || actionEnum == ApprovalActionEnum.RETURN) {
      String comment = dto.getComment();
      if (comment == null || comment.trim().isEmpty()) {
        logger.warn(
            "[驳回拒绝] 驳回原因为空 - 工单ID: {}, 操作人: {}", dto.getWorkOrderId(), operator.getRealName());
        throw new BusinessException("驳回工单必须填写原因");
      }
      logger.info("[驳回校验通过] 工单ID: {}, 驳回原因长度: {}", dto.getWorkOrderId(), comment.trim().length());
    }

    // 确定审批结论（W-02 修复：中间节点通过不再直接置 APPROVED，
    // 最终状态由流程是否结束决定，见下方"流程结束终态判定"）
    Integer oldStatus = workOrder.getStatus();
    boolean approved;

    switch (actionEnum) {
      case APPROVE:
        approved = true;
        break;
      case REJECT:
      case RETURN:
        approved = false;
        break;
      default:
        logger.warn("[审批拒绝] 不支持的动作 - 工单ID: {}, 动作: {}", dto.getWorkOrderId(), dto.getAction());
        throw new BusinessException("不支持的审批动作: " + actionEnum.getDescription());
    }

    logger.info(
        "[审批操作] 开始 - 工单ID={}, 编号={}, 标题=\"{}\", 操作人={}({}), 动作={}, 节点={}",
        workOrder.getId(),
        workOrder.getOrderNo(),
        workOrder.getTitle(),
        operator.getRealName(),
        operator.getUsername(),
        actionEnum.getDescription(),
        task.getName());

    // ========== 4. 完成任务（携带流程变量） ==========
    Map<String, Object> variables = new HashMap<>();
    variables.put("approved", approved);
    variables.put("comment", dto.getComment());
    variables.put("action", actionEnum.name());

    try {
      taskService.complete(task.getId(), variables);
      logger.info(
          "[审批完成] 任务已流转 - TaskID={}, 节点=\"{}\", 工单ID={}",
          task.getId(),
          task.getName(),
          dto.getWorkOrderId());
    } catch (Exception e) {
      logger.error(
          "[审批失败] 任务处理异常 - TaskID={}, 工单ID={}, 错误: {}",
          task.getId(),
          dto.getWorkOrderId(),
          e.getMessage());
      throw new RuntimeException("任务处理失败: " + e.getMessage(), e);
    }

    // ========== 5. 检查流程是否完成（OPTIMIZATION 一：通过 flowableQueryService 解耦） ==========
    String processInstanceId = workOrder.getProcessInstanceId();
    boolean isProcessFinished = false;
    if (processInstanceId != null) {
      isProcessFinished = flowableQueryService.isProcessFinished(processInstanceId);
    }

    // ========== 5.1 流程结束终态判定（W-01/W-02 联动修复，fix-spec §3） ==========
    // 状态机：仅当流程实例结束后才落终态：
    //   - approved == true  → APPROVED
    //   - approved == false → REJECTED（BPMN 已由结果网关路由到 end_rejected）
    //   中间节点通过时流程未结束，工单保持 PENDING（审批中），下一节点继续审批。
    Integer newStatus =
        workOrderStateMachine.resolveApprovalFinalStatus(isProcessFinished, approved);
    if (isProcessFinished) {
      logger.info(
          "Workflow process completed - Process instance ID: {}, 终态: {}",
          processInstanceId,
          workOrderStateMachine.resolveStatusDesc(newStatus));
    } else {
      logger.info(
          "Workflow still running - Process instance ID: {}, 工单保持审批中", processInstanceId);
    }

    // 获取下一节点信息（用于显示，非持久化）
    String currentNode = null;
    Long currentAssigneeId = null;
    String currentAssigneeName = null;

    if (!isProcessFinished && processInstanceId != null) {
      WorkOrderDisplayAssembler.NextNodeInfo nextNode =
          displayAssembler.buildNextNodeInfo(processInstanceId);
      currentNode = nextNode.getNodeName();
      currentAssigneeId = nextNode.getAssigneeId();
      currentAssigneeName = nextNode.getAssigneeName();
    }

    // ========== 6. 更新工单业务状态（OPTIMIZATION 二：乐观锁更新） ==========
    workOrder.setStatus(newStatus);
    workOrder.setUpdateTime(Instant.now());
    // 流程结束时记录完成时间（终态 APPROVED/REJECTED）
    if (WorkOrderStatusEnum.APPROVED.getCode().equals(newStatus)
        || WorkOrderStatusEnum.REJECTED.getCode().equals(newStatus)) {
      workOrder.setCompleteTime(Instant.now());
    }
    // 填充下一节点显示字段
    workOrder.setCurrentNode(currentNode);
    workOrder.setCurrentAssignee(currentAssigneeId);
    workOrder.setCurrentAssigneeName(currentAssigneeName);

    int updateResult = workOrderStateMachine.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      logger.error(
          "Optimistic lock conflict - Order ID: {}, version: {}",
          dto.getWorkOrderId(),
          workOrder.getVersion());
      throw new OptimisticLockException(
          "工单状态更新失败（乐观锁冲突，请刷新后重试）", dto.getWorkOrderId(), "WorkOrder");
    }

    logger.info(
        "[状态更新] {} -> {} - 工单ID={}",
        workOrderStateMachine.resolveStatusDesc(oldStatus),
        workOrderStateMachine.resolveStatusDesc(newStatus),
        dto.getWorkOrderId());

    // ========== 7. 发布领域事件（OPTIMIZATION 一 架构解耦） ==========
    // 事件监听器（@Async + AFTER_COMMIT）异步处理：
    //   - 记录 APPROVE/REJECT 审计日志（包含 taskId、taskName）
    //   - 通知申请人
    //   - 触发后续业务联动（如报销打款）
    if (actionEnum == ApprovalActionEnum.APPROVE) {
      auditService.publishApproved(
          workOrder,
          oldStatus,
          newStatus,
          operatorId,
          operator.getRealName(),
          dto.getComment(),
          processInstanceId,
          task.getId(),
          task.getName());
    } else if (actionEnum == ApprovalActionEnum.REJECT || actionEnum == ApprovalActionEnum.RETURN) {
      auditService.publishRejected(
          workOrder,
          oldStatus,
          newStatus,
          operatorId,
          operator.getRealName(),
          dto.getComment(),
          processInstanceId,
          task.getId(),
          task.getName());
    }

    logger.info(
        "[审批完成] 最终状态={}, 当前节点={}, 下一处理人={}",
        workOrderStateMachine.resolveStatusDesc(newStatus),
        currentNode,
        currentAssigneeName);

    return Result.success(workOrder);
  }
}