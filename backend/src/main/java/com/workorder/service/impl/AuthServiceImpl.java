package com.workorder.service.impl;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.result.Result;
import com.workorder.config.MetricsConfig;
import com.workorder.dao.UserMapper;
import com.workorder.dto.LoginDTO;
import com.workorder.dto.RoleInfoDTO;
import com.workorder.entity.User;
import com.workorder.security.CustomUserDetails;
import com.workorder.security.EncryptionHelper;
import com.workorder.security.JwtUtil;
import com.workorder.security.TokenVersionCache;
import com.workorder.service.IAuthService;
import com.workorder.service.IPasswordPolicyService;
import com.workorder.service.ISecurityAuditService;
import com.workorder.service.ISystemSettingService;

/**
 * Authentication Service Implementation - Enterprise Edition 认证服务实现 - 企业级完整版
 *
 * <p>Features: - Password policy enforcement (complexity, expiration, history) - Account lockout
 * mechanism (brute force protection) - Security audit logging for all authentication events - Token
 * management with Redis - Comprehensive error handling and fault tolerance
 *
 * @author KLord
 */
@Service
public class AuthServiceImpl implements IAuthService {

  private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

  @Autowired private AuthenticationManager authenticationManager;

  @Autowired private UserMapper userMapper;

  @Autowired private JwtUtil jwtUtil;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  @Autowired private IPasswordPolicyService passwordPolicyService;

  @Autowired private ISecurityAuditService securityAuditService;

  @Autowired private ISystemSettingService systemSettingService;

  @Autowired private BCryptPasswordEncoder passwordEncoder;

  @Autowired private MetricsConfig metricsConfig;

  /** tokenVersion 二级缓存（注销/改密后失效） */
  @Autowired private TokenVersionCache tokenVersionCache;

  /** 加密上下文辅助工具（OPTIMIZATION 三.3.3 per-user DEK） */
  @Autowired private EncryptionHelper encryptionHelper;

  /** Token prefix in Redis */
  private static final String TOKEN_PREFIX = "auth:token:";

  /** 反代白名单（逗号分隔 IP）— W-16：仅当 remoteAddr 命中白名单时才信任 XFF 等代理头 */
  @Value("${work-order-system.rate-limit.trusted-proxies:}")
  private String trustedProxies;

  /** Default token expiration time (hours) */
  private static final long DEFAULT_TOKEN_EXPIRE_HOURS = 24;

