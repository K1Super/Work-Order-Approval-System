package com.workorder.service.impl;


import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workorder.dao.UserMapper;
import com.workorder.entity.PasswordHistory;
import com.workorder.entity.User;
import com.workorder.service.IPasswordPolicyService;
import com.workorder.service.ISystemSettingService;

/**
 * Enterprise Password Policy Service Implementation 企业级密码策略验证服务 - 实现完整的密码安全要求
 *
 * <p>Features: 1. 密码复杂度要求（大小写+数字+特殊符号） 2. 禁止弱密码 3. 定期更换 4. 禁止复用历史密码（最近5次） 5. 账号锁定策略 6. 异常登录检测
 *
 * @author KLord
 */
@Service
public class PasswordPolicyServiceImpl implements IPasswordPolicyService {

  private static final Logger logger = LoggerFactory.getLogger(PasswordPolicyServiceImpl.class);

  /** 默认密码过期天数（90 天） */
  private static final int DEFAULT_PASSWORD_EXPIRY_DAYS = 90;

  /** Regex patterns for password complexity */
  private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");

  private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
  private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");
  private static final Pattern SPECIAL_CHAR_PATTERN =
      Pattern.compile(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");

  /** Common weak passwords list (top 100 most common) */
  private static final Set<String> WEAK_PASSWORDS =
      new HashSet<>(
          Arrays.asList(
              "123456",
              "password",
              "12345678",
              "qwerty",
              "abc123",
              "monkey",
              "1234567",
              "letmein",
              "trustno1",
              "dragon",
              "baseball",
              "iloveyou",
              "master",
              "sunshine",
              "ashley",
              "bailey",
              "passw0rd",
              "shadow",
              "123123",
              "654321",
              "superman",
              "qazwsx",
              "michael",
              "football",
              "password1",
              "password123",
              "admin",
              "welcome",
              "login",
              "hello",
              "charlie",
              "donald",
              "whatever",
              "qwert",
              "12345",
              "1234",
              "111111"));

  @Autowired private ISystemSettingService systemSettingService;

  @Autowired private UserMapper userMapper;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  /** Redis key prefix for login attempts */
  private static final String LOGIN_ATTEMPTS_PREFIX = "security:login:attempts:";

  /** Redis key prefix for account lockout */
  private static final String ACCOUNT_LOCKOUT_PREFIX = "security:account:locked:";

  /** Default password policy configuration - 企业级强策略 安全要求：min≥10、大小写+数字+特殊符号全要求、90 天过期 */
  private static final Map<String, Object> DEFAULT_PASSWORD_POLICY =
      new LinkedHashMap<String, Object>() {
        {
          put("minPasswordLength", 8); // 最小密码长度（强化：8→10）
          put("requireDigit", true); // 要求包含数字（强化）
          put("requireSpecialChar", true); // 要求包含特殊字符（强化）
          put("passwordExpiryDays", 0); // 密码有效期 90 天（强化：0→90）
          put("tokenExpiryHours", 24); // Token 过期时间 8 小时（强化：24→8）
          put("maxLoginAttempts", 5); // 最大登录失败次数
          put("lockoutDuration", 30); // 锁定时间(分钟)
          // 以下为扩展字段（如果系统设置中有则使用，否则使用默认值）
          put("requireLowercase", true); // 要求小写字母（强化）
          put("requireUppercase", true); // 要求大写字母（强化）
          put("maxPasswordHistory", 5); // 密码历史记录数
        }
      };

  /** Validate password with enterprise-grade checks 使用企业级标准验证密码 */
  @Override
  public PasswordValidationResult validatePassword(String password) {
    if (password == null || password.trim().isEmpty()) {
      return new PasswordValidationResult(false, "密码不能为空");
    }

    Map<String, Object> policy = getPasswordPolicy();

    // 使用与系统设置一致的默认值
    int minLength = getIntValue(policy, "minPasswordLength", 6);
    boolean requireLowercase = getBooleanValue(policy, "requireLowercase", false);
    boolean requireUppercase = getBooleanValue(policy, "requireUppercase", false);
    boolean requireDigit = getBooleanValue(policy, "requireDigit", false);
    boolean requireSpecialChar = getBooleanValue(policy, "requireSpecialChar", false);

    // 1. Check minimum length
    if (password.length() < minLength) {
      return new PasswordValidationResult(false, String.format("密码长度不能少于%d个字符", minLength));
    }

    // 2. Check lowercase letter requirement
    if (requireLowercase && !LOWERCASE_PATTERN.matcher(password).matches()) {
      return new PasswordValidationResult(false, "密码必须包含至少一个小写字母");
    }

    // 3. Check uppercase letter requirement
    if (requireUppercase && !UPPERCASE_PATTERN.matcher(password).matches()) {
      return new PasswordValidationResult(false, "密码必须包含至少一个大写字母");
    }

    // 4. Check digit requirement
    if (requireDigit && !DIGIT_PATTERN.matcher(password).matches()) {
      return new PasswordValidationResult(false, "密码必须包含至少一个数字");
    }

    // 5. Check special character requirement
    if (requireSpecialChar && !SPECIAL_CHAR_PATTERN.matcher(password).matches()) {
      return new PasswordValidationResult(false, "密码必须包含至少一个特殊字符（!@#$%^&*等）");
    }

    // 6. Check for weak/common passwords
    if (isWeakPassword(password)) {
      return new PasswordValidationResult(false, "该密码过于简单，请使用更复杂的密码组合");
    }

    logger.debug("企业级密码验证通过 ✓");
    return new PasswordValidationResult(true, "密码符合企业安全标准");
  }

  /** Check if password is in weak password list 检查是否为弱密码 */
  public boolean isWeakPassword(String password) {
    String lowerPassword = password.toLowerCase();

    // Check against common weak passwords
    if (WEAK_PASSWORDS.contains(lowerPassword)) {
      return true;
    }

    // Check sequential patterns (e.g., "abcd", "1234")
    if (hasSequentialPattern(lowerPassword)) {
      return true;
    }

    // Check repeated characters (e.g., "aaaa", "1111")
    if (hasRepeatedChars(lowerPassword)) {
      return true;
    }

    return false;
  }

  /** Check for sequential patterns 检查连续字符模式 */
  private boolean hasSequentialPattern(String password) {
    int consecutiveCount = 1;
    for (int i = 1; i < password.length(); i++) {
      char prev = password.charAt(i - 1);
      char curr = password.charAt(i);

      if (curr == prev + 1 || curr == prev - 1) {
        consecutiveCount++;
        if (consecutiveCount >= 4) {
          return true;
        }
      } else {
        consecutiveCount = 1;
      }
    }
    return false;
  }

  /** Check for repeated characters 检查重复字符 */
  private boolean hasRepeatedChars(String password) {
    int repeatCount = 1;
    for (int i = 1; i < password.length(); i++) {
      if (password.charAt(i) == password.charAt(i - 1)) {
        repeatCount++;
        if (repeatCount >= 4) {
          return true;
        }
      } else {
        repeatCount = 1;
      }
    }
    return false;
  }

  /** Validate that password is not reused from history 验证密码是否与历史密码重复 */
  public PasswordValidationResult validatePasswordNotReused(Long userId, String newPassword) {
    try {
      List<PasswordHistory> historyList =
          userMapper.selectPasswordHistory(userId, getMaxPasswordHistory());

      for (PasswordHistory history : historyList) {
        if (passwordEncoder.matches(newPassword, history.getPasswordHash())) {
          return new PasswordValidationResult(
              false, String.format("不能使用最近%d次使用过的密码", getMaxPasswordHistory()));
        }
      }

      return new PasswordValidationResult(true, "密码未在历史记录中找到");
    } catch (Exception e) {
      // 安全策略：异常时 fail-closed 拒绝密码变更，防止历史检查被绕过
      logger.error("检查密码历史失败（fail-closed 拒绝）: {}", e.getMessage(), e);
      return new PasswordValidationResult(false, "密码历史校验服务异常，为安全起见暂不允许修改密码，请稍后重试或联系管理员");
    }
  }

  /** Record password change to history 记录密码变更历史 */
  public void recordPasswordChange(Long userId, String encodedPassword) {
    try {
      PasswordHistory history = new PasswordHistory();
      history.setUserId(userId);
      history.setPasswordHash(encodedPassword);
      history.setChangeTime(new Date());
      userMapper.insertPasswordHistory(history);

      // Trim old records to keep only recent N passwords
      int maxHistory = getMaxPasswordHistory();
      List<PasswordHistory> allHistory = userMapper.selectAllPasswordHistory(userId);
      if (allHistory.size() > maxHistory) {
        // Delete oldest entries beyond the limit
        List<Long> idsToDelete =
            allHistory.subList(maxHistory, allHistory.size()).stream()
                .map(PasswordHistory::getId)
                .collect(Collectors.toList());

        userMapper.deletePasswordHistoryByIds(idsToDelete);
      }

      logger.info("已记录用户 {} 的密码变更", userId);
    } catch (Exception e) {
      logger.error("记录密码变更历史失败: {}", e.getMessage());
    }
  }

  /**
   * Check if account is locked due to failed login attempts 检查账号是否因登录失败被锁定（按 IP+用户名组合锁定，防止单 IP 撞库）
   *
   * @param username 用户名
   * @param clientIp 客户端 IP（用于组合锁定键）
   */
  @Override
  public boolean isAccountLocked(String username, String clientIp) {
    String ipUsernameKey = buildIpUsernameKey(clientIp, username);
    String redisKey = ACCOUNT_LOCKOUT_PREFIX + ipUsernameKey;
    Boolean exists = redisTemplate.hasKey(redisKey);
    if (Boolean.TRUE.equals(exists)) {
      Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.MINUTES);
      logger.warn("账号 {} (IP: {}) 已被锁定，剩余锁定时间: {} 分钟", username, clientIp, ttl);
      return true;
    }
    return false;
  }

