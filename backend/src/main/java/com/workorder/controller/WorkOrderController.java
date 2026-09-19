package com.workorder.controller;


import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.annotation.DataPermission;
import com.workorder.common.result.PageRequest;
import com.workorder.common.result.PageResult;
import com.workorder.common.result.Result;
import com.workorder.dto.TaskInfoDTO;
import com.workorder.dto.WorkOrderDTO;
import com.workorder.dto.WorkOrderUpdateDTO;
import com.workorder.entity.WorkOrder;
import com.workorder.security.CustomUserDetails;
import com.workorder.service.IWorkOrderService;

/**
 * 工单管理控制器 提供工单的CRUD和查询接口
 *
 * @author KLord
 */
@RestController
@RequestMapping("/work-orders")
public class WorkOrderController {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderController.class);

  @Autowired private IWorkOrderService workOrderService;

  /** 创建工单草稿 */
  @PostMapping("/draft")
  @PreAuthorize("isAuthenticated()")
  public Result<WorkOrder> createDraft(@RequestBody WorkOrderDTO dto) {
    Long userId = getCurrentUserId();
    return workOrderService.createDraft(dto, userId);
  }

  /** 提交新工单（直接提交） */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public Result<WorkOrder> submitNewWorkOrder(@RequestBody WorkOrderDTO dto) {
    Long userId = getCurrentUserId();
    return workOrderService.submitNewWorkOrder(dto, userId);
  }

  /** 提交草稿工单 */
  @PostMapping("/{id}/submit")
  @PreAuthorize("isAuthenticated()")
  public Result<WorkOrder> submitWorkOrder(@PathVariable Long id) {
    Long userId = getCurrentUserId();
    return workOrderService.submitWorkOrder(id, userId);
  }

  /** 查询工单详情（含权限校验，防止IDOR越权） */
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public Result<WorkOrder> getWorkOrderById(@PathVariable Long id) {
    Long userId = getCurrentUserId();
    return workOrderService.getWorkOrderById(id, userId);
  }

  /**
   * 根据编号查询工单（阶段 2 修复 A-11 / H-07：IDOR 越权防护） @DataPermission 校验当前用户对该工单的所有权（发起人/审批人/同部门管理层/view-all
   * 权限）
   */
  @GetMapping("/no/{orderNo}")
  @PreAuthorize("isAuthenticated()")
  @DataPermission(entityType = "WORK_ORDER", checkOwnership = true, resourceIdParam = "orderNo")
  public Result<WorkOrder> getWorkOrderByNo(@PathVariable String orderNo) {
    Long userId = getCurrentUserId();
    return workOrderService.getWorkOrderByNo(orderNo, userId);
  }

  /** 分页查询我的工单列表 */
  @GetMapping("/my")
  @PreAuthorize("isAuthenticated()")
  public Result<PageResult<WorkOrder>> getMyWorkOrders(
      PageRequest pageRequest,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String orderType,
      @RequestParam(required = false) String keyword) {
    try {
      Long userId = getCurrentUserId();
      logger.info(
          "查询我的工单 - 用户ID: {}, 页码: {}, 大小: {}, 状态: {}, 类型: {}",
          userId,
          pageRequest.getPageNum(),
          pageRequest.getPageSize(),
          status,
          orderType);
      return workOrderService.getMyWorkOrders(pageRequest, userId, status, orderType, keyword);
    } catch (Exception e) {
      logger.error("查询我的工单失败", e);
      throw e;
    }
  }

  /** 分页查询所有工单（管理员） 权限：高管级(view-all) / 管理级(approve) 均可访问，Service层按部门过滤数据 */
  @GetMapping("/list")
  @PreAuthorize("hasAnyAuthority('workorder:view-all', 'workorder:approve')")
  public Result<PageResult<WorkOrder>> getAllWorkOrders(
      PageRequest pageRequest,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String orderType,
      @RequestParam(required = false) String keyword) {
    Long userId = getCurrentUserId();
    return workOrderService.getAllWorkOrders(pageRequest, status, orderType, keyword, userId);
  }

  /** 查询待我审批的工单列表 */
  @GetMapping("/pending")
  @PreAuthorize("hasAuthority('workorder:pending')")
  public Result<?> getPendingApprovalList() {
    Long userId = getCurrentUserId();
    return workOrderService.getPendingApprovalList(userId);
  }

  /** 重新提交被驳回的工单 */
  @PostMapping("/{id}/resubmit")
  @PreAuthorize("hasAuthority('workorder:submit')")
  public Result<WorkOrder> resubmitWorkOrder(
      @PathVariable Long id, @RequestParam(required = false) String comment) {
    Long userId = getCurrentUserId();
    return workOrderService.resubmitWorkOrder(id, userId, comment);
  }

  /** 终止流程 */
  @PostMapping("/{id}/terminate")
  @PreAuthorize("hasAnyAuthority('system:user', 'workorder:approve')")
  public Result<WorkOrder> terminateWorkOrder(
      @PathVariable Long id, @RequestParam(required = false) String reason) {
    Long userId = getCurrentUserId();
    return workOrderService.terminateWorkOrder(id, userId, reason);
  }

  /** 归档工单（阶段 2 修复 A-11 / H-07：IDOR 越权防护） @DataPermission 校验当前用户对该工单的所有权 */
  @PostMapping("/{id}/archive")
  @PreAuthorize("hasAuthority('system:user')")
  @DataPermission(entityType = "WORK_ORDER", checkOwnership = true, resourceIdParam = "id")
  public Result<WorkOrder> archiveWorkOrder(@PathVariable Long id) {
    Long userId = getCurrentUserId();
    return workOrderService.archiveWorkOrder(id, userId);
  }

  /**
   * 更新工单信息（W-04 修复：白名单 DTO，防 Mass Assignment）
   * @DataPermission 校验当前用户对该工单的所有权（仅发起人可改草稿）
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('workorder:submit')")
  @DataPermission(entityType = "WORK_ORDER", checkOwnership = true, resourceIdParam = "id")
  public Result<WorkOrder> updateWorkOrder(
      @PathVariable Long id, @Valid @RequestBody WorkOrderUpdateDTO dto) {
    Long userId = getCurrentUserId();
    return workOrderService.updateWorkOrder(id, dto, userId);
  }

  /** 删除工单 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('workorder:submit')")
  public Result<?> deleteWorkOrder(@PathVariable Long id) {
    Long userId = getCurrentUserId();
    return workOrderService.deleteWorkOrder(id, userId);
  }

  /** 撤回工单（将PENDING状态改为DRAFT） */
  @PostMapping("/{id}/withdraw")
  @PreAuthorize("hasAuthority('workorder:submit')")
  public Result<WorkOrder> withdrawWorkOrder(@PathVariable Long id) {
    Long userId = getCurrentUserId();
    return workOrderService.withdrawWorkOrder(id, userId);
  }

  /**
   * 查询工单的当前任务信息（OPTIMIZATION 一 架构解耦）
   *
   * <p>工单表已移除 current_node/current_assignee 等流程运行时字段， 前端通过此端点实时获取当前审批节点和审批人。
   */
  @GetMapping("/{id}/current-task")
  @PreAuthorize(
      "hasAnyAuthority('workorder:mine', 'workorder:view-all', 'workorder:approve', 'system:user')")
  public Result<TaskInfoDTO> getCurrentTask(@PathVariable Long id) {
    return workOrderService.getCurrentTask(id);
  }

  /** 获取当前登录用户ID */
  private Long getCurrentUserId() {
    CustomUserDetails userDetails =
        (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return userDetails.getUserId();
  }
}
