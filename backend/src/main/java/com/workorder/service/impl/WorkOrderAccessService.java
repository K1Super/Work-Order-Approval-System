package com.workorder.service.impl;


import java.util.List;

import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.workorder.common.exception.BusinessException;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.OrderProcessLink;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;

/**
 * 工单访问控制服务。
 *
 * <p>职责：集中承载工单相关「候选人/权限校验」逻辑，供审批编排、生命周期编排与门面服务复用， 包括审批人权限判定（跨部门/跨级）、候选人双重校验（防任务哄抢）、当前任务访问权限、 工单可见性
 * （IDOR 防护）以及基于 Flowable 任务反查工单 ID / 活跃任务 ID 等。
 *
 * @author KLord
 */
@Component
public class WorkOrderAccessService {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderAccessService.class);

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Autowired private TaskService taskService;

  @Autowired private UserMapper userMapper;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  /**
   * 判断用户是否有权限审批指定工单 规则： 1. 高管级(orgLevel&lt;=1)：可审批所有部门工单 2. 管理级(orgLevel=2)：仅可审批本部门工单 3.
   * 专员级(orgLevel=3)：仅可审批分配给自己的任务（由Flowable任务查询控制）
   *
   * @param workOrder 目标工单
   * @param operator 操作人
   * @return 有权限返回 true
   */
  public boolean canUserApproveWorkOrder(WorkOrder workOrder, User operator) {
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

  /**
   * IDOR防护：判断用户是否有权限查看指定工单。
   *
   * @param workOrder 目标工单
   * @param currentUserId 当前用户 ID
   * @return 有权限返回 true
   */
  public boolean canUserViewWorkOrder(WorkOrder workOrder, Long currentUserId) {
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

  /**
   * 判断审批人是否有权限查看/审批该工单（阶段 2 修复 A-15 / H-04：fail-closed）。
   *
   * @param assigneeUser 审批人
   * @param wo 目标工单
   * @return 有权限返回 true
   */
  public boolean isSameDepartmentOrSuperAdmin(User assigneeUser, WorkOrder wo) {
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

  /**
   * 从 Flowable 任务变量中反查工单 ID。
   *
   * @param task Flowable 任务
   * @return 工单 ID，反查失败返回 null
   */
  public Long resolveWorkOrderId(Task task) {
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

  /**
   * 查找用户在指定工单流程上的活跃任务 ID（OPTIMIZATION 一：通过 order_process_link 获取流程实例）。
   *
   * @param workOrder 目标工单
   * @param userId 用户 ID
   * @return 活跃任务 ID，未找到返回 null
   */
  public String findActiveTaskForUser(WorkOrder workOrder, Long userId) {
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

  /**
   * 校验当前用户是否为任务的合法候选人（§9 防任务哄抢）。
   *
   * <p>先以 Flowable API 为准，未命中时回退到 Redis 候选人缓存二次确认； 两者均未命中则抛出越权审批异常。
   *
   * @param task Flowable 任务
   * @param userId 用户 ID
   */
  public void validateTaskCandidate(Task task, Long userId) {
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

  /**
   * W-25：判断当前登录用户是否有权访问指定工单的当前任务。
   *
   * <p>允许条件（满足其一）：申请人是当前用户；当前 Flowable 任务 assignee 或 candidate 含当前用户； 当前用户有
   * workorder:view-all 权限；当前用户为 SUPER_ADMIN。
   *
   * @param workOrder 目标工单
   * @return 有权限返回 true
   */
  public boolean hasCurrentTaskAccess(WorkOrder workOrder) {
    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (auth == null
        || !(auth.getPrincipal() instanceof com.workorder.security.CustomUserDetails)) {
      return false;
    }
    com.workorder.security.CustomUserDetails ud =
        (com.workorder.security.CustomUserDetails) auth.getPrincipal();

    // 超管或拥有 view-all 数据权限 → 放行
    if (ud.isSuperAdmin() || ud.hasPermission("workorder:view-all")) {
      return true;
    }

    Long userId = ud.getUserId();
    // 申请人本人 → 放行
    if (workOrder.getApplicantId() != null && workOrder.getApplicantId().equals(userId)) {
      return true;
    }

    // 当前 Flowable 任务的 assignee 或 candidate 含当前用户才放行
    OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrder.getId());
    if (link == null || link.getProcessInstanceId() == null) {
      return false;
    }
    String userIdStr = String.valueOf(userId);
    java.util.Collection<String> roleCodes = getCurrentUserRoleCodes();

    List<Task> activeTasks;
    try {
      activeTasks =
          taskService
              .createTaskQuery()
              .processInstanceId(link.getProcessInstanceId())
              .active()
              .list();
    } catch (Exception e) {
      logger.error(
          "校验当前任务权限时查询 Flowable 任务失败: workOrderId={}, err={}",
          workOrder.getId(),
          e.getMessage(),
          e);
      return false;
    }

    for (Task task : activeTasks) {
      if (task.getAssignee() != null && task.getAssignee().equals(userIdStr)) {
        return true;
      }
      List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
      if (links != null) {
        for (IdentityLink identityLink : links) {
          if (!"candidate".equals(identityLink.getType())) {
            continue;
          }
          if (identityLink.getUserId() != null && identityLink.getUserId().equals(userIdStr)) {
            return true;
          }
          if (identityLink.getGroupId() != null
              && roleCodes != null
              && roleCodes.contains(identityLink.getGroupId())) {
            return true;
          }
        }
      }
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