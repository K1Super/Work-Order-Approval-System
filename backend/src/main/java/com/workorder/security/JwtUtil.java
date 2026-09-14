package com.workorder.security;


import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT Utility Class Used to generate and parse JWT Tokens
 *
 * <p>jjwt 0.11.5 API migration: - signWith(SignatureAlgorithm, String) → signWith(SecretKey) -
 * Jwts.parser().setSigningKey(String) → Jwts.parserBuilder().setSigningKey(SecretKey).build() -
 * SecretKey derived via Keys.hmacShaKeyFor(bytes) — algorithm auto-selected by key size
 *
 * @author KLord
 */
@Component
public class JwtUtil {

  /** JWT Secret Key (from configuration / environment variable) */
  @Value("${jwt.secret}")
  private String secret;

  /** JWT Expiration Time (milliseconds) */
  @Value("${jwt.expiration}")
  private Long expiration;

  /** HMAC SecretKey derived from the raw secret string (computed once at startup) */
  private SecretKey signingKey;

  /**
   * Initialize the signing key after dependency injection. Validates that the secret meets the
   * minimum length for HMAC-SHA (≥ 32 bytes / 256 bits).
   */
  @PostConstruct
  public void init() {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "JWT secret must be at least 32 bytes (256 bits) for HMAC-SHA. "
              + "Current secret length: "
              + (secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length)
              + " bytes. "
              + "Set a stronger JWT_SECRET environment variable.");
    }
    this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Generate Token (legacy — 不携带 tokenVersion，仅供向后兼容调用)
   *
   * @param username Username
   * @param userId User ID
   * @return Token string
   * @deprecated OPTIMIZATION 三.3.1 已启用 tokenVersion 混合状态管理， 新调用方必须使用 {@link #generateToken(String,
   *     Long, Long)}， 此方法仅保留用于 refreshToken 兜底等历史路径，将在后续版本移除。
   */
  @Deprecated
  public String generateToken(String username, Long userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("userId", userId);
    return createToken(claims, username);
  }

  /**
   * Generate Token (携带 tokenVersion — OPTIMIZATION 三.3.1)
   *
   * <p>在 JWT 荷载中增加 tokenVersion 字段，与数据库 sys_user.token_version 对应。 每次用户改密 / 被管理员禁用时，Service 层调用
   * {@code UserMapper.incrementTokenVersion} 递增版本号；JwtAuthenticationFilter 解析 Token 后比对版本号，不匹配直接拒绝。
   * 这样彻底废止了"JWT 签发后直至过期始终有效"的缺陷。
   *
   * @param username Username
   * @param userId User ID
   * @param tokenVersion 当前用户的 token_version（来自数据库）
   * @return Token string
   */
  public String generateToken(String username, Long userId, Long tokenVersion) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("userId", userId);
    claims.put("tokenVersion", tokenVersion != null ? tokenVersion : 0L);
    return createToken(claims, username);
  }

  /** Create Token (algorithm auto-selected by key size via Keys.hmacShaKeyFor) */
  private String createToken(Map<String, Object> claims, String subject) {
    Date now = new Date();
    Date expiryDate = new Date(now.getTime() + expiration);

    return Jwts.builder()
        .setClaims(claims)
        .setSubject(subject)
        .setIssuedAt(now)
        .setExpiration(expiryDate)
        .signWith(signingKey)
        .compact();
  }

  /** Get username from token */
  public String getUsernameFromToken(String token) {
    return getClaimFromToken(token, Claims::getSubject);
  }

  /** Get user ID from token */
  public Long getUserIdFromToken(String token) {
    Claims claims = getAllClaimsFromToken(token);
    // userId may be stored as Integer or Long depending on JSON serialization; coerce safely.
    Object raw = claims.get("userId");
    if (raw instanceof Number) {
      return ((Number) raw).longValue();
    }
    return null;
  }

  /**
   * 从 Token 中提取 tokenVersion（OPTIMIZATION 三.3.1）
   *
   * <p>用于 JwtAuthenticationFilter 与数据库 sys_user.token_version 比对： - 一致 → Token 有效 - 不一致 →
   * 用户已改密/被禁用，Token 立即失效
   *
   * <p>兼容旧 Token（无 tokenVersion 声明）→ 返回 0，由 Filter 配合 DB 版本号判断。
   *
   * @param token JWT Token
   * @return Token 中携带的版本号；旧 Token 无此声明返回 0
   */
  public Long getTokenVersionFromToken(String token) {
    try {
      Claims claims = getAllClaimsFromToken(token);
      Object raw = claims.get("tokenVersion");
      if (raw instanceof Number) {
        return ((Number) raw).longValue();
      }
      // 旧 Token 不携带 tokenVersion 声明 → 视为版本 0
      return 0L;
    } catch (Exception e) {
      // Token 解析失败 → 视为非法版本
      return null;
    }
  }

  /** Get expiration time from token */
  public Date getExpirationDateFromToken(String token) {
    return getClaimFromToken(token, Claims::getExpiration);
  }

  /** Get specified claim from token */
  private <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = getAllClaimsFromToken(token);
    return claimsResolver.apply(claims);
  }

  /** Parse Token to get all claims (jjwt 0.11.5 parserBuilder API) */
  private Claims getAllClaimsFromToken(String token) {
    return Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(token).getBody();
  }

  /** Check if Token is expired */
  public boolean isTokenExpired(String token) {
    try {
      final Date expiration = getExpirationDateFromToken(token);
      return expiration.before(new Date());
    } catch (Exception e) {
      // Token parsing failure considered as expired
      return true;
    }
  }

  /**
   * Validate if Token is valid
   *
   * @param token JWT Token
   * @param username Expected username
   * @return Whether valid
   */
  public boolean validateToken(String token, String username) {
    try {
      final String tokenUsername = getUsernameFromToken(token);
      return (tokenUsername.equals(username) && !isTokenExpired(token));
    } catch (Exception e) {
      // Token invalid or exception
      return false;
    }
  }

  /**
   * Validate Token signature and structure (without comparing username)
   *
   * @param token JWT Token
   * @return Whether the token parses successfully and is not expired
   */
  public boolean validateToken(String token) {
    try {
      getAllClaimsFromToken(token);
      return !isTokenExpired(token);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Refresh Token
   *
   * @param token Original Token
   * @return New Token
   */
  public String refreshToken(String token) {
    try {
      String username = getUsernameFromToken(token);
      Long userId = getUserIdFromToken(token);
      Long tokenVersion = getTokenVersionFromToken(token);
      return generateToken(username, userId, tokenVersion);
    } catch (Exception e) {
      throw new RuntimeException("Cannot refresh Token: " + e.getMessage(), e);
    }
  }

  /**
   * Check if Token is about to expire (remaining validity less than 30 minutes)
   *
   * @param token JWT Token
   * @return Whether about to expire
   */
  public boolean isTokenExpiringSoon(String token) {
    try {
      Date expiration = getExpirationDateFromToken(token);
      long remainingTime = expiration.getTime() - System.currentTimeMillis();
      long thirtyMinutesInMillis = 30 * 60 * 1000L;
      return remainingTime < thirtyMinutesInMillis;
    } catch (Exception e) {
      return true; // Exception case considered as about to expire
    }
  }
}
