package com.workorder.interceptor;


import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.workorder.security.CustomUserDetails;

/**
 * MyBatis 审计字段自动填充拦截器（规范 §2.7.1 + §2.4.6 可观测性）
 *
 * <p>职责：在 INSERT / UPDATE 操作执行前，自动为实体对象填充审计字段：
 *
 * <ul>
 *   <li>INSERT：createBy、updateBy、createTime、updateTime、isDeleted(=0)
 *   <li>UPDATE：updateBy、updateTime
 * </ul>
 *
 * <p>设计原则：
 *
 * <ol>
 *   <li>最小侵入：通过反射检测实体是否含有审计字段，无审计字段的实体自动跳过；
 *   <li>安全获取当前用户：从 SecurityContextHolder 获取操作人 ID，无认证上下文时填充 null（系统任务）；
 *   <li>不覆盖业务显式赋值：仅在字段为 null 时填充，避免覆盖业务层已设的值；
 *   <li>异常兜底：反射操作失败时仅记录 WARN 日志，不阻断主流程（审计填充不应影响业务写入）。
 * </ol>
 *
 * @author KLord
 */
@Component
@Intercepts({
    @Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class})
})
public class AuditFieldInterceptor implements Interceptor {

  private static final Logger logger = LoggerFactory.getLogger(AuditFieldInterceptor.class);

  /** 审计字段常量（规范 §2.3.3 禁止魔法值） */
  private static final String FIELD_IS_DELETED = "isDeleted";

  private static final String FIELD_CREATE_BY = "createBy";
  private static final String FIELD_UPDATE_BY = "updateBy";
  private static final String FIELD_CREATE_TIME = "createTime";
  private static final String FIELD_UPDATE_TIME = "updateTime";

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object[] args = invocation.getArgs();
    MappedStatement ms = (MappedStatement) args[0];
    Object parameter = args[1];

    SqlCommandType sqlCommandType = ms.getSqlCommandType();
    Long currentUserId = getCurrentUserId();

    // 仅处理 INSERT 和 UPDATE
    if (sqlCommandType == SqlCommandType.INSERT) {
      fillAuditFields(parameter, currentUserId, true);
    } else if (sqlCommandType == SqlCommandType.UPDATE) {
      fillAuditFields(parameter, currentUserId, false);
    }

