package com.workorder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部化配置属性类（规范 §8 安全开发规范 — 敏感配置外部化）
 *
 * <p>统一管理应用配置，前缀 {@code work-order-system}。 所有敏感配置通过环境变量注入，禁止硬编码。
 *
 * <p>嵌套结构：
 *
 * <ul>
 *   <li>{@code work-order-system.security} — 安全配置（JWT、CORS 等）
 *   <li>{@code work-order-system.rate-limit} — 限流配置
 *   <li>{@code work-order-system.db} — 数据库配置
 *   <li>{@code work-order-system.dek-migration} — DEK 迁移配置
 * </ul>
 *
 * <p>使用示例（application.yml）：
 *
 * <pre>
 * work-order-system:
 *   security:
 *     jwt-secret: ${JWT_SECRET}
 *     jwt-expiration-minutes: 60
 *     cors-allowed-origins: https://example.com
 *   rate-limit:
 *     enabled: true
 *     requests-per-minute: 60
 *   db:
 *     password: ${DB_PASSWORD}
 *     encryption-key: ${AES_ENCRYPTION_KEY}
 *   dek-migration:
 *     kek-base64: ${KEK_BASE64}
 *     batch-size: 100
 * </pre>
 *
 * @author KLord
 */
@Component
@ConfigurationProperties(prefix = "work-order-system")
public class ExternalConfigProperties {

  /** 安全相关配置 */
  private Security security = new Security();

  /** 限流相关配置 */
  private RateLimit rateLimit = new RateLimit();

  /** 数据库相关配置 */
  private Db db = new Db();

  /** DEK 迁移相关配置 */
  private DekMigration dekMigration = new DekMigration();

  // ==================== Getter / Setter ====================

  public Security getSecurity() {
    return security;
  }

  public void setSecurity(Security security) {
    this.security = security;
  }

  public RateLimit getRateLimit() {
    return rateLimit;
  }

  public void setRateLimit(RateLimit rateLimit) {
    this.rateLimit = rateLimit;
  }

  public Db getDb() {
    return db;
  }

  public void setDb(Db db) {
    this.db = db;
  }

  public DekMigration getDekMigration() {
    return dekMigration;
  }

  public void setDekMigration(DekMigration dekMigration) {
    this.dekMigration = dekMigration;
  }

  // ==================== 嵌套配置类 ====================

  /** 安全配置 */
  public static class Security {

    /** JWT 签名密钥（必须通过环境变量 JWT_SECRET 注入） */
    private String jwtSecret;

    /** JWT 令牌有效期（分钟），默认 60 */
    private long jwtExpirationMinutes = 60;

    /** JWT 刷新令牌有效期（分钟），默认 10080（7天） */
    private long jwtRefreshExpirationMinutes = 10080;

    /** CORS 允许的来源（逗号分隔） */
    private String corsAllowedOrigins;

    /** 是否启用 CSRF 防护，默认 true */
    private boolean csrfEnabled = true;

    public String getJwtSecret() {
      return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
      this.jwtSecret = jwtSecret;
    }

    public long getJwtExpirationMinutes() {
      return jwtExpirationMinutes;
    }

    public void setJwtExpirationMinutes(long jwtExpirationMinutes) {
      this.jwtExpirationMinutes = jwtExpirationMinutes;
    }

    public long getJwtRefreshExpirationMinutes() {
      return jwtRefreshExpirationMinutes;
    }

    public void setJwtRefreshExpirationMinutes(long jwtRefreshExpirationMinutes) {
      this.jwtRefreshExpirationMinutes = jwtRefreshExpirationMinutes;
    }

    public String getCorsAllowedOrigins() {
      return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(String corsAllowedOrigins) {
      this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public boolean isCsrfEnabled() {
      return csrfEnabled;
    }

    public void setCsrfEnabled(boolean csrfEnabled) {
      this.csrfEnabled = csrfEnabled;
    }
  }

  /** 限流配置 */
  public static class RateLimit {

    /** 是否启用限流，默认 true */
    private boolean enabled = true;

    /** 每分钟最大请求数，默认 60 */
    private int requestsPerMinute = 60;

    /** 登录接口每分钟最大请求数，默认 5 */
    private int loginRequestsPerMinute = 5;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getRequestsPerMinute() {
      return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
      this.requestsPerMinute = requestsPerMinute;
    }

    public int getLoginRequestsPerMinute() {
      return loginRequestsPerMinute;
    }

    public void setLoginRequestsPerMinute(int loginRequestsPerMinute) {
      this.loginRequestsPerMinute = loginRequestsPerMinute;
    }
  }

  /** 数据库相关配置 */
  public static class Db {

    /** 数据库密码（必须通过环境变量 DB_PASSWORD 注入） */
    private String password;

    /** AES 加密密钥（必须通过环境变量 AES_ENCRYPTION_KEY 注入） */
    private String encryptionKey;

    /** 数据库连接池最大大小，默认 20 */
    private int poolMaxSize = 20;

    /** 数据库连接池最小空闲，默认 5 */
    private int poolMinIdle = 5;

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public String getEncryptionKey() {
      return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
      this.encryptionKey = encryptionKey;
    }

    public int getPoolMaxSize() {
      return poolMaxSize;
    }

    public void setPoolMaxSize(int poolMaxSize) {
      this.poolMaxSize = poolMaxSize;
    }

    public int getPoolMinIdle() {
      return poolMinIdle;
    }

    public void setPoolMinIdle(int poolMinIdle) {
      this.poolMinIdle = poolMinIdle;
    }
  }

  /** DEK 迁移相关配置 */
  public static class DekMigration {

    /** KEK Base64 编码密钥（必须通过环境变量 KEK_BASE64 注入） */
    private String kekBase64;

    /** 批量迁移每批大小，默认 100 */
    private int batchSize = 100;

    /** 是否启用 DEK 自动迁移，默认 false */
    private boolean autoMigrationEnabled = false;

    public String getKekBase64() {
      return kekBase64;
    }

    public void setKekBase64(String kekBase64) {
      this.kekBase64 = kekBase64;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public boolean isAutoMigrationEnabled() {
      return autoMigrationEnabled;
    }

    public void setAutoMigrationEnabled(boolean autoMigrationEnabled) {
      this.autoMigrationEnabled = autoMigrationEnabled;
    }
  }
}
