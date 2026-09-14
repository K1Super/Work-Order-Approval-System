package com.workorder.controller;


import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.annotation.RequireReAuth;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.common.result.Result;
import com.workorder.service.ISystemSettingService;

/**
 * 系统设置控制器（规范 §4 代码风格 — 消除魔法值 + 规范 §7 异常处理 — 统一异常抛出）
 *
 * <p>安全策略（阶段 1 修复 A-03）：
 *
 * <ol>
 *   <li>类级 @PreAuthorize("hasAuthority('system:user')") — 仅拥有用户管理权限的可访问
 *   <li>/security 子端点叠加 hasRole('SUPER_ADMIN') — 安全策略仅超管可改（防止普通管理员降级安全配置）
 * </ol>
 *
 * <p>异常处理改进：所有 try-catch + Result.error 替换为直接抛出 BusinessException(ErrorCode)， 由
 * GlobalExceptionHandler 统一捕获并返回标准 Result。
 *
 * @author KLord
 */
@RestController
@RequestMapping("/system/settings")
@PreAuthorize("hasAuthority('system:user')")
public class SystemSettingController {

  private static final Logger logger = LoggerFactory.getLogger(SystemSettingController.class);

  @Autowired private ISystemSettingService systemSettingService;

  /** 获取所有系统设置 */
  @GetMapping("/all")
  public Result<Map<String, Object>> getAllSettings() {
    Map<String, Object> settings = systemSettingService.getAllSettings();
    return Result.success(settings);
  }

  /** 保存基础设置 */
  @PutMapping("/basic")
  public Result<Void> saveBasicSettings(@RequestBody Map<String, Object> settings) {
    try {
      systemSettingService.saveBasicSettings(settings);
      return Result.success(null);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      logger.error("[系统设置] 基础设置保存失败", e);
      throw new BusinessException(ErrorCode.SETTINGS_SAVE_FAILED, e.getMessage());
    }
  }

  /** 保存工单设置 */
  @PutMapping("/workorder")
  public Result<Void> saveWorkorderSettings(@RequestBody Map<String, Object> settings) {
    try {
      systemSettingService.saveWorkorderSettings(settings);
      return Result.success(null);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      logger.error("[系统设置] 工单设置保存失败", e);
      throw new BusinessException(ErrorCode.SETTINGS_SAVE_FAILED, e.getMessage());
    }
  }

  /** 保存通知设置 */
  @PutMapping("/notification")
  public Result<Void> saveNotificationSettings(@RequestBody Map<String, Object> settings) {
    try {
      systemSettingService.saveNotificationSettings(settings);
      return Result.success(null);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      logger.error("[系统设置] 通知设置保存失败", e);
      throw new BusinessException(ErrorCode.SETTINGS_SAVE_FAILED, e.getMessage());
    }
  }

  /** 保存安全设置 — 仅超级管理员可修改安全策略 防止拥有 system:user 权限的非超管用户降级密码策略、关闭锁定等 */
  @PutMapping("/security")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @RequireReAuth(description = "修改安全设置")
  public Result<Void> saveSecuritySettings(@RequestBody Map<String, Object> settings) {
    try {
      systemSettingService.saveSecuritySettings(settings);
      return Result.success(null);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      logger.error("[系统设置] 安全设置保存失败", e);
      throw new BusinessException(ErrorCode.SETTINGS_SAVE_FAILED, e.getMessage());
    }
  }

  /** 测试邮件发送（预留接口） — 仅超管可测试邮件配置（含 SMTP 密码） */
  @PostMapping("/test-email")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<Void> testEmail(@RequestBody Map<String, Object> emailConfig) {
    String smtpHost = (String) emailConfig.get("smtpHost");
    Integer smtpPort = (Integer) emailConfig.get("smtpPort");
    String smtpUsername = (String) emailConfig.get("smtpUsername");
    // 注意：不记录 smtpPassword 到日志（规范 §8 数据保护）
    String senderEmail = (String) emailConfig.get("senderEmail");
    String senderName = (String) emailConfig.get("senderName");

    // 参数校验（规范 §8 输入验证 — 白名单优先）
    if (smtpHost == null || smtpHost.isEmpty()) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "smtpHost 不能为空");
    }
    if (smtpPort == null) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "smtpPort 不能为空");
    }

    logger.info(
        "[系统设置] 测试邮件配置 - Host: {}, Port: {}, User: {}, Sender: {} ({})",
        smtpHost,
        smtpPort,
        smtpUsername,
        senderEmail,
        senderName);

    // TODO: 接入实际邮件发送逻辑
    return Result.success(null);
  }
}
