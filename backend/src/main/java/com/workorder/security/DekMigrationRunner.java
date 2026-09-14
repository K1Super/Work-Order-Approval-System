package com.workorder.security;


import java.util.List;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import com.workorder.util.AesEncryptionUtil;

/**
 * DEK 数据迁移 Runner（OPTIMIZATION 三.3.3 配套）
 *
 * <p>启动时为所有缺少 encrypted_dek 的用户生成 DEK 并重新加密 email/phone 字段。
 *
 * <p><b>迁移逻辑（幂等）：</b>
 *
 * <ol>
 *   <li>查询所有 is_deleted=0 的用户
 *   <li>对每个用户：
 *       <ul>
 *         <li>若 encrypted_dek 为空 → 生成新 DEK，保存到数据库
 *         <li>用 legacy 静态密钥解密 email/phone（旧密文）
 *         <li>用新 DEK 重新加密 email/phone
 *         <li>更新数据库
 *       </ul>
 *   <li>已迁移用户跳过（encrypted_dek 非空）
 * </ol>
 *
 * <p><b>启用控制：</b> 通过配置项 {@code work-order-system.security.dek-migration.enabled} 控制（默认 true）。
 * 已迁移的数据库可关闭以加速启动。
 *
 * @author KLord
 */
@Component
@Order(50) // 在 KekService/DekService 初始化之后运行
public class DekMigrationRunner implements ApplicationRunner {

  private static final Logger logger = LoggerFactory.getLogger(DekMigrationRunner.class);
  private static final Logger migrationLogger = LoggerFactory.getLogger("DEK_MIGRATION_LOGGER");

  @Autowired private UserMapper userMapper;

  @Autowired private DekService dekService;

  @Autowired private KekService kekService;

  @Autowired private AesEncryptionUtil aesEncryptionUtil;

  @Autowired private EncryptionHelper encryptionHelper;

  @Value("${work-order-system.security.dek-migration.enabled:true}")
  private boolean migrationEnabled;

  @Override
  public void run(ApplicationArguments args) {
    if (!migrationEnabled) {
      logger.info("[DEK 迁移] 已通过配置禁用（work-order-system.security.dek-migration.enabled=false）");
      return;
    }

    logger.info("[DEK 迁移] 启动 DEK 数据迁移检查...");
    migrationLogger.info("[DEK 迁移] 启动检查 - KEK fingerprint={}", kekService.getKekFingerprint());

    try {
      // 1. 查询所有未删除用户
      List<User> allUsers = userMapper.selectAll();
      if (allUsers == null || allUsers.isEmpty()) {
        logger.info("[DEK 迁移] 无用户记录，跳过迁移");
        return;
      }

      int totalUsers = allUsers.size();
      int migratedCount = 0;
      int skippedCount = 0;
      int failedCount = 0;

      logger.info("[DEK 迁移] 共 {} 个用户待检查", totalUsers);

      for (User user : allUsers) {
        try {
          // 不跳过已有 DEK 的用户：migrateUserDek 会检测并修复双重加密
          // 对于已正确加密的用户：解密→明文→重新加密（IV 不同但明文一致，幂等安全）
          // 对于双重加密用户：多次解密→明文→重新加密一次（修复 Bug）
          migrateUserDek(user);
          migratedCount++;

        } catch (Exception e) {
          failedCount++;
          migrationLogger.error("[DEK 迁移] 用户 {} 迁移失败: {}", user.getId(), e.getMessage(), e);
        }
      }

      String summary =
          String.format(
              "[DEK 迁移] 完成 - 总数=%d, 已迁移=%d, 已跳过=%d, 失败=%d",
              totalUsers, migratedCount, skippedCount, failedCount);
      logger.info(summary);
      migrationLogger.info(summary);

    } catch (Exception e) {
      migrationLogger.error("[DEK 迁移] 迁移过程异常: {}", e.getMessage(), e);
      // 不抛异常，避免阻塞应用启动
    }
  }