    return invocation.proceed();
  }

  /**
   * 填充审计字段
   *
   * @param parameter MyBatis 参数对象（可能是实体、ParamMap、集合）
   * @param currentUserId 当前操作人 ID（无认证上下文时为 null）
   * @param isInsert 是否为 INSERT 操作
   */
  @SuppressWarnings("unchecked")
  private void fillAuditFields(Object parameter, Long currentUserId, boolean isInsert) {
    if (parameter == null) {
      return;
    }

    // 处理 MyBatis ParamMap（使用 @Param 注解时生成）
    if (parameter instanceof Map) {
      Map<String, Object> paramMap = (Map<String, Object>) parameter;
      for (Object value : paramMap.values()) {
        if (isEntityCandidate(value)) {
          fillSingleEntity(value, currentUserId, isInsert);
        }
      }
      return;
    }

    // 处理集合参数（批量操作）
    if (parameter instanceof Collection) {
      for (Object item : (Collection<?>) parameter) {
        if (isEntityCandidate(item)) {
          fillSingleEntity(item, currentUserId, isInsert);
        }
      }
      return;
    }

    // 单实体参数
    fillSingleEntity(parameter, currentUserId, isInsert);
  }

  /** 判断对象是否为实体候选（含有审计字段） */
  private boolean isEntityCandidate(Object obj) {
    if (obj == null) {
      return false;
    }
    // 排除基本类型、字符串、数字等非实体对象
    if (obj instanceof CharSequence || obj instanceof Number || obj instanceof Boolean) {
      return false;
    }
    return getField(obj.getClass(), FIELD_UPDATE_BY) != null
        || getField(obj.getClass(), FIELD_CREATE_BY) != null;
  }

  /** 为单个实体填充审计字段 */
  private void fillSingleEntity(Object entity, Long currentUserId, boolean isInsert) {
    try {
      LocalDateTime now = LocalDateTime.now();

      if (isInsert) {
        // INSERT：填充创建人、更新人、时间戳、逻辑删除标识
        setFieldIfNull(entity, FIELD_IS_DELETED, 0);
        setFieldIfNull(entity, FIELD_CREATE_BY, currentUserId);
        setFieldIfNull(entity, FIELD_UPDATE_BY, currentUserId);
        setFieldIfNull(entity, FIELD_CREATE_TIME, now);
        setFieldIfNull(entity, FIELD_UPDATE_TIME, now);
      } else {
        // UPDATE：仅填充更新人与更新时间
        setFieldIfNull(entity, FIELD_UPDATE_BY, currentUserId);
        setFieldIfNull(entity, FIELD_UPDATE_TIME, now);
      }
    } catch (Exception e) {
      logger.warn(
          "审计字段自动填充失败，不影响业务写入: entity={}, error={}",
          entity.getClass().getSimpleName(),
          e.getMessage());
    }
  }

  /** 若字段值为 null 则设置（不覆盖业务显式赋值） */
  private void setFieldIfNull(Object entity, String fieldName, Object value) {
    try {
      Field field = getField(entity.getClass(), fieldName);
      if (field == null) {
        return;
      }
      field.setAccessible(true);

      // createTime/updateTime 可能是 LocalDateTime 或 Date 类型
      if (fieldName.equals(FIELD_CREATE_TIME) || fieldName.equals(FIELD_UPDATE_TIME)) {
        Object current = field.get(entity);
        if (current == null) {
          Object converted = convertTimeValue(value, field.getType());
          if (converted != null) {
            field.set(entity, converted);
          }
        }
        return;
      }

      // 其他字段仅在 null 时填充
      Object current = field.get(entity);
      if (current == null) {
        field.set(entity, value);
      }
    } catch (IllegalAccessException e) {
      logger.debug("字段 {} 不可访问，跳过: {}", fieldName, e.getMessage());
    }
  }

  /** 时间类型转换（支持 LocalDateTime 和 Date 两种实体时间字段类型） */
  private Object convertTimeValue(Object source, Class<?> targetType) {
    if (source == null) {
      return null;
    }
    if (targetType.isAssignableFrom(source.getClass())) {
      return source;
    }
    if (source instanceof LocalDateTime) {
      LocalDateTime ldt = (LocalDateTime) source;
      if (Date.class.isAssignableFrom(targetType)) {
        return java.sql.Timestamp.valueOf(ldt);
      }
    }
    if (source instanceof Date && targetType == LocalDateTime.class) {
      return ((Date) source).toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }
    return null;
  }

  /** 递归查找字段（含父类） */
  private Field getField(Class<?> clazz, String fieldName) {
    Class<?> current = clazz;
    while (current != null && current != Object.class) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    return null;
  }

  /**
   * 从 Spring Security 上下文获取当前操作人 ID
   *
   * @return 当前用户 ID，无认证上下文或系统任务时返回 null
   */
  private Long getCurrentUserId() {
    try {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !auth.isAuthenticated()) {
        return null;
      }
      Object principal = auth.getPrincipal();
      if (principal instanceof CustomUserDetails) {
        return ((CustomUserDetails) principal).getUserId();
      }
    } catch (Exception e) {
      logger.debug("获取当前用户 ID 失败（可能是系统任务无认证上下文）: {}", e.getMessage());
    }
    return null;
  }

  @Override
  public Object plugin(Object target) {
    return target instanceof Executor ? Plugin.wrap(target, this) : target;
  }

  @Override
  public void setProperties(Properties properties) {
    // 无额外属性配置
  }
}
