package com.workorder.config;


import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 消息配置 — 审批状态同步安全
 *
 * <p>安全策略（§9 流程变量安全 + 实时同步）： 1. 频道隔离：按部门 ID 隔离消息（wos:approval:{departmentId}），避免跨部门消息泄露 2.
 * 消息签名：HMAC-SHA256 防止伪造（恶意客户端无法伪造审批状态变更消息） 3. 签名校验失败时拒绝消息（fail-closed）
 *
 * <p>密钥来源： - 优先从 work-order-system.message.signing-key 配置读取 - 回退到 MESSAGE_SIGNING_KEY 环境变量 - 均缺失时
 * sign() 抛 IllegalStateException，强制运维配置
 *
 * @author KLord
 */
@Configuration
public class RedisMessageConfig {

  private static final Logger logger = LoggerFactory.getLogger(RedisMessageConfig.class);

  @Value("${work-order-system.message.signing-key:${MESSAGE_SIGNING_KEY:}}")
  private String signingKey;

  @Value("${work-order-system.message.channel-prefix:wos:approval}")
  private String channelPrefix;

  /**
   * HMAC-SHA256 签名
   *
   * @param payload 待签名消息内容
   * @return Base64 编码的签名
   * @throws IllegalStateException 密钥未配置时抛出
   */
  public String sign(String payload) {
    if (signingKey == null || signingKey.isEmpty()) {
      throw new IllegalStateException(
          "消息签名密钥未配置（请设置 work-order-system.message.signing-key 或 MESSAGE_SIGNING_KEY 环境变量）");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hash);
    } catch (Exception e) {
      throw new RuntimeException("消息签名失败: " + e.getMessage(), e);
    }
  }

  /**
   * 校验签名（恒定时间比较，防时序攻击）
   *
   * @param payload 原始消息内容
   * @param signature 待校验的签名
   * @return true=校验通过，false=校验失败或异常
   */
  public boolean verify(String payload, String signature) {
    if (signature == null || signature.isEmpty()) {
      return false;
    }
    try {
      String expected = sign(payload);
      // 恒定时间比较，防时序攻击
      return constantTimeEquals(expected, signature);
    } catch (Exception e) {
      logger.warn("消息签名校验异常: {}", e.getMessage());
      return false;
    }
  }

  /**
   * 构造部门隔离频道路
   *
   * @param departmentId 部门 ID
   * @return 频道名（如 wos:approval:100）
   */
  public String getChannelForDepartment(Long departmentId) {
    return channelPrefix + ":" + departmentId;
  }

  /** 消息监听容器（各 listener 通过 container.addMessageListener 自行注册频道） */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    logger.info("✅ Redis 消息监听容器已初始化，频道前缀: {}", channelPrefix);
    return container;
  }

  /** 恒定时间字符串比较（防时序攻击） */
  private boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    if (a.length() != b.length()) return false;
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }
}
