package com.workorder.config;


import javax.annotation.PostConstruct;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus 指标配置 — 安全告警埋点 配合 docs/alertmanager-rules.yml 实现实时告警
 *
 * <p>安全要求（§6）：实时告警 — 短时间大量 401/403、敏感操作频率异常
 *
 * <p>指标列表： 1. wos_login_attempts_total{result=success|failure} — 登录尝试 2.
 * wos_authorization_denials_total{reason=no_token|invalid_token|no_permission|data_denied} — 授权拒绝
 * 3. wos_sensitive_operations_total{type=login|password_reset|delete|data_export} — 敏感操作
 *
 * @author KLord
 */
@Configuration
public class MetricsConfig {

  private static final Logger logger = LoggerFactory.getLogger(MetricsConfig.class);

  @Autowired private MeterRegistry meterRegistry;

  @PostConstruct
  public void init() {
    logger.info(
        "📊 Prometheus 安全指标已注册：wos_login_attempts_total, wos_authorization_denials_total, "
            + "wos_sensitive_operations_total, wos_client_errors_total");
  }

  /**
   * 增加登录尝试指标
   *
   * @param success true=成功，false=失败
   */
  public void recordLoginAttempt(boolean success) {
    try {
      Counter.builder("wos_login_attempts_total")
          .description("Total login attempts")
          .tag("result", success ? "success" : "failure")
          .register(meterRegistry)
          .increment();
    } catch (Exception e) {
      logger.debug("记录登录指标失败（不影响业务）: {}", e.getMessage());
    }
  }

  /**
   * 增加授权拒绝指标
   *
   * @param reason no_token / invalid_token / no_permission / data_denied
   */
  public void recordAuthorizationDenial(String reason) {
    try {
      Counter.builder("wos_authorization_denials_total")
          .description("Authorization denials")
          .tag("reason", reason)
          .register(meterRegistry)
          .increment();
    } catch (Exception e) {
      logger.debug("记录授权拒绝指标失败（不影响业务）: {}", e.getMessage());
    }
  }

  /**
   * 增加敏感操作指标
   *
   * @param type login / password_reset / delete / data_export
   */
  public void recordSensitiveOperation(String type) {
    try {
      Counter.builder("wos_sensitive_operations_total")
          .description("Sensitive operations")
          .tag("type", type)
          .register(meterRegistry)
          .increment();
    } catch (Exception e) {
      logger.debug("记录敏感操作指标失败（不影响业务）: {}", e.getMessage());
    }
  }

  /**
   * 增加前端错误指标 — 规范条款 5（可采集可告警）
   *
   * @param level WARN / ERROR
   */
  public void recordClientError(String level) {
    try {
      Counter.builder("wos_client_errors_total")
          .description("Client-side errors reported")
          .tag("level", level != null ? level : "ERROR")
          .register(meterRegistry)
          .increment();
    } catch (Exception e) {
      logger.debug("记录前端错误指标失败（不影响业务）: {}", e.getMessage());
    }
  }
}
