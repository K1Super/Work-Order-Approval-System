package com.workorder.aspect;

import java.time.Duration;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.workorder.annotation.AntiReplay;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.result.Result;

/**
 * 防重放切面
 *
 * <p>阶段 3 修复 §3 — API 限流与防重放
 *
 * <p>校验逻辑： 1. X-Request-Nonce 头必须存在 → 否则 400 "缺少防重放标识" 2. X-Request-Timestamp 头必须存在且与服务器偏差 ≤
 * timeWindow 秒 → 否则 401 "请求已过期" 3. Redis SETNX request:nonce:{nonce} 1 EX {timeWindow} → 返回 null
 * 表示已存在 → 409 "重复请求"
 *
 * @author KLord
 */
@Aspect
@Component
public class AntiReplayAspect {

  private static final Logger logger = LoggerFactory.getLogger(AntiReplayAspect.class);
  private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_VIOLATION_LOGGER");

  private static final String NONCE_HEADER = "X-Request-Nonce";
  private static final String TIMESTAMP_HEADER = "X-Request-Timestamp";
  private static final String NONCE_KEY_PREFIX = "request:nonce:";

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  /** 校验请求防重放标识，通过后执行业务方法 */
  @Around("@annotation(antiReplay)")
  public Object checkAntiReplay(ProceedingJoinPoint joinPoint, AntiReplay antiReplay)
      throws Throwable {
    HttpServletRequest request = getCurrentRequest();
    if (request == null) {
      // 非请求上下文（如异步调用）放行
      return joinPoint.proceed();
    }

    int timeWindow = antiReplay.timeWindow();
    String clientIp = getClientIp(request);
    String uri = request.getRequestURI();

    // 1. 校验 Nonce 头存在
    String nonce = request.getHeader(NONCE_HEADER);
    if (nonce == null || nonce.trim().isEmpty()) {
      logger.warn("[防重放拦截] 缺少 Nonce 头 - IP: {}, URI: {}", clientIp, uri);
      throw new BusinessException(Result.PARAM_INVALID, "缺少防重放标识，请重试");
    }

    // 2. 校验 Timestamp 头存在且未过期
    String timestampStr = request.getHeader(TIMESTAMP_HEADER);
    if (timestampStr == null || timestampStr.trim().isEmpty()) {
      logger.warn("[防重放拦截] 缺少 Timestamp 头 - IP: {}, URI: {}, nonce: {}", clientIp, uri, nonce);
      throw new BusinessException(Result.PARAM_INVALID, "缺少请求时间戳，请重试");
    }

    long timestamp;
    try {
      timestamp = Long.parseLong(timestampStr.trim());
    } catch (NumberFormatException e) {
      logger.warn(
          "[防重放拦截] Timestamp 格式非法 - IP: {}, URI: {}, nonce: {}, ts: {}",
          clientIp,
          uri,
          nonce,
          timestampStr);
      throw new BusinessException(Result.PARAM_INVALID, "请求时间戳格式非法");
    }

    long now = Instant.now().getEpochSecond();
    long diff = Math.abs(now - timestamp);
    if (diff > timeWindow) {
      securityLogger.warn(
          "[防重放拦截] 请求已过期 - IP: {}, URI: {}, nonce: {}, diff: {}s", clientIp, uri, nonce, diff);
      throw new BusinessException(Result.UNAUTHORIZED, "请求已过期，请重新发起");
    }

    // 3. Redis SETNX 校验 Nonce 唯一性
    String nonceKey = NONCE_KEY_PREFIX + nonce;
    Boolean acquired =
        redisTemplate.opsForValue().setIfAbsent(nonceKey, "1", Duration.ofSeconds(timeWindow));

    if (acquired == null || !acquired) {
      securityLogger.warn("[防重放拦截] 重复请求 - IP: {}, URI: {}, nonce: {}", clientIp, uri, nonce);
      throw new BusinessException(Result.REPEAT_REQUEST, "重复请求，请勿重复提交");
    }

    // 校验通过，执行业务方法
    return joinPoint.proceed();
  }

  /** 获取当前 HttpServletRequest */
  private HttpServletRequest getCurrentRequest() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
      return attrs.getRequest();
    } catch (Exception e) {
      return null;
    }
  }

  /** 获取客户端真实 IP */
  private String getClientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
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
}
