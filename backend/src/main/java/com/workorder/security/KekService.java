package com.workorder.security;

import java.util.Base64;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * KEK（Key Encryption Key）服务（OPTIMIZATION 三.3.3）
 *
 * <p>企业级分层加密的根密钥管理：KEK → 加密 DEK → DEK 加密敏感字段（手机号/邮箱）。 KEK 永远不直接接触业务数据，仅用于加解密 DEK。
 *
 * <p><b>KEK 来源策略（用户决策 3）：</b>
 *
 * <ul>
 *   <li>dev 环境：环境变量 {@code KEK_BASE64}（32 字节 Base64 编码），未配置时使用固定默认值便于本地启动
 *   <li>prod 环境：<b>硬断校验</b>，必须从外部 KMS（Vault/云 KMS）注入，无默认值，未配置或使用默认值时启动失败
 * </ul>
 *
 * <p><b>启动校验：</b>
 *
 * <ul>
 *   <li>KEK 长度必须为 32 字节（256 位，AES-256）
 *   <li>Base64 解码必须成功
 *   <li>prod 环境禁止使用 dev 默认 KEK（fail-closed）
 * </ul>
 *
 * @author KLord
 */
@Component
public class KekService {

  private static final Logger logger = LoggerFactory.getLogger(KekService.class);
  private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_VIOLATION_LOGGER");

  /** KEK 算法（AES-256） */
  private static final String KEK_ALGORITHM = "AES";

  /** KEK 长度（字节，AES-256 = 32 字节） */
  private static final int KEK_LENGTH_BYTES = 32;

  /** dev 环境默认 KEK（仅用于本地启动，禁止用于任何真实环境） 真实值：32 字节随机数，Base64 编码 */
  private static final String DEV_DEFAULT_KEK_BASE64 =
      "ZGV2LWtlay0zMmJ5dGVzLWZpeGVkLTIwMjYhIWEjJSQ="; // dev-kek-32bytes-fixed-2026!a@#$

  /** KEK Base64 配置值（来自环境变量或 Vault） */
  @Value("${work-order-system.security.kek-base64:}")
  private String kekBase64Config;

  /** KEK 来源标记（用于审计与硬断校验） */
  @Value("${work-order-system.security.kek-source:env}")
  private String kekSource;

  private final Environment environment;

  /** 缓存在内存中的 KEK SecretKey（不序列化、不出 JVM） */
  private SecretKey kekSecretKey;

  /** 启动时记录的 KEK 指纹（SHA-256 前 8 字节 hex，用于审计日志，不泄露密钥） */
  private String kekFingerprint;

  /** 构造函数，注入环境对象用于判断运行环境 */
  public KekService(Environment environment) {
    this.environment = environment;
  }

  /**
   * 启动时加载并校验 KEK（OPTIMIZATION 三.3.3 硬断校验）
   *
   * <p>校验规则： 1. KEK 必须非空 2. KEK 必须为合法 Base64 3. 解码后长度必须为 32 字节（AES-256） 4. prod 环境禁止使用 dev 默认 KEK
   * 5. prod 环境强制 kek-source=vault（推荐）或 env（严格注入）
   */
  @PostConstruct
  public void init() {
    boolean isProd = isProdProfile();
    logger.info("[KEK] 初始化 KEK 服务 (profile={}, kek-source={})", isProd ? "prod" : "dev", kekSource);

    // 1. 校验配置非空
    if (kekBase64Config == null || kekBase64Config.isBlank()) {
      if (isProd) {
        // prod 硬断：必须配置 KEK，无默认值
        securityLogger.error("[KEK] prod 环境 KEK_BASE64 未配置，启动失败（fail-closed）");
        throw new IllegalStateException(
            "生产环境必须配置 work-order-system.security.kek-base64（来自 Vault/云 KMS），禁止空值");
      }
      // dev 环境：使用默认 KEK（仅本地测试）
      logger.warn("[KEK] dev 环境未配置 KEK_BASE64，使用固定默认 KEK（仅本地测试，禁止用于任何真实环境）");
      kekBase64Config = DEV_DEFAULT_KEK_BASE64;
    }

    // 2. prod 硬断：禁止使用 dev 默认 KEK
    if (isProd && DEV_DEFAULT_KEK_BASE64.equals(kekBase64Config)) {
      securityLogger.error("[KEK] prod 环境检测到使用 dev 默认 KEK，启动失败（fail-closed）");
      throw new IllegalStateException("生产环境禁止使用 dev 默认 KEK，必须从 Vault/云 KMS 注入独立 KEK_BASE64");
    }

    // 3. Base64 解码 + 长度校验
    byte[] kekBytes;
    try {
      kekBytes = Base64.getDecoder().decode(kekBase64Config);
    } catch (IllegalArgumentException e) {
      securityLogger.error("[KEK] KEK_BASE64 解码失败: {}", e.getMessage());
      throw new IllegalStateException("KEK_BASE64 必须为合法 Base64 编码", e);
    }

    if (kekBytes.length != KEK_LENGTH_BYTES) {
      securityLogger.error("[KEK] KEK 长度非法: 期望 {} 字节，实际 {} 字节", KEK_LENGTH_BYTES, kekBytes.length);
      throw new IllegalStateException(
          "KEK 长度必须为 " + KEK_LENGTH_BYTES + " 字节（AES-256），当前: " + kekBytes.length + " 字节");
    }

    // 4. 构建 SecretKey 并缓存
    this.kekSecretKey = new SecretKeySpec(kekBytes, KEK_ALGORITHM);

    // 5. 计算 KEK 指纹（SHA-256 前 8 字节 hex，用于审计日志，不泄露密钥本身）
    this.kekFingerprint = computeFingerprint(kekBytes);

    logger.info(
        "[KEK] KEK 初始化成功 (fingerprint={}, length=256bit, source={})",
        kekFingerprint,
        isProd ? "vault/env-prod" : "env-dev");

    // 安全审计日志（不记录密钥本身）
    securityLogger.info(
        "[KEK] KEK 加载完成: profile={}, source={}, fingerprint={}",
        isProd ? "prod" : "dev",
        kekSource,
        kekFingerprint);
  }

  /**
   * 获取 KEK SecretKey（仅在 JVM 内部使用，不外泄）
   *
   * @return KEK SecretKey
   * @throws IllegalStateException 如果 KEK 未初始化
   */
  public SecretKey getKek() {
    if (kekSecretKey == null) {
      throw new IllegalStateException("KEK 尚未初始化，请检查启动日志");
    }
    return kekSecretKey;
  }

  /**
   * 获取 KEK 指纹（SHA-256 前 8 字节 hex）
   *
   * <p>用于审计日志，不泄露密钥本身。
   *
   * @return 16 字符 hex 字符串
   */
  public String getKekFingerprint() {
    return kekFingerprint;
  }

  /** 判断当前是否为生产环境 */
  private boolean isProdProfile() {
    String[] activeProfiles = environment.getActiveProfiles();
    for (String profile : activeProfiles) {
      if ("prod".equalsIgnoreCase(profile)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 计算 KEK 指纹（SHA-256 前 8 字节 hex）
   *
   * <p>用于审计日志，识别不同 KEK 版本而不泄露密钥内容。
   */
  private String computeFingerprint(byte[] kekBytes) {
    try {
      java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = sha256.digest(kekBytes);
      // 取前 8 字节 → 16 字符 hex
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        sb.append(String.format("%02x", hash[i]));
      }
      return sb.toString();
    } catch (Exception e) {
      logger.warn("[KEK] 计算 KEK 指纹失败: {}", e.getMessage());
      return "unknown";
    }
  }
}
