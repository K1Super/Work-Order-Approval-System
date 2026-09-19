package com.workorder.util;


import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.workorder.common.exception.BusinessException;
import com.workorder.config.MetricsConfig;
import com.workorder.security.DekContext;
import com.workorder.security.DekService;

/**
 * AES-GCM 256 加密工具（OPTIMIZATION 三.3.3 改造为 DEK/KEK 分层加密）
 *
 * <p><b>演进历程：</b> 1. 阶段 4 §5：单一静态 AES 密钥（来自环境变量 AES_ENCRYPTION_KEY） 2. OPTIMIZATION 三.3.3：DEK/KEK
 * 分层加密 - 每个用户独立的 DEK（Data Encryption Key） - DEK 用 KEK 加密后存数据库（sys_user.encrypted_dek） - KEK 来自外部
 * KMS（dev 环境变量 / prod Vault） - 密钥轮换时只需重新加密 DEK，历史密文无需改动
 *
 * <p><b>密文格式：</b> base64(iv) + ":" + base64(cipherText+authTag) - IV：12 字节随机数（每次加密不同） - Auth Tag：16
 * 字节（GCM 模式内置完整性校验）
 *
 * <p><b>密钥选择策略（混合模式，向后兼容）：</b>
 *
 * <ul>
 *   <li>优先：DekContext 中有 userId → 使用该用户的 DEK 加密/解密
 *   <li>回退：DekContext 无 userId 或用户无 DEK → 使用 legacy 静态密钥（兼容旧数据）
 * </ul>
 *
 * <p>这样既支持 per-user DEK（新数据），又能解密历史数据（旧密钥加密）， 数据迁移完成后可移除 legacy 静态密钥。
 *
 * @author KLord
 */
@Component
public class AesEncryptionUtil {

  private static final Logger logger = LoggerFactory.getLogger(AesEncryptionUtil.class);

  private static final String ALGORITHM = "AES";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH_BIT = 128; // 16 字节
  private static final int IV_LENGTH_BYTE = 12;

  private final SecureRandom secureRandom = new SecureRandom();

  // 修复（2026-07-26）：统一配置项名称为 work-order-system.security.aes-encryption-key
  @Value("${work-order-system.security.aes-encryption-key:}")
  private String encryptionKeyConfig;

  /** Spring 环境对象：用于判断当前激活 profile（与 KekService.isProdProfile() 保持一致） */
  @Autowired private Environment environment;

  /** 安全告警指标（审计修复 P1-1 / P2-4：解密全失败与 DEK 降级必须可采集可告警） */
  @Autowired private MetricsConfig metricsConfig;

  /**
   * Legacy 静态密钥（OPTIMIZATION 三.3.3 之前的密钥，用于解密历史数据）
   *
   * <p>数据迁移完成后可移除（所有数据都改用 per-user DEK 加密）。
   */
  private SecretKeySpec legacySecretKeySpec;

  /**
   * DekService 注入（@Lazy 避免循环依赖）
   *
   * <p>用于 per-user DEK 加密/解密。仅当 DekContext 中有 userId 时使用。
   */
  @Autowired @Lazy private DekService dekService;

