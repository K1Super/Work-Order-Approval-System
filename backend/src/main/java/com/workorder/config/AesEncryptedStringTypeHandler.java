package com.workorder.config;


import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.crypto.SecretKey;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.workorder.security.DekService;
import com.workorder.util.AesEncryptionUtil;

/**
 * AES 加密字符串 MyBatis TypeHandler（DEK/KEK 分层加密版）
 *
 * <p>用于 sys_user.phone、sys_user.email 等敏感字段的自动加解密。
 *
 * <h3>密钥选择策略（根治手机号密文显示 Bug）：</h3>
 *
 * <ol>
 *   <li><b>写入（setNonNullParameter）</b>： 依赖 {@link AesEncryptionUtil#encrypt(String)} 内部检查
 *       DekContext： - DekContext 有 userId → 用该用户的 DEK 加密 - DekContext 无 userId → 用 legacy 静态密钥加密
 *   <li><b>读取（getNullableResult）</b>： <b>核心修复</b>：从同一行 ResultSet 读取 {@code id} 和 {@code
 *       encrypted_dek} 列， 直接用该用户的 DEK 解密——不依赖 DekContext（ThreadLocal 只能存一个 userId， 批量查询多个用户时无法为每行切换
 *       DekContext）。
 *       <ol type="a">
 *         <li>ResultSet 有 encrypted_dek 列 → 用 DekService 解密 DEK → 用 DEK 解密字段
 *         <li>DEK 解密失败 或 无 encrypted_dek → 回退到 AesEncryptionUtil.decrypt（DekContext + legacy）
 *         <li>全部失败 → 原样返回（兼容旧明文数据）
 *       </ol>
 * </ol>
 *
 * <h3>重要安全修复（登录失败 Bug 根因）：</h3>
 *
 * <ul>
 *   <li>不使用 @MappedTypes(String.class) — 全局注册会导致所有 String 参数被加密
 *   <li>不使用 @Component — 会被全局注册到 SqlSessionFactory
 * </ul>
 *
 * @author KLord
 */
public class AesEncryptedStringTypeHandler extends BaseTypeHandler<String> {

  private static final Logger logger = LoggerFactory.getLogger(AesEncryptedStringTypeHandler.class);

  /** 静态 AesEncryptionUtil 实例（由 AesTypeHandlerInitializer 注入） */
  private static AesEncryptionUtil aesUtilInstance;

  /** 静态 DekService 实例（由 AesTypeHandlerInitializer 注入） 用于在读取时根据 encrypted_dek 解密字段 */
  private static DekService dekServiceInstance;

  /** 由 AesTypeHandlerInitializer 调用，注入 AesEncryptionUtil 实例 */
  static void setAesUtil(AesEncryptionUtil util) {
    aesUtilInstance = util;
    logger.info("✅ AesEncryptedStringTypeHandler 静态注入 AesEncryptionUtil 完成");
  }

  /** 由 AesTypeHandlerInitializer 调用，注入 DekService 实例 */
  static void setDekService(DekService dekService) {
    dekServiceInstance = dekService;
    logger.info("✅ AesEncryptedStringTypeHandler 静态注入 DekService 完成");
  }

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
      throws SQLException {
    if (aesUtilInstance == null) {
      ps.setString(i, parameter);
      return;
    }
    // 空字符串/纯空白不加密，直接写入 NULL
    if (parameter == null || parameter.trim().isEmpty()) {
      ps.setNull(i, java.sql.Types.VARCHAR);
      return;
    }
    // 防止双重加密：如果传入的值已经是加密格式（iv:cipher），先解密再加密
    // 场景：前端显示密文，用户未修改直接提交 → 前端把密文作为 phone 发送
    // 如果不处理，TypeHandler 会加密密文 → 双重加密 → 读取时只解密一次 → 仍显示密文
    if (aesUtilInstance.isEncryptedFormat(parameter)) {
      logger.warn("TypeHandler 检测到加密格式的输入值，先解密再加密以防止双重加密");
      try {
        String decrypted = aesUtilInstance.decrypt(parameter);
        if (decrypted != null && !decrypted.equals(parameter)) {
          // 解密成功，用明文重新加密
          parameter = decrypted;
        } else {
          // 解密失败，可能是一个看起来像加密格式的合法字符串（极端情况）
          logger.warn("TypeHandler 加密格式输入解密失败，原样写入");
          ps.setString(i, parameter);
          return;
        }
      } catch (Exception e) {
        // 解密异常，原样写入（宁可存密文也不能丢数据）
        logger.warn("TypeHandler 加密格式输入解密异常，原样写入: {}", e.getMessage());
        ps.setString(i, parameter);
        return;
      }
    }
    try {
      // AesEncryptionUtil.encrypt 内部检查 DekContext：
      // 有 userId → DEK 加密；无 userId → legacy 密钥加密
      String encrypted = aesUtilInstance.encrypt(parameter);
      ps.setString(i, encrypted);
    } catch (Exception e) {
      logger.error("TypeHandler 加密失败", e);
      throw new SQLException("字段加密失败", e);
    }
  }

