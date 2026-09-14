package com.workorder.config;


import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 敏感配置校验器（规范 §8 安全开发规范 — 生产环境敏感配置校验）
 *
 * <p>在 {@code prod} Profile 下启动时校验关键敏感配置是否通过环境变量注入， 防止使用开发默认值导致生产环境安全事故。
 *
 * <p>校验规则：
 *
 * <ul>
 *   <li>JWT_SECRET — JWT 签名密钥，不允许使用默认值
 *   <li>DB_PASSWORD — 数据库密码，不允许使用默认值
 *   <li>AES_ENCRYPTION_KEY — AES 加密密钥，不允许使用默认值
 *   <li>KEK_BASE64 — KEK 密钥（Base64），不允许使用默认值
 * </ul>
 *
 * <p>任一必要配置缺失或使用开发默认值，将抛出 {@link IllegalStateException} 阻止应用启动。
 *
 * @author KLord
 */
@Component
@Profile("prod")
public class SensitiveConfigValidator {

  private static final Logger logger = LoggerFactory.getLogger(SensitiveConfigValidator.class);

  /** 开发环境默认值黑名单（这些值不允许在生产环境使用） */
  private static final String[] DEV_DEFAULT_SECRETS = {
      "dev-secret-key",
      "change-me",
      "changeme",
      "default-password",
      "defaultpassword",
      "dev-aes-key-32bytes-long-enough",
      "dGVzdC1rZXktZm9yLWRldi1vbmx5",
      "test-key-for-dev-only",
      "secret",
      "password",
      "123456",
      "admin"
  };

  @Autowired private ExternalConfigProperties configProperties;

  @PostConstruct
  public void validate() {
    logger.info("开始校验生产环境敏感配置...");

    List<String> violations = new ArrayList<>();

    // 1. 校验 JWT_SECRET
    String jwtSecret = configProperties.getSecurity().getJwtSecret();
    validateSecret(violations, "JWT_SECRET (work-order-system.security.jwt-secret)", jwtSecret);

    // 2. 校验 DB_PASSWORD
    String dbPassword = configProperties.getDb().getPassword();
    validateSecret(violations, "DB_PASSWORD (work-order-system.db.password)", dbPassword);

    // 3. 校验 AES_ENCRYPTION_KEY
    String aesKey = configProperties.getDb().getEncryptionKey();
    validateSecret(violations, "AES_ENCRYPTION_KEY (work-order-system.db.encryption-key)", aesKey);

    // 4. 校验 KEK_BASE64
    String kekBase64 = configProperties.getDekMigration().getKekBase64();
    validateSecret(
        violations, "KEK_BASE64 (work-order-system.dek-migration.kek-base64)", kekBase64);

    if (!violations.isEmpty()) {
      String errorMessage = buildErrorMessage(violations);
      logger.error(errorMessage);
      throw new IllegalStateException(errorMessage);
    }

    logger.info("生产环境敏感配置校验通过");
  }

  /**
   * 校验单个敏感配置项
   *
   * @param violations 违规列表（收集错误信息）
   * @param configName 配置项名称
   * @param value 配置值
   */
  private void validateSecret(List<String> violations, String configName, String value) {
    if (value == null || value.trim().isEmpty()) {
      violations.add(String.format("配置项 [%s] 未设置，请通过环境变量注入", configName));
      return;
    }

    String trimmedValue = value.trim();

    // 检查是否使用开发默认值
    for (String devDefault : DEV_DEFAULT_SECRETS) {
      if (trimmedValue.equalsIgnoreCase(devDefault)) {
        violations.add(
            String.format("配置项 [%s] 使用了开发默认值 [%s]，生产环境必须通过环境变量注入安全的值", configName, devDefault));
        return;
      }
    }

    // 检查长度过短（至少 16 字符）
    if (trimmedValue.length() < 16) {
      violations.add(
          String.format(
              "配置项 [%s] 长度过短（当前 %d 字符，生产环境要求至少 16 字符）", configName, trimmedValue.length()));
    }
  }

  /** 构建错误信息 */
  private String buildErrorMessage(List<String> violations) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n");
    sb.append("=============================================================\n");
    sb.append("  生产环境敏感配置校验失败！应用无法启动。\n");
    sb.append("  以下配置项不符合安全要求：\n");
    sb.append("=============================================================\n");
    for (int i = 0; i < violations.size(); i++) {
      sb.append(String.format("  %d. %s\n", i + 1, violations.get(i)));
    }
    sb.append("=============================================================\n");
    sb.append("  请通过环境变量设置上述配置项，禁止使用开发默认值。\n");
    sb.append("=============================================================");
    return sb.toString();
  }
}
