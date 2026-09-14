package com.workorder.aspect;

import javax.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.workorder.annotation.RequireReAuth;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.security.CustomUserDetails;
import com.workorder.security.CustomUserDetailsService;

/**
 * 敏感操作二次鉴权切面（规范 §8 安全开发规范）
 *
 * <p>拦截标注 {@link RequireReAuth} 的控制器方法，校验请求头 {@code X-Reauth-Password} 中的密码是否与当前登录用户密码一致。
 *
 * <p>校验流程：
 *
 * <ol>
 *   <li>从 SecurityContext 获取当前登录用户（CustomUserDetails）
 *   <li>从请求头 X-Reauth-Password 读取用户输入的密码
 *   <li>使用 PasswordEncoder 校验密码是否匹配
 *   <li>校验失败抛出 {@link BusinessException}(ErrorCode.REAUTH_FAILED)
 * </ol>
 *
 * @author KLord
 */
@Aspect
@Component
public class SensitiveOperationAspect {

  private static final Logger logger = LoggerFactory.getLogger(SensitiveOperationAspect.class);
  private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_VIOLATION_LOGGER");

  /** 二次鉴权密码请求头 */
  private static final String REAUTH_PASSWORD_HEADER = "X-Reauth-Password";

  @Autowired private CustomUserDetailsService customUserDetailsService;

  @Autowired private PasswordEncoder passwordEncoder;

  /** 校验请求头中的二次鉴权密码是否与当前用户密码匹配 */
  @Around("@annotation(requireReAuth)")
  public Object checkReAuth(ProceedingJoinPoint joinPoint, RequireReAuth requireReAuth)
      throws Throwable {
    HttpServletRequest request = getCurrentRequest();

    // 1. 获取当前登录用户
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails)) {
      securityLogger.warn("[二次鉴权拦截] 未认证用户尝试访问敏感操作");
      throw new BusinessException(ErrorCode.AUTH_FAILED);
    }

    CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
    String username = userDetails.getUsername();

    // 2. 读取请求头中的密码
    String reauthPassword = null;
    if (request != null) {
      reauthPassword = request.getHeader(REAUTH_PASSWORD_HEADER);
    }

    if (reauthPassword == null || reauthPassword.isEmpty()) {
      securityLogger.warn(
          "[二次鉴权拦截] 缺少二次鉴权密码 - 用户: {}, 操作: {}", username, requireReAuth.description());
      throw new BusinessException(ErrorCode.REAUTH_FAILED);
    }

    // 3. 使用 PasswordEncoder 校验密码
    String encodedPassword = userDetails.getPassword();
    if (!passwordEncoder.matches(reauthPassword, encodedPassword)) {
      securityLogger.warn(
          "[二次鉴权拦截] 密码校验失败 - 用户: {}, 操作: {}", username, requireReAuth.description());
      throw new BusinessException(ErrorCode.REAUTH_FAILED);
    }

    // 4. 校验通过，记录日志
    logger.debug("[二次鉴权通过] 用户: {}, 操作: {}", username, requireReAuth.description());

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
}
