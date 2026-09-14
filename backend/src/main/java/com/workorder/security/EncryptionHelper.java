package com.workorder.security;


import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * 加密上下文辅助工具（OPTIMIZATION 三.3.3 配套）
 *
 * <p>提供 try-with-resources 风格的 DekContext 管理，确保 ThreadLocal 在使用后被清理。
 *
 * <p><b>使用示例：</b>
 *
 * <pre>
 *   User user = encryptionHelper.executeWithDekContext(userId,
 *           () -> userMapper.selectById(userId));
 * </pre>
 *
 * <p><b>设计目的：</b> AesEncryptedStringTypeHandler 在解密 email/phone 字段时，需要通过 DekContext 获取当前 userId，
 * 进而加载该用户的 DEK。如果不设置 DekContext，TypeHandler 会回退到 legacy 静态密钥 （仅能解密历史数据，无法解密新加密的数据）。
 *
 * <p>本辅助类封装 try-finally 清理逻辑，避免线程池复用导致 userId 串号。
 *
 * @author KLord
 */
@Component
public class EncryptionHelper {

  /**
   * 在 DekContext 上下文中执行操作（自动设置与清理 userId）
   *
   * <p>在方法执行期间，AesEncryptedStringTypeHandler 可通过 DekContext.getUserId() 获取当前操作的 userId，从而加载该用户的 DEK
   * 进行加密/解密。
   *
   * @param userId 当前操作的用户ID
   * @param supplier 实际业务操作（通常包含 userMapper 调用）
   * @param <T> 返回值类型
   * @return supplier 的返回值
   */
  public <T> T executeWithDekContext(Long userId, Supplier<T> supplier) {
    Long previousUserId = DekContext.getUserId();
    try {
      DekContext.setUserId(userId);
      return supplier.get();
    } finally {
      // 恢复 previousUserId（支持嵌套调用），无则清理
      if (previousUserId != null) {
        DekContext.setUserId(previousUserId);
      } else {
        DekContext.clear();
      }
    }
  }

  /**
   * 在 DekContext 上下文中执行无返回值操作（自动设置与清理 userId）
   *
   * @param userId 当前操作的用户ID
   * @param runnable 实际业务操作
   */
  public void executeWithDekContext(Long userId, Runnable runnable) {
    Long previousUserId = DekContext.getUserId();
    try {
      DekContext.setUserId(userId);
      runnable.run();
    } finally {
      if (previousUserId != null) {
        DekContext.setUserId(previousUserId);
      } else {
        DekContext.clear();
      }
    }
  }
}
