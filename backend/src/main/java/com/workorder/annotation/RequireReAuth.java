package com.workorder.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.web.bind.annotation.PathVariable;

/**
 * 敏感操作二次鉴权注解（规范 §8 安全开发规范）
 *
 * <p>应用于审批通过/驳回、密码修改、员工删除等敏感操作，要求用户重新输入密码确认身份。 切面 {@link
 * com.workorder.aspect.SensitiveOperationAspect} 校验：
 *
 * <ol>
 *   <li>请求头 X-Reauth-Password 必须存在
 *   <li>密码与当前用户密码匹配（通过 PasswordEncoder 校验）
 *   <li>校验失败抛出 BusinessException(ErrorCode.REAUTH_FAILED)
 * </ol>
 *
 * <p>使用示例：
 *
 * <pre>
 *   &#64;RequireReAuth
 *   &#64;PostMapping("/approvals/{id}/approve")
 *   public Result&lt;?&gt; approve(@PathVariable Long id) { ... }
 * </pre>
 *
 * @author KLord
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireReAuth {

  /** 二次鉴权描述信息（用于日志记录，便于审计追溯） */
  String description() default "";
}
