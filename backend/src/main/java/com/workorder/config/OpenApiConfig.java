package com.workorder.config;


import java.util.Collections;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置（规范 §1 API 设计规范 — 自动化检验）
 *
 * <p>自动生成 OpenAPI 3.0 规范文档，CI 集成交互校验：
 *
 * <ul>
 *   <li>URL 风格：复数名词 + 连字符
 *   <li>请求方法：RESTful 规范
 *   <li>响应体结构：统一 Result 包装
 *   <li>状态码使用：是否合规
 * </ul>
 *
 * <p>访问路径：
 *
 * <ul>
 *   <li>Swagger UI: /api/v1/swagger-ui.html
 *   <li>API 文档 JSON: /api/v1/v3/api-docs
 * </ul>
 *
 * @author KLord
 */
@Configuration
public class OpenApiConfig {

  @Value("${server.servlet.context-path:/api/v1}")
  private String contextPath;

  @Bean
  public OpenAPI workOrderOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("工单审批流转系统 API")
                .description("企业级工单审批流转系统 RESTful API 文档，基于 Spring Boot 2.7 + Flowable 6.8 + Vue3")
                .version("v1")
                .contact(new Contact().name("KLord").email("dev@workorder-system.com"))
                .license(new License().name("Proprietary").url("https://workorder-system.com")))
        .servers(Collections.singletonList(new Server().url(contextPath).description("当前环境")))
        .addSecurityItem(new SecurityRequirement().addList("Bearer JWT"))
        .schemaRequirement(
            "Bearer JWT",
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("在 Authorization 头中携带 JWT 令牌"));
  }
}
