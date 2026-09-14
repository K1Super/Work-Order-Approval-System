package com.workorder.controller;


import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.common.result.Result;
import com.workorder.service.IPasswordResetService;

/**
 * 密码重置 Controller
 *
 * <p>提供无需登录即可访问的密码重置端点（用户通过重置链接访问）： - POST /password-resets/validate 验证 token 有效性（前端展示用户名/过期时间） -
 * POST /password-resets/confirm 消费 token 并设置新密码
 *
 * <p>安全措施： - 端点在 SecurityConfig 中放行（permitAll），但 token 本身作为凭证 - token 一次性使用、15 分钟过期 - 新密码需通过密码策略校验
 * + 密码历史校验 - 操作记录审计日志
 *
 * <p>注意：生成 token 的接口在 /employees/{id}/reset-password（需登录 + system:user 权限）
 *
 * @author KLord
 */
@RestController
@RequestMapping("/password-resets")
public class PasswordResetController {

  private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);

  @Autowired private IPasswordResetService passwordResetService;

  /**
   * 验证重置令牌有效性
   *
   * <p>请求体：{ "token": "..." } 响应：{ "code": 200, "data": { "username": "...", "realName": "...",
   * "expiryTime": "..." } }
   *
   * <p>前端在用户打开重置链接时调用此接口，验证 token 是否有效， 并展示目标用户名和剩余时间。
   */
  @PostMapping("/validate")
  public Result<Map<String, Object>> validateToken(
      @RequestBody Map<String, String> body, HttpServletRequest request) {
    String token = body != null ? body.get("token") : null;
    logger.info("验证密码重置令牌: IP={}", getClientIp(request));
    return passwordResetService.validateResetToken(token);
  }

  /**
   * 消费重置令牌并设置新密码
   *
   * <p>请求体：{ "token": "...", "newPassword": "..." } 响应：{ "code": 200, "message": "密码重置成功，请使用新密码登录"
   * }
   *
   * <p>前端在用户输入新密码并提交时调用此接口。 成功后 token 失效，用户需使用新密码登录。
   */
  @PostMapping("/confirm")
  public Result<Void> confirmReset(
      @RequestBody Map<String, String> body, HttpServletRequest request) {
    if (body == null) {
      throw new BusinessException(ErrorCode.PARAM_INVALID, "请求体不能为空");
    }
    String token = body.get("token");
    String newPassword = body.get("newPassword");
    logger.info("消费密码重置令牌: IP={}", getClientIp(request));
    return passwordResetService.consumeResetToken(token, newPassword);
  }

  /** 获取客户端 IP（用于审计日志） */
  private String getClientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("Proxy-Client-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("WL-Proxy-Client-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getRemoteAddr();
    }
    // 多级代理时取第一个
    if (ip != null && ip.contains(",")) {
      ip = ip.split(",")[0].trim();
    }
    return ip;
  }
}