  /**
   * 启动时校验 legacy 密钥并初始化（向后兼容用）
   *
   * <p>OPTIMIZATION 三.3.3 实施后，新数据使用 per-user DEK； legacy 静态密钥仅用于解密历史数据，直至数据迁移完成。
   */
  @PostConstruct
  public void init() {
    boolean isProd = isProdProfile();

    if (encryptionKeyConfig == null || encryptionKeyConfig.isBlank()) {
      if (isProd) {
        throw new IllegalStateException(
            "生产环境必须配置 work-order-system.security.aes-encryption-key（32 字节 Base64 编码，用于历史数据兼容）");
      }
      // 仅当未激活 prod profile 时进入临时密钥分支（与 KekService 的 prod 判断逻辑一致）
      logger.warn("[AES] 非生产环境未配置 work-order-system.security.aes-encryption-key，生成临时 legacy 密钥（仅用于解密旧数据）");
      byte[] tempKey = new byte[32];
      secureRandom.nextBytes(tempKey);
      legacySecretKeySpec = new SecretKeySpec(tempKey, ALGORITHM);
      return;
    }

    try {
      byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyConfig);
      if (keyBytes.length != 32) {
        throw new IllegalArgumentException("AES 密钥长度必须为 32 字节（256 位），当前: " + keyBytes.length);
      }
      legacySecretKeySpec = new SecretKeySpec(keyBytes, ALGORITHM);
      logger.info("[AES] Legacy 静态密钥初始化成功（仅用于解密历史数据）");
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("AES 密钥配置非法: " + e.getMessage(), e);
    }
  }

  /**
   * 加密字符串（OPTIMIZATION 三.3.3 优先使用 per-user DEK）
   *
   * <p>密钥选择： 1. DekContext 有 userId → 使用该用户的 DEK（若用户无 DEK，自动生成并保存） 2. DekContext 无 userId → 使用
   * legacy 静态密钥（向后兼容）
   *
   * @param plain 明文
   * @return 密文（base64(iv):base64(cipher)）
   */
  public String encrypt(String plain) {
    if (plain == null) {
      return null;
    }

    // 优先：使用 per-user DEK
    Long userId = DekContext.getUserId();
    if (userId != null) {
      try {
        SecretKey dek = dekService.getUserDek(userId);
        if (dek == null) {
          // 用户无 DEK（新用户或未迁移）→ 生成并保存
          logger.info("[AES] 用户 {} 无 DEK，自动生成并保存", userId);
          dek = dekService.generateAndSaveDekForUser(userId);
        }
        return encryptWithKey(plain, dek);
      } catch (Exception e) {
        // 审计修复 P2-4：DEK 加密失败回退 legacy 共享密钥打破「主 KEK 泄露不牵连单用户」的隔离假设，
        // 必须记告警指标（Prometheus 采集）而非静默降级，配合日志供运维排查
        metricsConfig.recordDekFallback();
        logger.error("[AES] 使用 DEK 加密失败 (userId={})，回退到 legacy 密钥: {}", userId, e.getMessage());
        // 回退到 legacy 密钥（容错）
      }
    }

    // 回退：使用 legacy 静态密钥
    return encryptWithKey(plain, legacySecretKeySpec);
  }

  /**
   * 解密字符串（OPTIMIZATION 三.3.3 优先使用 per-user DEK）
   *
   * <p>密钥选择： 1. DekContext 有 userId → 优先使用该用户的 DEK 解密 2. DEK 解密失败或无 userId → 回退到 legacy
   * 静态密钥（解密历史数据） 3. 都失败 → fail-close（审计修复 P1-1）：prod 返回 null + 告警指标，非 prod 原样返回（迁移期容错）
   *
   * @param stored 密文（base64(iv):base64(cipher)）
   * @return 明文；非加密格式（旧明文数据）原样返回；全密钥解密失败时 prod 返回 null / 非 prod 返回原值
   */
  public String decrypt(String stored) {
    if (stored == null || stored.isEmpty()) {
      return stored;
    }

    // 兼容旧明文数据：非 iv:cipher 格式直接返回原值
    if (!isEncryptedFormat(stored)) {
      return stored;
    }

    // 优先：使用 per-user DEK 解密
    Long userId = DekContext.getUserId();
    if (userId != null) {
      try {
        SecretKey dek = dekService.getUserDek(userId);
        if (dek != null) {
          String decrypted = decryptWithKey(stored, dek);
          if (decrypted != null) {
            return decrypted;
          }
        }
      } catch (Exception e) {
        logger.debug("[AES] DEK 解密失败 (userId={})，尝试 legacy 密钥: {}", userId, e.getMessage());
        // 继续尝试 legacy 密钥
      }
    }

    // 回退：使用 legacy 静态密钥
    String legacyResult = decryptWithKey(stored, legacySecretKeySpec);
    if (legacyResult != null) {
      return legacyResult;
    }

    // ============================================================
    // 审计修复 P1-1：全密钥解密失败时 fail-close，密文绝不交还调用方
    // （原实现原样返回密文，被篡改的手机号/邮箱会被当明文展示甚至写回）
    // prod：返回 null + error 日志 + 告警指标（由 Prometheus 采集告警）
    // 非 prod：保留迁移期容错（原样返回 + warn，便于旧数据排查）
    // ============================================================
    metricsConfig.recordAesDecryptFailure();
    if (isProdProfile()) {
      logger.error(
          "[AES] 所有密钥均无法解密（密文被篡改/损坏或密钥错配），按 fail-close 返回 null: {}",
          stored.substring(0, Math.min(50, stored.length())));
      return null;
    }
    logger.warn("[AES] 所有密钥均无法解密，返回原值（非生产环境迁移期容错）: {}",
        stored.substring(0, Math.min(50, stored.length())));
    return stored;
  }

  /** 用指定密钥加密字符串（内部辅助方法） */
  private String encryptWithKey(String plain, SecretKey key) {
    try {
      byte[] iv = new byte[IV_LENGTH_BYTE];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
      cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

      byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

      return Base64.getEncoder().encodeToString(iv)
          + ":"
          + Base64.getEncoder().encodeToString(cipherText);
    } catch (Exception e) {
      logger.error("[AES] 加密失败: {}", e.getMessage(), e);
      throw new BusinessException("数据加密失败");
    }
  }

  /**
   * 用指定密钥解密字符串
   *
   * <p>可见性：public — AesEncryptedStringTypeHandler（com.workorder.config）跨包调用， 用于行级 DEK 解密（从
   * ResultSet 读取 encrypted_dek → 解密 DEK → 解密 phone/email）。
   *
   * @return 明文；解密失败返回 null（由调用方决定回退策略）
   */
  public String decryptWithKey(String stored, SecretKey key) {
    if (key == null) {
      return null;
    }
    try {
      String[] parts = stored.split(":");
      if (parts.length != 2) {
        return null;
      }

      byte[] iv = Base64.getDecoder().decode(parts[0]);
      byte[] cipherText = Base64.getDecoder().decode(parts[1]);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
      cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

      byte[] plainBytes = cipher.doFinal(cipherText);
      return new String(plainBytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      // 解密失败：可能是密钥不匹配（DEK 与历史密文不匹配）
      return null;
    }
  }

  /** 加密（null 安全） */
  public String encryptIfNotNull(String plain) {
    if (plain == null) return null;
    return encrypt(plain);
  }

  /** 解密（null 安全） */
  public String decryptIfNotNull(String stored) {
    if (stored == null) return null;
    return decrypt(stored);
  }

  /** 判断字符串是否为加密格式（base64(iv):base64(cipher)） */
  public boolean isEncryptedFormat(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    int colonIdx = value.indexOf(':');
    if (colonIdx <= 0 || colonIdx >= value.length() - 1) {
      return false;
    }
    String ivPart = value.substring(0, colonIdx);
    String cipherPart = value.substring(colonIdx + 1);
    return isBase64(ivPart) && isBase64(cipherPart);
  }

  private boolean isBase64(String str) {
    if (str == null || str.isEmpty()) return false;
    return str.matches("^[A-Za-z0-9+/]*={0,2}$");
  }

  /**
   * 判断当前是否为生产环境。
   *
   * <p>与 KekService.isProdProfile() 保持同一逻辑：activeProfiles 含 "prod" 即视为生产环境， 避免使用
   * System.getProperty("spring.profiles.active") 在环境变量方式激活 prod 时取不到值而误走 dev 临时密钥分支。
   */
  private boolean isProdProfile() {
    String[] activeProfiles = environment.getActiveProfiles();
    for (String profile : activeProfiles) {
      if ("prod".equalsIgnoreCase(profile)) {
        return true;
      }
    }
    return false;
  }
}
