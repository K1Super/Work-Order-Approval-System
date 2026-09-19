package com.workorder.service.impl;


import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.enums.OrderTypeEnum;
import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.OptimisticLockException;
import com.workorder.common.result.PageRequest;
import com.workorder.common.result.PageResult;
import com.workorder.common.result.Result;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.dto.ApprovalDTO;
import com.workorder.dto.TaskInfoDTO;
import com.workorder.dto.WorkOrderDTO;
import com.workorder.dto.WorkOrderUpdateDTO;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.service.IFlowableQueryService;
import com.workorder.service.IWorkOrderService;
import com.workorder.util.XssCleanUtil;

/**
 * 工单服务门面（薄门面）。
 *
 * <p>职责：实现 {@link IWorkOrderService} 公开契约，保留工单 CRUD / 查询与其专属辅助逻辑； 流程相关公开方法（提交/审批/撤回/终止/归档等）委托给
 * 生命周期编排器与审批编排器。依赖收敛为组件，不再散装注入 Flowable 运行时服务。
 *
 * <p>OPTIMIZATION 改造（2026-07-26）：work_order 表移除 5 个 Flowable 运行时字段改由 order_process_link 承载； 基于 version
 * 字段的乐观锁（updateWithVersion）保证并发审批防覆盖；status/order_type/action 字典化为 Integer；时间类型统一为 Instant。
 *
 * @author KLord
 */
