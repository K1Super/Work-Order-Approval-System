package com.workorder.service;


import java.util.Map;


/**
 * 密码策略验证服务接口 用于验证密码是否符合系统安全设置中的密码策略
 *
 * @author KLord
 */
public interface IPasswordPolicyService {

  /**
   * 验证密码是否符合当前安全策略
   *
   * @param password 待验证的密码
   * @return 验证结果，包含是否通过和错误信息
   */
  PasswordValidationResult validatePassword(String password);

  /**
   * 获取当前的密码策略配置
   *
   * @return 密码策略配置Map
   */
  Map<String, Object> getPasswordPolicy();

  /**
   * Check if account is locked due to failed login attempts 检查账号是否因登录失败被锁定（按 IP+用户名组合锁定，防止单 IP 撞库）
   *
   * @param username 用户名
   * @param clientIp 客户端 IP（用于组合锁定键）
   */
  boolean isAccountLocked(String username, String clientIp);

  /**
   * Record failed login attempt and check if account should be locked 记录登录失败尝试并检查是否需要锁定账号（按
   * IP+用户名组合锁定）
   *
   * @param username 用户名
   * @param clientIp 客户端 IP（用于组合锁定键）
   * @return LoginAttemptResult containing lock status and message
   */
  Object recordFailedLoginAttempt(String username, String clientIp);

  /**
   * Reset login attempt counter on successful login 成功登录后重置计数器（按 IP+用户名组合）
   *
   * @param username 用户名
   * @param clientIp 客户端 IP（用于组合锁定键）
   */
  void resetLoginAttempts(String username, String clientIp);

  /** Check if password has expired (90-day policy) 检查密码是否过期 */
  boolean isPasswordExpired(Object user);

  /** Validate that password is not reused from history 验证密码是否与历史密码重复 */
  PasswordValidationResult validatePasswordNotReused(Long userId, String newPassword);

  /** Record password change to history 记录密码变更历史 */
  void recordPasswordChange(Long userId, String encodedPassword);

  /** Password validation result inner class 密码验证结果内部类 */
  class PasswordValidationResult {
    private boolean valid;
    private String message;

    /** 构造函数，初始化密码验证结果 */
    public PasswordValidationResult(boolean valid, String message) {
      this.valid = valid;
      this.message = message;
    }

    public boolean isValid() {
      return valid;
    }

    public String getMessage() {
      return message;
    }
  }
}