  /**
   * User login authentication with full enterprise security features 用户登录认证（完整企业级安全功能）
   *
   * <p>Security Flow: 1. Check account lockout status 2. Authenticate credentials via Spring
   * Security 3. Check password expiration policy 4. Reset failed attempt counter on success 5.
   * Generate JWT token with configurable expiry 6. Store token in Redis for management 7. Cache
   * user permissions to reduce DB access 8. Record comprehensive audit logs 9. Build response with
   * all required information
   *
   * @param loginDTO Login data transfer object containing username and password
   * @return Result containing token, userId, username, roles, permissions, expiration info
   */
  @Override
  public Result<Map<String, Object>> login(LoginDTO loginDTO) {
    String username = loginDTO.getUsername();
    String clientIp = getClientIp();
    String userAgent = getUserAgent();

    logger.info("用户尝试登录: {}, IP: {}", username, clientIp);

    // ============================================
    // 正确的登录流程顺序
    // 1. 先进行Spring Security认证（验证用户是否存在+密码是否正确）
    // 2. 认证失败后才记录失败次数和检查锁定状态
    // 3. 这样可以避免对不存在的账号误报"已锁定"
    // ============================================

    try {
      // ============================================
      // STEP 1: Authenticate credentials via Spring Security
      // 验证用户名和密码（同时验证用户是否存在）
      // ============================================
      logger.debug("开始Spring Security认证用户: {}", username);

      Authentication authentication =
          authenticationManager.authenticate(
              new UsernamePasswordAuthenticationToken(username, loginDTO.getPassword()));

      // ============================================
      // STEP 2: Get authenticated user information
      // ============================================
      CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
      // OPTIMIZATION 三.3.3：包裹 DekContext 以便 TypeHandler 使用 per-user DEK 解密 email/phone
      User user =
          encryptionHelper.executeWithDekContext(
              userDetails.getUserId(), () -> userMapper.selectById(userDetails.getUserId()));

      if (user == null) {
        logger.error("用户不存在: userId={}", userDetails.getUserId());
        securityAuditService.logLoginFailure(username, clientIp, userAgent, "用户信息不存在");
        throw new BusinessException("用户信息不存在，请联系管理员");
      }

      logger.info("Spring Security认证成功: {} ({})", username, userDetails.getRealName());

      // ============================================
      // STEP 3: Check password expiration policy (90-day rule)
      // ============================================
      boolean passwordExpired = false;
      try {
        if (passwordPolicyService.isPasswordExpired(user)) {
          logger.warn("用户 {} 的密码已过期，需要强制更改", username);
          passwordExpired = true;
          // Allow login but flag for forced password change
          securityAuditService.logPasswordChange(
              userDetails.getUserId(), username, clientIp, true); // forcedByExpiry=true
        }
      } catch (Exception e) {
        logger.warn("密码过期检查失败（允许继续）: {}", e.getMessage());
      }

      // ============================================
      // STEP 4: Reset failed login attempt counter on success
      // ============================================
      try {
        passwordPolicyService.resetLoginAttempts(username, clientIp);
        logger.debug("已重置用户 {} (IP: {}) 的登录失败计数器", username, clientIp);
      } catch (Exception e) {
        logger.warn("重置登录计数器失败（不影响登录）: {}", e.getMessage());
      }

      // ============================================
      // STEP 5: Get token expiration time from system settings
      // ============================================
      long tokenExpiryHours = DEFAULT_TOKEN_EXPIRE_HOURS;
      try {
        String expirySetting = systemSettingService.getSettingValue("tokenExpiryHours", "24");
        tokenExpiryHours = Long.parseLong(expirySetting);
        logger.debug("Token有效期: {} 小时", tokenExpiryHours);
      } catch (Exception e) {
        logger.warn("获取Token配置失败，使用默认值24小时: {}", e.getMessage());
      }

      // ============================================
      // STEP 6: Generate JWT token (携带 tokenVersion — OPTIMIZATION 三.3.1)
      // 从数据库读取用户当前 token_version，写入 JWT 荷载；
      // JwtAuthenticationFilter 解析 Token 后与 DB 版本号比对，不匹配直接拒绝。
      // ============================================
      Long tokenVersion = user.getTokenVersion();
      if (tokenVersion == null) {
        // 兼容老用户：DB 未设置 token_version 时视为 0
        tokenVersion = 0L;
        logger.warn("用户 {} 的 token_version 为空，已兜底为 0（建议检查 V7 迁移是否已应用）", username);
      }
      String token =
          jwtUtil.generateToken(userDetails.getUsername(), userDetails.getUserId(), tokenVersion);
      logger.debug("JWT Token已生成 (tokenVersion={})", tokenVersion);

      // ============================================
      // STEP 7: Store token in Redis (for token management & offline invalidation)
      // ============================================
      try {
        String redisKey = TOKEN_PREFIX + userDetails.getUserId();
        redisTemplate.opsForValue().set(redisKey, token, tokenExpiryHours, TimeUnit.HOURS);
        logger.debug("Token已存储到Redis: key={}", redisKey);
      } catch (Exception e) {
        logger.error("Redis存储Token失败（不影响登录）: {}", e.getMessage());
        // Continue even if Redis fails - token will still work until expiry
      }

      // ============================================
      // STEP 8: Cache user permissions to Redis (reduce database load)
      // ============================================
      try {
        cacheUserPermissions(userDetails.getUserId(), tokenExpiryHours);
        logger.debug("已缓存用户权限到Redis");
      } catch (Exception e) {
        logger.warn("缓存权限到Redis失败（不影响登录）: {}", e.getMessage());
      }

      // ============================================
      // STEP 9: Record successful login audit log
      // ============================================
      try {
        securityAuditService.logLoginSuccess(
            userDetails.getUserId(), username, clientIp, userAgent);
        logger.info("已记录成功登录审计日志");
      } catch (Exception e) {
        logger.warn("记录审计日志失败（不影响登录）: {}", e.getMessage());
      }

      // ============================================
      // STEP 10: Build comprehensive return result
      // ============================================
      Map<String, Object> result = new HashMap<>();

      // Basic auth info
      result.put("token", token);
      result.put("userId", userDetails.getUserId());
      result.put("username", userDetails.getUsername());
      result.put("realName", userDetails.getRealName());

      // User roles (with type conversion)
      try {
        List<RoleInfoDTO> roleInfoList = userMapper.selectRolesByUserId(userDetails.getUserId());
        List<String> roles =
            roleInfoList.stream().map(RoleInfoDTO::getRoleCode).collect(Collectors.toList());
        result.put("roles", roles);
        logger.debug("用户角色: {}", roles);
      } catch (Exception e) {
        logger.warn("获取角色失败，使用默认角色USER: {}", e.getMessage());
        result.put("roles", List.of("USER"));
      }

      // User permissions
      try {
        List<String> permissions =
            userMapper.selectPermissionCodesByUserId(userDetails.getUserId());
        result.put("permissions", permissions);
        logger.debug("用户权限数量: {}", permissions.size());
      } catch (Exception e) {
        logger.warn("获取权限失败，使用空列表: {}", e.getMessage());
        result.put("permissions", List.of());
      }

      // Token expiration timestamp
      // 修复 bug：getExpirationDateFromToken().getTime() 已是绝对时间戳，不应再加 System.currentTimeMillis()
      try {
        long expiration = jwtUtil.getExpirationDateFromToken(token).getTime();
        result.put("expiration", expiration);
      } catch (Exception e) {
        logger.warn("计算过期时间失败，使用当前时间 + {}h: {}", tokenExpiryHours, e.getMessage());
        result.put("expiration", System.currentTimeMillis() + (tokenExpiryHours * 60 * 60 * 1000L));
      }

      // Password status
      result.put("passwordExpired", passwordExpired);

      // ============================================
      // STEP 11: Check if first-time login (need to force password change)
      // ============================================
      boolean needChangePassword = false;
      try {
        // 检查是否首次登录（passwordChanged == false 或 null）
        if (user.getPasswordChanged() == null || !user.getPasswordChanged()) {
          logger.warn("用户 {} 是首次登录，需要强制修改密码", username);
          needChangePassword = true;
        }
      } catch (Exception e) {
        logger.warn("检测首次登录状态失败（默认不需要改密）: {}", e.getMessage());
      }
      result.put("needChangePassword", needChangePassword);

      // ============================================
      // SUCCESS: Log completion and return
      // ============================================
      logger.info(
          "用户 {} 登录成功！Token有效期: {}小时, 密码过期: {}, 首次登录: {}",
          username,
          tokenExpiryHours,
          passwordExpired ? "是" : "否",
          needChangePassword ? "是" : "否");

      // Prometheus 埋点：记录登录成功
      metricsConfig.recordLoginAttempt(true);

      return Result.success(result);

    } catch (BusinessException e) {
      // Re-throw business exceptions as-is (already formatted)
      logger.warn("业务异常: {}", e.getMessage());
      // Prometheus 埋点：业务异常视为登录失败
      metricsConfig.recordLoginAttempt(false);
      throw e;

    } catch (Exception e) {
      // ============================================
      // FAILURE HANDLING: Comprehensive error processing
      // ============================================

      // Prometheus 埋点：记录登录失败
      metricsConfig.recordLoginAttempt(false);

      logger.error("登录失败详情:");
      logger.error("   用户名: {}", username);
      logger.error("   异常类型: {}", e.getClass().getSimpleName());
      logger.error("   异常消息: {}", e.getMessage());
      logger.error("   堆栈跟踪:", e);

      // Step A: Record failed attempt in password policy service
      Object attemptResult = null;
      String lockedMessage = null;
      try {
        attemptResult = passwordPolicyService.recordFailedLoginAttempt(username, clientIp);

        // Extract error message from LoginAttemptResult using reflection
        if (attemptResult != null) {
          try {
            java.lang.reflect.Method getMessageMethod =
                attemptResult.getClass().getMethod("getMessage");
            String policyMessage = (String) getMessageMethod.invoke(attemptResult);

            // Check if account is now locked
            java.lang.reflect.Method isLockedMethod =
                attemptResult.getClass().getMethod("isLocked");
            boolean isLocked = (Boolean) isLockedMethod.invoke(attemptResult);

            if (isLocked) {
              // Account has been locked due to too many failures
              logger.error("账号 {} 已被自动锁定！", username);

              try {
                int maxAttempts =
                    Integer.parseInt(systemSettingService.getSettingValue("maxLoginAttempts", "5"));
                int lockoutMinutes =
                    Integer.parseInt(
                        systemSettingService.getSettingValue("lockoutDurationMinutes", "30"));

                securityAuditService.logAccountLocked(
                    username, clientIp, maxAttempts, lockoutMinutes);

                logger.info("已记录账号锁定审计日志: 最大尝试{}次, 锁定{}分钟", maxAttempts, lockoutMinutes);
              } catch (Exception auditEx) {
                logger.warn("记录锁定审计日志失败: {}", auditEx.getMessage());
              }

              // 修复：不在内层 try 中直接 throw —— 会被下方 catch (Exception reflectEx) 吞掉，
              // 导致锁定账号仍返回「用户名或密码错误」。先记录消息，Step B 审计完成后统一抛出。
              lockedMessage = policyMessage;
            }

            logger.warn("登录失败统计: {}", policyMessage);

          } catch (Exception reflectEx) {
            logger.debug("反射调用LoginAttemptResult方法失败: {}", reflectEx.getMessage());
          }
        }
      } catch (Exception policyEx) {
        logger.error("记录登录失败尝试时出错: {}", policyEx.getMessage());
      }

      // Step B: Record failure in audit log
      try {
        securityAuditService.logLoginFailure(
            username, clientIp, userAgent, e.getMessage() != null ? e.getMessage() : "认证失败");
        logger.debug("已记录登录失败审计日志");
      } catch (Exception auditEx) {
        logger.warn("记录审计日志失败: {}", auditEx.getMessage());
      }

      // 账号已锁定：抛出业务异常（位于 reflectEx 的 catch 之外，确保不被吞掉）
      if (lockedMessage != null) {
        throw new BusinessException(lockedMessage);
      }

      // Step C: Determine friendly error message based on exception type
      String errorMessage;
      String exceptionMsg = e.getMessage();

      if (exceptionMsg != null && exceptionMsg.contains("Bad credentials")) {
        errorMessage = "用户名或密码错误";
      } else if (exceptionMsg != null && exceptionMsg.contains("locked")) {
        errorMessage = "账号已被锁定，请联系管理员";
      } else if (exceptionMsg != null && exceptionMsg.contains("disabled")) {
        errorMessage = "账号已被禁用，请联系管理员";
      } else if (exceptionMsg != null && exceptionMsg.contains("credentials")) {
        errorMessage = "用户名或密码错误";
      } else {
        errorMessage = "用户名或密码错误";
      }

      logger.warn("返回给用户的友好错误消息: {}", errorMessage);
      // 使用 40101 业务码（未授权/认证失败），避免响应体携带误导性的 50001 被误判为系统/SQL 异常
      throw new BusinessException(Result.UNAUTHORIZED, errorMessage);
    }
  }

