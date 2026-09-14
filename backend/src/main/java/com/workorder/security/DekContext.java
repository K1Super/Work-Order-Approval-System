package com.workorder.security;

/**
 * DEK 上下文（ThreadLocal 持有当前操作的 userId）
 *
 * <p>OPTIMIZATION 三.3.3 配套：用于在 MyBatis TypeHandler（AesEncryptedStringTypeHandler） 中获取当前操作的
 * userId，从而加载该用户的 DEK 解密 email/phone 字段。
 *
 * <p><b>使用模式：</b>
 *
 * <pre>
 *   try {
 *       DekContext.setUserId(userId);
 *       User user = userMapper.selectById(userId);  // TypeHandler 自动读取 DekContext
 *   } finally {
 *       DekContext.clear();  // 必须清理，防止线程池污染
 *   }
 * </pre>
 *
 * <p><b>注意：</b>必须配合 try-finally 清理 ThreadLocal，否则线程复用会导致 userId 串号。 推荐在 Service 层封装为模板方法（如
 * EncryptionHelper.executeWithDekContext）。
 *
 * @author KLord
 */
public final class DekContext {

  private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

  private DekContext() {
    // 工具类禁止实例化
  }

  /**
   * 设置当前线程操作的 userId
   *
   * @param userId 用户ID
   */
  public static void setUserId(Long userId) {
    USER_ID_HOLDER.set(userId);
  }

  /**
   * 获取当前线程操作的 userId
   *
   * @return userId；未设置返回 null（TypeHandler 回退到 legacy 静态密钥）
   */
  public static Long getUserId() {
    return USER_ID_HOLDER.get();
  }

  /** 清除当前线程的 userId（防止线程池复用串号） */
  public static void clear() {
    USER_ID_HOLDER.remove();
  }
}
