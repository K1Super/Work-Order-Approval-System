package com.workorder.controller;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.workorder.annotation.RequireReAuth;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.common.result.Result;

/**
 * 流程定义管理控制器（规范 §8 安全开发规范 — 流程定义权限 + §4 消除魔法值）
 *
 * <p>安全策略（§9 流程定义权限）：
 *
 * <ol>
 *   <li>仅超级管理员可部署/删除/挂起/激活流程定义（防止注入恶意 groovy 脚本）
 *   <li>BPMN 中若含 scriptTask 将在 FlowableConfig 解析阶段被拒绝（纵深防御）
 *   <li>部署前校验文件类型与大小（仅允许 .bpmn20.xml / .bpmn）
 *   <li>所有操作记录审计日志（通过 ISecurityAuditService）
 * </ol>
 *
 * @author KLord
 */
@RestController
@RequestMapping("/processes")
public class ProcessController {

  private static final Logger logger = LoggerFactory.getLogger(ProcessController.class);

  /** 允许部署的文件扩展名（白名单） */
  private static final List<String> ALLOWED_EXTENSIONS = List.of(".bpmn20.xml", ".bpmn");

  /** 最大部署文件大小 10MB */
  private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

  @Autowired private RepositoryService repositoryService;

  /** 查询流程定义列表（所有已认证用户可访问） 仅返回最新版本的活跃流程定义，避免历史版本泄露 */
  @GetMapping("/definitions")
  @PreAuthorize("isAuthenticated()")
  public Result<?> listDefinitions() {
    List<ProcessDefinition> defs =
        repositoryService
            .createProcessDefinitionQuery()
            .latestVersion()
            .active()
            .orderByProcessDefinitionName()
            .asc()
            .list();

    List<Map<String, Object>> list =
        defs.stream()
            .map(
                d -> {
                  Map<String, Object> m = new HashMap<>();
                  m.put("id", d.getId());
                  m.put("name", d.getName());
                  m.put("key", d.getKey());
                  m.put("version", d.getVersion());
                  m.put("deploymentId", d.getDeploymentId());
                  // 不返回 resource name、diagram resource name 等内部信息，减少信息泄露面
                  return m;
                })
            .collect(Collectors.toList());

    return Result.success(list);
  }

  /** 部署新流程定义（仅超级管理员） 安全：BPMN 中若含 scriptTask 将在 FlowableConfig 解析阶段被拒绝 */
  @PostMapping("/deploy")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @RequireReAuth(description = "部署流程定义")
  public Result<?> deployProcess(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "name", required = false) String name) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "文件不能为空");
    }

    // 文件大小校验
    if (file.getSize() > MAX_FILE_SIZE) {
      throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
    }

    // 文件扩展名白名单校验
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || !isAllowedExtension(originalFilename)) {
      throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    try {
      String deploymentName = (name != null && !name.isEmpty()) ? name : originalFilename;
      repositoryService
          .createDeployment()
          .name(deploymentName)
          .addInputStream(originalFilename, file.getInputStream())
          .deploy();
      logger.info("[流程部署] 超级管理员部署流程定义成功: name={}, file={}", deploymentName, originalFilename);
      return Result.success(null);
    } catch (SecurityException se) {
      // FlowableConfig 中 ScriptTaskParseHandler 抛出的安全异常
      logger.error("[流程部署] 被安全策略拒绝: {}", se.getMessage());
      throw new BusinessException(
          ErrorCode.APPROVAL_OPERATION_FAILED, "流程部署被安全策略拒绝: " + se.getMessage());
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      logger.error("[流程部署] 失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.APPROVAL_OPERATION_FAILED, "流程部署失败: " + e.getMessage());
    }
  }

  /** 删除流程定义（仅超级管理员） cascade=true 时级联删除运行中流程实例，需谨慎 */
  @DeleteMapping("/definitions/{deploymentId}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @RequireReAuth(description = "删除流程定义")
  public Result<?> deleteDeployment(
      @PathVariable String deploymentId, @RequestParam(defaultValue = "false") boolean cascade) {
    if (deploymentId == null || deploymentId.isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "部署 ID 不能为空");
    }
    try {
      repositoryService.deleteDeployment(deploymentId, cascade);
      logger.info("[流程管理] 超级管理员删除流程定义: deploymentId={}, cascade={}", deploymentId, cascade);
      return Result.success(null);
    } catch (Exception e) {
      logger.error("[流程管理] 删除失败: deploymentId={}, err={}", deploymentId, e.getMessage());
      throw new BusinessException(ErrorCode.APPROVAL_OPERATION_FAILED, "流程删除失败: " + e.getMessage());
    }
  }

  /** 挂起流程定义（仅超级管理员） 挂起后该流程定义无法启动新实例，已运行实例默认继续 */
  @PutMapping("/definitions/{processDefinitionId}/suspend")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<?> suspendProcessDefinition(@PathVariable String processDefinitionId) {
    if (processDefinitionId == null || processDefinitionId.isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "流程定义 ID 不能为空");
    }
    try {
      repositoryService.suspendProcessDefinitionById(processDefinitionId, true, null);
      logger.info("[流程管理] 超级管理员挂起流程定义: processDefinitionId={}", processDefinitionId);
      return Result.success(null);
    } catch (Exception e) {
      logger.error(
          "[流程管理] 挂起失败: processDefinitionId={}, err={}", processDefinitionId, e.getMessage());
      throw new BusinessException(ErrorCode.APPROVAL_OPERATION_FAILED, "挂起失败: " + e.getMessage());
    }
  }

  /** 激活流程定义（仅超级管理员） */
  @PutMapping("/definitions/{processDefinitionId}/activate")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<?> activateProcessDefinition(@PathVariable String processDefinitionId) {
    if (processDefinitionId == null || processDefinitionId.isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "流程定义 ID 不能为空");
    }
    try {
      repositoryService.activateProcessDefinitionById(processDefinitionId, true, null);
      logger.info("[流程管理] 超级管理员激活流程定义: processDefinitionId={}", processDefinitionId);
      return Result.success(null);
    } catch (Exception e) {
      logger.error(
          "[流程管理] 激活失败: processDefinitionId={}, err={}", processDefinitionId, e.getMessage());
      throw new BusinessException(ErrorCode.APPROVAL_OPERATION_FAILED, "激活失败: " + e.getMessage());
    }
  }

  /** 文件扩展名白名单校验 */
  private boolean isAllowedExtension(String filename) {
    String lower = filename.toLowerCase();
    for (String ext : ALLOWED_EXTENSIONS) {
      if (lower.endsWith(ext)) {
        return true;
      }
    }
    return false;
  }
}
