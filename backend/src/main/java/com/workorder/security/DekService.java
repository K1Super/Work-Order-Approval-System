package com.workorder.security;


import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.common.exception.BusinessException;
import com.workorder.dao.UserMapper;

/**
 * DEK（Data Encryption Key）服务（OPTIMIZATION 三.3.3）
 *
 * <p>每个用户独立的 DEK，用于加密敏感字段（手机号/邮箱）。 DEK 用 KEK 加密后存数据库（sys_user.encrypted_dek 列）。
 *
 * <p><b>分层加密架构：</b>
 *
 * <pre>
 *   KEK（来自 env/Vault，内存中）
 *     ↓ 加密
 *   DEK（per-user，存数据库 encrypted_dek 列）
 *     ↓ 加密
 *   敏感字段（手机号/邮箱，存数据库 email/phone 列）
 * </pre>
 *
 * <p><b>密钥轮换：</b>KEK 轮换时只需重新加密所有 DEK，历史密文（email/phone）无需改动。 DEK 轮换时需重新加密该用户的所有敏感字段。
 *
 * @author KLord
 */
@Component
public class DekService {

  private static final Logger logger = LoggerFactory.getLogger(DekService.class);

  /** DEK 算法（AES-256-GCM） */
  private static final String DEK_ALGORITHM = "AES";

  /** DEK 加密算法（用 KEK 加密 DEK 时使用 AES-GCM） */
  private static final String DEK_WRAP_TRANSFORMATION = "AES/GCM/NoPadding";

  /** GCM Tag 长度（位） */
  private static final int GCM_TAG_LENGTH_BIT = 128;

  /** IV 长度（字节，GCM 推荐 12 字节） */
  private static final int IV_LENGTH_BYTE = 12;

  /** DEK 长度（位，AES-256 = 256 位） */
  private static final int DEK_LENGTH_BIT = 256;

  private final SecureRandom secureRandom = new SecureRandom();

  @Autowired private KekService kekService;

  @Autowired private UserMapper userMapper;

  /**
   * DEK 内存缓存（userId → 已解密的 DEK SecretKey）
   *
   * <p>缓存已解密的 DEK 避免每次解密字段都查询数据库 + KEK 解密。 缓存仅在 JVM 内存（不持久化、不外泄）。 缓存大小受限于用户数（每个在线用户 32 字节），可忽略不计。
   */
  private final ConcurrentHashMap<Long, SecretKey> dekCache = new ConcurrentHashMap<>();

  /**
   * 生成新的 DEK（明文，未加密）
   *
   * <p>用于：1) 新用户创建时生成 DEK；2) KEK 轮换后重新生成 DEK
   *
   * @return 新生成的 DEK SecretKey
   */
  public SecretKey generateNewDek() {
    try {
      KeyGenerator keyGen = KeyGenerator.getInstance(DEK_ALGORITHM);
      keyGen.init(DEK_LENGTH_BIT, secureRandom);
      return keyGen.generateKey();
    } catch (Exception e) {
      logger.error("[DEK] 生成 DEK 失败: {}", e.getMessage(), e);
      throw new BusinessException("DEK 生成失败");
    }
  }

  /**
   * 用 KEK 加密 DEK（DEK 明文 → encrypted_dek 密文）
   *
   * <p>密文格式：base64(iv) + ":" + base64(cipherText+authTag)
   *
   * @param dek 明文 DEK SecretKey
   * @return Base64 编码的加密 DEK（用于存数据库 encrypted_dek 列）
   */
  public String encryptDek(SecretKey dek) {
    try {
      // 1. 生成随机 IV
      byte[] iv = new byte[IV_LENGTH_BYTE];
      secureRandom.nextBytes(iv);

      // 2. 用 KEK 加密 DEK
      Cipher cipher = Cipher.getInstance(DEK_WRAP_TRANSFORMATION);
      GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
      cipher.init(Cipher.ENCRYPT_MODE, kekService.getKek(), parameterSpec);

      byte[] dekBytes = dek.getEncoded();
      byte[] cipherText = cipher.doFinal(dekBytes);

      // 3. 拼接 base64(iv):base64(cipherText)
      String encrypted =
          Base64.getEncoder().encodeToString(iv)
              + ":"
              + Base64.getEncoder().encodeToString(cipherText);

      logger.debug("[DEK] DEK 加密成功（KEK fingerprint={}）", kekService.getKekFingerprint());
      return encrypted;
    } catch (Exception e) {
      logger.error("[DEK] DEK 加密失败: {}", e.getMessage(), e);
      throw new BusinessException("DEK 加密失败");
    }
  }

