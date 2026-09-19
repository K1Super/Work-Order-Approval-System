package com.workorder.security;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.workorder.config.MetricsConfig;

/**
 * JWT Authentication Filter Extract and validate Token from Header on each request
 *
 * <p>Core Features: 1. Extract Bearer Token from Authorization header in request 2. Validate Token
 * validity and expiration time 3. Load user permission info into Security context
 *
 * @author KLord
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  /**
   * SLF4J Logger（独立于父类 GenericFilterBean 的 JCL logger）
   *
   * <p>父类 OncePerRequestFilter 继承自 GenericFilterBean，其 logger 字段为
   * org.apache.commons.logging.Log（JCL），不支持 SLF4J 的 {} 占位符语法。 本类需使用 SLF4J {} 占位符输出结构化日志，故独立声明 SLF4J
   * Logger。
   */
  private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

  /** 应用 context-path（与 server.servlet.context-path=/api/v1 保持一致，用于路径匹配兜底） */
  private static final String CONTEXT_PATH = "/api/v1";

  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  private final JwtUtil jwtUtil;
  private final CustomUserDetailsService userDetailsService;
  private final MetricsConfig metricsConfig;
  private final TokenVersionCache tokenVersionCache;
  private final AuthCookieUtil authCookieUtil;

  /** Constructor injection (recommended) */
  @Autowired
  public JwtAuthenticationFilter(
      JwtUtil jwtUtil,
      CustomUserDetailsService userDetailsService,
      MetricsConfig metricsConfig,
      TokenVersionCache tokenVersionCache,
      AuthCookieUtil authCookieUtil) {
    this.jwtUtil = jwtUtil;
    this.userDetailsService = userDetailsService;
    this.metricsConfig = metricsConfig;
    this.tokenVersionCache = tokenVersionCache;
    this.authCookieUtil = authCookieUtil;
  }

  /** Core filtering logic */
  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    try {
      // 1. Get token from request header
      String token = extractTokenFromRequest(request);

      // 2. If Token exists and is valid, set security context
      if (StringUtils.hasText(token)) {
        String username = null;

        try {
          // Validate Token validity
          username = jwtUtil.getUsernameFromToken(token);
          boolean isValid = jwtUtil.validateToken(token, username);

          if (isValid && StringUtils.hasText(username)) {
            // Load user details (from Redis cache or database)
            CustomUserDetails userDetails =
                (CustomUserDetails) userDetailsService.loadUserByUsername(username);

            // ============================================
            // OPTIMIZATION 三.3.1：tokenVersion 一致性校验
            // 从 JWT 荷载解析 tokenVersion，与数据库 sys_user.token_version 比对（经 Redis 60s 缓存）：
            // - 一致 → Token 有效，设置 SecurityContext
            // - 不一致 → 用户已改密/被禁用/已注销，Token 立即失效，拒绝请求
            // 兼容旧 Token（无 tokenVersion 声明）→ 视为版本 0，与 DB 默认值 0 对齐
            // ============================================
            Long tokenVersionInToken = jwtUtil.getTokenVersionFromToken(token);
            Long tokenVersionInDb = tokenVersionCache.getOrLoad(userDetails.getUserId());

            if (tokenVersionInDb == null) {
              // 用户已被删除（DB 软删除或硬删除），拒绝
              logger.warn("[TokenVersion] 用户 {} 的 token_version 数据库查询返回 null（用户已删除）", username);
              metricsConfig.recordAuthorizationDenial("invalid_token");
              filterChain.doFilter(request, response);
              return;
            }

            if (tokenVersionInToken == null || !tokenVersionInToken.equals(tokenVersionInDb)) {
              logger.warn(
                  "[TokenVersion] 版本不一致，Token 已失效: user={}, tokenVersionInToken={}, tokenVersionInDb={}",
                  username,
                  tokenVersionInToken,
                  tokenVersionInDb);
              metricsConfig.recordAuthorizationDenial("token_version_mismatch");
              // Token 已失效，不设置 SecurityContext，让后续授权链拒绝
              filterChain.doFilter(request, response);
              return;
            }

            // Create authentication object (no password needed, already passed JWT validation)
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

            // Set to Security context
            SecurityContextHolder.getContext().setAuthentication(authentication);

            logger.debug(
                "Successfully set user authentication: {} (tokenVersion={})",
                username,
                tokenVersionInDb);
          } else {
            // Token 存在但校验未通过（如 username 为空或 validateToken 返回 false）
            logger.warn("Token validation returned invalid: username={}", username);
            metricsConfig.recordAuthorizationDenial("invalid_token");
          }
        } catch (Exception e) {
          logger.warn("Token validation failed: {}", e.getMessage());
          // Prometheus 埋点：token 验证异常（过期/签名错误/格式错误等）
          metricsConfig.recordAuthorizationDenial("invalid_token");
          // Do not throw exception, continue filter chain (let unauthenticated requests pass
          // normally)
        }
      } else {
        // 请求受保护端点但未携带 Token（shouldNotFilter 已排除公开端点/静态资源）
        metricsConfig.recordAuthorizationDenial("no_token");
      }
    } catch (Exception e) {
      logger.error("JWT filter exception: {}", e.getMessage(), e);
    }

    // 3. Continue filter chain
    filterChain.doFilter(request, response);
  }

  /**
   * Extract Bearer Token from request header
   *
   * <p>安全策略（阶段 1 修复 A-06 + OPTIMIZATION 三.3.1 Cookie 迁移）： 1. 优先从 Authorization: Bearer xxx Header
   * 读取（兼容移动端 / 强制改密流程） 2. 回退至 HttpOnly Cookie（浏览器主流程，HttpOnly; Secure; SameSite=Strict） 已移除从 URL
   * 查询参数 ?token= 读取的逻辑 — URL 会被记入访问日志、浏览器历史、Referer 头，导致 Token 泄露。
   *
   * @param request HTTP request object
   * @return Extracted token string, returns null if not exists
   */
  private String extractTokenFromRequest(@NonNull HttpServletRequest request) {
    // 委托 AuthCookieUtil：优先 Authorization Header，回退 Cookie
    String token = authCookieUtil.extractTokenFromRequest(request);
    if (StringUtils.hasText(token)) {
      return token;
    }
    return null;
  }

  /**
   * Determine if should filter this request (optional optimization). 排除登录、公开与静态资源端点；其余端点（含
   * /auth/current-user、/auth/users 等）均需 JWT 校验。
   */
  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    String path = request.getRequestURI();

    // 同时匹配无前缀（MockMvc 场景）与 /api/v1 前缀（真实 context-path 场景）两种形式。
    // 注意：/auth/users 有 @PreAuthorize("hasRole('SUPER_ADMIN')")，必须保留在此过滤器链内，
    // 否则合法超管携带 Cookie 调用时无法建立 SecurityContext 会被拒绝。
    return matchesAny(
            path,
            "/auth/sessions",
            CONTEXT_PATH + "/auth/sessions",
            "/public/**",
            CONTEXT_PATH + "/public/**",
            "/static/**")
        || isStaticResource(path);
  }

  /** 使用 AntPathMatcher 匹配任一模式（支持 ** 通配符） */
  private boolean matchesAny(@NonNull String path, @NonNull String... patterns) {
    for (String pattern : patterns) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /** 静态资源后缀（不参与鉴权） */
  private boolean isStaticResource(@NonNull String path) {
    return path.endsWith(".html")
        || path.endsWith(".css")
        || path.endsWith(".js")
        || path.endsWith(".png")
        || path.endsWith(".ico");
  }
}
