package com.workorder.it;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import com.workorder.config.EnterpriseSecurityFilter;

/**
 * 集成测试专用安全配置。
 *
 * <p>唯一必要兜底：{@link EnterpriseSecurityFilter} 的 Caffeine 限流桶在 {@code init(FilterConfig)}
 * 中初始化。该 Filter 同时是 Spring 容器内的 Bean，并被 {@code SecurityConfig} 以
 * {@code addFilterBefore} 显式加入过滤链——在真实 Servlet 容器中会通过 FilterRegistrationBean 触发
 * {@code init()}，而 MockMvc 不会对「手动加入安全链的 Filter 实例」调用 init，若跳过一次非豁免请求
 * 将命中 {@code bucket} 为 null 的 NPE（已在集成测试启动期实证）。故在测试源码中手动补一次
 * {@code init(null)}（方法体不使用 FilterConfig 参数，传 null 安全）。
 */
@Configuration
public class ItTestSecurityConfig {

  @Autowired private EnterpriseSecurityFilter enterpriseSecurityFilter;

  /** 手动初始化限流桶，避免 MockMvc 环境未触发 Filter.init 导致 doFilter 中 NPE */
  @PostConstruct
  void initEnterpriseSecurityFilter() {
    enterpriseSecurityFilter.init(null);
  }
}