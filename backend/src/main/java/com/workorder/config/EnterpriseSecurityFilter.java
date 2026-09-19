package com.workorder.config;


import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.workorder.common.result.Result;
import com.workorder.util.XssAttackPatterns;

/**
 * Enterprise Web Security Filter 企业级Web安全过滤器 — 防护常见Web漏洞
 *
 * <p>阶段 3 修复 §3 — 输入与输出防护
 *
 * <p>Security Features: 1. 严格 CSP（script-src 'self'，移除 unsafe-inline） 2.
 * 安全响应头（X-Frame-Options、X-Content-Type-Options、Referrer-Policy；HSTS 由 SecurityConfig 单一来源仅 prod 下发） 3.
 * CSRF Protection（JWT Bearer 校验） 4. Caffeine 本地限流（登录 5/min/IP、审批 30/min/用户、其他 100/min/用户；仅单实例有效，分布式由
 * Nginx limit_req 承担） 5. AntPathMatcher 路径匹配（避免子串匹配绕过）
 *
 * @author KLord
 */
@Component
@Order(1)
public class EnterpriseSecurityFilter implements Filter {

  private static final Logger logger = LoggerFactory.getLogger(EnterpriseSecurityFilter.class);
  private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_VIOLATION_LOGGER");

  /** Caffeine 缓存最大容量 */
  private static final int CACHE_MAXIMUM_SIZE = 100_000;

  /** HTTP 429 Too Many Requests 状态码 */
  private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;

  /**
   * XSS 攻击模式 — 统一取自 {@link XssAttackPatterns}（审计修复 P3-6：参数级与 JSON body 级检测共用同一口径）。
   */
  private static final Pattern[] XSS_PATTERNS = XssAttackPatterns.PATTERNS;

  /** AntPathMatcher 路径匹配器 */
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  /**
   * 排除安全检查的路径模式（AntPathMatcher 精确匹配）。
   *
   * <p>W-08：路径统一基于 getServletPath()（不含 context-path，不依赖硬编码 /api/v1 前缀），
   * 模式与实际 Controller 映射前缀保持一致（如 /auth/sessions、/auth/users、/password-resets/** 等）。
   */
  private static final String[] EXCLUDED_PATTERNS = {
      "/auth/sessions",
      "/auth/users",
      "/password-resets/validate",
      "/password-resets/confirm",
      "/actuator/**",
      "/swagger*/**",
      "/api-docs/**",
      "/v3/api-docs/**",
      "/webjars/**"
  };

  /** 限流路径模式（对应 AuthController POST /auth/sessions 登录端点） */
  private static final String LOGIN_PATTERN = "/auth/sessions";

  private static final String APPROVAL_PATTERN = "/approvals/**";

  /** 三级 Caffeine 令牌桶（key = IP 或 userId，value = 计数器） */
  private Cache<String, AtomicInteger> loginBucket;

  private Cache<String, AtomicInteger> approveBucket;
  private Cache<String, AtomicInteger> defaultBucket;

  @Value("${work-order-system.rate-limit.login-per-minute:5}")
  private int loginPerMinute;

  @Value("${work-order-system.rate-limit.approve-per-minute:30}")
  private int approvePerMinute;

  @Value("${work-order-system.rate-limit.default-per-minute:100}")
  private int defaultPerMinute;

  /** 反代白名单（逗号分隔 IP，如 127.0.0.1,10.0.0.8）— W-16：仅当 remoteAddr 命中白名单时才信任 XFF 等代理头 */
  @Value("${work-order-system.rate-limit.trusted-proxies:}")
  private String trustedProxies;

  /** Spring context-path（如 /api/v1）— W-08：用于 servletPath 为空时的路径兜底解析 */
  @Value("${server.servlet.context-path:}")
  private String contextPath;

