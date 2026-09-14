package com.workorder.service.impl;


import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.enums.ApprovalActionEnum;
import com.workorder.common.enums.OrderTypeEnum;
import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.event.WorkOrderEventPublisher;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.OptimisticLockException;
import com.workorder.common.result.PageRequest;
import com.workorder.common.result.PageResult;
import com.workorder.common.result.Result;
import com.workorder.dao.ApprovalLogMapper;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.dto.ApprovalDTO;
import com.workorder.dto.TaskInfoDTO;
import com.workorder.dto.WorkOrderDTO;
import com.workorder.entity.ApprovalLog;
import com.workorder.entity.OrderProcessLink;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.service.IFlowableQueryService;
import com.workorder.service.IWorkOrderService;
import com.workorder.util.XssCleanUtil;

/**
 * Work Order Service Implementation 核心业务逻辑：工单 CRUD、流程启动、审批、状态管理
 *
 * <p>OPTIMIZATION 改造（2026-07-26）： 1. 架构解耦：work_order 表移除 5 个 Flowable 运行时字段，改由 order_process_link
 * 承载； 显示用 currentNode/currentAssigneeName 等字段由服务层动态查询填充（非持久化） 2. 数据一致性：基于 version
 * 字段的乐观锁（updateWithVersion），并发审批防覆盖 3. 枚举字典化：status/order_type/action 全部改为 Integer（对应枚举类） 4.
 * 时间类型：LocalDateTime → Instant（对应 TIMESTAMPTZ）
 *
 * @author KLord
 */