@Service
public class WorkOrderServiceImpl implements IWorkOrderService {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderServiceImpl.class);

  /** 工单编号随机数上界 */
  private static final int ORDER_NO_RANDOM_BOUND = 10000;

  @Autowired private WorkOrderMapper workOrderMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Autowired private TaskService taskService;

  @Autowired private IFlowableQueryService flowableQueryService;

  @Autowired private WorkOrderAccessService accessService;

  @Autowired private WorkOrderDisplayAssembler displayAssembler;

  @Autowired private WorkOrderApprovalOrchestrator approvalOrchestrator;

  @Autowired private WorkOrderLifecycleOrchestrator lifecycleOrchestrator;

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
  public Result<WorkOrder> submitWorkOrder(Long workOrderId, Long applicantId) {
    return lifecycleOrchestrator.submitWorkOrder(workOrderId, applicantId);
  }

  @Override
  public Result<WorkOrder> submitNewWorkOrder(WorkOrderDTO dto, Long applicantId) {
    return lifecycleOrchestrator.submitNewWorkOrder(dto, applicantId);
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
      if (!accessService.canUserViewWorkOrder(workOrder, currentUserId)) {
        logger.warn("IDOR拦截: 用户 {} 尝试访问无权查看的工单 {}", currentUserId, id);
        throw new BusinessException("无权查看该工单");
      }

      // OPTIMIZATION 一：动态填充显示字段（从 order_process_link + Flowable）
      displayAssembler.populateProcessDisplayFields(workOrder);

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
    displayAssembler.populateProcessDisplayFields(workOrder);
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
    if (!accessService.canUserViewWorkOrder(workOrder, currentUserId)) {
      logger.warn("IDOR拦截: 用户 {} 尝试访问无权查看的工单 orderNo={}", currentUserId, orderNo);
      throw new BusinessException("您没有权限访问此工单");
    }
    // OPTIMIZATION 一：动态填充显示字段
    displayAssembler.populateProcessDisplayFields(workOrder);
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
    displayAssembler.populateProcessDisplayFields(list);

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
    displayAssembler.populateProcessDisplayFields(list);

    PageResult<WorkOrder> pageResult =
        new PageResult<>(list, total, pageRequest.getPageNum(), pageRequest.getPageSize());

    return Result.success(pageResult);
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
      Long workOrderId = accessService.resolveWorkOrderId(task);
      if (workOrderId != null && !workOrderMap.containsKey(workOrderId)) {
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        // OPTIMIZATION：status 改为 Integer 枚举码比较
        if (wo != null && WorkOrderStatusEnum.PENDING.getCode().equals(wo.getStatus())) {
          if (!accessService.isSameDepartmentOrSuperAdmin(assigneeUser, wo)) {
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
      Long workOrderId = accessService.resolveWorkOrderId(task);
      if (workOrderId != null && !workOrderMap.containsKey(workOrderId)) {
        WorkOrder wo = workOrderMapper.selectById(workOrderId);
        if (wo != null && WorkOrderStatusEnum.PENDING.getCode().equals(wo.getStatus())) {
          if (!accessService.isSameDepartmentOrSuperAdmin(assigneeUser, wo)) {
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

  @Override
  public Result<WorkOrder> handleApproval(ApprovalDTO dto, Long operatorId) {
    return approvalOrchestrator.handleApproval(dto, operatorId);
  }

  @Override
  public Result<WorkOrder> resubmitWorkOrder(Long workOrderId, Long applicantId, String comment) {
    return lifecycleOrchestrator.resubmitWorkOrder(workOrderId, applicantId, comment);
  }

  @Override
  public Result<WorkOrder> terminateWorkOrder(Long workOrderId, Long operatorId, String reason) {
    return lifecycleOrchestrator.terminateWorkOrder(workOrderId, operatorId, reason);
  }

  @Override
  public Result<WorkOrder> archiveWorkOrder(Long workOrderId) {
    return lifecycleOrchestrator.archiveWorkOrder(workOrderId);
  }

  @Override
  public Result<WorkOrder> archiveWorkOrder(Long workOrderId, Long userId) {
    return lifecycleOrchestrator.archiveWorkOrder(workOrderId, userId);
  }

  /** W-04 修复：更新工单（白名单 DTO，仅发起人可改草稿；禁止 Mass Assignment） */
  @Override
  public Result<WorkOrder> updateWorkOrder(Long workOrderId, WorkOrderUpdateDTO dto, Long userId) {
    logger.info("更新工单 - workOrderId: {}, 操作人: {}", workOrderId, userId);
    if (dto == null) {
      throw new BusinessException("更新内容不能为空");
    }

    WorkOrder existing = workOrderMapper.selectById(workOrderId);
    if (existing == null) {
      throw new BusinessException("工单不存在");
    }

    if (existing.getApplicantId() == null || !existing.getApplicantId().equals(userId)) {
      logger.warn(
          "IDOR拦截: 用户 {} 尝试编辑非本人创建的工单 {} (applicant={})",
          userId,
          workOrderId,
          existing.getApplicantId());
      throw new BusinessException("只能编辑自己创建的工单");
    }

    if (!WorkOrderStatusEnum.DRAFT.getCode().equals(existing.getStatus())) {
      throw new BusinessException("只能编辑草稿状态的工单");
    }

    // 仅从白名单 DTO 取值构建待更新实体（status/completeTime/departmentId/priority 均不可达）
    WorkOrder workOrder = new WorkOrder();
    workOrder.setId(workOrderId);
    workOrder.setTitle(dto.getTitle());
    workOrder.setContent(dto.getContent());
    workOrder.setRemark(dto.getRemark());
    workOrder.setAttachmentUrl(dto.getAttachmentUrl());
    workOrder.setDepartment(dto.getDepartment());

    // XSS 净化
    sanitizeWorkOrderEntity(workOrder);

    workOrder.setUpdateTime(Instant.now());
    workOrder.setVersion(existing.getVersion());

    int updateResult = workOrderMapper.updateDraft(workOrder);
    if (updateResult <= 0) {
      throw new OptimisticLockException("工单更新失败（乐观锁冲突，请刷新后重试）", workOrderId, "WorkOrder");
    }

    return Result.success(workOrderMapper.selectById(workOrderId));
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
  public Result<WorkOrder> withdrawWorkOrder(Long workOrderId, Long userId) {
    return lifecycleOrchestrator.withdrawWorkOrder(workOrderId, userId);
  }

  /**
   * 查询工单的当前任务信息（OPTIMIZATION 一 架构解耦）。
   *
   * <p>工单表已移除 current_node/current_assignee 等流程运行时字段， 通过 order_process_link 反查
   * process_instance_id，再调用 Flowable API 获取当前任务。
   *
   * @param workOrderId 工单ID
   * @return 当前任务信息
   */
  @Override
  public Result<TaskInfoDTO> getCurrentTask(Long workOrderId) {
    if (workOrderId == null) {
      throw new BusinessException("工单ID不能为空");
    }

    // W-25：数据权限校验 — 工单必须存在；仅申请人 / 当前任务审批人(assignee 或 candidate) / view-all 权限 / 超管可访问
    WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
    if (workOrder == null) {
      throw new BusinessException("工单不存在");
    }
    if (!accessService.hasCurrentTaskAccess(workOrder)) {
      throw new BusinessException("您没有权限访问此工单");
    }

    TaskInfoDTO taskInfo = flowableQueryService.getCurrentTask(workOrderId);
    return Result.success(taskInfo);
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
   * 优先级权限校验 - 普通员工（org_level &gt;= 4）选择高(3)或紧急(4)优先级时，必须在内容中填写紧急原因（至少20字） - 部门负责人及以上（org_level
   * &lt;= 2）可自由选择任意优先级
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
}