package com.workorder.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.env.Environment;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.util.StringUtils;

/**
 * 会话无关的 Cookie CSRF Token 存储实现（W-24）。
 *
 * <p>系统采用 STATELESS 会话策略（JWT），Spring Security 内置的 {@code CookieCsrfTokenRepository}
 * 无法绑定服务端会话，因此改为「双提交 Cookie」方案：Token 同时写入 Cookie（XSRF-TOKEN）与请求头
 * （X-XSRF-TOKEN），由 {@code CsrfFilter} 比较两者是否一致，不依赖任何服务端会话。
 *
 * <p>与内置实现相比的加固点：
 *
 * <ul>
 *   <li>Cookie 显式携带 {@code SameSite=Lax}（约束跨站携带）</li>
 *   <li>{@code Secure} 按 {@code spring.profiles.active} 是否含 prod 决定（prod 仅 HTTPS 传输）</li>
 *   <li>Token 生成使用 {@code SecureRandom}（默认为密码学安全随机源）</li>
 *   <li>提供 {@link #matches(String, String)} 常量时间比较（{@code CsrfFilter} 内部亦使用
 *       {@code MessageDigest.isEqual} 做恒定时间比较）</li>
 * </ul>
 *
 * @author KLord
 */
public class SessionLessCookieCsrfTokenRepository implements CsrfTokenRepository {

  /** CSRF Cookie 名称（与 Axios 默认 xsrfCookieName 对齐） */
  public static final String DEFAULT_COOKIE_NAME = "XSRF-TOKEN";

  /** CSRF 请求头名称（与 Axios 默认 xsrfHeaderName 对齐） */
  public static final String DEFAULT_HEADER_NAME = "X-XSRF-TOKEN";

  /** CSRF 参数名（表单提交兜底） */
  public static final String DEFAULT_PARAMETER_NAME = "_csrf";

  /** 随机 Token 字节长度（128 位熵） */
  private static final int TOKEN_BYTES = 16;

  private final String cookieName;
  private final String headerName;
  private final String parameterName;
  private final Environment environment;
  private final SecureRandom secureRandom = new SecureRandom();

  /** 使用默认 Cookie/Header/参数名构造 */
  public SessionLessCookieCsrfTokenRepository(Environment environment) {
    this(environment, DEFAULT_COOKIE_NAME, DEFAULT_HEADER_NAME, DEFAULT_PARAMETER_NAME);
  }

  /** 自定义名称构造 */
  public SessionLessCookieCsrfTokenRepository(
      Environment environment, String cookieName, String headerName, String parameterName) {
    this.environment = environment;
    this.cookieName = cookieName;
    this.headerName = headerName;
    this.parameterName = parameterName;
  }

  @Override
  public CsrfToken generateToken(HttpServletRequest request) {
    return new DefaultCsrfToken(this.headerName, this.parameterName, createNewToken());
  }

  @Override
  public void saveToken(
      CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
    String tokenValue = (token != null) ? token.getToken() : "";
    // 登出/清除时（token == null）写入 Max-Age=0 删除 Cookie
    long maxAge = (token != null) ? -1 : 0;
    response.addHeader("Set-Cookie", buildCookieHeader(tokenValue, maxAge));
  }

  @Override
  public CsrfToken loadToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    Cookie csrfCookie = null;
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (this.cookieName.equals(cookie.getName())) {
          csrfCookie = cookie;
          break;
        }
      }
    }
    if (csrfCookie == null) {
      return null;
    }
    String token = csrfCookie.getValue();
    if (!StringUtils.hasText(token)) {
      return null;
    }
    return new DefaultCsrfToken(this.headerName, this.parameterName, token);
  }

  /**
   * 使用 SecureRandom 生成不依赖会话的随机 Token（128 位熵，Base64 URL 编码）。
   */
  private String createNewToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    this.secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * 手工拼接 Set-Cookie 头以支持 SameSite 属性（javax.servlet Cookie API 在 Servlet 4.0 不支持 SameSite）。
   *
   * <p>HttpOnly 不设置：前端 JS 需读取该 Cookie 以注入 X-XSRF-TOKEN 请求头（双提交 Cookie 方案）。
   */
  private String buildCookieHeader(String tokenValue, long maxAge) {
    StringBuilder header = new StringBuilder();
    header.append(this.cookieName).append("=").append(tokenValue);
    header.append("; Path=/");
    if (maxAge >= 0) {
      header.append("; Max-Age=").append(maxAge);
    }
    if (isProdProfile()) {
      header.append("; Secure");
    }
    header.append("; SameSite=Lax");
    return header.toString();
  }

  /** 判断当前是否为 prod 环境（prod 强制 Secure，dev 兼容 HTTP localhost） */
  private boolean isProdProfile() {
    String[] activeProfiles = this.environment.getActiveProfiles();
    for (String profile : activeProfiles) {
      if ("prod".equalsIgnoreCase(profile)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 恒定时间比较两个 CSRF Token。
   *
   * <p>用于需要显式校验的场景；Spring Security 5.7 的 {@code CsrfFilter} 内部已使用
   * {@code MessageDigest.isEqual} 对请求头与 Cookie 中的 Token 做恒定时间比较。
   */
  public static boolean matches(String expected, String actual) {
    if (expected == actual) {
      return true;
    }
    if (expected == null || actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
