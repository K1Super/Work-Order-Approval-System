package com.workorder.service.impl;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.result.Result;
import com.workorder.dao.PasswordResetTokenMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.PasswordResetToken;
import com.workorder.entity.User;
import com.workorder.security.TokenVersionCache;
import com.workorder.service.IPasswordPolicyService;
import com.workorder.service.IPasswordResetService;

/**
 * 密码重置服务实现
 *
 * <p>企业级密码重置流程实现： - token 生成：64 字符随机十六进制串（256 位熵） - token 存储：数据库仅存 SHA-256 哈希 - token 过期：15 分钟 -
 * token 使用：一次性 - 新 token 生成时使该用户旧 token 失效
 *
 * @author KLord
 */
@Service
public class PasswordResetServiceImpl implements IPasswordResetService {

  private static final Logger logger = LoggerFactory.getLogger(PasswordResetServiceImpl.class);

  /** token 有效期：15 分钟 */
  private static final long TOKEN_EXPIRY_MINUTES = 15;

  /** token 字节长度（32 字节 = 256 位熵）→ 64 字符十六进制串 */
  private static final int TOKEN_BYTES = 32;

  /** 字节掩码（用于 byte→int 无符号转换） */
  private static final int BYTE_MASK = 0xff;

  @Autowired private PasswordResetTokenMapper tokenMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private IPasswordPolicyService passwordPolicyService;

  /** tokenVersion 二级缓存（密码重置后失效） */
  @Autowired private TokenVersionCache tokenVersionCache;

  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  @Transactional
  public Result<Map<String, Object>> generateResetToken(Long userId, Long operatorId) {
    try {
      User targetUser = userMapper.selectById(userId);
      if (targetUser == null) {
        logger.warn("生成重置令牌失败：目标用户不存在 userId={}", userId);
        return Result.error("目标用户不存在");
      }

      // 使该用户旧的未使用 token 失效（一次只能有一个有效 token）
      tokenMapper.invalidateByUserId(userId);

      // 生成明文 token（64 字符十六进制）
      byte[] tokenBytes = new byte[TOKEN_BYTES];
      secureRandom.nextBytes(tokenBytes);
      String plainToken = bytesToHex(tokenBytes);

      // 计算 SHA-256 哈希存库
      String tokenHash = sha256(plainToken);

      // 计算过期时间
      Date expiryTime = new Date(System.currentTimeMillis() + TOKEN_EXPIRY_MINUTES * 60 * 1000L);

      // 存入数据库
      PasswordResetToken entity = new PasswordResetToken();
      entity.setTokenHash(tokenHash);
      entity.setUserId(userId);
      entity.setOperatorId(operatorId);
      entity.setExpiryTime(expiryTime);
      entity.setUsed(false);
      entity.setCreateTime(new Date());
      tokenMapper.insert(entity);

      logger.info(
          "✅ 密码重置令牌已生成: 目标用户={}, 操作者={}, 过期时间={} 分钟",
          targetUser.getUsername(),
          operatorId != null ? operatorId : "self",
          TOKEN_EXPIRY_MINUTES);

      Map<String, Object> data = new HashMap<>();
      // 明文 token 仅在此返回一次，调用方负责传递给最终用户
      data.put("token", plainToken);
      data.put("expiryMinutes", TOKEN_EXPIRY_MINUTES);
      data.put("expiryTime", expiryTime);
      data.put("username", targetUser.getUsername());
      data.put("realName", targetUser.getRealName());
      return Result.success("重置令牌已生成，有效期 " + TOKEN_EXPIRY_MINUTES + " 分钟", data);

    } catch (Exception e) {
      logger.error("❌ 生成密码重置令牌异常: userId={}, error={}", userId, e.getMessage(), e);
      return Result.error("生成重置令牌失败，请稍后重试");
    }
  }

  @Override
  public Result<Map<String, Object>> validateResetToken(String token) {
    if (token == null || token.trim().isEmpty()) {
      return Result.error("重置令牌不能为空");
    }

    try {
      String tokenHash = sha256(token);
      PasswordResetToken entity = tokenMapper.selectByTokenHash(tokenHash);

      if (entity == null) {
        logger.warn("验证重置令牌失败：令牌不存在");
        return Result.error("重置令牌无效或不存在");
      }

      if (entity.getUsed() != null && entity.getUsed()) {
        logger.warn("验证重置令牌失败：令牌已使用, userId={}", entity.getUserId());
        return Result.error("重置令牌已使用，请重新申请");
      }

      if (entity.getExpiryTime().before(new Date())) {
        logger.warn(
            "验证重置令牌失败：令牌已过期, userId={}, 过期时间={}", entity.getUserId(), entity.getExpiryTime());
        return Result.error("重置令牌已过期，请重新申请");
      }

      User targetUser = userMapper.selectById(entity.getUserId());
      if (targetUser == null) {
        logger.error("验证重置令牌失败：令牌关联的用户不存在, userId={}", entity.getUserId());
        return Result.error("重置令牌关联的用户不存在");
      }

      if (targetUser.getStatus() != null && targetUser.getStatus() == 0) {
        logger.warn("验证重置令牌失败：用户已被禁用, userId={}", entity.getUserId());
        return Result.error("账号已被禁用，请联系管理员");
      }

      Map<String, Object> data = new HashMap<>();
      data.put("username", targetUser.getUsername());
      data.put("realName", targetUser.getRealName());
      data.put("expiryTime", entity.getExpiryTime());
      return Result.success("令牌有效", data);

    } catch (Exception e) {
      logger.error("❌ 验证重置令牌异常: error={}", e.getMessage(), e);
      return Result.error("验证重置令牌失败，请稍后重试");
    }
  }

