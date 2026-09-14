package com.workorder.filter;


import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * API 兼容性过滤器 — 旧 URL 到新 URL 的 301 重定向
 *
 * <p>职责：在过渡期内，将旧版 API URL 通过 301 永久重定向到新版 URL， 同时添加 Deprecation 和 Sunset 响应头，通知客户端旧接口即将废弃。
 *
 * <p>URL 映射规则：
 *
 * <ul>
 *   <li>/workorder/** → /work-orders/**
 *   <li>/employee/** → /employees/**
 *   <li>/approval/** → /approvals/**
 *   <li>/process/** → /processes/**
 *   <li>/client-log/** → /client-logs/**
 *   <li>/password-reset/** → /password-resets/**
 *   <li>/file/** → /files/**
 *   <li>/emergency/** → /emergencies/**
 *   <li>/auth/login → /auth/sessions
 *   <li>/auth/logout → /auth/sessions
 *   <li>/auth/refresh-token → /auth/sessions/refresh
 *   <li>/auth/change-password → /auth/password
 *   <li>/auth/current-user → /auth/me
 * </ul>
 *
 * <p>仅处理 GET 请求的重定向；POST/PUT/DELETE 等写操作直接放行（避免丢失请求体）。
 *
 * <p>执行顺序：{@code @Order(Ordered.HIGHEST_PRECEDENCE + 10)}， 在 TraceIdFilter（HIGHEST_PRECEDENCE +
 * 2）之后执行。
 *
 * <p>Profile：{@code @Profile("!prod")} — 生产环境不启用，过渡期结束后可移除此过滤器。
 *
 * @author KLord
 */
@Component
@Profile("!prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiCompatFilter implements Filter {

  private static final Logger logger = LoggerFactory.getLogger(ApiCompatFilter.class);

  /** 旧前缀 → 新前缀映射（按前缀长度降序排列，优先匹配更长的前缀） */
  private static final Map<String, String> PREFIX_MAPPINGS = new LinkedHashMap<>();

  /** Auth 子路径精确映射 */
  private static final Map<String, String> AUTH_PATH_MAPPINGS = new LinkedHashMap<>();

  /** Deprecation 响应头 */
  private static final String HEADER_DEPRECATION = "Deprecation";

  /** Sunset 响应头 */
  private static final String HEADER_SUNSET = "Sunset";

  /** Link 响应头 */
  private static final String HEADER_LINK = "Link";

  /** Sunset 日期：过渡期截止日（ISO 8601 格式） */
  private static final String SUNSET_DATE = "Sat, 01 Mar 2027 00:00:00 GMT";

  static {
    // 旧前缀 → 新前缀（按长度降序排列，确保更具体的前缀优先匹配）
    PREFIX_MAPPINGS.put("/password-reset", "/password-resets");
    PREFIX_MAPPINGS.put("/client-log", "/client-logs");
    PREFIX_MAPPINGS.put("/workorder", "/work-orders");
    PREFIX_MAPPINGS.put("/employee", "/employees");
    PREFIX_MAPPINGS.put("/approval", "/approvals");
    PREFIX_MAPPINGS.put("/process", "/processes");
    PREFIX_MAPPINGS.put("/emergency", "/emergencies");
    PREFIX_MAPPINGS.put("/file", "/files");

    // Auth 子路径精确映射
    AUTH_PATH_MAPPINGS.put("/auth/login", "/auth/sessions");
    AUTH_PATH_MAPPINGS.put("/auth/logout", "/auth/sessions");
    AUTH_PATH_MAPPINGS.put("/auth/refresh-token", "/auth/sessions/refresh");
    AUTH_PATH_MAPPINGS.put("/auth/change-password", "/auth/password");
    AUTH_PATH_MAPPINGS.put("/auth/current-user", "/auth/me");
  }

  @Override
  public void init(FilterConfig filterConfig) {
    logger.info("ApiCompatFilter 初始化完成（旧 API 兼容重定向过滤器已注册，非生产环境生效）");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    String requestURI = httpRequest.getRequestURI();
    String contextPath = httpRequest.getContextPath();

    // 去除 context path 得到纯路径
    String path = requestURI;
    if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }

    // 1. 优先检查 auth 子路径精确匹配
    String newAuthPath = AUTH_PATH_MAPPINGS.get(path);
    if (newAuthPath != null) {
      handleRedirect(httpRequest, httpResponse, contextPath, path, newAuthPath, true);
      return;
    }

    // 2. 检查前缀匹配
    for (Map.Entry<String, String> entry : PREFIX_MAPPINGS.entrySet()) {
      String oldPrefix = entry.getKey();
      String newPrefix = entry.getValue();

      if (path.equals(oldPrefix) || path.startsWith(oldPrefix + "/")) {
        String suffix = path.substring(oldPrefix.length());
        String newPath = newPrefix + suffix;
        handleRedirect(httpRequest, httpResponse, contextPath, path, newPath, false);
        return;
      }
    }

    // 3. 无匹配，放行
    chain.doFilter(request, response);
  }

  /**
   * 执行重定向（仅 GET 请求）或添加废弃头后放行（非 GET 请求）
   *
   * @param httpRequest 原始请求
   * @param httpResponse 原始响应
   * @param contextPath 应用上下文路径
   * @param oldPath 旧路径
   * @param newPath 新路径
   * @param isExactMatch 是否为精确匹配（auth 子路径）
   */
  private void handleRedirect(
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse,
      String contextPath,
      String oldPath,
      String newPath,
      boolean isExactMatch)
      throws IOException, ServletException {
    // 添加废弃标记头
    httpResponse.setHeader(HEADER_DEPRECATION, "true");
    httpResponse.setHeader(HEADER_SUNSET, SUNSET_DATE);

    String method = httpRequest.getMethod();
    if ("GET".equalsIgnoreCase(method)) {
      // GET 请求：执行 301 永久重定向
      String redirectUrl = (contextPath != null ? contextPath : "") + newPath;
      // 保留查询参数
      String queryString = httpRequest.getQueryString();
      if (queryString != null && !queryString.isEmpty()) {
        redirectUrl += "?" + queryString;
      }

      // 添加 Link 头指向新位置（RFC 8529 规范）
      String newFullUrl =
          httpRequest.getScheme()
              + "://"
              + httpRequest.getServerName()
              + ":"
              + httpRequest.getServerPort()
              + redirectUrl;
      httpResponse.setHeader(HEADER_LINK, "<" + newFullUrl + ">; rel=\"successor-version\"");

      logger.info("[API兼容重定向] {} {} → {} (301)", method, oldPath, newPath);
      httpResponse.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
      httpResponse.setHeader("Location", redirectUrl);
    } else {
      // 非 GET 请求：仅添加废弃头，放行到原始路径（避免丢失请求体）
      logger.warn("[API兼容警告] 非 GET 请求访问旧路径: {} {} → 新路径应为: {}", method, oldPath, newPath);
      // 非 GET 请求不做重定向，返回 404 让调用方更新 URL
      // 因为 POST/PUT/DELETE 的旧 URL 已不再映射到 Controller
      httpResponse.setStatus(HttpServletResponse.SC_GONE);
      httpResponse.setContentType("application/json;charset=UTF-8");
      httpResponse
          .getWriter()
          .write("{\"bizCode\":\"01-000-005\",\"msg\":\"API路径已废弃，请使用新路径: " + newPath + "\"}");
    }
  }

  @Override
  public void destroy() {
    logger.info("ApiCompatFilter 销毁");
  }
}