  /**
   * 迁移单个用户的 DEK 与敏感字段
   *
   * <p><b>关键修复（双重加密 Bug）：</b> 旧实现手动调用 {@code aesEncryptionUtil.encrypt(plainEmail)} 后再调用 {@code
   * userMapper.update(user)}，但 update 的 TypeHandler 会再次加密， 导致 email/phone 被双重加密（密文 → 密文 → 数据库存密文²）。
   * 读取时 TypeHandler 只解密一次，返回的仍是密文。
   *
   * <p><b>正确流程：</b>
   *
   * <ol>
   *   <li>读取用户（selectAll 时 DekContext 为空，TypeHandler 用 legacy 密钥解密旧密文 → 明文）
   *   <li>若已有 encrypted_dek（已迁移过但可能双重加密）→ 用 DEK 反复解密至明文
   *   <li>生成新 DEK 并保存（仅新用户）
   *   <li>设置明文 email/phone 到 user 对象
   *   <li>设置 DekContext 后调用 userMapper.update → TypeHandler 自动用 DEK 加密一次
   * </ol>
   */
  private void migrateUserDek(User user) {
    Long userId = user.getId();
    migrationLogger.info("[DEK 迁移] 开始迁移用户 {} ({})", userId, user.getUsername());

    // 1. selectAll 时 DekContext 为空，TypeHandler 行为：
    //    - 旧密文（legacy 加密）→ legacy 密钥解密成功 → 返回明文
    //    - 新密文（DEK 加密/双重加密）→ legacy 密钥解密失败 → 返回密文
    String email = user.getEmail();
    String phone = user.getPhone();

    // 2. 判断是否已有 DEK（决定是"首次迁移"还是"修复双重加密"）
    boolean hasExistingDek = user.getEncryptedDek() != null && !user.getEncryptedDek().isEmpty();

    final SecretKey dek;
    final String finalEmail;
    final String finalPhone;

    if (hasExistingDek) {
      // 已迁移过：加载现有 DEK
      dek = dekService.getUserDek(userId);
      if (dek == null) {
        migrationLogger.warn("[DEK 迁移] 用户 {} 的 encrypted_dek 存在但 DEK 加载失败，跳过", userId);
        return;
      }
      // 在 DekContext 内反复解密至明文（修复双重/多重加密）
      // 必须设置 DekContext，否则 decrypt() 只会用 legacy 密钥
      finalEmail =
          encryptionHelper.executeWithDekContext(userId, () -> decryptToPlaintext(email, userId));
      finalPhone =
          encryptionHelper.executeWithDekContext(userId, () -> decryptToPlaintext(phone, userId));
    } else {
      // 首次迁移：生成新 DEK
      dek = dekService.generateAndSaveDekForUser(userId);
      // email/phone 已经是明文（selectAll 时 legacy 解密成功）
      // 或仍是密文（legacy 解密失败，用 legacy 密钥再试一次）
      finalEmail = tryLegacyDecrypt(email);
      finalPhone = tryLegacyDecrypt(phone);
    }

    // 3. 设置明文 email/phone，DekContext 内调用 update
    //    TypeHandler 的 setNonNullParameter 会用 DEK 加密一次（正确流程，无双重加密）
    encryptionHelper.executeWithDekContext(
        userId,
        () -> {
          user.setEmail(finalEmail);
          user.setPhone(finalPhone);
          userMapper.update(user);
        });

    migrationLogger.info(
        "[DEK 迁移] 用户 {} 迁移成功 (DEK fingerprint={})", userId, dekService.getDekFingerprint(dek));
  }

  /**
   * 反复用 DEK 解密直至得到明文（修复双重/多重加密）
   *
   * <p>双重加密 Bug 的产物：明文 → DEK加密 → 密文1 → TypeHandler再加密 → 密文2（存DB） 读取时 TypeHandler 解密一次 → 密文1（仍为密文）
   * 本方法循环解密：密文2 → 密文1 → 明文
   *
   * <p><b>必须在 DekContext 上下文内调用</b>（decrypt() 依赖 DekContext 获取 DEK）
   *
   * @param value 可能多重加密的值
   * @param userId 用户ID（日志用）
   * @return 明文；若无法解密则返回原值
   */
  private String decryptToPlaintext(String value, Long userId) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String current = value;
    // 最多解密 5 次（防止异常数据导致死循环）
    for (int i = 0; i < 5; i++) {
      if (!aesEncryptionUtil.isEncryptedFormat(current)) {
        // 已是明文
        migrationLogger.info("[DEK 迁移] 用户 {} 解密 {} 轮后得到明文", userId, i);
        return current;
      }
      try {
        String decrypted = aesEncryptionUtil.decrypt(current);
        if (decrypted == null || decrypted.equals(current)) {
          // 解密失败或无变化，返回当前值
          migrationLogger.warn("[DEK 迁移] 用户 {} 第{}轮解密无变化，返回当前值", userId, i + 1);
          return current;
        }
        current = decrypted;
      } catch (Exception e) {
        migrationLogger.warn("[DEK 迁移] 用户 {} 第{}轮解密失败: {}", userId, i + 1, e.getMessage());
        return current;
      }
    }
    migrationLogger.warn("[DEK 迁移] 用户 {} 解密 5 轮后仍为密文，返回当前值", userId);
    return current;
  }

  /** 尝试用 legacy 静态密钥解密（兼容旧数据） 若不是加密格式或解密失败，返回原值 */
  private String tryLegacyDecrypt(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    if (!aesEncryptionUtil.isEncryptedFormat(value)) {
      return value; // 已是明文
    }
    try {
      return aesEncryptionUtil.decrypt(value);
    } catch (Exception e) {
      return value;
    }
  }
}
