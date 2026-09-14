package com.workorder.filter;


import java.io.IOException;
import java.security.SecureRandom;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.workorder.security.CustomUserDetails;

/**
 * 全链路追踪过滤器 — 规范条款 4（结构化全链路）
 *
 * <p>职责：
 *
 * <ol>
 *   <li>为每个请求生成全局唯一 TraceId + SpanId，注入 SLF4J MDC
 *   <li>从请求头读取前端传入的 TraceId（跨服务链路透传）
 *   <li>补充业务上下文（userId、clientIp、path）到 MDC
 *   <li>响应头回传 TraceId / SpanId，供前端关联
 *   <li>请求结束清理 MDC，防止线程池复用导致 TraceId 串链
 * </ol>
 *
 * <p>TraceId 格式：{@code WOS-<8位hex>-<8位hex>}（便于日志搜索，避免与其他系统冲突）
 *
 * <p>SpanId 格式：{@code <16位hex>}（每次请求唯一）
 *
 * <p>执行顺序：{@code @Order(Ordered.HIGHEST_PRECEDENCE + 2)} — 在 XssFilter（HIGHEST_PRECEDENCE +
 * 1）之后、EnterpriseSecurityFilter（1）之后， 确保请求体已被净化，且在所有业务过滤器之前注入 TraceId。
 *
 * @author KLord
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter implements Filter {

  private static final Logger logger = LoggerFactory.getLogger(TraceIdFilter.class);

  /** MDC 键名常量 */
  public static final String MDC_TRACE_ID = "traceId";

  public static final String MDC_SPAN_ID = "spanId";
  public static final String MDC_USER_ID = "userId";
  public static final String MDC_CLIENT_IP = "clientIp";
  public static final String MDC_PATH = "path";
  public static final String MDC_REQUEST_START_TIME = "requestStartTime";

  /** HTTP 头名常量 */
  public static final String HEADER_TRACE_ID = "X-Trace-Id";

  public static final String HEADER_REQUEST_ID = "X-Request-Id";

  /** TraceId 前缀 */
  private static final String TRACE_ID_PREFIX = "WOS-";

  /** 安全随机数生成器 */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  @Override
  public void init(FilterConfig filterConfig) {
    logger.info("✅ TraceIdFilter 初始化完成（全链路追踪过滤器已注册）");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    try {
      // 1. 从请求头获取前端传入的 TraceId（跨服务链路透传）
      String traceId = httpRequest.getHeader(HEADER_TRACE_ID);
      if (traceId == null || traceId.isEmpty()) {
        // 首次请求：生成 TraceId
        traceId = generateTraceId();
      }

      // 2. 生成 SpanId（每次请求唯一）
      String spanId = generateSpanId();

      // 3. 注入 MDC（后续所有日志自动携带 traceId/spanId）
      MDC.put(MDC_TRACE_ID, traceId);
      MDC.put(MDC_SPAN_ID, spanId);

      // 4. 补充业务上下文到 MDC
      populateBusinessContext(httpRequest);

      // 5. 响应头回传 TraceId 和 SpanId（前端可提取用于问题排查）
      httpResponse.setHeader(HEADER_TRACE_ID, traceId);
      httpResponse.setHeader(HEADER_REQUEST_ID, spanId);

      // 6. 记录请求开始时间（用于后续计算 duration）
      MDC.put(MDC_REQUEST_START_TIME, String.valueOf(System.currentTimeMillis()));

      chain.doFilter(request, response);
    } finally {
      // 7. 请求结束清理 MDC（防止 Tomcat 线程池复用导致 TraceId 串链）
      MDC.clear();
    }
  }

  @Override
  public void destroy() {
    logger.info("TraceIdFilter 销毁");
  }

  /**
   * 填充业务上下文到 MDC
   *
   * <p>- userId：从 SecurityContext 获取（已认证时） - clientIp：从请求头获取真实 IP（支持代理） - path：请求 URI
   */
  private void populateBusinessContext(HttpServletRequest request) {
    // 从 SecurityContext 获取 userId（已认证时）
    try {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getPrincipal() instanceof CustomUserDetails) {
        CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
        MDC.put(MDC_USER_ID, String.valueOf(userDetails.getUserId()));
      }
    } catch (Exception e) {
      // SecurityContext 未设置（如登录请求），忽略
    }

    // 客户端 IP（支持 X-Forwarded-For / X-Real-IP 代理头）
    String clientIp = getClientIp(request);
    MDC.put(MDC_CLIENT_IP, clientIp);

    // 请求路径
    MDC.put(MDC_PATH, request.getRequestURI());
  }

  /**
   * 获取客户端真实 IP（支持多级代理）
   *
   * <p>优先级：X-Forwarded-For 第一个 > X-Real-IP > remoteAddr
   */
  private String getClientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      // X-Forwarded-For 可能包含多个 IP，取第一个
      int commaIndex = ip.indexOf(',');
      return commaIndex > 0 ? ip.substring(0, commaIndex).trim() : ip.trim();
    }
    ip = request.getHeader("X-Real-IP");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      return ip.trim();
    }
    return request.getRemoteAddr();
  }

  /** 生成 TraceId：格式 WOS-xxxxxxxx-xxxxxxxx */
  private String generateTraceId() {
    byte[] part1 = new byte[4];
    byte[] part2 = new byte[4];
    SECURE_RANDOM.nextBytes(part1);
    SECURE_RANDOM.nextBytes(part2);
    return TRACE_ID_PREFIX + Hex.encodeHexString(part1) + "-" + Hex.encodeHexString(part2);
  }

  /** 生成 SpanId：16 位 hex */
  private String generateSpanId() {
    byte[] bytes = new byte[8];
    SECURE_RANDOM.nextBytes(bytes);
    return Hex.encodeHexString(bytes);
  }
}