  @Override
  public void init(FilterConfig filterConfig) {
    // 三级令牌桶：1 分钟滑动窗口，最大 10 万 entry
    loginBucket =
        Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(CACHE_MAXIMUM_SIZE).build();
    approveBucket =
        Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(CACHE_MAXIMUM_SIZE).build();
    defaultBucket =
        Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(CACHE_MAXIMUM_SIZE).build();

    logger.info(
        "Enterprise Security Filter initialized (login={}/min, approve={}/min, default={}/min)",
        loginPerMinute,
        approvePerMinute,
        defaultPerMinute);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    // 1. 设置安全响应头
    setSecurityHeaders(httpResponse);

    // W-08：路径统一基于 getServletPath()（不含 context-path），
    // 不依赖硬编码 /api/v1 前缀；servletPath 为空时从 URI 剥离 context-path 兜底
    String requestPath = resolvePath(httpRequest);
    String method = httpRequest.getMethod();

    // 2. 排除路径直接放行
    if (isExcludedPath(requestPath)) {
      chain.doFilter(request, response);
      return;
    }

    // 3. XSS 检测（参数级，深度防御与 XssFilter 互补）
    if (!checkXss(httpRequest, httpResponse)) {
      return;
    }

    // 4. CSRF 校验已由 Spring Security CsrfFilter 接管（CookieCsrfTokenRepository）
    // OPTIMIZATION 三.3.2：废弃 JWT Bearer Header 充当 CSRF Token 的伪校验，
    // 改用 Spring Security 内置 CSRF（XSRF-TOKEN Cookie + X-XSRF-TOKEN 头）
    // 此处不再做 CSRF 校验，避免与 Spring Security CsrfFilter 冲突

    // 5. 限流校验
    if (!checkRateLimit(httpRequest, httpResponse)) {
      return;
    }

    chain.doFilter(request, response);
  }

  /** 设置严格安全响应头 */
  private void setSecurityHeaders(HttpServletResponse response) {
    // 防点击劫持
    response.setHeader("X-Frame-Options", "DENY");

    // 防 MIME 嗅探
    response.setHeader("X-Content-Type-Options", "nosniff");

    // 审计修复 P1-2：HSTS 与 X-XSS-Protection 不由本 filter 下发 ——
    // HSTS 由 SecurityConfig 单一来源控制（仅 prod 下发）；X-XSS-Protection 已废弃，直接移除。
    // 本 filter 仅在 Spring Security 链内最先执行，若仍在此无条件设置会覆盖 SecurityConfig 的 prod 判断。

    // Referrer 控制
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

    // 严格 CSP：script-src 仅 'self'（移除 unsafe-inline）
    // style-src 保留 'unsafe-inline'（Element Plus 需要内联样式）
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; "
            + "connect-src 'self'; "
            + "font-src 'self'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'");

    // 防敏感数据缓存
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Expires", "0");

    // 权限策略：禁用摄像头、麦克风、地理位置等
    response.setHeader(
        "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
  }

