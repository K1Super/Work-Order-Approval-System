package com.workorder.controller;


import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.common.result.Result;
import com.workorder.config.MetricsConfig;

/**
 * 前端错误上报接口 — 规范条款 5（可采集可告警）+ 规范条款 13（前端错误上报后端接口）
 *
 * <p>接收前端统一日志工具（logger.js）上报的 WARN/ERROR 级别日志， 输出到 CLIENT_ERROR_LOGGER（路由到 security-violations.log
 * + data-access-audit.log）， 同时记录 Prometheus 前端错误指标。
 *
 * <p>不要求认证（错误可能在未登录时发生），已在 SecurityConfig 中加入 permitAll。
 *
 * @author KLord
 */
@RestController
@RequestMapping("/client-logs")
public class ClientLogController {

  private static final Logger logger = LoggerFactory.getLogger(ClientLogController.class);
  private static final Logger clientErrorLogger = LoggerFactory.getLogger("CLIENT_ERROR_LOGGER");

  @Autowired private MetricsConfig metricsConfig;

  /**
   * 前端错误上报接口
   *
   * <p>接收前端 logger.js 的批量上报（{ logs: [...] }）或单条上报， 统一通过 CLIENT_ERROR_LOGGER 输出到审计日志文件。
   *
   * @param payload 日志数据（支持批量 { logs: [...] } 和单条格式）
   * @param request HTTP 请求（获取客户端 IP）
   */
  @PostMapping("/report")
  public Result<?> reportClientLog(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    try {
      String clientIp = getClientIp(request);
      String userAgent = request.getHeader("User-Agent");

      // 支持批量上报（{ logs: [...] }）和单条上报
      Object logsObj = payload.get("logs");
      if (logsObj instanceof Iterable) {
        // 批量上报
        for (Object item : (Iterable<?>) logsObj) {
          if (item instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> logEntry = (Map<String, Object>) item;
            logClientError(logEntry, clientIp, userAgent);
          }
        }
      } else {
        // 单条上报（整个 payload 就是日志条目）
        logClientError(payload, clientIp, userAgent);
      }

      return Result.success(null);
    } catch (Exception e) {
      logger.warn("前端日志上报处理异常: {}", e.getMessage());
      // 上报接口不暴露后端错误，始终返回成功
      return Result.success(null);
    }
  }

  /** 记录单条前端错误日志 */
  private void logClientError(Map<String, Object> logEntry, String clientIp, String userAgent) {
    String level = (String) logEntry.getOrDefault("level", "ERROR");
    String action = (String) logEntry.getOrDefault("action", "UNKNOWN");
    String message = (String) logEntry.getOrDefault("message", "");
    String traceId = (String) logEntry.getOrDefault("traceId", "NO_TRACE");
    String url = (String) logEntry.getOrDefault("url", "");
    String stack = (String) logEntry.getOrDefault("stack", "");

    // 输出到 CLIENT_ERROR_LOGGER（路由到 security-violations.log + data-access-audit.log）
    clientErrorLogger.warn(
        "[CLIENT_ERROR] traceId={}, level={}, action={}, url={}, ip={}, msg={}, stack={}",
        traceId,
        level,
        action,
        url,
        clientIp,
        message,
        stack);

    // Prometheus 埋点：前端错误
    try {
      metricsConfig.recordClientError(level);
    } catch (Exception ignored) {
      // 指标记录失败不影响主流程
    }
  }

  /** 获取客户端真实 IP（支持多级代理） */
  private String getClientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      int commaIndex = ip.indexOf(',');
      return commaIndex > 0 ? ip.substring(0, commaIndex).trim() : ip.trim();
    }
    ip = request.getHeader("X-Real-IP");
    if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
      return ip.trim();
    }
    return request.getRemoteAddr();
  }
}
