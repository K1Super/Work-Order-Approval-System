package com.workorder.controller;


import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.common.result.Result;
import com.workorder.dto.LoginDTO;
import com.workorder.security.AuthCookieUtil;
import com.workorder.security.CustomUserDetails;
import com.workorder.security.JwtUtil;
import com.workorder.service.IAuthService;

/**
 * Authentication Controller Provides login, register, logout, token refresh and other interfaces
 *
 * <p>OPTIMIZATION 三.3.1 配套改造： - login：JWT Token 写入 HttpOnly; Secure; SameSite=Strict
 * Cookie（同时回填响应体供特殊流程） - logout：清除 Cookie - refresh-token：从 Cookie 或 Authorization Header 读取
 * Token，刷新后更新 Cookie - change-password：复用 JwtAuthenticationFilter 注入的 SecurityContext（无需手动读 Token）
 *
 * @author KLord
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

  @Autowired private IAuthService authService;

  @Autowired private JwtUtil jwtUtil;

  @Autowired private AuthCookieUtil authCookieUtil;

  /** JWT 有效期（毫秒）— 用于计算 Cookie Max-Age */
  @Value("${jwt.expiration:86400000}")
  private long jwtExpirationMs;

  /**
   * User login
   *
   * <p>OPTIMIZATION 三.3.1： 1. AuthService 完成认证后返回 Token + 用户信息 2. AuthController 把 Token 写入
   * HttpOnly; Secure; SameSite=Strict Cookie 3. W-47：响应体不再下发 token 到 JS 内存（移除 token 字段），强制改密流程
   * 改由浏览器凭 WOS_TOKEN HttpOnly Cookie 调用 /auth/change-password（JwtAuthenticationFilter 已支持从 Cookie 回退读取 Token）
   */
  @PostMapping("/sessions")
  public Result<Map<String, Object>> login(
      @Valid @RequestBody LoginDTO loginDTO, HttpServletResponse response) {
    Result<Map<String, Object>> result = authService.login(loginDTO);

    // 登录成功 → 设置 HttpOnly Cookie
    if (result.isSuccess() && result.getData() != null) {
      String token = (String) result.getData().get("token");
      if (token != null) {
        authCookieUtil.setAuthCookie(response, token, jwtExpirationMs);
        // W-47：Token 仅驻留在 HttpOnly Cookie，不下发到 JS 内存，避免被 XSS 旁路窃取
        result.getData().remove("token");
        logger.debug("[Login] 已设置 HttpOnly Cookie (Max-Age={}s)", jwtExpirationMs / 1000);
      }
    }

    return result;
  }

  /**
   * User registration — 仅超级管理员可调用（阶段 1 修复 A-13）
   *
   * <p>安全策略： 1. /auth/register 已从 SecurityConfig permitAll 移除 → 必须认证
   * 2. @PreAuthorize("hasRole('SUPER_ADMIN')") → 仅超管可创建账号 3.
   * 普通用户注册功能改为通过员工管理入口（EmployeeController.createEmployee）实现
   */
  @PostMapping("/users")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<?> register(@RequestBody Map<String, Object> registerInfo) {
    return authService.register(registerInfo);
  }

  /**
   * Refresh Token
   *
   * <p>OPTIMIZATION 三.3.1：从 Cookie 或 Authorization Header 读取旧 Token， 刷新后更新 HttpOnly Cookie。
   */
  @PostMapping("/sessions/refresh")
  public Result<Map<String, Object>> refreshToken(
      HttpServletRequest request, HttpServletResponse response) {
    String token = authCookieUtil.extractTokenFromRequest(request);
    if (token == null) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID, "缺少认证令牌，请重新登录");
    }

    Result<Map<String, Object>> result = authService.refreshToken(token);

    // 刷新成功 → 更新 HttpOnly Cookie
    if (result.isSuccess() && result.getData() != null) {
      String newToken = (String) result.getData().get("token");
      if (newToken != null) {
        authCookieUtil.setAuthCookie(response, newToken, jwtExpirationMs);
        logger.debug("[RefreshToken] 已更新 HttpOnly Cookie");
      }
    }

    return result;
  }

  /**
   * User logout
   *
   * <p>OPTIMIZATION 三.3.1： 1. 从 Cookie 或 Authorization Header 读取 Token 2. AuthService.logout 递增
   * token_version（旧 Token 立即失效） 3. 清除 HttpOnly Cookie
   */
  @DeleteMapping("/sessions")
  public Result<?> logout(HttpServletRequest request, HttpServletResponse response) {
    String token = authCookieUtil.extractTokenFromRequest(request);
    if (token == null) {
      // 即便没有 Token，也清除 Cookie（防止前端状态不一致）
      authCookieUtil.clearAuthCookie(response);
      return Result.success("注销成功");
    }

    Result<?> result = authService.logout(token);

    // 无论注销是否成功，都清除前端 Cookie
    authCookieUtil.clearAuthCookie(response);
    logger.debug("[Logout] 已清除 HttpOnly Cookie");

    return result;
  }

  /** Get current user info */
  @GetMapping("/me")
  public Result<Map<String, Object>> getCurrentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof CustomUserDetails) {
      CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
      return authService.getCurrentUser(userDetails.getUserId());
    }
    return Result.unauthorized("Not logged in");
  }

  /**
   * Change password (for first-time login forced change) 强制修改密码接口（首次登录时调用）
   *
   * <p>复用 JwtAuthenticationFilter 注入的 SecurityContext（从 Cookie 或 Header 提取的 Token 已通过校验）， 无需手动读取
   * Token。改密成功后 AuthService 递增 token_version，旧 Token 立即失效。 前端需重新登录获取新 Cookie。
   */
  @PutMapping("/password")
  public Result<?> changePassword(
      @RequestBody Map<String, String> passwordInfo, HttpServletResponse response) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof CustomUserDetails) {
      CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
      String oldPassword = passwordInfo.get("oldPassword");
      String newPassword = passwordInfo.get("newPassword");
      Result<?> result =
          authService.changePassword(userDetails.getUserId(), oldPassword, newPassword);

      // 改密成功 → token_version 已递增，旧 Cookie 失效，主动清除
      if (result.isSuccess()) {
        authCookieUtil.clearAuthCookie(response);
        logger.debug("[ChangePassword] 已清除旧 HttpOnly Cookie（token_version 已递增）");
      }
      return result;
    }
    return Result.unauthorized("Not logged in");
  }
}
