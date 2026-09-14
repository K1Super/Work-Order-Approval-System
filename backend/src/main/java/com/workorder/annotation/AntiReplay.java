package com.workorder.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重放注解
 *
 * <p>阶段 3 修复 §3 — API 限流与防重放
 *
 * <p>应用于审批、密码重置、工单删除等关键操作，防止请求被重放。 切面 {@link com.workorder.aspect.AntiReplayAspect} 校验： 1. 请求头
 * X-Request-Nonce 必须存在（否则 400） 2. 请求头 X-Request-Timestamp 与服务器偏差不超过 timeWindow 秒（否则 401） 3. Nonce 在
 * Redis 中唯一（SETNX，否则 409 重复请求）
 *
 * @author KLord
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AntiReplay {

  /** 时间窗口（秒），默认 300 秒（5 分钟） 请求时间戳与服务器时间偏差超过此值则拒绝 */
  int timeWindow() default 300;
}
