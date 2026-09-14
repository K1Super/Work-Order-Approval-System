package com.workorder.config;


import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instant 类型处理器（OPTIMIZATION 四.4.4 统一时间类型）
 *
 * <p>处理 Java Instant ↔ Postgres TIMESTAMPTZ 的双向转换。 - 写入：Instant → OffsetDateTime (UTC) →
 * PreparedStatement.setObject - 读取：ResultSet.getObject(col, OffsetDateTime.class).toInstant()
 *
 * <p>Postgres JDBC 驱动原生支持 OffsetDateTime 与 TIMESTAMPTZ 互转， 无需依赖 TIMESTAMP 字符串解析，规避时区歧义。
 *
 * @author KLord
 */
@MappedTypes(Instant.class)
@MappedJdbcTypes(value = JdbcType.TIMESTAMP_WITH_TIMEZONE, includeNullJdbcType = true)
public class InstantTypeHandler extends BaseTypeHandler<Instant> {

  private static final Logger logger = LoggerFactory.getLogger(InstantTypeHandler.class);

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
      throws SQLException {
    // Instant → OffsetDateTime (UTC) → Postgres TIMESTAMPTZ
    OffsetDateTime odt = parameter.atOffset(ZoneOffset.UTC);
    ps.setObject(i, odt);
  }

  @Override
  public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return extractInstant(rs, columnName);
  }

  @Override
  public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    Object obj = rs.getObject(columnIndex);
    return toInstant(obj);
  }

  @Override
  public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    Object obj = cs.getObject(columnIndex);
    return toInstant(obj);
  }

  private Instant extractInstant(ResultSet rs, String columnName) throws SQLException {
    try {
      Object obj = rs.getObject(columnName);
      return toInstant(obj);
    } catch (SQLException e) {
      // 兼容旧数据：若列不存在或类型不匹配，返回 null
      logger.debug("Instant 读取失败，列={}，返回 null: {}", columnName, e.getMessage());
      return null;
    }
  }

  /** 将 JDBC 返回对象转为 Instant 支持：OffsetDateTime / Timestamp / java.util.Date / Instant */
  private Instant toInstant(Object obj) {
    if (obj == null) {
      return null;
    }
    if (obj instanceof Instant) {
      return (Instant) obj;
    }
    if (obj instanceof OffsetDateTime) {
      return ((OffsetDateTime) obj).toInstant();
    }
    if (obj instanceof Timestamp) {
      return ((Timestamp) obj).toInstant();
    }
    if (obj instanceof java.util.Date) {
      return ((java.util.Date) obj).toInstant();
    }
    logger.warn("InstantTypeHandler 收到未识别类型: {}", obj.getClass().getName());
    return null;
  }
}
