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

/**
 * Enterprise Web Security Filter 企业级Web安全过滤器 — 防护常见Web漏洞
 *
 * <p>阶段 3 修复 §3 — 输入与输出防护
 *
 * <p>Security Features: 1. 严格 CSP（script-src 'self'，移除 unsafe-inline） 2.
 * 安全响应头（HSTS、X-Frame-Options、X-Content-Type-Options、Referrer-Policy） 3. CSRF Protection（JWT Bearer
 * 校验） 4. Caffeine 本地限流（登录 5/min/IP、审批 30/min/用户、其他 100/min/用户） 5. AntPathMatcher 路径匹配（避免子串匹配绕过）
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

  /** XSS 攻击模式（仅检测真实 XSS，不误拦合法 HTML） */
  private static final Pattern[] XSS_PATTERNS = {
    Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
    Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
    Pattern.compile(
        "on(load|error|click|mouseover|focus|blur|submit)\\s*=", Pattern.CASE_INSENSITIVE),
    Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
    Pattern.compile("<object[^>]*>", Pattern.CASE_INSENSITIVE),
    Pattern.compile("<embed[^>]*>", Pattern.CASE_INSENSITIVE),
    Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
    Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE)
  };

  /** AntPathMatcher 路径匹配器 */
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  /** 排除安全检查的路径模式（AntPathMatcher 精确匹配） */
  private static final String[] EXCLUDED_PATTERNS = {
      "/api/auth/sessions",
      "/api/auth/users",
      "/api/password-resets/validate",
      "/api/password-resets/confirm",
      "/actuator/**",
      "/swagger*/**",
      "/api-docs/**",
      "/v3/api-docs/**",
      "/webjars/**"
  };

  /** 限流路径模式 */
  private static final String LOGIN_PATTERN = "/api/auth/sessions";

  private static final String APPROVAL_PATTERN = "/api/approvals/**";

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

    String requestUri = httpRequest.getRequestURI();
    String method = httpRequest.getMethod();

    // 2. 排除路径直接放行
    if (isExcludedPath(requestUri)) {
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

    // 浏览器内置 XSS 保护
    response.setHeader("X-XSS-Protection", "1; mode=block");

    // Referrer 控制
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

    // 严格 CSP：script-src 仅 'self'（移除 unsafe-inline）
    // style-src 保留 'unsafe-inline'（Element Plus 需要内联样式）
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; "
            + "connect-src 'self'; "
            + "font-src 'self'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'");

    // HSTS：强制 HTTPS 1 年
    response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

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
    String uri = request.getRequestURI();
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

  /** 获取客户端真实 IP */
  private String getClientIp(HttpServletRequest request) {
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
      ip = request.getRemoteAddr();
    }
    if (ip != null && ip.contains(",")) {
      ip = ip.split(",")[0].trim();
    }
    return ip;
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