  /**
   * 从 ResultSet 读取并解密（按列名）
   *
   * <p>核心修复：从同一行读取 encrypted_dek，用对应用户的 DEK 解密。 这解决了批量查询时 DekContext（ThreadLocal）只能存一个 userId 的问题。
   */
  @Override
  public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
    String value = rs.getString(columnName);
    if (value == null || value.isEmpty()) {
      return value;
    }
    return decryptWithRowDek(rs, value);
  }

  @Override
  public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    String value = rs.getString(columnIndex);
    if (value == null || value.isEmpty()) {
      return value;
    }
    return decryptWithRowDek(rs, value);
  }

  @Override
  public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    String value = cs.getString(columnIndex);
    if (value == null || value.isEmpty()) {
      return value;
    }
    // CallableStatement 无法读取同行的 encrypted_dek，回退到 AesEncryptionUtil
    return decryptFallback(value);
  }

  /**
   * 核心解密逻辑：从同一行 ResultSet 读取用户 DEK，解密字段值
   *
   * <p>流程： 1. 从 ResultSet 读取 id 列 → 作为用户标识 2. 从 ResultSet 读取 encrypted_dek 列 → 用 KEK 解密得到 DEK 3. 用
   * DEK 解密 phone/email 字段（支持多层解密，修复双重加密问题） 4. DEK 解密失败 → 回退到 AesEncryptionUtil.decrypt（DekContext +
   * legacy）
   *
   * <p>此方法不依赖 DekContext（ThreadLocal），因此批量查询多用户时每行都能用对应用户的 DEK 解密。
   *
   * <p><b>多层解密修复：</b>旧代码存在双重加密 Bug（明文 → TypeHandler加密 → 密文 → TypeHandler再加密 → 密文²），
   * 导致数据库中存在多层嵌套密文。本方法循环解密直至得到明文或无法继续解密。
   *
   * @param rs 当前行的 ResultSet
   * @param value 待解密的密文
   * @return 明文；解密失败返回原值
   */
  private String decryptWithRowDek(ResultSet rs, String value) {
    if (aesUtilInstance == null || !aesUtilInstance.isEncryptedFormat(value)) {
      // 非加密格式，原样返回
      return value;
    }

    // 尝试获取行级 DEK
    SecretKey rowDek = null;
    Long rowUserId = null;

    if (dekServiceInstance != null) {
      try {
        try {
          rowUserId = rs.getLong("id");
          if (rs.wasNull()) {
            rowUserId = null;
          }
        } catch (SQLException ignored) {
          // id 列可能不存在（子查询等场景）
        }

        if (rowUserId != null) {
          rowDek = dekServiceInstance.getUserDek(rowUserId);
        }
      } catch (Exception e) {
        logger.debug("TypeHandler 获取行 DEK 失败: {}", e.getMessage());
      }
    }

    // 多层解密循环：反复解密直至得到明文或无法继续
    // 最多 5 轮（防止异常数据导致死循环）
    String current = value;
    for (int round = 0; round < 5; round++) {
      if (!aesUtilInstance.isEncryptedFormat(current)) {
        // 已是明文
        return current;
      }

      String decrypted = null;

      // 策略 1：用行级 DEK 解密
      if (rowDek != null) {
        try {
          decrypted = aesUtilInstance.decryptWithKey(current, rowDek);
        } catch (Exception e) {
          logger.debug("TypeHandler 第{}轮 DEK 解密失败: {}", round + 1, e.getMessage());
        }
      }

      // 策略 2：DEK 解密失败，回退到 AesEncryptionUtil.decrypt（DekContext + legacy）
      if (decrypted == null) {
        try {
          decrypted = aesUtilInstance.decrypt(current);
        } catch (Exception e) {
          logger.debug("TypeHandler 第{}轮回退解密失败: {}", round + 1, e.getMessage());
        }
      }

      if (decrypted == null || decrypted.equals(current)) {
        // 解密失败或无变化，返回当前值
        return current;
      }

      current = decrypted;
    }

    // 5 轮后仍为密文
    logger.warn("TypeHandler 解密 5 轮后仍为密文，返回当前值 (userId={})", rowUserId);
    return current;
  }

  /** 回退解密：使用 AesEncryptionUtil.decrypt（内部检查 DekContext + legacy） */
  private String decryptFallback(String value) {
    if (value == null || value.isEmpty() || aesUtilInstance == null) {
      return value;
    }
    try {
      return aesUtilInstance.decrypt(value);
    } catch (Exception e) {
      logger.debug("TypeHandler 回退解密失败，返回原值: {}", e.getMessage());
      return value;
    }
  }
}
