package com.workorder.security;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.workorder.dao.UserMapper;

/**
 * tokenVersion 二级缓存（Redis，TTL 60 秒）。
 *
 * <p>为避免 JwtAuthenticationFilter 每请求直查数据库，将 token_version 缓存到 Redis； 注销 / 改密 / 密码重置 / 禁用用户等
 * 递增 token_version 的场景调用 {@link #evict(Long)} 主动失效，保证旧 Token 最多延迟一个 TTL 内被拒绝。
 *
 * @author KLord
 */
@Component
public class TokenVersionCache {

  private static final Logger logger = LoggerFactory.getLogger(TokenVersionCache.class);

  /** Redis key 前缀：token:version:{userId} */
  private static final String KEY_PREFIX = "token:version:";

  /** 缓存 TTL（秒） */
  private static final long TTL_SECONDS = 60;

  private final RedisTemplate<String, Object> redisTemplate;

  private final UserMapper userMapper;

  /** 构造函数注入 RedisTemplate 与 UserMapper */
  public TokenVersionCache(RedisTemplate<String, Object> redisTemplate, UserMapper userMapper) {
    this.redisTemplate = redisTemplate;
    this.userMapper = userMapper;
  }

  /**
   * 获取用户 token_version（优先命中 Redis 缓存，miss 时回源数据库并回填）。
   *
   * @param userId 用户ID
   * @return 当前 token_version；用户不存在返回 null
   */
  public Long getOrLoad(Long userId) {
    String key = key(userId);
    try {
      Object cached = redisTemplate.opsForValue().get(key);
      if (cached instanceof Number) {
        return ((Number) cached).longValue();
      }
    } catch (Exception e) {
      // Redis 异常时降级直查数据库，保证鉴权功能可用性
      logger.warn("读取 tokenVersion 缓存失败，降级直查 DB: userId={}, error={}", userId, e.getMessage());
      return userMapper.selectTokenVersion(userId);
    }

    Long version = userMapper.selectTokenVersion(userId);
    if (version != null) {
      try {
        redisTemplate.opsForValue().set(key, version, TTL_SECONDS, TimeUnit.SECONDS);
      } catch (Exception e) {
        logger.warn("写入 tokenVersion 缓存失败: userId={}, error={}", userId, e.getMessage());
      }
    }
    return version;
  }

  /**
   * 主动失效指定用户的 tokenVersion 缓存（token_version 递增成功后调用）。
   *
   * @param userId 用户ID
   */
  public void evict(Long userId) {
    try {
      redisTemplate.delete(key(userId));
    } catch (Exception e) {
      logger.debug("删除 tokenVersion 缓存失败（忽略）: userId={}, error={}", userId, e.getMessage());
    }
  }

  private String key(Long userId) {
    return KEY_PREFIX + userId;
  }
}