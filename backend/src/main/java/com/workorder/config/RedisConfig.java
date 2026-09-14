package com.workorder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * Redis Configuration Class
 *
 * <p>安全策略（阶段 1 修复 A-05）： 移除 LaissezFaireSubTypeValidator + NON_FINAL 默认类型（CVE-2017-7525 同型 RCE 风险）。
 * 改用 BasicPolymorphicTypeValidator，仅允许 com.workorder.* 前缀的类进行多态反序列化。
 *
 * @author KLord
 */
@Configuration
public class RedisConfig {

  /**
   * Configure RedisTemplate using JSON serialization
   *
   * <p>安全要点： 1. PolymorphicTypeValidator 仅允许 com.workorder.* 与 java.util.* 前缀 — 阻止任意类反序列化 2. 不使用
   * LaissezFaireSubTypeValidator（放行所有类型，RCE 风险） 3. 不使用 NON_FINAL 默认类型（攻击面过大）
   */
  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);

    // 构建安全的类型校验器：仅允许白名单前缀的类进行多态反序列化
    PolymorphicTypeValidator ptv =
        BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .allowIfSubType("com.workorder.")
            .allowIfSubType("java.util.")
            .allowIfSubType("java.lang.")
            .allowIfSubType("java.time.")
            .allowIfSubType("java.math.")
            .build();

    ObjectMapper om = new ObjectMapper();
    om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
    // 使用安全的类型校验器激活默认类型（仅对白名单类生效）
    om.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);

    // 使用 GenericJackson2JsonRedisSerializer 配合安全 ObjectMapper
    GenericJackson2JsonRedisSerializer jacksonSerializer =
        new GenericJackson2JsonRedisSerializer(om);

    // Use StringRedisSerializer to serialize/deserialize Redis key
    StringRedisSerializer strSerializer = new StringRedisSerializer();

    // key uses String serialization method
    template.setKeySerializer(strSerializer);
    // hash key also uses String serialization method
    template.setHashKeySerializer(strSerializer);
    // value serialization method uses Jackson
    template.setValueSerializer(jacksonSerializer);
    // hash value serialization method uses Jackson
    template.setHashValueSerializer(jacksonSerializer);

    template.afterPropertiesSet();
    return template;
  }
}