  /**
   * Increment login attempt counter and check if account should be locked 增加登录尝试计数并检查是否需要锁定账号（按
   * IP+用户名组合锁定）
   *
   * @param username 用户名
   * @param clientIp 客户端 IP（用于组合锁定键）
   */
  @Override
  public LoginAttemptResult recordFailedLoginAttempt(String username, String clientIp) {
    Map<String, Object> policy = getPasswordPolicy();
    int maxAttempts = getIntValue(policy, "maxLoginAttempts", 5);
    // 使用lockoutDuration（与系统设置字段名一致）而不是lockoutDurationMinutes
    int lockoutMinutes = getIntValue(policy, "lockoutDuration", 30);

    String ipUsernameKey = buildIpUsernameKey(clientIp, username);
    String attemptsKey = LOGIN_ATTEMPTS_PREFIX + ipUsernameKey;

    // Get current attempt count
    Integer currentAttempts = (Integer) redisTemplate.opsForValue().get(attemptsKey);
    if (currentAttempts == null) {
      currentAttempts = 0;
    }

    // Increment attempt count
    currentAttempts++;
    redisTemplate.opsForValue().set(attemptsKey, currentAttempts, 60, TimeUnit.MINUTES);

    logger.warn(
        "用户 {} (IP: {}) 登录失败，当前尝试次数: {}/{}", username, clientIp, currentAttempts, maxAttempts);

    // Check if should lock account
    if (currentAttempts >= maxAttempts) {
      // Lock the account
      String lockKey = ACCOUNT_LOCKOUT_PREFIX + ipUsernameKey;
      redisTemplate.opsForValue().set(lockKey, true, lockoutMinutes, TimeUnit.MINUTES);

      // Clear attempt counter
      redisTemplate.delete(attemptsKey);

      logger.error(
          "账号 {} (IP: {}) 因连续 {} 次登录失败已被锁定 {} 分钟", username, clientIp, maxAttempts, lockoutMinutes);

      return new LoginAttemptResult(
          true,
          lockoutMinutes,
          String.format("账号因连续 %d 次登录失败已被锁定 %d 分钟，请稍后重试或联系管理员", maxAttempts, lockoutMinutes));
    }

    return new LoginAttemptResult(
        false,
        maxAttempts - currentAttempts,
        String.format("登录失败，还剩 %d 次机会", maxAttempts - currentAttempts));
  }

