package com.workorder.service.impl;


import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.enums.OrderTypeEnum;
import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.OptimisticLockException;
import com.workorder.common.result.Result;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.dto.WorkOrderDTO;
import com.workorder.entity.OrderProcessLink;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.service.IOrderProcessLinkService;
import com.workorder.service.IWorkOrderService;
import com.workorder.util.XssCleanUtil;

/**
 * 工单生命周期编排器。
 *
 * <p>职责：编排工单提交、重新提交、撤回、终止、归档等生命周期流程。 依赖收敛为状态机、展示组装器、审计服务、访问控制服务等组件，不再直接散装注入 Flowable
 * 运行时依赖。
 *
 * <p>事务边界说明：submitWorkOrder / submitNewWorkOrder / resubmitWorkOrder / terminateWorkOrder /
 * withdrawWorkOrder 保留 {@code @Transactional}（与拆分前 WorkOrderServiceImpl 一致）； archiveWorkOrder
 * 无事务（与拆分前一致）。submitNewWorkOrder 内部自调用 submitWorkOrder，二者共享同一事务。
 *
 * @author KLord
 */
@Component
public class WorkOrderLifecycleOrchestrator {

  private static final Logger logger =
      LoggerFactory.getLogger(WorkOrderLifecycleOrchestrator.class);

  // ========== 金额阈值常量 ==========
  /** 报销工单默认金额 */
  private static final double REIMBURSEMENT_AMOUNT = 5000.0;
  /** 采购工单默认金额 */
  private static final double PURCHASE_AMOUNT = 15000.0;
  /** 大额工单阈值（需高管层审批） */
  private static final double LARGE_AMOUNT_THRESHOLD = 10000.0;
  /** 超大额工单阈值（需决策层终审） */
  private static final double VERY_LARGE_AMOUNT_THRESHOLD = 50000.0;

  @Autowired private WorkOrderMapper workOrderMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Autowired private RuntimeService runtimeService;

  @Autowired private TaskService taskService;

  @Autowired private IOrderProcessLinkService orderProcessLinkService;

  @Autowired private WorkOrderStateMachine workOrderStateMachine;

  @Autowired private WorkOrderDisplayAssembler displayAssembler;

  @Autowired private WorkOrderAuditService auditService;

  @Autowired private WorkOrderAccessService accessService;

  /** 门面服务（惰性注入，仅用于 submitNewWorkOrder 复用 createDraft，规避循环依赖） */
  @Lazy @Autowired private IWorkOrderService workOrderService;