  /**
   * Cache user permissions to Redis to reduce database queries 将用户权限缓存到Redis以减少数据库查询
   *
   * @param userId User ID
   * @param expiryHours Cache expiration time in hours
   */
  private void cacheUserPermissions(Long userId, long expiryHours) {
    try {
      String cacheKey = "auth:permissions:" + userId;

      // Get current permissions
      List<String> permissions = userMapper.selectPermissionCodesByUserId(userId);

      // Cache to Redis
      redisTemplate.opsForValue().set(cacheKey, permissions, expiryHours, TimeUnit.HOURS);

      logger.debug("已缓存用户 {} 的 {} 个权限到Redis", userId, permissions.size());
    } catch (Exception e) {
      logger.warn("缓存权限到Redis失败: {}", e.getMessage());
      // Don't throw - this is optimization only
    }
  }

  /**
   * Get client IP address from request context 从请求上下文中获取客户端IP地址。
   *
   * <p>W-16：账号锁定/审计键一律以 request.getRemoteAddr() 为准（伪造 XFF 不能绕锁）。
   * 仅当 remoteAddr 命中配置的反代白名单（work-order-system.rate-limit.trusted-proxies）时，
   * 才回退解析 XFF/X-Real-IP 等代理头；否则直接返回 RemoteAddr。
   *
   * @return Client IP address or "unknown" if unavailable
   */
  private String getClientIp() {
    try {
      ServletRequestAttributes attributes =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

      if (attributes != null) {
        HttpServletRequest request = attributes.getRequest();

        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
          return (remoteAddr != null && !remoteAddr.isEmpty()) ? remoteAddr : "unknown";
        }

        // 仅信任白名单内的反向代理：才解析 X-Forwarded-For 等代理头
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
          ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
          ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
          ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
          ip = request.getRemoteAddr();
        }

        // For multiple proxies, take first IP
        if (ip != null && ip.contains(",")) {
          ip = ip.split(",")[0].trim();
        }

        return ip;
      }
    } catch (Exception e) {
      logger.debug("获取客户端IP失败: {}", e.getMessage());
    }
    return "unknown";
  }

  /** 判断远端地址是否为配置的反代白名单成员（精确匹配，支持 IPv4/IPv6 字面量） */
  private boolean isTrustedProxy(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isEmpty()) {
      return false;
    }
    if (trustedProxies == null || trustedProxies.trim().isEmpty()) {
      return false;
    }
    for (String proxy : trustedProxies.split(",")) {
      String candidate = proxy.trim();
      if (!candidate.isEmpty() && candidate.equals(remoteAddr)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get User-Agent string from request context 从请求上下文中获取User-Agent字符串
   *
   * @return User-Agent string or empty string if unavailable
   */
  private String getUserAgent() {
    try {
      ServletRequestAttributes attributes =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

      if (attributes != null) {
        HttpServletRequest request = attributes.getRequest();
        String userAgent = request.getHeader("User-Agent");
        return userAgent != null ? userAgent : "";
      }
    } catch (Exception e) {
      logger.debug("获取User-Agent失败: {}", e.getMessage());
    }
    return "";
  }

  /**
   * Get current user information by user ID 根据用户ID获取当前用户信息
   *
   * @param userId User ID
   * @return Result containing user information including roles and permissions
   */
  @Override
  public Result<Map<String, Object>> getCurrentUser(Long userId) {
    try {
      logger.debug("获取用户信息: userId={}", userId);

      // OPTIMIZATION 三.3.3：包裹 DekContext 以便 TypeHandler 使用 per-user DEK 解密 email/phone
      User user =
          encryptionHelper.executeWithDekContext(userId, () -> userMapper.selectById(userId));
      if (user == null) {
        logger.warn("用户不存在: userId={}", userId);
        return Result.error("用户不存在");
      }

      Map<String, Object> userInfo = new HashMap<>();
      userInfo.put("id", user.getId());
      userInfo.put("username", user.getUsername());
      userInfo.put("realName", user.getRealName());
      userInfo.put("email", user.getEmail());
      userInfo.put("phone", user.getPhone());
      userInfo.put("avatar", user.getAvatar());
      userInfo.put("status", user.getStatus());
      // 部门信息（用于工单创建时自动回填）
      userInfo.put("department", user.getDepartment());
      userInfo.put("departmentId", user.getDepartmentId());
      // 组织层级（用于优先级权限控制：<=2为管理层可自由选高/紧急）
      userInfo.put("orgLevel", user.getOrgLevel());

      // Get roles with fault tolerance
      try {
        List<RoleInfoDTO> roleInfoList = userMapper.selectRolesByUserId(userId);
        userInfo.put("roles", roleInfoList);
      } catch (Exception e) {
        logger.warn("获取用户角色失败: {}", e.getMessage());
        userInfo.put("roles", List.of());
      }

      // Get permissions with fault tolerance
      try {
        List<String> permissions = userMapper.selectPermissionCodesByUserId(userId);
        userInfo.put("permissions", permissions);
      } catch (Exception e) {
        logger.warn("获取用户权限失败: {}", e.getMessage());
        userInfo.put("permissions", List.of());
      }

      logger.info("成功获取用户信息: {} ({})", user.getUsername(), user.getRealName());
      return Result.success(userInfo);

    } catch (Exception e) {
      logger.error("获取用户信息失败: userId={}, error={}", userId, e.getMessage(), e);
      return Result.error("获取用户信息失败，请稍后重试");
    }
  }

  /**
   * Logout - invalidate token and clear session data 注销 - 使Token失效并清除会话数据
   *
   * @param token JWT Token to invalidate
   * @return Result indicating logout success
   */
  @Override
  public Result<?> logout(String token) {
    try {
      logger.info("用户请求注销...");

      // Try to extract user info from token for logging
      try {
        String username = jwtUtil.getUsernameFromToken(token);
        Long userId = jwtUtil.getUserIdFromToken(token);
        logger.info("👤 用户注销: {} (ID: {})", username, userId);

        // ============================================
        // OPTIMIZATION 三.3.1：递增 token_version
        // 注销后所有携带旧版本号的 JWT 立即失效，
        // 杜绝"注销后旧 Token 仍可访问"的安全风险
        // ============================================
        try {
          int bumped = userMapper.incrementTokenVersion(userId);
          if (bumped > 0) {
            logger.info("用户 {} 注销时 token_version 已递增（旧 Token 立即失效）", userId);
            tokenVersionCache.evict(userId);
          }
        } catch (Exception bumpEx) {
          logger.warn("注销时递增 token_version 失败（不影响注销）: {}", bumpEx.getMessage());
        }

        // Remove token from Redis if exists
        try {
          String redisKey = TOKEN_PREFIX + userId;
          Boolean deleted = redisTemplate.delete(redisKey);
          if (Boolean.TRUE.equals(deleted)) {
            logger.debug("已从Redis删除Token");
          } else {
            logger.debug("ℹToken未在Redis中找到（可能已过期）");
          }
        } catch (Exception redisEx) {
          logger.warn("删除Redis Token失败（不影响注销）: {}", redisEx.getMessage());
        }

        // Clear permission cache
        try {
          String cacheKey = "auth:permissions:" + userId;
          redisTemplate.delete(cacheKey);
          logger.debug("已清除权限缓存");
        } catch (Exception cacheEx) {
          logger.warn("清除权限缓存失败: {}", cacheEx.getMessage());
        }

        // Audit logout event
        try {
          securityAuditService.audit(
              userId,
              username,
              "LOGOUT",
              "用户主动注销",
              getClientIp(),
              getUserAgent(),
              "/api/auth/logout",
              "SUCCESS",
              Map.of("action", "logout"));
          logger.debug("已记录注销审计日志");
        } catch (Exception auditEx) {
          logger.warn("⚠️ 记录注销审计日志失败: {}", auditEx.getMessage());
        }

      } catch (Exception tokenEx) {
        logger.warn("解析Token信息失败（继续注销流程）: {}", tokenEx.getMessage());
      }

      logger.info("注销成功");
      return Result.success("注销成功");

    } catch (Exception e) {
      logger.error("注销失败: error={}", e.getMessage(), e);
      // Even if there's an error, return success (token will auto-expire anyway)
      return Result.success("注销成功");
    }
  }

  /**
   * Refresh Token - generate new token from existing valid token 刷新Token - 从有效的现有令牌生成新令牌
   *
   * @param token Current valid JWT token
   * @return Result containing new token and updated user information
   */
  @Override
  public Result<Map<String, Object>> refreshToken(String token) {
    try {
      logger.info("请求刷新Token...");

      // Validate existing token
      String username = jwtUtil.getUsernameFromToken(token);
      Long userId = jwtUtil.getUserIdFromToken(token);

      if (username == null || userId == null) {
        logger.warn("无效的Token：无法解析用户信息");
        return Result.error("无效的Token，请重新登录");
      }

      logger.debug("Token解析成功: user={}, id={}", username, userId);

      // Check if old token exists in Redis (optional validation)
      try {
        String oldRedisKey = TOKEN_PREFIX + userId;
        Object storedToken = redisTemplate.opsForValue().get(oldRedisKey);
        if (storedToken != null && !storedToken.equals(token)) {
          logger.warn("Token不匹配，可能已在其他设备刷新");
          // Continue anyway - allow refresh for better UX
        }
      } catch (Exception redisEx) {
        logger.debug("Redis验证跳过: {}", redisEx.getMessage());
      }

      // Generate new token
      // OPTIMIZATION 三.3.1：刷新时从 DB 重新读取 tokenVersion，
      // 避免使用旧 Token 中的版本号（用户可能在 Token 过期前已改密，旧版本号已失效）
      User currentUser =
          encryptionHelper.executeWithDekContext(userId, () -> userMapper.selectById(userId));
      if (currentUser == null) {
        logger.warn("刷新Token失败：用户不存在 userId={}", userId);
        return Result.error("用户不存在，请重新登录");
      }
      Long currentTokenVersion =
          currentUser.getTokenVersion() != null ? currentUser.getTokenVersion() : 0L;

      // 校验旧 Token 中的 tokenVersion 与 DB 是否一致
      // 不一致 → 用户已改密或被禁用，旧 Token 应该已经失效，拒绝刷新
      Long tokenVersionInToken = jwtUtil.getTokenVersionFromToken(token);
      if (tokenVersionInToken == null || !tokenVersionInToken.equals(currentTokenVersion)) {
        logger.warn(
            "刷新Token拒绝：Token 版本号与 DB 不一致 (token={}, db={})",
            tokenVersionInToken,
            currentTokenVersion);
        return Result.error("Token 已失效，请重新登录");
      }

      String newToken = jwtUtil.generateToken(username, userId, currentTokenVersion);
      logger.debug("新Token已生成 (tokenVersion={})", currentTokenVersion);

      // Get token expiry settings
      long tokenExpiryHours = DEFAULT_TOKEN_EXPIRE_HOURS;
      try {
        String expirySetting = systemSettingService.getSettingValue("tokenExpiryHours", "24");
        tokenExpiryHours = Long.parseLong(expirySetting);
      } catch (Exception e) {
        logger.warn("使用默认Token有效期: 24小时");
      }

      // Update Redis with new token
      try {
        String redisKey = TOKEN_PREFIX + userId;
        redisTemplate.opsForValue().set(redisKey, newToken, tokenExpiryHours, TimeUnit.HOURS);
        logger.debug("新Token已更新到Redis");
      } catch (Exception redisEx) {
        logger.warn("更新Redis失败（不影响刷新）: {}", redisEx.getMessage());
      }

      // Build result
      Map<String, Object> result = new HashMap<>();
      result.put("token", newToken);
      result.put("userId", userId);
      result.put("username", username);

      // Calculate new expiration time
      // 修复 bug：getExpirationDateFromToken().getTime() 已是绝对时间戳，不应再加 System.currentTimeMillis()
      try {
        long expiration = jwtUtil.getExpirationDateFromToken(newToken).getTime();
        result.put("expiration", expiration);
      } catch (Exception e) {
        result.put("expiration", System.currentTimeMillis() + (tokenExpiryHours * 60 * 60 * 1000L));
      }

      // Refresh permission cache
      try {
        cacheUserPermissions(userId, tokenExpiryHours);
      } catch (Exception e) {
        logger.debug("权限缓存刷新失败（不影响）: {}", e.getMessage());
      }

      // Audit token refresh
      try {
        securityAuditService.audit(
            userId,
            username,
            "TOKEN_REFRESH",
            "Token刷新成功",
            getClientIp(),
            getUserAgent(),
            "/api/auth/refresh",
            "SUCCESS",
            Map.of("action", "refresh_token"));
      } catch (Exception auditEx) {
        logger.debug("审计日志记录失败: {}", auditEx.getMessage());
      }

      logger.info("Token刷新成功: user={}, 新有效期={}小时", username, tokenExpiryHours);
      return Result.success(result);

    } catch (Exception e) {
      logger.error("Token刷新失败: error={}", e.getMessage(), e);
      return Result.error("Token刷新失败，请稍后重试");
    }
  }

  /**
   * User registration (currently disabled - admin-only) 用户注册（当前禁用 - 仅管理员可创建）
   *
   * @param registerInfo Registration information map
   * @return Result indicating registration status or error message
   */
  @Override
  public Result<?> register(Map<String, Object> registerInfo) {
    try {
      String username = (String) registerInfo.get("username");
      logger.info("收到注册请求: {}", username);

      // TODO: Implement registration logic when needed
      // For enterprise systems, self-registration should be disabled
      // Users must be created by administrators

      logger.warn("注册功能未开放: 用户 {}", username);
      return Result.error("注册功能暂未开放，请联系管理员创建账号");

    } catch (Exception e) {
      logger.error("注册请求处理失败: error={}", e.getMessage(), e);
      return Result.error("注册请求处理失败，请稍后重试");
    }
  }

  /**
   * Change password (for first-time login forced change) 修改密码（首次登录强制改密）
   *
   * @param userId User ID
   * @param oldPassword Old password
   * @param newPassword New password
   * @return Operation result
   */
  @Override
  public Result<?> changePassword(Long userId, String oldPassword, String newPassword) {
    try {
      logger.info("用户 {} 请求修改密码", userId);

      // 1. 查询用户信息（OPTIMIZATION 三.3.3：包裹 DekContext）
      User user =
          encryptionHelper.executeWithDekContext(userId, () -> userMapper.selectById(userId));
      if (user == null) {
        return Result.error("用户不存在");
      }

      // 2. 验证旧密码是否正确
      if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
        logger.warn("用户 {} 输入的旧密码不正确", userId);
        // 修复 bug：第二个参数应为 IP，原代码错误地传了两次 getUserAgent()
        securityAuditService.logLoginFailure(
            user.getUsername(), getClientIp(), getUserAgent(), "旧密码错误");
        return Result.error("旧密码不正确");
      }

      // 3. 验证新密码是否符合安全策略
      IPasswordPolicyService.PasswordValidationResult validationResult =
          passwordPolicyService.validatePassword(newPassword);
      if (!validationResult.isValid()) {
        logger.warn("用户 {} 新密码不符合安全策略: {}", userId, validationResult.getMessage());
        return Result.error(validationResult.getMessage());
      }

      // 4. 检查新密码不能与旧密码相同
      if (oldPassword.equals(newPassword)) {
        return Result.error("新密码不能与旧密码相同");
      }

      // 5. 更新密码
      String encodedNewPassword = passwordEncoder.encode(newPassword);
      user.setPassword(encodedNewPassword);

      // 标记为已修改初始密码
      user.setPasswordChanged(true);

      // 修复 bug：使用 updatePassword 而非 updateById
      // updatePassword 同时更新 password_change_time = CURRENT_TIMESTAMP，
      // 确保 90 天密码过期策略正常工作；updateById 不会更新 password_change_time
      int result = userMapper.updatePassword(user);
      if (result > 0) {
        logger.info("用户 {} 密码修改成功（password_change_time 已更新）", userId);

        // ============================================
        // OPTIMIZATION 三.3.1：递增 token_version
        // 改密后所有携带旧版本号的 JWT 立即失效，
        // 杜绝"改密后旧 Token 仍可访问"的安全风险
        // ============================================
        try {
          int bumped = userMapper.incrementTokenVersion(userId);
          if (bumped > 0) {
            logger.info("用户 {} 的 token_version 已递增（旧 Token 立即失效）", userId);
            tokenVersionCache.evict(userId);
          } else {
            logger.warn("用户 {} 的 token_version 递增返回 0 行（可能用户已被删除）", userId);
          }
        } catch (Exception bumpEx) {
          logger.error("递增 token_version 失败（关键安全步骤，请人工核查）: {}", bumpEx.getMessage(), bumpEx);
        }

        // 同步清除 Redis 中的旧 Token 缓存（双保险）
        try {
          String redisKey = TOKEN_PREFIX + userId;
          redisTemplate.delete(redisKey);
          logger.debug("已清除用户 {} 的 Redis Token 缓存", userId);
        } catch (Exception redisEx) {
          logger.warn("清除 Redis Token 缓存失败（不影响改密结果）: {}", redisEx.getMessage());
        }

        // 接通密码历史：记录新密码 hash 到 sys_password_history（保留最近 5 条）
        try {
          passwordPolicyService.recordPasswordChange(userId, encodedNewPassword);
          logger.info("用户 {} 密码历史已记录", userId);
        } catch (Exception histEx) {
          logger.error("记录密码历史失败（不影响密码修改结果）: {}", histEx.getMessage(), histEx);
        }

        // 记录审计日志
        // 修复 bug：第三个参数应为 IP（接口签名是 ipAddress），原代码传了 getUserAgent()
        securityAuditService.logPasswordChange(
            userId, user.getUsername(), getClientIp(), false); // forcedByExpiry=false

        return Result.success(null, "密码修改成功！请重新登录");
      } else {
        return Result.error("密码修改失败，请稍后重试");
      }

    } catch (Exception e) {
      logger.error("密码修改异常: error={}", e.getMessage(), e);
      return Result.error("密码修改失败，请稍后重试");
    }
  }
}
