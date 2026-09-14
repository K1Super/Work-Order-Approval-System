package com.workorder.security;


import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * JWT Auth Cookie 工具类（OPTIMIZATION 三.3.1 配套）
 *
 * <p>将 Token 从 localStorage 迁移至 HttpOnly; Secure; SameSite=Strict 的 Cookie， 彻底杜绝 XSS 窃取 Token
 * 的安全风险。浏览器自动发送 Cookie，前端 JS 无法读取。
 *
 * <p>Cookie 属性：
 *
 * <ul>
 *   <li>HttpOnly=true：JS 无法读取（防 XSS 窃取）
 *   <li>Secure：仅 HTTPS 传输（prod 强制开启；dev 关闭以兼容 HTTP localhost）
 *   <li>SameSite=Strict：跨站请求不携带（防 CSRF）
 *   <li>Path=/：全站可见
 *   <li>Max-Age=JWT 有效期：到期自动清除
 * </ul>
 *
 * @author KLord
 */
@Component
public class AuthCookieUtil {

  private static final Logger logger = LoggerFactory.getLogger(AuthCookieUtil.class);

  /** Cookie 名称 */
  public static final String COOKIE_NAME = "WOS_TOKEN";

  private final Environment environment;

  /** 构造函数，注入环境对象用于判断运行环境 */
  public AuthCookieUtil(Environment environment) {
    this.environment = environment;
  }

  /**
   * 判断当前是否为生产环境
   *
   * <p>prod 环境强制 Secure=true（仅 HTTPS 传输）； dev 环境 Secure=false（兼容 HTTP localhost，避免浏览器拒绝 Set-Cookie）
   */
  private boolean isProdProfile() {
    String[] activeProfiles = environment.getActiveProfiles();
    for (String profile : activeProfiles) {
      if ("prod".equalsIgnoreCase(profile)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 设置 JWT HttpOnly Cookie
   *
   * @param response HTTP 响应
   * @param token JWT Token 字符串
   * @param expirationMs Token 有效期（毫秒），用于计算 Max-Age
   */
  public void setAuthCookie(HttpServletResponse response, String token, long expirationMs) {
    boolean isProd = isProdProfile();
    long maxAgeSec = Math.max(0, expirationMs / 1000);

    // 使用 Set-Cookie 响应头手动拼接，支持 SameSite 属性
    // （javax.servlet.http.Cookie API 在 Servlet 4.0 中不支持 SameSite，必须手动设置）
    StringBuilder cookieHeader = new StringBuilder();
    cookieHeader.append(COOKIE_NAME).append("=").append(token);
    cookieHeader.append("; Path=/");
    cookieHeader.append("; Max-Age=").append(maxAgeSec);
    cookieHeader.append("; HttpOnly");
    if (isProd) {
      cookieHeader.append("; Secure");
    }
    cookieHeader.append("; SameSite=Strict");

    response.addHeader("Set-Cookie", cookieHeader.toString());

    logger.debug(
        "[AuthCookie] 已设置 HttpOnly Cookie (Secure={}, SameSite=Strict, Max-Age={}s)",
        isProd,
        maxAgeSec);
  }

  /**
   * 清除 JWT Cookie（注销时调用）
   *
   * <p>通过设置 Max-Age=0 立即删除 Cookie。
   *
   * @param response HTTP 响应
   */
  public void clearAuthCookie(HttpServletResponse response) {
    boolean isProd = isProdProfile();

    StringBuilder cookieHeader = new StringBuilder();
    cookieHeader.append(COOKIE_NAME).append("=");
    cookieHeader.append("; Path=/");
    cookieHeader.append("; Max-Age=0");
    cookieHeader.append("; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
    cookieHeader.append("; HttpOnly");
    if (isProd) {
      cookieHeader.append("; Secure");
    }
    cookieHeader.append("; SameSite=Strict");

    response.addHeader("Set-Cookie", cookieHeader.toString());

    logger.debug("[AuthCookie] 已清除 HttpOnly Cookie");
  }

  /**
   * 从请求中提取 JWT Token
   *
   * <p>优先级： 1. Authorization: Bearer xxx Header（兼容移动端 / 特殊流程如强制改密） 2. WOS_TOKEN Cookie（浏览器自动发送，主流程）
   *
   * @param request HTTP 请求
   * @return Token 字符串；不存在返回 null
   */
  public String extractTokenFromRequest(HttpServletRequest request) {
    // 1. 优先从 Authorization Header 读取（兼容移动端 + 强制改密流程）
    String bearerToken = request.getHeader("Authorization");
    if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      return bearerToken.substring(7);
    }

    // 2. 从 Cookie 读取（浏览器主流程）
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (COOKIE_NAME.equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }

    return null;
  }
}
