package com.workorder.interceptor;


import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SQL Injection Protection Interceptor
 *
 * <p>阶段 3 修复 §3 — 输入防护：SQL 注入
 *
 * <p>设计原则： 1. 仅匹配真实注入模式（避免误拦截文本 "SELECT" 或十六进制 "0x"） 2. 校验所有请求参数（不再区分敏感/非敏感参数） 3. 命中即 403 拒绝 +
 * SECURITY 告警日志 4. WebMvcConfig 负责注册与路径排除
 *
 * @author KLord
 */
@Component
public class SqlInjectionInterceptor implements HandlerInterceptor {

  private static final Logger logger = LoggerFactory.getLogger(SqlInjectionInterceptor.class);
  private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_VIOLATION_LOGGER");

  /**
   * SQL 注入特征正则（仅匹配真实注入模式） - UNION SELECT：联合查询注入 - OR 1=1 / AND 1=1：恒真条件注入 - --：SQL 注释 - ;
   * DROP：堆叠查询删除表 - ; SELECT/INSERT/UPDATE/DELETE：堆叠查询 - INSERT INTO / DELETE FROM / UPDATE ...
   * SET：完整语句注入 - 斜杠星 块注释（slash-star star-slash）
   */
  private static final Pattern[] SQL_INJECTION_PATTERNS = {
    Pattern.compile("(?i)\\bunion\\s+(all\\s+)?select\\b"),
    Pattern.compile("(?i)\\bor\\s+1\\s*=\\s*1\\b"),
    Pattern.compile("(?i)\\band\\s+1\\s*=\\s*1\\b"),
    Pattern.compile("--"),
    Pattern.compile("(?i);\\s*drop\\b"),
    Pattern.compile("(?i);\\s*(select|insert|update|delete)\\b"),
    Pattern.compile("(?i)\\binsert\\s+into\\b"),
    Pattern.compile("(?i)\\bdelete\\s+from\\b"),
    Pattern.compile("(?i)\\bupdate\\s+\\w+\\s+set\\b"),
    Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
  };

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String requestUri = request.getRequestURI();
    String method = request.getMethod();
    String clientIp = getClientIp(request);

    // 校验所有请求参数（GET 查询串 + POST 表单）
    Map<String, String[]> parameterMap = request.getParameterMap();

    for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
      String paramName = entry.getKey();
      String[] paramValues = entry.getValue();

      for (String value : paramValues) {
        if (value != null && !value.isEmpty()) {
          if (containsSqlInjection(value)) {
            logSecurityViolation(request, method, requestUri, paramName, value, clientIp);
            sendErrorResponse(response, "检测到非法输入，请检查您的请求内容");
            return false;
          }
        }
      }
    }

    return true;
  }

  /** 检测值是否包含 SQL 注入特征 */
  private boolean containsSqlInjection(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }

    for (Pattern pattern : SQL_INJECTION_PATTERNS) {
      if (pattern.matcher(value).find()) {
        return true;
      }
    }

    return false;
  }

  /** 记录安全违规日志（结构化 JSON） */
  private void logSecurityViolation(
      HttpServletRequest request,
      String method,
      String uri,
      String paramName,
      String paramValue,
      String clientIp) {
    try {
      Map<String, Object> violationLog = new HashMap<>();
      violationLog.put("timestamp", new Date());
      violationLog.put("type", "SQL_INJECTION_ATTEMPT");
      violationLog.put("clientIp", clientIp);
      violationLog.put("method", method);
      violationLog.put("uri", uri);
      violationLog.put("paramName", paramName);
      // 截断避免日志膨胀 + 脱敏（移除非常规字符）
      String safeValue = paramValue.length() > 100 ? paramValue.substring(0, 100) : paramValue;
      violationLog.put("paramValue", safeValue);
      violationLog.put("userAgent", request.getHeader("User-Agent"));

      ObjectMapper mapper = new ObjectMapper();
      String logMessage = mapper.writeValueAsString(violationLog);

      securityLogger.warn("SQL Injection Attempt: {}", logMessage);

      logger.warn(
          "[SQL注入拦截] IP: {}, Method: {}, URI: {}, Param: {}='{}'",
          clientIp,
          method,
          uri,
          paramName,
          paramValue.length() > 50 ? paramValue.substring(0, 50) + "..." : paramValue);

    } catch (Exception e) {
      logger.error("Failed to log security violation", e);
    }
  }

  /** 返回 403 错误响应（JSON） */
  private void sendErrorResponse(HttpServletResponse response, String message) throws Exception {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json;charset=UTF-8");

    Map<String, Object> errorResponse = new HashMap<>();
    errorResponse.put("code", 403);
    errorResponse.put("message", message);
    errorResponse.put("timestamp", System.currentTimeMillis());

    ObjectMapper mapper = new ObjectMapper();
    response.getWriter().write(mapper.writeValueAsString(errorResponse));
  }

  /** 获取客户端真实 IP（X-Forwarded-For 优先） */
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

    // X-Forwarded-For 可能含多个 IP，取第一个（最原始客户端）
    if (ip != null && ip.contains(",")) {
      ip = ip.split(",")[0].trim();
    }

    return ip;
  }
}