  /**
   * 提交工单（启动流程）。
   *
   * @param workOrderId 工单 ID
   * @param applicantId 申请人 ID
   * @return 提交后的工单
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> submitWorkOrder(Long workOrderId, Long applicantId) {
    logger.info("========== Starting order submission (Enterprise 6-layer architecture) ========");
    logger.info("Order ID: {}, Applicant ID: {}", workOrderId, applicantId);

    // ========== 0. 获取事务级咨询锁（OPTIMIZATION 二 数据一致性保障） ==========
    // 防止并发提交同一工单导致流程实例重复创建
    workOrderMapper.acquireAdvisoryLock(workOrderId);

    // ========== 1. 数据验证阶段 ==========
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      logger.error("Order does not exist - ID: {}", workOrderId);
      throw new BusinessException("工单不存在");
    }

    if (!workOrder.getApplicantId().equals(applicantId)) {
      logger.warn(
          "Insufficient permissions - Order applicant {}, Current operator {}",
          workOrder.getApplicantId(),
          applicantId);
      throw new BusinessException("无权操作此工单");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较
    Integer currentStatus = workOrder.getStatus();
    if (!WorkOrderStatusEnum.DRAFT.getCode().equals(currentStatus)
        && !WorkOrderStatusEnum.REJECTED.getCode().equals(currentStatus)) {
      logger.warn("Status not allowed for submission - Current status: {}", currentStatus);
      throw new BusinessException("当前状态下无法提交，仅允许草稿或已退回状态的工单提交");
    }

    // ========== 2. 获取申请人信息并分析审批规则 ==========
    User applicant = userMapper.selectById(applicantId);
    Integer orderTypeCode = workOrder.getOrderType();
    String orderTypeDesc = resolveOrderTypeDesc(orderTypeCode);
    Double amount = determineAmount(workOrder);
    logger.info(
        "Order type: {}({}), Estimated amount: {}, Applicant orgLevel: {}",
        orderTypeCode,
        orderTypeDesc,
        amount,
        applicant != null ? applicant.getOrgLevel() : "unknown");

    // 根据申请人组织层级和业务规则确定审批链（支持高管跳转）
    ApprovalChainConfig chainConfig =
        buildApprovalChain(orderTypeCode, amount, applicant, workOrder.getPriority());
    logger.info("Approval chain configuration: {}", chainConfig.getDescription());

    // ========== 3. 流程启动阶段（OPTIMIZATION 一：work_order 不再持久化 process_instance_id） ==========
    Map<String, Object> variables = new HashMap<>();
    variables.put("applicantId", applicantId.toString());
    variables.put("workOrderId", workOrderId);
    variables.put("orderType", orderTypeCode != null ? orderTypeCode.toString() : null);
    variables.put("amount", amount);
    variables.put("priority", workOrder.getPriority());
    variables.put("needSpecialistReview", chainConfig.isNeedSpecialistReview());
    variables.put("needDirectorApproval", chainConfig.isNeedDirectorApproval());
    variables.put("needExecutiveApproval", chainConfig.isNeedExecutiveApproval());
    variables.put("applicantOrgLevel", applicant != null ? applicant.getOrgLevel() : 4);

    ProcessInstance processInstance;
    try {
      // 流程变量净化（§9 流程变量安全）
      variables = sanitizeProcessVariables(variables);
      processInstance =
          runtimeService.startProcessInstanceByKey(
              "enterprise_approval_process", workOrder.getOrderNo(), variables);
      logger.info(
          "Enterprise-level workflow started successfully - Process instance ID: {}",
          processInstance.getId());
    } catch (Exception e) {
      logger.error(
          "Process startup failed - Order ID: {}, Error: {}", workOrderId, e.getMessage(), e);
      throw new RuntimeException("Process startup failed: " + e.getMessage(), e);
    }

    // ========== 4. 自动完成初始任务 ==========
    try {
      List<Task> initialTasks =
          taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().list();

      for (Task task : initialTasks) {
        if ("提交申请".equals(task.getName())) {
          logger.info(
              "Automatically complete 'Submit Application' task - Task ID: {}", task.getId());
          taskService.complete(task.getId());
        }
      }
    } catch (Exception e) {
      logger.error(
          "Failed to automatically complete initial task - Process instance ID: {}",
          processInstance.getId(),
          e);
      throw new RuntimeException("Task processing failed: " + e.getMessage(), e);
    }

    // ========== 5. 获取当前审批节点信息（用于显示，不持久化到 work_order） ==========
    String currentNode = null;
    Long currentAssigneeId = null;
    String currentAssigneeName = null;

    try {
      List<Task> activeTasks =
          taskService.createTaskQuery().processInstanceId(processInstance.getId()).active().list();

      if (!activeTasks.isEmpty()) {
        Task currentTask = activeTasks.get(0);
        currentNode = currentTask.getName();
        logger.info(
            "Current node: {} (Approval level: {})",
            currentNode,
            displayAssembler.getApprovalLevelName(currentTask));

        if (currentTask.getAssignee() != null) {
          currentAssigneeId = Long.parseLong(currentTask.getAssignee());
          User assigneeUser = userMapper.selectById(currentAssigneeId);
          if (assigneeUser != null) {
            currentAssigneeName = assigneeUser.getRealName();
            logger.info("Current approver: ID={}, Name={}", currentAssigneeId, currentAssigneeName);
          }
        }
      } else {
        logger.warn("No active tasks found - Process may be completed or abnormally terminated");
      }
    } catch (Exception e) {
      logger.error("Failed to get current node information", e);
    }

    // ========== 6. 更新工单业务状态（OPTIMIZATION 二：使用乐观锁更新） ==========
    Integer oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatusEnum.PENDING.getCode());
    workOrder.setSubmitTime(Instant.now());
    workOrder.setUpdateTime(Instant.now());

    int updateResult = workOrderStateMachine.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      logger.error(
          "Optimistic lock conflict - Order ID: {}, version: {}",
          workOrderId,
          workOrder.getVersion());
      // 流程实例已启动但工单状态未更新，需要回滚事务（包括流程实例）
      throw new OptimisticLockException("工单状态更新失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    logger.info(
        "Order status updated successfully - New status: PENDING({})",
        WorkOrderStatusEnum.PENDING.getCode());

    // 填充显示字段（非持久化）
    workOrder.setProcessInstanceId(processInstance.getId());
    workOrder.setCurrentNode(currentNode);
    workOrder.setCurrentAssignee(currentAssigneeId);
    workOrder.setCurrentAssigneeName(currentAssigneeName);

    // ========== 6.1 W-03/W-13 修复：主事务内同步持久化 order_process_link ==========
    // 原实现依赖 @Async + AFTER_COMMIT 事件创建关联，提交后宕机即失联；
    // 现改为主事务内同步写入（createLink 幂等：恢复/更新/插入），失败抛异常回滚整事务。
    orderProcessLinkService.createLink(
        workOrderId, processInstance.getId(), processInstance.getProcessDefinitionId());

    // ========== 7. 发布领域事件（OPTIMIZATION 一 架构解耦） ==========
    // 事件监听器（@Async + AFTER_COMMIT）异步处理：
    //   - 记录 SUBMIT 审计日志
    //   - 通知申请人
    // 主业务流程不等待事件处理完成，关联记录缺失由 P4 对账任务修复
    auditService.publishSubmitted(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.PENDING.getCode(),
        applicantId,
        workOrder.getApplicantName(),
        "Submit work order application | Approval chain: " + chainConfig.getDescription(),
        processInstance.getId(),
        processInstance.getProcessDefinitionId());

    logger.info("========== Order submission completed (Enterprise 6-layer architecture) ========");
    logger.info(
        "Final status: PENDING | Current node: {} | Approver: {}",
        currentNode,
        currentAssigneeName);

    return Result.success(workOrder);
  }

  /**
   * 提交新工单（先创建草稿，再提交）。
   *
   * @param dto 工单提交 DTO
   * @param applicantId 申请人 ID
   * @return 提交后的工单
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> submitNewWorkOrder(WorkOrderDTO dto, Long applicantId) {
    // 先创建草稿，再提交
    // 事务边界说明：拆分前为同 bean 自调用 createDraft + submitWorkOrder，共享 submitNewWorkOrder 事务；
    // 拆分后 createDraft 保留在门面（无 @Transactional），跨 bean 调用 join 本方法事务，
    // submitWorkOrder 为本类自调用（@Transactional 代理被绕过，同样运行在本方法事务内）——边界不变。
    Result<WorkOrder> draftResult = workOrderService.createDraft(dto, applicantId);
    return submitWorkOrder(draftResult.getData().getId(), applicantId);
  }

  /**
   * 重新提交被驳回的工单。
   *
   * @param workOrderId 工单 ID
   * @param applicantId 申请人 ID
   * @param comment 备注
   * @return 提交后的工单
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> resubmitWorkOrder(Long workOrderId, Long applicantId, String comment) {
    // 0. XSS 净化
    String sanitizedComment = XssCleanUtil.cleanHtml(comment);

    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }

    if (!workOrder.getApplicantId().equals(applicantId)) {
      throw new BusinessException("无权操作此工单");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较
    if (!WorkOrderStatusEnum.REJECTED.getCode().equals(workOrder.getStatus())) {
      throw new BusinessException("只有已退回的工单可以重新提交");
    }

    // 重置为草稿状态（使用乐观锁）
    Integer oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatusEnum.DRAFT.getCode());
    workOrder.setUpdateTime(Instant.now());
    int updateResult = workOrderStateMachine.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单状态更新失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    // 记录 RESUBMIT 审计日志（此处直接插入，因为 P3 事件类未涵盖 RESUBMIT）
    // 后续 submitWorkOrder 调用会自动触发 SubmittedEvent，记录 SUBMIT 审计日志
    auditService.recordResubmitLog(workOrder, sanitizedComment, applicantId);

    return submitWorkOrder(workOrderId, applicantId);
  }

  /**
   * 终止流程。
   *
   * @param workOrderId 工单 ID
   * @param operatorId 操作人 ID
   * @param reason 终止原因
   * @return 终止后的工单
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> terminateWorkOrder(Long workOrderId, Long operatorId, String reason) {
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较
    if (!WorkOrderStatusEnum.PENDING.getCode().equals(workOrder.getStatus())) {
      throw new BusinessException("当前状态不允许终止");
    }

    // 权限校验：仅高管级(orgLevel<=1)可终止工单
    User operator = userMapper.selectById(operatorId);
    if (operator != null && operator.getOrgLevel() != null && operator.getOrgLevel() > 1) {
      logger.warn(
          "[终止拒绝] 用户 {} ({}, orgLevel={}) 无权终止工单 - 仅高管级可终止",
          operator.getRealName(),
          operator.getUsername(),
          operator.getOrgLevel());
      throw new BusinessException("无权终止工单（仅高管级可执行此操作）");
    }

    // 删除流程实例（OPTIMIZATION 一：通过 order_process_link 获取流程实例）
    OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrderId);
    String processInstanceId = null;
    if (link != null && link.getProcessInstanceId() != null) {
      try {
        runtimeService.deleteProcessInstance(link.getProcessInstanceId(), reason);
        processInstanceId = link.getProcessInstanceId();
        workOrder.setProcessInstanceId(processInstanceId);
      } catch (Exception e) {
        logger.warn("Failed to delete process instance: {}", e.getMessage());
      }
      // 关联记录的逻辑删除由事件监听器统一处理
    }

    // 更新工单状态（使用乐观锁）
    Integer oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatusEnum.TERMINATED.getCode());
    workOrder.setUpdateTime(Instant.now());
    int updateResult = workOrderStateMachine.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单状态更新失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    // 发布工单终止事件（OPTIMIZATION 一 架构解耦）
    // 监听器异步处理：逻辑删除 order_process_link、记录审计日志、通知申请人
    auditService.publishTerminated(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.TERMINATED.getCode(),
        operatorId,
        operator != null ? operator.getRealName() : "unknown",
        "Terminated by admin: " + reason,
        processInstanceId);

    return Result.success(workOrder);
  }

  /**
   * 归档工单。
   *
   * @param workOrderId 工单 ID
   * @return 归档后的工单
   */
  public Result<WorkOrder> archiveWorkOrder(Long workOrderId) {
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较
    Integer status = workOrder.getStatus();
    if (!WorkOrderStatusEnum.APPROVED.getCode().equals(status)
        && !WorkOrderStatusEnum.TERMINATED.getCode().equals(status)) {
      throw new BusinessException("只有已完成或已终止的工单可以归档");
    }

    Integer oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatusEnum.ARCHIVED.getCode());
    workOrder.setUpdateTime(Instant.now());
    workOrderMapper.update(workOrder);

