package com.workorder.config;


import java.time.LocalDateTime;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * MyBatis TypeHandler 配置 — 强制覆盖默认 JSR-310 LocalDateTimeTypeHandler
 *
 * <p>背景：OPTIMIZATION P6 把数据库时间列统一升级为 TIMESTAMPTZ（PostgreSQL timestamp with time zone）。MyBatis 内置的
 * JSR-310 LocalDateTimeTypeHandler 调用 {@code rs.getObject(col, LocalDateTime.class)} 读取 TIMESTAMPTZ
 * 列会抛 {@code PSQLException: Cannot convert the column of type TIMESTAMPTZ to requested type
 * java.time.LocalDateTime}，导致登录、DEK 迁移、工单查询全部失败。
 *
 * <p>修复：用 {@link TimestamptzLocalDateTimeTypeHandler} 覆盖以下三处注册位：
 *
 * <ul>
 *   <li>(LocalDateTime, null) — 通用 fallback（覆盖默认）
 *   <li>(LocalDateTime, TIMESTAMP) — 显式 JDBC TIMESTAMP
 *   <li>(LocalDateTime, TIMESTAMP_WITH_TIMEZONE) — 显式 TIMESTAMPTZ
 * </ul>
 *
 * <p>为什么不用 {@code @MappedTypes} + {@code @Component}？ 该自动注册方式只把 Handler 注册到 (LocalDateTime, null)
 * 位， 但 mybatis-spring-boot-starter 在创建 SqlSessionFactory 时会再次注册默认 JSR-310 Handler， 覆盖自定义
 * Handler（实测启动日志显示仍走 org.apache.ibatis.type.LocalDateTimeTypeHandler）。 ConfigurationCustomizer 在
 * SqlSessionFactory 构造前运行， 显式调用 {@code TypeHandlerRegistry.register} 强制覆盖默认注册，保证生效。
 *
 * @author KLord
 */
@Configuration
public class MyBatisTypeHandlerConfig {

  private static final Logger logger = LoggerFactory.getLogger(MyBatisTypeHandlerConfig.class);

  /**
   * 注册自定义 LocalDateTime TypeHandler，覆盖 MyBatis 默认 JSR-310 实现。
   *
   * @return ConfigurationCustomizer Bean
   */
  @Bean
  public ConfigurationCustomizer typeHandlerOverrideCustomizer() {
    return new ConfigurationCustomizer() {
      @Override
      public void customize(org.apache.ibatis.session.Configuration configuration) {
        TimestamptzLocalDateTimeTypeHandler handler = new TimestamptzLocalDateTimeTypeHandler();
        // 1. 覆盖 fallback（LocalDateTime + 任意 JDBC 类型）
        configuration.getTypeHandlerRegistry().register(LocalDateTime.class, null, handler);
        // 2. 显式覆盖 TIMESTAMP JDBC 类型
        configuration
            .getTypeHandlerRegistry()
            .register(LocalDateTime.class, JdbcType.TIMESTAMP, handler);
        // 3. 显式覆盖 TIMESTAMP_WITH_TIMEZONE JDBC 类型（PostgreSQL TIMESTAMPTZ）
        configuration
            .getTypeHandlerRegistry()
            .register(LocalDateTime.class, JdbcType.TIMESTAMP_WITH_TIMEZONE, handler);
        logger.info(
            "[TypeHandler] 已覆盖默认 LocalDateTimeTypeHandler → TimestamptzLocalDateTimeTypeHandler（适配 TIMESTAMPTZ）");
      }
    };
  }
}