  @Override
  @Transactional
  public Result<Void> consumeResetToken(String token, String newPassword) {
    if (token == null || token.trim().isEmpty()) {
      return Result.error("重置令牌不能为空");
    }
    if (newPassword == null || newPassword.trim().isEmpty()) {
      return Result.error("新密码不能为空");
    }

    try {
      String tokenHash = sha256(token);
      PasswordResetToken entity = tokenMapper.selectByTokenHash(tokenHash);

      // 1. 验证令牌存在
      if (entity == null) {
        logger.warn("消费重置令牌失败：令牌不存在");
        return Result.error("重置令牌无效或不存在");
      }

      // 2. 验证未使用（一次性）
      if (entity.getUsed() != null && entity.getUsed()) {
        logger.warn("消费重置令牌失败：令牌已使用, userId={}", entity.getUserId());
        return Result.error("重置令牌已使用，请重新申请");
      }

      // 3. 验证未过期
      if (entity.getExpiryTime().before(new Date())) {
        logger.warn("消费重置令牌失败：令牌已过期, userId={}", entity.getUserId());
        return Result.error("重置令牌已过期，请重新申请");
      }

      // 4. 查询目标用户
      User targetUser = userMapper.selectById(entity.getUserId());
      if (targetUser == null) {
        logger.error("消费重置令牌失败：用户不存在, userId={}", entity.getUserId());
        return Result.error("用户不存在");
      }

      if (targetUser.getStatus() != null && targetUser.getStatus() == 0) {
        return Result.error("账号已被禁用，无法重置密码");
      }

      // 5. 验证新密码符合安全策略
      IPasswordPolicyService.PasswordValidationResult validation =
          passwordPolicyService.validatePassword(newPassword);
      if (!validation.isValid()) {
        return Result.error(validation.getMessage());
      }

      // 6. 验证新密码不在密码历史中
      IPasswordPolicyService.PasswordValidationResult historyCheck =
          passwordPolicyService.validatePasswordNotReused(targetUser.getId(), newPassword);
      if (!historyCheck.isValid()) {
        return Result.error(historyCheck.getMessage());
      }

      // 7. 更新密码（使用 updatePassword 同时更新 password_change_time）
      String encodedPassword = passwordEncoder.encode(newPassword);
      targetUser.setPassword(encodedPassword);
      targetUser.setPasswordChanged(true);
      int updateResult = userMapper.updatePassword(targetUser);
      if (updateResult <= 0) {
        logger.error("消费重置令牌失败：更新密码失败, userId={}", targetUser.getId());
        return Result.error("密码更新失败，请稍后重试");
      }

      // 7.1 OPTIMIZATION 三.3.1：密码重置后递增 token_version，使旧 JWT/HttpOnly Cookie 立即失效。
      // 安全要求：用户通过重置链接设置新密码后，所有旧会话必须立即作废，
      // 防止账号被盗后旧 Token 仍可访问系统。
      try {
        int bumped = userMapper.incrementTokenVersion(targetUser.getId());
        if (bumped > 0) {
          logger.info("用户 {} 密码重置后 token_version 已递增（旧 Token 立即失效）", targetUser.getId());
          tokenVersionCache.evict(targetUser.getId());
        } else {
          logger.warn("用户 {} 密码重置后 token_version 递增返回 0 行（用户可能已被删除）", targetUser.getId());
        }
      } catch (Exception ex) {
        // token_version 递增失败属于严重安全事件：密码已改但旧 Token 可能仍有效。
        // 不回滚密码更新（用户已成功设新密码），但记录 ERROR 以便人工排查并强制下线。
        logger.error(
            "密码重置后递增 token_version 失败（旧 Token 可能仍有效，需人工排查）: userId={}, error={}",
            targetUser.getId(),
            ex.getMessage(),
            ex);
      }

      // 8. 标记令牌为已使用
      tokenMapper.markAsUsed(entity.getId(), new Date());

      // 9. 记录密码历史
      try {
        passwordPolicyService.recordPasswordChange(targetUser.getId(), encodedPassword);
      } catch (Exception histEx) {
        logger.error("记录密码历史失败（不影响重置结果）: {}", histEx.getMessage(), histEx);
      }

      logger.info(
          "✅ 密码重置成功: 用户={}, 操作者={}",
          targetUser.getUsername(),
          entity.getOperatorId() != null ? entity.getOperatorId() : "self");

      return Result.success("密码重置成功，请使用新密码登录", null);

    } catch (Exception e) {
      logger.error("❌ 消费重置令牌异常: error={}", e.getMessage(), e);
      return Result.error("密码重置失败，请稍后重试");
    }
  }

  /** 字节数组转十六进制字符串（小写） */
  private String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b & BYTE_MASK));
    }
    return sb.toString();
  }

  /** SHA-256 哈希（返回 64 字符十六进制串） */
  private String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return bytesToHex(hashBytes);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 算法不可用", e);
    }
  }
}