  /**
   * 用 KEK 解密 DEK（encrypted_dek 密文 → DEK 明文）
   *
   * @param encryptedDek Base64 编码的加密 DEK（来自数据库 encrypted_dek 列）
   * @return 解密后的 DEK SecretKey
   */
  public SecretKey decryptDek(String encryptedDek) {
    try {
      String[] parts = encryptedDek.split(":");
      if (parts.length != 2) {
        throw new IllegalArgumentException("encrypted_dek 格式非法（应为 iv:cipher）");
      }

      byte[] iv = Base64.getDecoder().decode(parts[0]);
      byte[] cipherText = Base64.getDecoder().decode(parts[1]);

      Cipher cipher = Cipher.getInstance(DEK_WRAP_TRANSFORMATION);
      GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
      cipher.init(Cipher.DECRYPT_MODE, kekService.getKek(), parameterSpec);

      byte[] dekBytes = cipher.doFinal(cipherText);
      return new SecretKeySpec(dekBytes, DEK_ALGORITHM);
    } catch (Exception e) {
      logger.error("[DEK] DEK 解密失败: {}", e.getMessage(), e);
      throw new BusinessException("DEK 解密失败（可能是 KEK 不匹配或数据损坏）");
    }
  }

  /**
   * 获取用户的 DEK（带内存缓存）
   *
   * <p>流程： 1. 从内存缓存查找 DEK 2. 缓存未命中 → 查询数据库 encrypted_dek 列 3. 用 KEK 解密 → 得到 DEK 明文 4. 存入内存缓存 5. 返回
   * DEK
   *
   * <p>若用户无 DEK（新用户或未迁移用户）： - 返回 null（由调用方决定是否生成新 DEK）
   *
   * @param userId 用户ID
   * @return 用户的 DEK SecretKey；无 DEK 返回 null
   */
  public SecretKey getUserDek(Long userId) {
    if (userId == null) {
      return null;
    }

    // 1. 内存缓存查找
    SecretKey cached = dekCache.get(userId);
    if (cached != null) {
      return cached;
    }

    // 2. 查询数据库
    String encryptedDek = userMapper.selectEncryptedDek(userId);
    if (encryptedDek == null || encryptedDek.isEmpty()) {
      logger.debug("[DEK] 用户 {} 无 encrypted_dek（新用户或未迁移）", userId);
      return null;
    }

    // 3. 用 KEK 解密
    SecretKey dek = decryptDek(encryptedDek);

    // 4. 存入缓存
    dekCache.put(userId, dek);

    logger.debug("[DEK] 已加载用户 {} 的 DEK（缓存命中后存入）", userId);
    return dek;
  }

  /**
   * 为用户生成并保存新 DEK
   *
   * <p>流程： 1. 生成新 DEK 2. 用 KEK 加密 → encrypted_dek 3. 写入数据库 sys_user.encrypted_dek 4. 存入内存缓存
   *
   * @param userId 用户ID
   * @return 新生成的 DEK SecretKey
   */
  public SecretKey generateAndSaveDekForUser(Long userId) {
    if (userId == null) {
      throw new IllegalArgumentException("userId 不能为空");
    }

    // 1. 生成新 DEK
    SecretKey dek = generateNewDek();

    // 2. 用 KEK 加密
    String encryptedDek = encryptDek(dek);

    // 3. 写入数据库
    int rows = userMapper.updateEncryptedDek(userId, encryptedDek);
    if (rows == 0) {
      logger.error("[DEK] 用户 {} 写入 encrypted_dek 失败（用户可能不存在）", userId);
      throw new BusinessException("用户 DEK 保存失败");
    }

    // 4. 存入缓存
    dekCache.put(userId, dek);

    logger.info("[DEK] 已为用户 {} 生成并保存新 DEK", userId);
    return dek;
  }

  /**
   * 清除用户 DEK 缓存（用户删除/DEK 轮换后调用）
   *
   * @param userId 用户ID
   */
  public void evictDekCache(Long userId) {
    dekCache.remove(userId);
    logger.debug("[DEK] 已清除用户 {} 的 DEK 缓存", userId);
  }

  /** 获取 DEK 的 Base64 编码（用于密钥指纹审计） */
  public String getDekFingerprint(SecretKey dek) {
    try {
      byte[] encoded = dek.getEncoded();
      java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = sha256.digest(encoded);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        sb.append(String.format("%02x", hash[i]));
      }
      return sb.toString();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
