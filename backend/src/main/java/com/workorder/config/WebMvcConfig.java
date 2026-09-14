package com.workorder.config;



import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.workorder.interceptor.SqlInjectionInterceptor;

/**
 * Web MVC 配置
 *
 * <p>阶段 3 修复 §3 — 注册 SqlInjectionInterceptor
 *
 * @author KLord
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  @Autowired private SqlInjectionInterceptor sqlInjectionInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(sqlInjectionInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(
            "/static/**",
            "/webjars/**",
            "/actuator/**",
            "/swagger*/**",
            "/api-docs/**",
            "/v3/api-docs/**");
  }
}
