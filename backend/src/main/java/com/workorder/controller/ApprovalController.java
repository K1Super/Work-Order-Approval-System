package com.workorder.controller;


import java.util.List;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.annotation.AntiReplay;
import com.workorder.annotation.DataPermission;
import com.workorder.common.enums.ApprovalActionEnum;
import com.workorder.common.result.Result;
import com.workorder.dao.ApprovalLogMapper;
import com.workorder.dto.ApprovalDTO;
import com.workorder.entity.ApprovalLog;
import com.workorder.entity.WorkOrder;
import com.workorder.security.CustomUserDetails;
import com.workorder.service.IWorkOrderService;

/**
 * 审批操作控制器 提供审批通过、驳回、退回等操作接口
 *
 * @author KLord
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

  @Autowired private IWorkOrderService workOrderService;

  @Autowired private ApprovalLogMapper approvalLogMapper;

  /**
   * 执行审批操作（通过/驳回/退回）
   *
   * <p>阶段 3 §3 — @AntiReplay 防重放：前端必须传 X-Request-Nonce + X-Request-Timestamp 头
   */
  @PostMapping
  @PreAuthorize("hasAnyAuthority('system:user', 'workorder:approve', 'workorder:pending')")
  @AntiReplay(timeWindow = 300)
  public Result<WorkOrder> handleApproval(@Valid @RequestBody ApprovalDTO approvalDTO) {
    Long operatorId = getCurrentUserId();
    return workOrderService.handleApproval(approvalDTO, operatorId);
  }

  /** 快捷审批通过 */
  @PostMapping("/{workOrderId}/approve")
  @PreAuthorize("hasAnyAuthority('system:user', 'workorder:approve', 'workorder:pending')")
  @AntiReplay(timeWindow = 300)
  public Result<WorkOrder> quickApprove(
      @PathVariable Long workOrderId, @RequestParam(required = false) String comment) {
    ApprovalDTO dto = new ApprovalDTO();
    dto.setWorkOrderId(workOrderId);
    dto.setAction(ApprovalActionEnum.APPROVE.getCode());
    dto.setComment(comment);

    Long operatorId = getCurrentUserId();
    return workOrderService.handleApproval(dto, operatorId);
  }

  /** 快捷驳回 */
  @PostMapping("/{workOrderId}/reject")
  @PreAuthorize("hasAnyAuthority('system:user', 'workorder:approve', 'workorder:pending')")
  @AntiReplay(timeWindow = 300)
  public Result<WorkOrder> quickReject(
      @PathVariable Long workOrderId,
      @RequestParam(required = false, defaultValue = "") String reason) {
    // 如果reason为null或空字符串，使用默认原因
    if (reason == null || reason.trim().isEmpty()) {
      reason = "审批驳回";
    }

    ApprovalDTO dto = new ApprovalDTO();
    dto.setWorkOrderId(workOrderId);
    dto.setAction(ApprovalActionEnum.REJECT.getCode());
    dto.setComment(reason);

    Long operatorId = getCurrentUserId();
    return workOrderService.handleApproval(dto, operatorId);
  }

  /**
   * 查询工单的审批日志 权限：查看全部工单 / 我的工单 / 系统用户 均可访问
   *
   * <p>阶段 2 修复 A-12 / H-08：IDOR 越权防护 @DataPermission 校验当前用户对工单（workOrderId 即为工单主键）的所有权
   */
  @GetMapping("/{workOrderId}/logs")
  @PreAuthorize("hasAnyAuthority('workorder:view-all', 'workorder:mine', 'system:user')")
  @DataPermission(
      entityType = "APPROVAL_LOG",
      checkOwnership = true,
      resourceIdParam = "workOrderId")
  public Result<List<ApprovalLog>> getApprovalLog(@PathVariable Long workOrderId) {
    List<ApprovalLog> logs = approvalLogMapper.selectByWorkOrderId(workOrderId);
    return Result.success(logs);
  }

  /** 获取当前登录用户ID */
  private Long getCurrentUserId() {
    CustomUserDetails userDetails =
        (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return userDetails.getUserId();
  }
}