@Service
public class WorkOrderServiceImpl implements IWorkOrderService {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderServiceImpl.class);

  // ========== 金额与编号常量 ==========
  /** 工单编号随机数上界 */
  private static final int ORDER_NO_RANDOM_BOUND = 10000;
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

  @Autowired private ApprovalLogMapper approvalLogMapper;

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Autowired private RuntimeService runtimeService;

  @Autowired private TaskService taskService;

  @Autowired private HistoryService historyService;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  /** OPTIMIZATION 一 架构解耦： - 事件发布器：集中发布工单生命周期事件 - Flowable 查询服务：封装 Flowable API 调用，业务层不直接依赖流程引擎 */
  @Autowired private WorkOrderEventPublisher workOrderEventPublisher;

  @Autowired private IFlowableQueryService flowableQueryService;

  /** 生成唯一工单编号 */
  private String generateOrderNo() {
    return "WO" + System.currentTimeMillis() + String.format("%04d", new Random().nextInt(ORDER_NO_RANDOM_BOUND));
  }

  @Override
  public Result<WorkOrder> createDraft(WorkOrderDTO dto, Long applicantId) {
    // 0. XSS 净化（阶段 3 §3）：标题/内容/备注
    sanitizeWorkOrderDTO(dto);

    // 1. 查询申请人信息
    User user = userMapper.selectById(applicantId);
    if (user == null) {
      throw new BusinessException("用户不存在");
    }

    // 2. 优先级权限校验：普通员工选高/紧急优先级必须在内容中填写紧急原因
    validatePriorityPermission(dto, user);

    // 3. 文件格式安全校验：禁止压缩包、exe等危险格式
    validateAttachmentSafety(dto);

    // 4. 构建工单对象（OPTIMIZATION：status 改为 Integer 枚举码）
    WorkOrder workOrder = new WorkOrder();
    workOrder.setOrderNo(generateOrderNo());
    workOrder.setTitle(dto.getTitle());
    workOrder.setContent(dto.getContent());
    // OPTIMIZATION 四.4.2：orderType 从 String 转为 Integer 枚举码
    workOrder.setOrderType(resolveOrderTypeCode(dto.getOrderType()));
    workOrder.setPriority(dto.getPriority());
    workOrder.setApplicantId(applicantId);
    workOrder.setApplicantName(user.getRealName());
    workOrder.setDepartment(
        dto.getDepartment() != null ? dto.getDepartment() : user.getDepartment());
    workOrder.setDepartmentId(
        dto.getDepartmentId() != null ? dto.getDepartmentId() : user.getDepartmentId());
    workOrder.setStatus(WorkOrderStatusEnum.DRAFT.getCode());
    workOrder.setAttachmentUrl(dto.getAttachmentUrl());
    workOrder.setRemark(dto.getRemark());
    workOrder.setSubmitTime(Instant.now());

    // 5. 保存到数据库
    workOrderMapper.insert(workOrder);

    logger.info(
        "Created draft successfully - Order ID: {}, Number: {}",
        workOrder.getId(),
        workOrder.getOrderNo());

    return Result.success(workOrder);
  }

  @Override
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
            getApprovalLevelName(currentTask));

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

    int updateResult = workOrderMapper.updateWithVersion(workOrder);
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

    // ========== 7. 发布领域事件（OPTIMIZATION 一 架构解耦） ==========
    // 事件监听器（@Async + AFTER_COMMIT）异步处理：
    //   - 创建 order_process_link 关联记录
    //   - 记录 SUBMIT 审计日志
    //   - 通知申请人
    // 主业务流程不等待事件处理完成，关联记录缺失由 P4 对账任务修复
    workOrderEventPublisher.publishSubmitted(
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
   * 优先级权限校验 - 普通员工（org_level >= 4）选择高(3)或紧急(4)优先级时，必须在内容中填写紧急原因（至少20字） - 部门负责人及以上（org_level <=
   * 2）可自由选择任意优先级
   */
  private void validatePriorityPermission(WorkOrderDTO dto, User user) {
    Integer priority = dto.getPriority();
    if (priority == null || priority < 3) return;

    Integer orgLevel = user.getOrgLevel();
    if (orgLevel == null) orgLevel = 4;

    if (orgLevel <= 2) return;

    String content = dto.getContent();
    if (content == null || content.trim().length() < 20) {
      throw new BusinessException(
          "选择「高」或「紧急」优先级时，请在工单内容中详细说明紧急原因（至少20个字），" + "例如：因XX项目上线在即，需要紧急审批。如需更高权限，请联系部门负责人提交。");
    }

    String lowerContent = content.toLowerCase();
    boolean hasReason =
        lowerContent.contains("原因")
            || lowerContent.contains("紧急")
            || lowerContent.contains("急需")
            || lowerContent.contains("重要")
            || lowerContent.contains("由于")
            || lowerContent.contains("因为");
    if (!hasReason && content.length() < 50) {
      throw new BusinessException("选择「高」或「紧急」优先级时，请在内容中说明具体的紧急原因和背景。");
    }
  }

  /**
   * 文件附件安全校验 按工单类型（Integer 枚举码）限制允许的文件格式 全局禁止：exe, bat, cmd, sh, ps1, vbs, js, jar, zip, rar, 7z
   * 等危险格式
   */
  private void validateAttachmentSafety(WorkOrderDTO dto) {
    String attachmentUrl = dto.getAttachmentUrl();
    if (attachmentUrl == null || attachmentUrl.isEmpty()) return;

    String[] dangerousExts = {
        "exe", "bat", "cmd", "sh", "ps1", "vbs", "js", "jar", "zip", "rar", "7z", "tar", "gz", "bz2",
        "msi", "scr", "com", "pif", "hta", "cpl"
    };

    String fileName = attachmentUrl.toLowerCase();
    for (String ext : dangerousExts) {
      if (fileName.endsWith("." + ext)) {
        throw new BusinessException("不允许上传 ." + ext.toUpperCase() + " 格式的文件，请选择安全的文档或图片格式。");
      }
    }

    // OPTIMIZATION：orderType 改为 Integer 枚举码
    Integer orderTypeCode = resolveOrderTypeCode(dto.getOrderType());
    if (orderTypeCode != null) {
      if (OrderTypeEnum.LEAVE.getCode() == orderTypeCode) {
        // 请假：仅允许图片和PDF
        if (!isAllowedExtension(fileName, new String[] {"jpg", "jpeg", "png", "gif", "pdf"})) {
          throw new BusinessException("请假工单仅支持上传图片(JPG/PNG/GIF)或PDF文件。");
        }
      } else if (OrderTypeEnum.REIMBURSEMENT.getCode() == orderTypeCode
          || OrderTypeEnum.PURCHASE.getCode() == orderTypeCode) {
        // 报销/采购：允许图片、PDF、Excel
        if (!isAllowedExtension(
            fileName, new String[] {"jpg", "jpeg", "png", "gif", "pdf", "xlsx", "xls", "csv"})) {
          throw new BusinessException("报销/采购工单仅支持图片、PDF或Excel文件。");
        }
      } else if (OrderTypeEnum.REPAIR.getCode() == orderTypeCode) {
        // 报修：允许图片、PDF、Word
        if (!isAllowedExtension(
            fileName, new String[] {"jpg", "jpeg", "png", "gif", "pdf", "docx", "doc"})) {
          throw new BusinessException("报修工单仅支持图片、PDF或Word文件。");
        }
      } else {
        // 其他类型：允许常见办公文档和图片
        if (!isAllowedExtension(
            fileName,
            new String[] {
                "jpg", "jpeg", "png", "gif", "pdf", "docx", "doc", "xlsx", "xls", "txt", "csv"
            })) {
          throw new BusinessException("该类型工单仅支持常见的文档、图片或表格格式。");
        }
      }
    }
  }

  private boolean isAllowedExtension(String fileName, String[] allowedExts) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0) return true;
    String ext = fileName.substring(dotIndex + 1);
    for (String allowed : allowedExts) {
      if (allowed.equalsIgnoreCase(ext)) return true;
    }
    return false;
  }

  /**
   * 解析工单类型为枚举码（OPTIMIZATION 四.4.2：兼容 String 旧值与 Integer 新值） 前端传入字符串如 "LEAVE" 或数字字符串 "1"，统一转为
   * Integer 枚举码
   */
  private Integer resolveOrderTypeCode(String orderType) {
    if (orderType == null || orderType.isEmpty()) return null;
    OrderTypeEnum typeEnum = OrderTypeEnum.fromCode(orderType);
    return typeEnum != null ? typeEnum.getCode() : null;
  }

  /** 解析工单状态为枚举码（用于前端查询参数兼容） */
  private Integer resolveStatusCode(String status) {
    if (status == null || status.isEmpty()) return null;
    WorkOrderStatusEnum statusEnum = WorkOrderStatusEnum.fromCode(status);
    return statusEnum != null ? statusEnum.getCode() : null;
  }

  /** 获取工单类型描述（用于日志） */
  private String resolveOrderTypeDesc(Integer orderTypeCode) {
    if (orderTypeCode == null) return "UNKNOWN";
    OrderTypeEnum typeEnum = OrderTypeEnum.fromCode(orderTypeCode);
    return typeEnum != null ? typeEnum.getDescription() : "UNKNOWN";
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

  /**
   * 构建审批链配置（企业级增强版，OPTIMIZATION：orderType 改为 Integer 枚举码）
   *
   * <p>规则： 1. 报销/采购/维修/补货工单需要专项审核 2. 金额 >= 10000 或 高优先级(>=3) 或 申请人级别较高 → 需要高管层（总监）审批 3. 金额 >= 50000
   * 或 紧急优先级(4) 或 申请人为副总及以上 → 需要决策层（总经理/董事长）终审 4. 董事长/总经理提单 → 自动跳过低层级节点（由DynamicTaskAssigner处理跳过逻辑）
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

  /** 获取审批层级名称 */
  private String getApprovalLevelName(Task task) {
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

  /** 审批链配置内部类 */
  private static class ApprovalChainConfig {
    private final boolean needSpecialistReview;
    private final boolean needDirectorApproval;
    private final boolean needExecutiveApproval;
    private final String description;

    public ApprovalChainConfig(
        boolean needSpecialistReview,
        boolean needDirectorApproval,
        boolean needExecutiveApproval,
        String description) {
      this.needSpecialistReview = needSpecialistReview;
      this.needDirectorApproval = needDirectorApproval;
      this.needExecutiveApproval = needExecutiveApproval;
      this.description = description;
    }

    public boolean isNeedSpecialistReview() {
      return needSpecialistReview;
    }

    public boolean isNeedDirectorApproval() {
      return needDirectorApproval;
    }

    public boolean isNeedExecutiveApproval() {
      return needExecutiveApproval;
    }

    public String getDescription() {
      return description;
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Result<WorkOrder> submitNewWorkOrder(WorkOrderDTO dto, Long applicantId) {
    // 先创建草稿，再提交
    Result<WorkOrder> draftResult = createDraft(dto, applicantId);
    return submitWorkOrder(draftResult.getData().getId(), applicantId);
  }

  @Override
  public Result<WorkOrder> getWorkOrderById(Long id, Long currentUserId) {
    logger.info("查询工单详情 - ID: {}, 用户ID: {}", id, currentUserId);
    try {
      WorkOrder workOrder = workOrderMapper.selectById(id);
      if (workOrder == null) {
        throw new BusinessException("工单不存在");
      }

      // IDOR防护：校验当前用户是否有权限查看此工单
      if (!canUserViewWorkOrder(workOrder, currentUserId)) {
        logger.warn("IDOR拦截: 用户 {} 尝试访问无权查看的工单 {}", currentUserId, id);
        throw new BusinessException("无权查看该工单");
      }

      // OPTIMIZATION 一：动态填充显示字段（从 order_process_link + Flowable）
      populateProcessDisplayFields(workOrder);

      return Result.success(workOrder);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      logger.error("查询工单详情失败 - ID: {}", id, e);
      throw new RuntimeException("查询工单详情失败", e);
    }
  }

  @Override
  public Result<WorkOrder> getWorkOrderByNo(String orderNo) {
    WorkOrder workOrder = workOrderMapper.selectByOrderNo(orderNo);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }
    populateProcessDisplayFields(workOrder);
    return Result.success(workOrder);
  }

  /** 阶段 2 修复 A-11 / H-07：根据工单编号查询（带所有权校验，防 IDOR） */
  @Override
  public Result<WorkOrder> getWorkOrderByNo(String orderNo, Long currentUserId) {
    logger.info("查询工单详情（按编号）- orderNo: {}, 用户ID: {}", orderNo, currentUserId);
    WorkOrder workOrder = workOrderMapper.selectByOrderNo(orderNo);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }
    if (!canUserViewWorkOrder(workOrder, currentUserId)) {
      logger.warn("IDOR拦截: 用户 {} 尝试访问无权查看的工单 orderNo={}", currentUserId, orderNo);
      throw new BusinessException("您没有权限访问此工单");
    }
    // OPTIMIZATION 一：动态填充显示字段
    populateProcessDisplayFields(workOrder);
    return Result.success(workOrder);
  }

  @Override
  public Result<PageResult<WorkOrder>> getMyWorkOrders(
      PageRequest pageRequest, Long applicantId, String status, String orderType, String keyword) {
    // OPTIMIZATION：String 转 Integer 枚举码
    Integer statusCode = resolveStatusCode(status);
    Integer orderTypeCode = resolveOrderTypeCode(orderType);

    int offset = pageRequest.getOffset();
    List<WorkOrder> list =
        workOrderMapper.selectPage(
            applicantId,
            statusCode,
            orderTypeCode,
            keyword,
            null,
            offset,
            pageRequest.getPageSize(),
            pageRequest.getSortField(),
            pageRequest.getSortOrder());
    long total = workOrderMapper.countTotal(applicantId, statusCode, orderTypeCode, keyword, null);

    // 为每个工单填充当前审批人信息（非持久化显示字段）
    populateProcessDisplayFields(list);

    PageResult<WorkOrder> pageResult =
        new PageResult<>(list, total, pageRequest.getPageNum(), pageRequest.getPageSize());

    return Result.success(pageResult);
  }

  @Override
  public Result<PageResult<WorkOrder>> getAllWorkOrders(
      PageRequest pageRequest,
      String status,
      String orderType,
      String keyword,
      Long currentUserId) {
    // 获取当前用户信息，用于部门过滤
    User currentUser = null;
    Long filterDepartmentId = null;
    try {
      currentUser = userMapper.selectById(currentUserId);
      if (currentUser != null) {
        boolean isTopExecutive =
            currentUser.getOrgLevel() != null && currentUser.getOrgLevel() <= 1;
        if (!isTopExecutive && currentUser.getDepartmentId() != null) {
          filterDepartmentId = currentUser.getDepartmentId();
          logger.info(
              "部门隔离: 用户 {} ({}, orgLevel={}) 只能查看部门ID={} 的工单",
              currentUser.getRealName(),
              currentUser.getUsername(),
              currentUser.getOrgLevel(),
              filterDepartmentId);
        } else if (isTopExecutive) {
          logger.info(
              "决策层权限: 用户 {} ({}, orgLevel={}) 可查看所有工单",
              currentUser.getRealName(),
              currentUser.getUsername(),
              currentUser.getOrgLevel());
        }
      }
    } catch (Exception e) {
      logger.warn("获取当前用户信息失败，不过滤: {}", e.getMessage());
    }

    // 安全：排序字段白名单校验
    String safeSortField = validateSortField(pageRequest.getSortField());
    String safeSortOrder = validateSortOrder(pageRequest.getSortOrder());

    // OPTIMIZATION：String 转 Integer 枚举码
    Integer statusCode = resolveStatusCode(status);
    Integer orderTypeCode = resolveOrderTypeCode(orderType);

    List<WorkOrder> list =
        workOrderMapper.selectPage(
            null,
            statusCode,
            orderTypeCode,
            keyword,
            filterDepartmentId,
            pageRequest.getOffset(),
            pageRequest.getPageSize(),
            safeSortField,
            safeSortOrder);
    long total =
        workOrderMapper.countTotal(null, statusCode, orderTypeCode, keyword, filterDepartmentId);

    // 为每个工单填充当前审批人信息（非持久化显示字段）
    populateProcessDisplayFields(list);

    PageResult<WorkOrder> pageResult =
        new PageResult<>(list, total, pageRequest.getPageNum(), pageRequest.getPageSize());

    return Result.success(pageResult);
  }

  /**
   * 批量填充工单列表的当前审批人信息（OPTIMIZATION 一：非持久化显示字段）
   *
   * <p>改造说明： - work_order 表已无 current_node/current_assignee 等列 - 此方法查询 order_process_link 获取
   * process_instance_id，再查 Flowable 获取当前任务 - 填充到 WorkOrder 实体的非持久化字段（仅用于 API 响应，不写入数据库）
   */
  private void populateProcessDisplayFields(List<WorkOrder> list) {
    if (list == null || list.isEmpty()) return;

    for (WorkOrder wo : list) {
      populateProcessDisplayFields(wo);
    }
  }

  /** 填充单个工单的当前审批人信息（OPTIMIZATION 一：非持久化显示字段） */
  private void populateProcessDisplayFields(WorkOrder wo) {
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

  @Override
  public Result<List<WorkOrder>> getPendingApprovalList(Long assigneeId) {
    String assigneeIdStr = assigneeId.toString();
    Map<Long, WorkOrder> workOrderMap = new LinkedHashMap<>();

    User assigneeUser = null;
    try {
      assigneeUser = userMapper.selectById(assigneeId);
    } catch (Exception e) {
      logger.warn("获取审批人信息失败: {}", e.getMessage());
    }

    // 1. Flowable 待办任务（主路径）
    List<Task> tasks =
        taskService
            .createTaskQuery()
            .taskAssignee(assigneeIdStr)
            .active()
            .orderByTaskCreateTime()
            .desc()
            .list();

    for (Task task : tasks) {
      Long workOrderId = resolveWorkOrderId(task);
      if (workOrderId != null && !workOrderMap.containsKey(workOrderId)) {
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        // OPTIMIZATION：status 改为 Integer 枚举码比较
        if (wo != null && WorkOrderStatusEnum.PENDING.getCode().equals(wo.getStatus())) {
          if (!isSameDepartmentOrSuperAdmin(assigneeUser, wo)) {
            logger.info(
                "跳过跨部门工单: 工单ID={}, 工单部门={}, 审批人部门={}",
                workOrderId,
                wo.getDepartment(),
                assigneeUser != null ? assigneeUser.getDepartment() : "未知");
            continue;
          }
          // 非持久化显示字段填充
          wo.setCurrentNode(task.getName());
          wo.setProcessInstanceId(task.getProcessInstanceId());
          workOrderMap.put(workOrderId, wo);
          logger.info(
              "Pending approval order (task) - ID: {}, Title: {}, Node: {}",
              workOrderId,
              wo.getTitle(),
              task.getName());
        }
      }
    }

    // 2. 候选任务（备用路径）
    List<Task> candidateTasks =
        taskService
            .createTaskQuery()
            .taskCandidateUser(assigneeIdStr)
            .active()
            .orderByTaskCreateTime()
            .desc()
            .list();

    for (Task task : candidateTasks) {
      Long workOrderId = resolveWorkOrderId(task);
      if (workOrderId != null && !workOrderMap.containsKey(workOrderId)) {
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        if (wo != null && WorkOrderStatusEnum.PENDING.getCode().equals(wo.getStatus())) {
          if (!isSameDepartmentOrSuperAdmin(assigneeUser, wo)) {
            logger.info(
                "跳过跨部门候选工单: 工单ID={}, 工单部门={}, 审批人部门={}",
                workOrderId,
                wo.getDepartment(),
                assigneeUser != null ? assigneeUser.getDepartment() : "未知");
            continue;
          }
          wo.setCurrentNode(task.getName());
          wo.setProcessInstanceId(task.getProcessInstanceId());
          workOrderMap.put(workOrderId, wo);
          logger.info(
              "Pending approval order (candidate) - ID: {}, Title: {}, Node: {}",
              workOrderId,
              wo.getTitle(),
              task.getName());
        }
      }
    }

    List<WorkOrder> result = new ArrayList<>(workOrderMap.values());
    logger.info("Total pending orders for user {}: {}", assigneeId, result.size());

    return Result.success(result);
  }

  /** 排序字段白名单 - 防止SQL注入 */
  private static final Set<String> SORT_FIELD_WHITELIST =
      Set.of(
          "create_time",
          "update_time",
          "priority",
          "status",
          "order_type",
          "title",
          "applicant_name",
          "department_id");

  private String validateSortField(String sortField) {
    if (sortField == null || sortField.isBlank()) return null;
    String trimmed = sortField.trim().toLowerCase();
    if (!SORT_FIELD_WHITELIST.contains(trimmed)) {
      logger.warn("安全警告: 非法排序字段 '{}' 已被拦截，使用默认排序", sortField);
      return null;
    }
    return trimmed;
  }

  private String validateSortOrder(String sortOrder) {
    if (sortOrder == null || sortOrder.isBlank()) return null;
    String trimmed = sortOrder.trim().toUpperCase();
    if ("ASC".equals(trimmed) || "DESC".equals(trimmed)) {
      return trimmed;
    }
    logger.warn("安全警告: 非法排序方向 '{}' 已被拦截", sortOrder);
    return null;
  }

  /**
   * 判断用户是否有权限审批指定工单 规则： 1. 高管级(orgLevel<=1)：可审批所有部门工单 2. 管理级(orgLevel=2)：仅可审批本部门工单 3.
   * 专员级(orgLevel=3)：仅可审批分配给自己的任务（由Flowable任务查询控制）
   */
  private boolean canUserApproveWorkOrder(WorkOrder workOrder, User operator) {
    if (operator == null || workOrder == null) return false;

    if (operator.getOrgLevel() != null && operator.getOrgLevel() <= 1) {
      return true;
    }
    if (operator.getDepartmentId() != null && operator.getDepartmentId() == 1L) {
      return true;
    }

    // 跨级别权限校验：检查当前节点是否超出用户审批级别
    // OPTIMIZATION 一：currentNode 为非持久化显示字段，需通过 order_process_link 查询
    String currentNode = workOrder.getCurrentNode();
    if (currentNode == null) {
      // 若 currentNode 未填充，则查询 order_process_link + Flowable
      OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrder.getId());
      if (link != null && link.getProcessInstanceId() != null) {
        try {
          List<Task> activeTasks =
              taskService
                  .createTaskQuery()
                  .processInstanceId(link.getProcessInstanceId())
                  .active()
                  .list();
          if (!activeTasks.isEmpty()) {
            currentNode = activeTasks.get(0).getName();
            workOrder.setCurrentNode(currentNode);
          }
        } catch (Exception e) {
          logger.warn("查询工单 {} 当前节点失败: {}", workOrder.getId(), e.getMessage());
        }
      }
    }

    if (currentNode != null) {
      int requiredLevel = getRequiredApprovalLevel(currentNode);
      int userLevel = operator.getOrgLevel() != null ? operator.getOrgLevel() : 4;

      if (userLevel > requiredLevel) {
        logger.warn(
            "[跨级审批拦截] 用户 {} ({}, orgLevel={}) 级别不足，无法审批「{}」节点(要求orgLevel<={})",
            operator.getRealName(),
            operator.getUsername(),
            userLevel,
            currentNode,
            requiredLevel);
        return false;
      }
    }

    if (operator.getDepartmentId() != null && workOrder.getDepartmentId() != null) {
      return operator.getDepartmentId().equals(workOrder.getDepartmentId());
    }
    return false;
  }

  /** 根据节点名称获取要求的最低审批级别(orgLevel) */
  private int getRequiredApprovalLevel(String nodeName) {
    if (nodeName == null) return 4;

    String name = nodeName.toLowerCase();
    if (name.contains("董事") || name.contains("总经理") || name.contains("终审") || name.contains("决策")) {
      return 1;
    }
    if (name.contains("总监") || name.contains("副总") || name.contains("vp")) {
      return 2;
    }
    return 4;
  }

  /** IDOR防护：判断用户是否有权限查看指定工单 */
  private boolean canUserViewWorkOrder(WorkOrder workOrder, Long currentUserId) {
    if (currentUserId == null || workOrder == null) return false;

    if (currentUserId.equals(workOrder.getApplicantId())) return true;

    try {
      User currentUser = userMapper.selectById(currentUserId);
      if (currentUser == null) return false;

      if (currentUser.getOrgLevel() != null && currentUser.getOrgLevel() <= 1) return true;
      if (currentUser.getDepartmentId() != null && currentUser.getDepartmentId() == 1L) return true;

      if (currentUser.getDepartmentId() != null && workOrder.getDepartmentId() != null) {
        return currentUser.getDepartmentId().equals(workOrder.getDepartmentId());
      }
    } catch (Exception e) {
      logger.warn("IDOR校验异常，默认拒绝: {}", e.getMessage());
    }
    return false;
  }

  /** 判断审批人是否有权限查看/审批该工单（阶段 2 修复 A-15 / H-04：fail-closed） */
  private boolean isSameDepartmentOrSuperAdmin(User assigneeUser, WorkOrder wo) {
    if (assigneeUser == null) return false;
    if (wo == null) return false;
    if (assigneeUser.getOrgLevel() != null && assigneeUser.getOrgLevel() <= 1) return true;
    if (assigneeUser.getDepartmentId() != null && assigneeUser.getDepartmentId() == 1L) return true;

    if (assigneeUser.getDepartmentId() != null && wo.getDepartmentId() != null) {
      return assigneeUser.getDepartmentId().equals(wo.getDepartmentId());
    }
    if (assigneeUser.getDepartment() != null && wo.getDepartment() != null) {
      return assigneeUser.getDepartment().equals(wo.getDepartment());
    }
    return false;
  }

  /** Resolve work order ID from Flowable task */
  private Long resolveWorkOrderId(Task task) {
    try {
      Object workOrderIdObj = taskService.getVariable(task.getId(), "workOrderId");
      if (workOrderIdObj != null) {
        return Long.parseLong(workOrderIdObj.toString());
      }
    } catch (Exception e) {
      logger.debug("Failed to resolve workOrderId from task {}: {}", task.getId(), e.getMessage());
    }
    return null;
  }

  /** 查找用户在指定工单流程上的活跃任务（OPTIMIZATION 一：通过 order_process_link 获取流程实例） */
  private String findActiveTaskForUser(WorkOrder workOrder, Long userId) {
    // OPTIMIZATION 一：从 order_process_link 查询 process_instance_id
    OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrder.getId());
    if (link == null || link.getProcessInstanceId() == null) {
      return null;
    }
    String processInstanceId = link.getProcessInstanceId();
    workOrder.setProcessInstanceId(processInstanceId);

    // 1. 查找分配给当前用户的任务
    List<Task> tasks =
        taskService
            .createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskAssignee(userId.toString())
            .active()
            .list();

    if (!tasks.isEmpty()) {
      return tasks.get(0).getId();
    }

    // 2. 查找候选任务
    tasks =
        taskService
            .createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskCandidateUser(userId.toString())
            .active()
            .list();

    if (!tasks.isEmpty()) {
      return tasks.get(0).getId();
    }

    // 3. 高管级特权：可接管该工单的任意活跃节点任务
    User operator = userMapper.selectById(userId);
    if (operator != null && operator.getOrgLevel() != null && operator.getOrgLevel() <= 1) {
      tasks = taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();
      if (!tasks.isEmpty()) {
        Task targetTask = tasks.get(0);
        logger.info(
            "[高管跨级审批] 用户 {} ({}, orgLevel={}) 接管工单ID={} 的「{}」节点任务 TaskID={}",
            operator.getRealName(),
            operator.getUsername(),
            operator.getOrgLevel(),
            workOrder.getId(),
            targetTask.getName(),
            targetTask.getId());
        return targetTask.getId();
      }
    }

    return null;
  }

  /** 构建审批日志对象（OPTIMIZATION：action 改为 ApprovalActionEnum，status 改为 Integer） */
  private ApprovalLog buildApprovalLog(
      WorkOrder workOrder,
      ApprovalActionEnum actionEnum,
      String comment,
      Integer oldStatus,
      Integer newStatus) {
    ApprovalLog log = new ApprovalLog();
    log.setWorkOrderId(workOrder.getId());
    log.setOrderNo(workOrder.getOrderNo());
    log.setAction(actionEnum.getNumericCode());
    log.setComment(comment);
    log.setBeforeStatus(oldStatus);
    log.setAfterStatus(newStatus);
    log.setCreateTime(Instant.now());
    return log;
  }

  @Override
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
    if (!canUserApproveWorkOrder(workOrder, operator)) {
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
    String taskId = findActiveTaskForUser(workOrder, operatorId);
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
    validateTaskCandidate(task, operatorId);

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

    // 确定新状态
    Integer oldStatus = workOrder.getStatus();
    Integer newStatus;
    boolean approved;

    switch (actionEnum) {
      case APPROVE:
        newStatus = WorkOrderStatusEnum.APPROVED.getCode();
        approved = true;
        break;
      case REJECT:
      case RETURN:
        newStatus = WorkOrderStatusEnum.REJECTED.getCode();
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

    if (isProcessFinished) {
      logger.info("Workflow process completed - Process instance ID: {}", processInstanceId);
    }

    // 获取下一节点信息（用于显示，非持久化）
    String currentNode = null;
    Long currentAssigneeId = null;
    String currentAssigneeName = null;

    if (!isProcessFinished && processInstanceId != null) {
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
    }

    // ========== 6. 更新工单业务状态（OPTIMIZATION 二：乐观锁更新） ==========
    workOrder.setStatus(newStatus);
    workOrder.setUpdateTime(Instant.now());
    // 填充下一节点显示字段
    workOrder.setCurrentNode(currentNode);
    workOrder.setCurrentAssignee(currentAssigneeId);
    workOrder.setCurrentAssigneeName(currentAssigneeName);

    int updateResult = workOrderMapper.updateWithVersion(workOrder);
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
        resolveStatusDesc(oldStatus),
        resolveStatusDesc(newStatus),
        dto.getWorkOrderId());

    // ========== 7. 发布领域事件（OPTIMIZATION 一 架构解耦） ==========
    // 事件监听器（@Async + AFTER_COMMIT）异步处理：
    //   - 记录 APPROVE/REJECT 审计日志（包含 taskId、taskName）
    //   - 通知申请人
    //   - 触发后续业务联动（如报销打款）
    if (actionEnum == ApprovalActionEnum.APPROVE) {
      workOrderEventPublisher.publishApproved(
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
      workOrderEventPublisher.publishRejected(
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
        resolveStatusDesc(newStatus),
        currentNode,
        currentAssigneeName);

    return Result.success(workOrder);
  }

  /** 获取工单状态描述（用于日志） */
  private String resolveStatusDesc(Integer statusCode) {
    if (statusCode == null) return "UNKNOWN";
    WorkOrderStatusEnum statusEnum = WorkOrderStatusEnum.fromCode(statusCode);
    return statusEnum != null
        ? statusEnum.getDescription() + "(" + statusCode + ")"
        : "UNKNOWN(" + statusCode + ")";
  }

  @Override
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
    int updateResult = workOrderMapper.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单状态更新失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    // 记录 RESUBMIT 审计日志（此处直接插入，因为 P3 事件类未涵盖 RESUBMIT）
    // 后续 submitWorkOrder 调用会自动触发 SubmittedEvent，记录 SUBMIT 审计日志
    try {
      ApprovalLog log =
          buildApprovalLog(
              workOrder,
              ApprovalActionEnum.RESUBMIT,
              sanitizedComment,
              WorkOrderStatusEnum.REJECTED.getCode(),
              WorkOrderStatusEnum.DRAFT.getCode());
      log.setOperatorId(applicantId);
      log.setOperatorName(workOrder.getApplicantName());
      approvalLogMapper.insert(log);
    } catch (Exception e) {
      logger.warn("Failed to record resubmit log", e);
    }

    return submitWorkOrder(workOrderId, applicantId);
  }

  @Override
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
    int updateResult = workOrderMapper.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单状态更新失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    // 发布工单终止事件（OPTIMIZATION 一 架构解耦）
    // 监听器异步处理：逻辑删除 order_process_link、记录审计日志、通知申请人
    workOrderEventPublisher.publishTerminated(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.TERMINATED.getCode(),
        operatorId,
        operator != null ? operator.getRealName() : "unknown",
        "Terminated by admin: " + reason,
        processInstanceId);

    return Result.success(workOrder);
  }

  @Override
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
    workOrderEventPublisher.publishArchived(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.ARCHIVED.getCode(),
        null,
        "system",
        "Order archived",
        null);

    return Result.success(workOrder);
  }

  /** 阶段 2 修复 A-11 / H-07：归档工单（带操作人 ID，二次所有权校验） */
  @Override
  public Result<WorkOrder> archiveWorkOrder(Long workOrderId, Long userId) {
    logger.info("归档工单 - workOrderId: {}, 操作人: {}", workOrderId, userId);
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }
    if (!canUserViewWorkOrder(workOrder, userId)) {
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
    workOrderEventPublisher.publishArchived(
        workOrder,
        oldStatus,
        WorkOrderStatusEnum.ARCHIVED.getCode(),
        userId,
        operator != null ? operator.getRealName() : "unknown",
        "Order archived",
        null);

    return Result.success(workOrder);
  }

  @Override
  public Result<WorkOrder> updateWorkOrder(WorkOrder workOrder) {
    sanitizeWorkOrderEntity(workOrder);

    WorkOrder existing = workOrderMapper.selectById(workOrder.getId());
    if (existing == null) {
      throw new BusinessException("工单不存在");
    }

    if (!WorkOrderStatusEnum.DRAFT.getCode().equals(existing.getStatus())) {
      throw new BusinessException("只能编辑草稿状态的工单");
    }

    workOrder.setUpdateTime(Instant.now());
    // 保留 version 字段用于乐观锁
    workOrder.setVersion(existing.getVersion());
    workOrderMapper.updateWithVersion(workOrder);

    return Result.success(workOrder);
  }

  /** 阶段 2 修复 A-11 / H-07：更新工单（带操作人 ID，仅发起人可改草稿） */
  @Override
  public Result<WorkOrder> updateWorkOrder(WorkOrder workOrder, Long userId) {
    logger.info("更新工单 - workOrderId: {}, 操作人: {}", workOrder.getId(), userId);

    sanitizeWorkOrderEntity(workOrder);

    WorkOrder existing = workOrderMapper.selectById(workOrder.getId());
    if (existing == null) {
      throw new BusinessException("工单不存在");
    }

    if (existing.getApplicantId() == null || !existing.getApplicantId().equals(userId)) {
      logger.warn(
          "IDOR拦截: 用户 {} 尝试编辑非本人创建的工单 {} (applicant={})",
          userId,
          workOrder.getId(),
          existing.getApplicantId());
      throw new BusinessException("只能编辑自己创建的工单");
    }

    if (!WorkOrderStatusEnum.DRAFT.getCode().equals(existing.getStatus())) {
      throw new BusinessException("只能编辑草稿状态的工单");
    }

    workOrder.setUpdateTime(Instant.now());
    // 保留 version 字段用于乐观锁
    workOrder.setVersion(existing.getVersion());
    workOrderMapper.updateWithVersion(workOrder);

    return Result.success(workOrder);
  }

  /** 阶段 3 §3 — XSS 净化 WorkOrderDTO */
  private void sanitizeWorkOrderDTO(WorkOrderDTO dto) {
    if (dto == null) return;
    if (dto.getTitle() != null) {
      dto.setTitle(XssCleanUtil.clean(dto.getTitle()));
    }
    if (dto.getContent() != null) {
      dto.setContent(XssCleanUtil.cleanRelaxed(dto.getContent()));
    }
    if (dto.getRemark() != null) {
      dto.setRemark(XssCleanUtil.clean(dto.getRemark()));
    }
  }

  /** 阶段 3 §3 — XSS 净化 WorkOrder 实体 */
  private void sanitizeWorkOrderEntity(WorkOrder workOrder) {
    if (workOrder == null) return;
    if (workOrder.getTitle() != null) {
      workOrder.setTitle(XssCleanUtil.clean(workOrder.getTitle()));
    }
    if (workOrder.getContent() != null) {
      workOrder.setContent(XssCleanUtil.cleanRelaxed(workOrder.getContent()));
    }
    if (workOrder.getRemark() != null) {
      workOrder.setRemark(XssCleanUtil.clean(workOrder.getRemark()));
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Result<?> deleteWorkOrder(Long workOrderId, Long userId) {
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }

    if (!workOrder.getApplicantId().equals(userId)) {
      throw new BusinessException("只能删除自己创建的工单");
    }

    // OPTIMIZATION：status 改为 Integer 枚举码比较
    Integer status = workOrder.getStatus();
    if (!WorkOrderStatusEnum.DRAFT.getCode().equals(status)
        && !WorkOrderStatusEnum.REJECTED.getCode().equals(status)) {
      throw new BusinessException("只能删除草稿或已退回的工单");
    }

    workOrderMapper.deleteById(workOrderId);

    // 逻辑删除 order_process_link（若存在）
    try {
      orderProcessLinkMapper.deleteByWorkOrderId(workOrderId);
    } catch (Exception e) {
      logger.warn(
          "Failed to delete OrderProcessLink for workOrderId={}: {}", workOrderId, e.getMessage());
    }

    return Result.success(null);
  }

  @Override
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
    int updateResult = workOrderMapper.updateWithVersion(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单撤回失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    // 发布工单撤回事件（OPTIMIZATION 一 架构解耦）
    // 监听器异步处理：逻辑删除 order_process_link、记录审计日志、通知审批人
    workOrderEventPublisher.publishWithdrawn(
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
   * 查询工单的当前任务信息（OPTIMIZATION 一 架构解耦）
   *
   * <p>工单表已移除 current_node/current_assignee 等流程运行时字段， 通过 order_process_link 反查
   * process_instance_id，再调用 Flowable API 获取当前任务。
   */
  @Override
  public Result<TaskInfoDTO> getCurrentTask(Long workOrderId) {
    if (workOrderId == null) {
      throw new BusinessException("工单ID不能为空");
    }
    TaskInfoDTO taskInfo = flowableQueryService.getCurrentTask(workOrderId);
    return Result.success(taskInfo);
  }

  // ============================================================
  // §9 Flowable 工作流安全 — 流程变量净化 + 候选人双重校验
  // ============================================================

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

  /** 校验当前用户是否为任务的合法候选人（§9 防任务哄抢） */
  private void validateTaskCandidate(Task task, Long userId) {
    if (task == null) {
      throw new BusinessException("任务不存在或已完成");
    }
    String userIdStr = String.valueOf(userId);
    boolean isCandidate = false;

    if (task.getAssignee() != null && task.getAssignee().equals(userIdStr)) {
      isCandidate = true;
    } else {
      List<IdentityLink> links;
      try {
        links = taskService.getIdentityLinksForTask(task.getId());
      } catch (Exception e) {
        logger.error("获取任务候选人链接失败: taskId={}, err={}", task.getId(), e.getMessage());
        throw new BusinessException("任务候选人校验失败，请稍后重试");
      }

      java.util.Collection<String> roleCodes = getCurrentUserRoleCodes();
      if (links != null) {
        for (IdentityLink link : links) {
          if (!"candidate".equals(link.getType())) {
            continue;
          }
          if (link.getUserId() != null && link.getUserId().equals(userIdStr)) {
            isCandidate = true;
            break;
          }
          if (link.getGroupId() != null
              && roleCodes != null
              && roleCodes.contains(link.getGroupId())) {
            isCandidate = true;
            break;
          }
        }
      }
    }

    if (!isCandidate) {
      String cacheKey = "task:candidates:" + task.getId();
      try {
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof java.util.Set) {
          @SuppressWarnings("unchecked")
          java.util.Set<String> candidateIds = (java.util.Set<String>) cached;
          if (candidateIds.contains(userIdStr)) {
            logger.warn(
                "⚠️ Flowable API 校验未通过但 Redis 缓存命中（数据不一致），用户 {} 任务 {}", userIdStr, task.getId());
            isCandidate = true;
          }
        }
      } catch (Exception e) {
        logger.warn("Redis 候选人缓存校验失败（仅依赖 Flowable API）: {}", e.getMessage());
      }
    }

    if (!isCandidate) {
      logger.error(
          "🚫 用户 {} 不是任务 {} 的合法候选人（越权审批尝试，流程实例 {}）",
          userIdStr,
          task.getId(),
          task.getProcessInstanceId());
      throw new BusinessException("您不是该任务的合法候选人，无法审批");
    }

    logger.debug("✅ 候选人校验通过: 用户={}, 任务={}", userIdStr, task.getId());
  }

  /** 从 SecurityContext 获取当前用户角色 code 列表 */
  private java.util.Collection<String> getCurrentUserRoleCodes() {
    try {
      org.springframework.security.core.Authentication auth =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (auth != null && auth.getPrincipal() instanceof com.workorder.security.CustomUserDetails) {
        com.workorder.security.CustomUserDetails ud =
            (com.workorder.security.CustomUserDetails) auth.getPrincipal();
        java.util.Collection<String> roles = ud.getRoles();
        return roles != null ? roles : java.util.Collections.emptyList();
      }
    } catch (Exception e) {
      logger.warn("获取当前用户角色失败: {}", e.getMessage());
    }
    return java.util.Collections.emptyList();
  }
}