    // 发布归档事件（OPTIMIZATION 一 架构解耦）
    // 监听器异步处理：记录 ARCHIVE 审计日志
    auditService.publishArchived(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.ARCHIVED.getCode(),
        null,
        "system",
        "Order archived",
        null);

    return Result.success(workOrder);
  }

  /**
   * 归档工单（带操作人 ID，二次所有权校验，阶段 2 修复 A-11 / H-07）。
   *
   * @param workOrderId 工单 ID
   * @param userId 当前操作用户 ID
   * @return 归档后的工单
   */
  public Result<WorkOrder> archiveWorkOrder(Long workOrderId, Long userId) {
    logger.info("归档工单 - workOrderId: {}, 操作人: {}", workOrderId, userId);
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }
    if (!accessService.canUserViewWorkOrder(workOrder, userId)) {
      logger.warn("IDOR拦截: 用户 {} 尝试归档无权访问的工单 {}", userId, workOrderId);
      throw new BusinessException("您没有权限归档此工单");
    }

    Integer status = workOrder.getStatus();
    if (!WorkOrderStatusEnum.APPROVED.getCode().equals(status)
        && !WorkOrderStatusEnum.TERMINATED.getCode().equals(status)) {
      throw new BusinessException("只有已完成或已终止的工单可以归档");
    }

    User operator = userMapper.selectById(userId);
    Integer oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatusEnum.ARCHIVED.getCode());
    workOrder.setUpdateTime(Instant.now());
    workOrderMapper.update(workOrder);

    // 发布归档事件（OPTIMIZATION 一 架构解耦）
    // 监听器异步处理：记录 ARCHIVE 审计日志
    auditService.publishArchived(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.ARCHIVED.getCode(),
        userId,
        operator != null ? operator.getRealName() : "unknown",
        "Order archived",
        null);

    return Result.success(workOrder);
  }

  /**
   * 撤回工单（将 PENDING 状态改为 DRAFT）。
   *
   * @param workOrderId 工单 ID
   * @param userId 申请人 ID
   * @return 撤回后的工单
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> withdrawWorkOrder(Long workOrderId, Long userId) {
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }

    if (!workOrder.getApplicantId().equals(userId)) {
      throw new BusinessException("只有申请人可以撤回工单");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较
    if (!WorkOrderStatusEnum.PENDING.getCode().equals(workOrder.getStatus())) {
      throw new BusinessException("当前状态不允许撤回");
    }

    // 删除流程实例（OPTIMIZATION 一：通过 order_process_link 获取）
    OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrderId);
    String processInstanceId = null;
    if (link != null && link.getProcessInstanceId() != null) {
      try {
        runtimeService.deleteProcessInstance(link.getProcessInstanceId(), "Withdrawn by applicant");
        processInstanceId = link.getProcessInstanceId();
        workOrder.setProcessInstanceId(processInstanceId);
      } catch (Exception e) {
        logger.warn("Failed to delete process instance: {}", e.getMessage());
      }
      // 关联记录的逻辑删除由事件监听器统一处理
    }

    // 更新工单状态为草稿（使用乐观锁）
    Integer oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatusEnum.DRAFT.getCode());
    workOrder.setUpdateTime(Instant.now());
    int updateResult = workOrderStateMachine.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单撤回失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    // 发布工单撤回事件（OPTIMIZATION 一 架构解耦）
    // 监听器异步处理：逻辑删除 order_process_link、记录审计日志、通知审批人
    auditService.publishWithdrawn(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.DRAFT.getCode(),
        userId,
        workOrder.getApplicantName(),
        "Applicant withdrew order",
        processInstanceId);

    return Result.success("工单撤回成功", workOrder);
  }

  /**
   * 构建审批链配置（企业级增强版，OPTIMIZATION：orderType 改为 Integer 枚举码）。
   *
   * <p>规则： 1. 报销/采购/维修/补货工单需要专项审核 2. 金额 &gt;= 10000 或 高优先级(&gt;=3) 或 申请人级别较高 → 需要高管层（总监）审批 3.
   * 金额 &gt;= 50000 或 紧急优先级(4) 或 申请人为副总及以上 → 需要决策层（总经理/董事长）终审 4. 董事长/总经理提单 →
   * 自动跳过低层级节点（由DynamicTaskAssigner处理跳过逻辑）
   *
   * @param orderTypeCode 工单类型码
   * @param amount 金额
   * @param applicant 申请人
   * @param priority 优先级
   * @return 审批链配置
   */
  private ApprovalChainConfig buildApprovalChain(
      Integer orderTypeCode, Double amount, User applicant, Integer priority) {
    boolean needSpecialist = false;
    boolean needDirector = false;
    boolean needExecutive = false;
    StringBuilder description = new StringBuilder();

    Integer orgLevel = (applicant != null) ? applicant.getOrgLevel() : 4;
    Integer priorityVal = (priority != null) ? priority : 2;

    // === 1. 是否需要专项审核（Level 4 专业职能岗）===
    if (OrderTypeEnum.REIMBURSEMENT.getCode() == orderTypeCode
        || OrderTypeEnum.PURCHASE.getCode() == orderTypeCode
        || OrderTypeEnum.REPAIR.getCode() == orderTypeCode
        || OrderTypeEnum.SUPPLY.getCode() == orderTypeCode) {
      needSpecialist = true;
      description.append("[专项审核]");
    }

    // === 2. 基础：部门经理审批 ===
    description.insert(0, "[部门经理审批]");

    // === 3. 是否需要高管层审批（总监级）===
    boolean isLargeAmount = amount != null && amount >= LARGE_AMOUNT_THRESHOLD;
    boolean isHighPriority = priorityVal >= 3;
    boolean isManagement = orgLevel != null && orgLevel <= 2;

    if (isLargeAmount || isHighPriority || isManagement) {
      needDirector = true;
      description.append("[高管层审批]");
    }

    // === 4. 是否需要决策层终审（总经理/董事长）===
    boolean isVeryLargeAmount = amount != null && amount >= VERY_LARGE_AMOUNT_THRESHOLD;
    boolean isUrgentPriority = priorityVal == 4;
    boolean isExecutive = orgLevel != null && orgLevel <= 1;

    if (isVeryLargeAmount || isUrgentPriority || isExecutive) {
      needExecutive = true;
      if (isExecutive) {
        description.append("[决策层终审-高管提单]");
      } else {
        description.append("[决策层终审]");
      }
    }

    if (description.length() == 0) {
      description.append("[标准审批]");
    }

    return new ApprovalChainConfig(
        needSpecialist, needDirector, needExecutive, description.toString());
  }

  /** 根据工单类型码确定金额（OPTIMIZATION：使用 Integer 枚举码比较） */
  private Double determineAmount(WorkOrder workOrder) {
    Integer orderType = workOrder.getOrderType();
    if (orderType == null) return 0.0;

    if (OrderTypeEnum.REIMBURSEMENT.getCode() == orderType) {
      return REIMBURSEMENT_AMOUNT;
    } else if (OrderTypeEnum.PURCHASE.getCode() == orderType) {
      return PURCHASE_AMOUNT;
    } else if (OrderTypeEnum.REPAIR.getCode() == orderType) {
      return 3000.0;
    } else if (OrderTypeEnum.SUPPLY.getCode() == orderType) {
      return 1000.0;
    }
    return 0.0;
  }

  /** 获取工单类型描述（用于日志） */
  private String resolveOrderTypeDesc(Integer orderTypeCode) {
    if (orderTypeCode == null) return "UNKNOWN";
    OrderTypeEnum typeEnum = OrderTypeEnum.fromCode(orderTypeCode);
    return typeEnum != null ? typeEnum.getDescription() : "UNKNOWN";
  }

  /** 净化流程变量：移除含敏感关键字的 key（§9 流程变量安全） */
  private Map<String, Object> sanitizeProcessVariables(Map<String, Object> variables) {
    if (variables == null) {
      return new HashMap<>();
    }
    Map<String, Object> safe = new HashMap<>();
    for (Map.Entry<String, Object> entry : variables.entrySet()) {
      String key = entry.getKey();
      if (key == null) {
        continue;
      }
      String lowerKey = key.toLowerCase();
      if (lowerKey.contains("password")
          || lowerKey.contains("secret")
          || lowerKey.contains("token")
          || lowerKey.contains("credential")
          || lowerKey.contains("privatekey")
          || lowerKey.contains("apikey")
          || lowerKey.contains("accesskey")) {
        logger.warn("🚫 流程变量 [{}] 含敏感关键字，已剔除（§9 流程变量安全）", key);
        continue;
      }
      safe.put(key, entry.getValue());
    }
    return safe;
  }
}