  /** XSS 检测（参数级深度防御） */
  private boolean checkXss(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Map<String, String[]> params = request.getParameterMap();

    for (Map.Entry<String, String[]> entry : params.entrySet()) {
      for (String value : entry.getValue()) {
        if (value != null && !value.isEmpty()) {
          for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(value).find()) {
              securityLogger.warn(
                  "[XSS拦截] IP: {}, URI: {}, Param: {}, Value: {}",
                  getClientIp(request),
                  request.getRequestURI(),
                  entry.getKey(),
                  value.length() > 100 ? value.substring(0, 100) : value);

              sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "检测到非法脚本内容，请检查您的输入");
              return false;
            }
          }
        }
      }
    }

    return true;
  }

  /**
   * 三级 Caffeine 限流 - 登录 /api/auth/login：loginPerMinute/min/IP - 审批
   * /api/approval/**：approvePerMinute/min/用户 - 其他：defaultPerMinute/min/用户
   */
  private boolean checkRateLimit(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String uri = resolvePath(request);
    String clientIp = getClientIp(request);
    String userId = extractUserIdFromToken(request);
    // 优先用 userId 作为限流 key（已认证），否则用 IP
    String rateLimitKey = userId != null ? "u:" + userId : "ip:" + clientIp;

    Cache<String, AtomicInteger> bucket;
    int limit;

    if (pathMatcher.match(LOGIN_PATTERN, uri)) {
      // 登录接口：按 IP 限流（用户未认证，无法用 userId）
      bucket = loginBucket;
      rateLimitKey = "ip:" + clientIp;
      limit = loginPerMinute;
    } else if (pathMatcher.match(APPROVAL_PATTERN, uri)) {
      bucket = approveBucket;
      limit = approvePerMinute;
    } else {
      bucket = defaultBucket;
      limit = defaultPerMinute;
    }

    AtomicInteger count = bucket.get(rateLimitKey, k -> new AtomicInteger(0));
    int current = count.incrementAndGet();

    if (current > limit) {
      securityLogger.warn(
          "[限流拦截] key={}, URI={}, count={}, limit={}", rateLimitKey, uri, current, limit);

      response.setStatus(HTTP_STATUS_TOO_MANY_REQUESTS);
      response.setContentType("application/json;charset=UTF-8");
      response.setHeader("Retry-After", "60");
      try {
        ObjectMapper mapper = new ObjectMapper();
        Result<?> errorResult = Result.error(HTTP_STATUS_TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        response.getWriter().write(mapper.writeValueAsString(errorResult));
      } catch (Exception e) {
        // ignore
      }
      return false;
    }

    return true;
  }

  /** 从 JWT Token 提取 userId（用于限流 key） */
  private String extractUserIdFromToken(HttpServletRequest request) {
    try {
      String token = request.getHeader("Authorization");
      if (token == null || !token.startsWith("Bearer ")) {
        return null;
      }
      // 简单解析 JWT payload（不验证签名，仅用于限流 key 提取）
      String jwt = token.substring(7);
      String[] parts = jwt.split("\\.");
      if (parts.length < 2) return null;
      String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
      // 提取 "userId":12345 或 "sub":"12345"
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("\"(?:userId|sub|uid)\"\\s*:\\s*\"?(\\d+)")
              .matcher(payload);
      if (m.find()) return m.group(1);
    } catch (Exception e) {
      // 解析失败用 IP 限流
    }
    return null;
  }

  /**
   * 获取客户端真实 IP。
   *
   * <p>W-16：限流/锁定键一律以 request.getRemoteAddr() 为准（伪造 XFF 不能绕锁）。
   * 仅当 remoteAddr 命中配置的反代白名单（work-order-system.rate-limit.trusted-proxies）时，
   * 才回退解析 XFF/X-Real-IP 等代理头；否则直接返回 RemoteAddr。
   */
  private String getClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (!isTrustedProxy(remoteAddr)) {
      return (remoteAddr != null && !remoteAddr.isEmpty()) ? remoteAddr : "unknown";
    }
    String ip = request.getHeader("X-Forwarded-For");
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("Proxy-Client-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("WL-Proxy-Client-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("X-Real-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = remoteAddr;
    }
    if (ip != null && ip.contains(",")) {
      ip = ip.split(",")[0].trim();
    }
    return ip;
  }

  /** 判断远端地址是否为配置的反代白名单成员（精确匹配，支持 IPv4/IPv6 字面量） */
  private boolean isTrustedProxy(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isEmpty()) {
      return false;
    }
    if (trustedProxies == null || trustedProxies.trim().isEmpty()) {
      return false;
    }
    for (String proxy : trustedProxies.split(",")) {
      String candidate = proxy.trim();
      if (!candidate.isEmpty() && candidate.equals(remoteAddr)) {
        return true;
      }
    }
    return false;
  }

  /**
   * W-08：统一路径解析。优先取 getServletPath()（不含 context-path，随部署自动适配）；
   * 若容器未提供 servletPath，则从 requestURI 剥离注入的 server.servlet.context-path 兜底。
   */
  private String resolvePath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath != null && !servletPath.isEmpty()) {
      return servletPath;
    }
    String uri = request.getRequestURI();
    if (uri == null) {
      return "";
    }
    if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  /** AntPathMatcher 路径匹配（避免 uri.contains() 子串绕过） */
  private boolean isExcludedPath(String uri) {
    if (uri == null) return false;
    for (String pattern : EXCLUDED_PATTERNS) {
      if (pathMatcher.match(pattern, uri)) {
        return true;
      }
    }
    return false;
  }

  /** 发送 JSON 错误响应 */
  private void sendErrorResponse(HttpServletResponse response, int statusCode, String message)
      throws IOException {
    response.setStatus(statusCode);
    response.setContentType("application/json;charset=UTF-8");

    ObjectMapper mapper = new ObjectMapper();
    Result<?> errorResult = Result.error(statusCode, message);
    response.getWriter().write(mapper.writeValueAsString(errorResult));
  }

  @Override
  public void destroy() {
    logger.info("Enterprise Security Filter destroyed");
  }
}
