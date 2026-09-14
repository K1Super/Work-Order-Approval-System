package com.workorder.config;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 企业级跨域配置 生产环境严格限制允许的来源域名
 *
 * <p>阶段 3 修复 §3 — CORS 严格配置
 *
 * <p>- 显式 AllowedHeaders（替代 "*"） - dev 环境精确白名单（替代 http://localhost:* 通配） - prod 环境 fail-closed：未配置
 * allowed-origins 抛异常 - 显式 ExposedHeaders
 *
 * @author KLord
 */
@Configuration
public class CorsConfig {

  private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  private final Environment environment;

  /** 构造函数，注入环境对象用于判断运行环境 */
  public CorsConfig(Environment environment) {
    this.environment = environment;
  }

  @Bean
  public CorsFilter corsFilter() {
    CorsConfiguration config = new CorsConfiguration();

    // 1. 配置允许的来源
    if (allowedOrigins != null && !allowedOrigins.isBlank()) {
      // 显式配置：按逗号分隔
      List<String> originList = Arrays.asList(allowedOrigins.split(","));
      config.setAllowedOrigins(originList);
      logger.info("CORS allowed origins: {}", originList);
    } else {
      // 未配置：根据 profile 决定
      String activeProfile = environment.getProperty("spring.profiles.active", "dev");
      if ("prod".equalsIgnoreCase(activeProfile)) {
        // 生产环境 fail-closed
        throw new IllegalStateException("生产环境必须配置 cors.allowed-origins，禁止使用通配来源");
      }
      // dev 环境精确白名单（不再用 http://localhost:* 通配）
      List<String> devOrigins =
          Arrays.asList(
              "http://localhost:5173",
              "http://localhost:3000",
              "http://127.0.0.1:5173",
              "http://127.0.0.1:3000");
      config.setAllowedOrigins(devOrigins);
      logger.info("CORS dev origins: {}", devOrigins);
    }

    // 2. 允许携带凭证（Cookie）
    config.setAllowCredentials(true);

    // 3. 显式允许的请求头（替代 "*"）
    config.setAllowedHeaders(
        Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "X-XSRF-TOKEN",
            "X-Request-Nonce",
            "X-Request-Timestamp",
            "Accept",
            "Origin"));

    // 4. 允许的 HTTP 方法
    config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

    // 5. 显式暴露的响应头（供前端获取）
    config.setExposedHeaders(
        Arrays.asList("Authorization", "Content-Type", "X-Request-Nonce", "Retry-After"));

    // 6. 预检请求缓存时间（秒）
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);

    return new CorsFilter(source);
  }
}