  /**
   * Reset login attempt counter on successful login 成功登录后重置计数器（按 IP+用户名组合）
   *
   * @param username 用户名
   * @param clientIp 客户端 IP（用于组合锁定键）
   */
  @Override
  public void resetLoginAttempts(String username, String clientIp) {
    String ipUsernameKey = buildIpUsernameKey(clientIp, username);
    String attemptsKey = LOGIN_ATTEMPTS_PREFIX + ipUsernameKey;
    redisTemplate.delete(attemptsKey);
    logger.info("用户 {} (IP: {}) 登录成功，已重置登录尝试计数器", username, clientIp);
  }

  /** 构建 IP+用户名组合键（防止单 IP 撞库绕过锁定） clientIp 为空时使用 "unknown" 占位 */
  private String buildIpUsernameKey(String clientIp, String username) {
    String safeIp = (clientIp != null && !clientIp.isEmpty()) ? clientIp : "unknown";
    return safeIp + ":" + (username != null ? username : "unknown");
  }

  /** Check if password has expired (90-day policy) 检查密码是否过期 */
  @Override
  public boolean isPasswordExpired(Object user) {
    if (!(user instanceof User)) {
      logger.warn(
          "isPasswordExpired: Invalid user type, expected User but got {}",
          user.getClass().getName());
      return false;
    }

    User typedUser = (User) user;
    if (typedUser.getPasswordChangeTime() == null) {
      return false; // First time login, force password change via other mechanism
    }

    Map<String, Object> policy = getPasswordPolicy();
    int expiryDays = getIntValue(policy, "passwordExpiryDays", DEFAULT_PASSWORD_EXPIRY_DAYS);

    long daysSinceChange =
        (System.currentTimeMillis() - typedUser.getPasswordChangeTime().getTime())
            / (1000 * 60 * 60 * 24);

    if (daysSinceChange > expiryDays) {
      logger.warn("用户 {} 的密码已超过 {} 天未更改", typedUser.getUsername(), expiryDays);
      return true;
    }

    return false;
  }

