package com.workorder.config;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import com.workorder.filter.TraceIdFilter;
import com.workorder.security.JwtAuthenticationFilter;

/**
 * Enterprise Security Configuration 企业级安全配置 - 集中管理所有安全相关组件
 *
 * <p>严格遵循 improve.md §1 第 5 条「安全过滤器链前置抢占」： @Order(Ordered.HIGHEST_PRECEDENCE) 强制安全配置优先加载，确保 XSS
 * 清洗、限流令牌桶、 JWT 鉴权在请求进入业务 DispatcherServlet / Controller 前生效。
 *
 * @author KLord
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityConfig {

  @Autowired private UserDetailsService userDetailsService;

  @Autowired private EnterpriseSecurityFilter enterpriseSecurityFilter;

  @Autowired private JwtAuthenticationFilter jwtAuthenticationFilter;

  @Autowired private TraceIdFilter traceIdFilter;

  /** Password Encoder with enterprise strength (BCrypt, 12 rounds) */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /**
   * Authentication Manager Bean (required by AuthServiceImpl) 认证管理器Bean（AuthServiceImpl需要注入）
   *
   * <p>关键修复（登录失败 Bug 根因）： 原实现使用 authConfig.getAuthenticationManager() 获取全局 AuthenticationManager，
   * 该方法返回的 AuthenticationManager 可能由 Spring Security 自动构建，使用默认的 DelegatingPasswordEncoder（要求哈希字符串带
   * {bcrypt} 前缀），而非我们配置的 BCryptPasswordEncoder，导致无前缀的 BCrypt 哈希验证失败（"Bad credentials"）。
   *
   * <p>同时，HttpSecurity.authenticationProvider() 只配置 SecurityFilterChain 的本地
   * AuthenticationManager（用于表单登录/HTTP Basic），不影响注入到 AuthServiceImpl 的全局 AuthenticationManager bean。
   *
   * <p>修复方案：显式用 ProviderManager 构建全局 AuthenticationManager，确保使用我们 配置的 DaoAuthenticationProvider +
   * BCryptPasswordEncoder，消除任何歧义。
   */
  @Bean
  public AuthenticationManager authenticationManager() {
    return new ProviderManager(authenticationProvider());
  }

  /**
   * DAO Authentication Provider DAO认证提供者（使用UserDetailsService + PasswordEncoder）
   *
   * <p>显式配置 BCryptPasswordEncoder（无 {bcrypt} 前缀要求），匹配数据库中存储的 标准 BCrypt 哈希（$2a$10$... 格式）。
   */
  @Bean
  public DaoAuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
    authProvider.setUserDetailsService(userDetailsService);
    authProvider.setPasswordEncoder(passwordEncoder());
    // 不隐藏 UsernameNotFoundException（便于排查用户不存在的情况）
    authProvider.setHideUserNotFoundExceptions(false);
    return authProvider;
  }

  /**
   * CSRF Token Repository（OPTIMIZATION 三.3.2 Spring Security 内置 CSRF）
   *
   * <p>使用 CookieCsrfTokenRepository 将 CSRF Token 写入 Cookie（XSRF-TOKEN）： - HttpOnly=false：允许前端 JS
   * 读取，由 Axios 自动注入 X-XSRF-TOKEN 请求头 - Secure：根据 profile 决定（dev=false 兼容 HTTP localhost，prod=true 仅
   * HTTPS） - SameSite=Lax：允许同站导航携带（登录跳转等）
   *
   * <p>前端配合：Axios xsfCookieName + xsrHeaderName 自动读取并注入 X-XSRF-TOKEN 头。 废弃前端 HMAC
   * 签名机制（密钥易通过开发工具泄露，形同虚设）。
   *
   * @return CookieCsrfTokenRepository 实例
   */
  @Bean
  public CsrfTokenRepository csrfTokenRepository() {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    // Cookie 名称（默认 XSRF-TOKEN，Axios 默认读取此名称）
    repository.setCookieName("XSRF-TOKEN");
    // 请求头名称（默认 X-XSRF-TOKEN，Axios 默认使用此名称）
    repository.setHeaderName("X-XSRF-TOKEN");
    // Cookie 路径
    repository.setCookiePath("/");
    return repository;
  }

  /** Security Filter Chain Configuration 安全过滤器链配置 */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // 显式注册 DaoAuthenticationProvider，确保 AuthenticationManager 使用我们配置的
        // BCryptPasswordEncoder（而非 Spring Security 默认的 DelegatingPasswordEncoder，
        // 后者要求哈希字符串带 {bcrypt} 前缀，会导致无前缀的 BCrypt 哈希验证失败）
        .authenticationProvider(authenticationProvider())

        // 安全响应头（企业级防护）
        .headers(
            headers ->
                headers.addHeaderWriter(
                    (request, response) -> {
                      // 防点击劫持
                      response.setHeader("X-Frame-Options", "DENY");
                      // 禁止MIME嗅探
                      response.setHeader("X-Content-Type-Options", "nosniff");
                      // HSTS强制HTTPS（生产环境生效）
                      response.setHeader(
                          "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                      // 引用策略
                      response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                      // 权限策略（禁止摄像头/麦克风/定位）
                      response.setHeader(
                          "Permissions-Policy", "camera=(), microphone=(), geolocation=()");
                      // XSS过滤（兜底）
                      response.setHeader("X-XSS-Protection", "1; mode=block");
                    }))

        // ============================================================
        // CSRF 防护（OPTIMIZATION 三.3.2 Spring Security 内置 CSRF）
        // 废弃前端 HMAC 签名，改用 Spring Security CookieCsrfTokenRepository：
        // 1. 登录成功后 GET /auth/current-user 触发 CSRF Cookie 写入
        // 2. 前端 Axios 自动读取 XSRF-TOKEN Cookie，注入 X-XSRF-TOKEN 头
        // 3. Spring Security CsrfFilter 校验 POST/PUT/DELETE/PATCH 请求的 CSRF Token
        //
        // 豁免路径：登录/注册/密码重置（无会话场景，无需 CSRF 保护）
        // ============================================================
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokenRepository())
                    .ignoringAntMatchers(
                        "/auth/sessions",
                        "/auth/users",
                        "/password-resets/validate",
                        "/password-resets/confirm",
                        "/client-logs/report"))

        // Session management: stateless (JWT-based)
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()

        // Authorization rules
        .authorizeRequests(
            authz ->
                authz
                    // Public endpoints (no authentication required) — 最小化白名单
                    // 仅 /auth/sessions (POST login) 允许匿名访问；/auth/users 改为仅超管可调（见 AuthController
                    // @PreAuthorize）
                    // /emergency/** 已从 permitAll 移除 — 紧急维护端点现在需要 SUPER_ADMIN 角色 + @Profile("dev")
                    .antMatchers("/auth/sessions")
                    .permitAll()

                    // 密码重置端点（用户通过重置链接访问，token 本身作为凭证）
                    .antMatchers("/password-resets/validate", "/password-resets/confirm")
                    .permitAll()

                    // 前端错误上报端点（允许未认证上报，错误可能在登录前发生）
                    .antMatchers("/client-logs/report")
                    .permitAll()

                    // Actuator 监控端点 — 仅 SUPER_ADMIN 可访问（生产环境再叠加 Nginx IP 白名单）
                    .antMatchers("/actuator/**")
                    .hasRole("SUPER_ADMIN")

                    // Static resources
                    .antMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico")
                    .permitAll()

                    // SpringDoc OpenAPI 文档端点（规范 §1 — API 文档自动生成）
                    .antMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()

                    // All other endpoints require authentication
                    .anyRequest()
                    .authenticated())

        // Add custom enterprise security filter BEFORE UsernamePasswordAuthenticationFilter
        .addFilterBefore(enterpriseSecurityFilter, UsernamePasswordAuthenticationFilter.class)

        // 全链路追踪过滤器 — 在 CsrfFilter 之前执行，确保 TraceId 覆盖所有后续过滤器日志
        .addFilterBefore(traceIdFilter, org.springframework.security.web.csrf.CsrfFilter.class)

        // 添加JWT认证过滤器（用于验证Token并设置用户认证信息）
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
