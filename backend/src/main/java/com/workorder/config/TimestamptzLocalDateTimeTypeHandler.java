package com.workorder.config;


import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * MyBatis TypeHandler — TIMESTAMPTZ ↔ LocalDateTime 双向转换
 *
 * <p>背景（OPTIMIZATION P6 数据库优化）： 数据库时间列已统一升级为 PostgreSQL TIMESTAMPTZ（timestamp with time zone）， 但
 * Java 实体仍使用 {@code LocalDateTime}（无时区信息）。PostgreSQL JDBC 42.6.0 严格类型校验：调用 {@code rs.getObject(col,
 * LocalDateTime.class)} 读取 TIMESTAMPTZ 列时会抛 {@code PSQLException: Cannot convert the column of type
 * TIMESTAMPTZ to requested type java.time.LocalDateTime}，导致登录、DEK 迁移、工单查询全部失败。
 *
 * <p>修复方案（替代 mybatis-typehandlers-jsr310 默认的 LocalDateTimeTypeHandler）：
 *
 * <ul>
 *   <li>读取：优先按 {@link OffsetDateTime} 取值（TIMESTAMPTZ），再转 LocalDateTime； 若 JDBC 不支持则回退按 {@link
 *       Timestamp} 取值，再转 LocalDateTime。
 *   <li>写入：LocalDateTime 视为 UTC 时间，转 {@link OffsetDateTime}(UTC) 后写入， 保证 TIMESTAMPTZ 列存储一致；回退为
 *       Timestamp 直接 set。
 * </ul>
 *
 * <p>全局注册说明： 通过 {@link MyBatisTypeHandlerConfig} 的 {@code ConfigurationCustomizer} 显式注册， 覆盖 MyBatis
 * 内置的 JSR-310 LocalDateTimeTypeHandler（fallback 类型）。 不使用 {@code @MappedTypes} + {@code @Component}
 * —— 该自动注册方式 会被内置 JSR-310 Handler 覆盖，无法生效。
 *
 * @author KLord
 */
public class TimestamptzLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

  /** UTC 时区偏移，写入时统一使用 */
  private static final ZoneOffset WRITE_ZONE = ZoneOffset.UTC;

  /**
   * 写入参数：LocalDateTime → TIMESTAMPTZ 将 LocalDateTime 视为 UTC 时间，转 OffsetDateTime(UTC) 交给 JDBC 驱动。
   */
  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType) throws SQLException {
    try {
      // 优先用 OffsetDateTime 传参（适配 TIMESTAMPTZ 列）
      OffsetDateTime odt = parameter.atOffset(WRITE_ZONE);
      ps.setObject(i, odt);
    } catch (Exception e) {
      // 回退：用 java.sql.Timestamp（兼容旧驱动）
      ps.setTimestamp(i, Timestamp.valueOf(parameter));
    }
  }

  /**
   * 按列名读取：TIMESTAMPTZ → LocalDateTime 优先按 OffsetDateTime 取值（PostgreSQL TIMESTAMPTZ），再转
   * LocalDateTime； 失败则按 Timestamp 取值再转 LocalDateTime（兼容 TIMESTAMP 列）。
   */
  @Override
  public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return readLocalDateTime(rs, columnName, null);
  }

  /** 按列索引读取：TIMESTAMPTZ → LocalDateTime */
  @Override
  public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return readLocalDateTime(rs, null, columnIndex);
  }

  /** 存储过程读取：TIMESTAMPTZ → LocalDateTime */
  @Override
  public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    Object obj = cs.getObject(columnIndex);
    return toLocalDateTime(obj);
  }

  /**
   * 通用读取逻辑：先尝试 OffsetDateTime，再回退 Timestamp。
   *
   * @param rs ResultSet
   * @param columnName 列名（与 columnIndex 互斥）
   * @param columnIndex 列索引（与 columnName 互斥）
   * @return LocalDateTime 或 null
   */
  private LocalDateTime readLocalDateTime(ResultSet rs, String columnName, Integer columnIndex)
      throws SQLException {
    // 1. 优先按 OffsetDateTime 取值（PostgreSQL TIMESTAMPTZ 列原生类型）
    try {
      OffsetDateTime odt =
          (columnName != null)
              ? rs.getObject(columnName, OffsetDateTime.class)
              : rs.getObject(columnIndex, OffsetDateTime.class);
      if (odt == null) {
        return null;
      }
      // 转为系统时区的 LocalDateTime（保证前端显示与用户时区一致）
      return odt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
    } catch (Exception ignore) {
      // 驱动或列不支持 OffsetDateTime，回退到 Timestamp 路径
    }

    // 2. 回退：按 Timestamp 取值（兼容 TIMESTAMP 列 / 旧驱动）
    try {
      Timestamp ts =
          (columnName != null) ? rs.getTimestamp(columnName) : rs.getTimestamp(columnIndex);
      return ts == null ? null : ts.toLocalDateTime();
    } catch (Exception e) {
      // 3. 最终回退：getObject 兜底（ZonedDateTime / String 等）
      Object obj = (columnName != null) ? rs.getObject(columnName) : rs.getObject(columnIndex);
      return toLocalDateTime(obj);
    }
  }

  /** 把任意对象转为 LocalDateTime（兼容 OffsetDateTime / ZonedDateTime / Timestamp / LocalDateTime） */
  private LocalDateTime toLocalDateTime(Object obj) {
    if (obj == null) {
      return null;
    }
    if (obj instanceof LocalDateTime) {
      return (LocalDateTime) obj;
    }
    if (obj instanceof OffsetDateTime) {
      return ((OffsetDateTime) obj)
          .atZoneSameInstant(java.time.ZoneId.systemDefault())
          .toLocalDateTime();
    }
    if (obj instanceof ZonedDateTime) {
      return ((ZonedDateTime) obj)
          .withZoneSameInstant(java.time.ZoneId.systemDefault())
          .toLocalDateTime();
    }
    if (obj instanceof Timestamp) {
      return ((Timestamp) obj).toLocalDateTime();
    }
    if (obj instanceof java.util.Date) {
      return new Timestamp(((java.util.Date) obj).getTime()).toLocalDateTime();
    }
    // 兜底：尝试字符串解析
    if (obj instanceof String) {
      String s = ((String) obj).trim();
      if (s.isEmpty()) {
        return null;
      }
      try {
        return LocalDateTime.parse(s.replace(" ", "T"));
      } catch (Exception ex) {
        return null;
      }
    }
    return null;
  }
}