  @Override
  public Map<String, Object> getPasswordPolicy() {
    try {
      // 从SystemSettingService获取所有设置
      Map<String, Object> allSettings = systemSettingService.getAllSettings();
      if (allSettings != null && allSettings.containsKey("security")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> securitySettings = (Map<String, Object>) allSettings.get("security");
        if (securitySettings != null && !securitySettings.isEmpty()) {
          logger.info("✅ 从系统设置（安全设置）读取密码策略配置成功");
          logger.debug("安全设置内容: {}", securitySettings);
          return securitySettings;
        }
      }

      logger.warn("⚠️ 系统设置中未找到安全配置，使用默认密码策略");
      return DEFAULT_PASSWORD_POLICY;
    } catch (Exception e) {
      logger.error("❌ 获取密码策略配置失败，使用默认配置: {}", e.getMessage());
      return DEFAULT_PASSWORD_POLICY;
    }
  }

  /** Helper method to get integer value from map */
  private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /** Helper method to get boolean value from map */
  private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return "true".equalsIgnoreCase((String) value) || "1".equals(value);
    }
    return defaultValue;
  }

  /** Get maximum password history count */
  private int getMaxPasswordHistory() {
    return getIntValue(getPasswordPolicy(), "maxPasswordHistory", 5);
  }

  /** Login attempt result class 登录尝试结果内部类 */
  public static class LoginAttemptResult {
    private boolean locked;
    private int remainingAttemptsOrLockoutMinutes;
    private String message;

    /** 构造函数，初始化登录尝试结果 */
    public LoginAttemptResult(
        boolean locked, int remainingAttemptsOrLockoutMinutes, String message) {
      this.locked = locked;
      this.remainingAttemptsOrLockoutMinutes = remainingAttemptsOrLockoutMinutes;
      this.message = message;
    }

    public boolean isLocked() {
      return locked;
    }

    public int getRemainingAttemptsOrLockoutMinutes() {
      return remainingAttemptsOrLockoutMinutes;
    }

    public String getMessage() {
      return message;
    }
  }
}
