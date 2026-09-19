package com.workorder.controller;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.common.result.Result;

/**
 * 紧急维护控制器（仅 dev 环境加载）
 *
 * <p>安全策略（阶段 1 修复 A-02）： 1. @Profile("dev") — 生产环境不加载此 Bean，端点完全不存在
 * 2. @PreAuthorize("hasRole('SUPER_ADMIN')") — 仅超管可调用 3. 所有操作记录审计日志
 *
 * @author KLord
 */
@Profile("dev")
@RestController
@RequestMapping("/emergencies")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class EmergencyController {

  private static final Logger logger = LoggerFactory.getLogger(EmergencyController.class);

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  /** 账号锁定的Redis Key前缀 */
  private static final String ACCOUNT_LOCKOUT_PREFIX = "security:account:locked:";

  /** 登录尝试计数的Redis Key前缀 */
  private static final String LOGIN_ATTEMPTS_PREFIX = "security:login:attempts:";

  /**
   * 解除所有账号的锁定状态（紧急修复用） GET /emergency/unlock-all-accounts
   *
   * @return 操作结果
   */
  @GetMapping("/unlock-all-accounts")
  public Result<?> unlockAllAccounts() {
    try {
      // 1. 删除所有账号锁定Key
      Set<String> lockedKeys = redisTemplate.keys(ACCOUNT_LOCKOUT_PREFIX + "*");
      long lockedDeleted = 0;
      if (lockedKeys != null && !lockedKeys.isEmpty()) {
        Long deletedCount = redisTemplate.delete(lockedKeys);
        lockedDeleted = deletedCount != null ? deletedCount : 0;
      }

      // 2. 重置所有登录尝试计数
      Set<String> attemptKeys = redisTemplate.keys(LOGIN_ATTEMPTS_PREFIX + "*");
      long attemptsDeleted = 0;
      if (attemptKeys != null && !attemptKeys.isEmpty()) {
        Long deletedCount = redisTemplate.delete(attemptKeys);
        attemptsDeleted = deletedCount != null ? deletedCount : 0;
      }

      logger.warn("[EMERGENCY] 紧急解锁全部账号: 删除锁定记录 {} 条, 重置尝试计数 {} 条", lockedDeleted, attemptsDeleted);

      return Result.success("所有账号已成功解锁！现在可以使用正确的密码登录了。");

    } catch (Exception e) {
      logger.error("[EMERGENCY] 紧急解锁全部账号失败", e);
      return Result.error("解锁失败，请稍后重试");
    }
  }

  /**
   * 解除指定账号的锁定状态 GET /emergency/unlock-account?username=admin
   *
   * @param username 用户名
   * @return 操作结果
   */
  @GetMapping("/unlock-account")
  public Result<?> unlockAccount(@RequestParam String username) {
    try {
      // 1. 删除该账号的锁定Key
      String lockKey = ACCOUNT_LOCKOUT_PREFIX + username;
      Boolean lockDeleted = redisTemplate.delete(lockKey);

      // 2. 重置该账号的登录尝试计数
      String attemptsKey = LOGIN_ATTEMPTS_PREFIX + username;
      Boolean attemptsDeleted = redisTemplate.delete(attemptsKey);

      if (Boolean.TRUE.equals(lockDeleted) || Boolean.TRUE.equals(attemptsDeleted)) {
        logger.warn(
            "[EMERGENCY] 紧急解锁账号: {} (lockDeleted={}, attemptsDeleted={})",
            username,
            lockDeleted,
            attemptsDeleted);
        return Result.success("账号 " + username + " 已成功解锁！");
      } else {
        logger.info("[EMERGENCY] 账号 {} 未被锁定，无需操作", username);
        return Result.success("账号 " + username + " 未被锁定，无需操作。");
      }

    } catch (Exception e) {
      logger.error("[EMERGENCY] 紧急解锁账号 {} 失败", username, e);
      return Result.error("解锁失败，请稍后重试");
    }
  }

  /**
   * 查看当前被锁定的账号列表 GET /emergency/locked-accounts
   *
   * @return 被锁定的账号列表
   */
  @GetMapping("/locked-accounts")
  public Result<?> getLockedAccounts() {
    try {
      Set<String> lockedKeys = redisTemplate.keys(ACCOUNT_LOCKOUT_PREFIX + "*");
      if (lockedKeys == null || lockedKeys.isEmpty()) {
        return Result.success("当前没有被锁定的账号");
      }

      List<String> lockedAccounts = new ArrayList<>();
      for (String key : lockedKeys) {
        // 提取用户名: security:account:locked:admin -> admin
        String username = key.replace(ACCOUNT_LOCKOUT_PREFIX, "");
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MINUTES);
        lockedAccounts.add(username + " (剩余" + ttl + "分钟)");
      }

      logger.info("[EMERGENCY] 查询锁定账号列表: {} 个", lockedAccounts.size());
      return Result.success(lockedAccounts);

    } catch (Exception e) {
      logger.error("[EMERGENCY] 查询锁定账号失败", e);
      return Result.error("查询失败，请稍后重试");
    }
  }
}
