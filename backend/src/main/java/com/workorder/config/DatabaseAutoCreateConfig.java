package com.workorder.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * Database Auto-Create Configuration
 *
 * <p>安全策略（阶段 1 修复 A-04）： 1. 移除硬编码的 postgres/666666 凭证与固定 URL — 改为从 spring.datasource 配置派生 2. 通过
 * work-order-system.db.auto-create 属性控制是否执行（默认 false，生产环境必须 false） 3. 维护库连接复用业务库的
 * username/password，仅切换库名为 postgres 4. 库名通过正则白名单校验，防止 CREATE DATABASE 注入
 *
 * <p>修复点（2026-07-19）：凭证读取改用 Spring {@link Environment}，正确解析
 *
 * @author KLord
 */
@Configuration
public class DatabaseAutoCreateConfig {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseAutoCreateConfig.class);

  private final Environment environment;

  /** 构造函数，注入环境对象用于读取数据库配置 */
  public DatabaseAutoCreateConfig(Environment environment) {
    this.environment = environment;
  }

  /** 是否启用自动建库（默认 false）。 dev 环境在 application-dev.yml 中设为 true；prod 不设置 → false → 永不自动建库。 */
  @Value("${work-order-system.db.auto-create:false}")
  private boolean autoCreateEnabled;

  /**
   * DataSource properties bean — 仅在 work-order-system.db.auto-create=true 时触发自动建库。
   * DataSourceProperties Bean 始终创建（业务库连接必需），但建库逻辑条件执行。
   */
  @Bean
  @Primary
  @ConfigurationProperties(prefix = "spring.datasource")
  public DataSourceProperties dataSourceProperties() {
    if (autoCreateEnabled) {
      createDatabaseIfNotExists();
    } else {
      logger.debug("work-order-system.db.auto-create=false，跳过自动建库（生产环境推荐）");
    }
    return new DataSourceProperties();
  }

  /**
   * Create database (if not exists)
   *
   * <p>凭证来源：通过 Spring {@link Environment} 读取 spring.datasource 配置， 已由 Spring 解析
   * ${DB_PASSWORD:666666} 等占位符。 - 维护库 URL：从业务库 URL 提取 host:port，替换库名为 "postgres" -
   * 用户名/密码：复用业务库凭证（避免硬编码）
   */
  private void createDatabaseIfNotExists() {
    // 通过 Spring Environment 读取业务库配置（正确解析占位符与 yml 配置）
    String businessUrl = environment.getProperty("spring.datasource.url");
    String user = environment.getProperty("spring.datasource.username");
    String password = environment.getProperty("spring.datasource.password");

    // 兜底默认值（仅当配置缺失时使用，正常情况下 application-dev.yml 已提供默认值）
    if (businessUrl == null || businessUrl.isEmpty()) {
      businessUrl = "jdbc:postgresql://localhost:5432/work_order_system?stringtype=unspecified";
    }
    if (user == null || user.isEmpty()) {
      user = "postgres";
    }
    if (password == null) {
      password = "";
    }

    // 从业务库 URL 提取目标库名与维护库 URL
    // 业务库 URL 格式: jdbc:postgresql://host:port/dbname?params
    String dbName = extractDatabaseName(businessUrl);
    String maintenanceUrl = buildMaintenanceUrl(businessUrl);

    if (dbName == null || maintenanceUrl == null) {
      logger.warn("无法从业务库 URL 解析库名或派生维护库 URL，跳过自动建库: {}", businessUrl);
      return;
    }

    // 库名白名单校验（防 SQL 注入）
    if (!isValidIdentifier(dbName)) {
      logger.error("非法的数据库名，跳过自动建库: {}", dbName);
      return;
    }

    logger.info("检查数据库是否存在: {} (维护库连接: {})", dbName, maintenanceUrl);

    try (Connection conn = DriverManager.getConnection(maintenanceUrl, user, password);
        Statement stmt = conn.createStatement()) {

      ResultSet rs =
          stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");

      if (!rs.next()) {
        logger.info("数据库 {} 不存在，正在创建...", dbName);
        stmt.executeUpdate("CREATE DATABASE \"" + dbName + "\" ENCODING 'UTF-8'");
        logger.info("数据库 {} 创建成功", dbName);
      } else {
        logger.info("数据库 {} 已存在", dbName);
      }

      rs.close();

    } catch (Exception e) {
      logger.error("自动建库失败: {}", e.getMessage(), e);
      throw new RuntimeException("Cannot create database " + dbName, e);
    }
  }

  /** 从 JDBC URL 提取数据库名 输入: jdbc:postgresql://host:port/dbname?params 输出: dbname */
  private String extractDatabaseName(String url) {
    if (url == null) return null;
    // 移除查询参数
    String urlWithoutParams = url.split("\\?")[0];
    // 取最后一个 / 之后的部分
    int lastSlash = urlWithoutParams.lastIndexOf('/');
    if (lastSlash < 0 || lastSlash == urlWithoutParams.length() - 1) return null;
    return urlWithoutParams.substring(lastSlash + 1);
  }

  /**
   * 构建维护库 URL（连接到 postgres 系统库以执行 CREATE DATABASE） 输入: jdbc:postgresql://host:port/dbname?params
   * 输出: jdbc:postgresql://host:port/postgres
   */
  private String buildMaintenanceUrl(String businessUrl) {
    if (businessUrl == null) return null;
    // 移除查询参数
    String urlWithoutParams = businessUrl.split("\\?")[0];
    int lastSlash = urlWithoutParams.lastIndexOf('/');
    if (lastSlash < 0) return null;
    return urlWithoutParams.substring(0, lastSlash) + "/postgres";
  }

  /** 校验数据库标识符合法性（仅允许小写字母、数字、下划线） 防止 CREATE DATABASE 注入 */
  private boolean isValidIdentifier(String identifier) {
    if (identifier == null || identifier.isEmpty()) return false;
    return identifier.matches("^[a-z_][a-z0-9_]*$");
  }
}
