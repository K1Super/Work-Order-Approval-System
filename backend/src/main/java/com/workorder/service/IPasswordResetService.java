package com.workorder.service;


import java.util.Map;


import com.workorder.common.result.Result;

/**
 * 密码重置服务接口
 *
 * <p>企业级密码重置流程： 1. 管理员触发重置 → {@link #generateResetToken} 生成一次性 token 2. 用户通过重置链接携带 token → {@link
 * #validateResetToken} 验证 token 有效性 3. 用户输入新密码 → {@link #consumeResetToken} 消费 token 并设置新密码
 *
 * <p>安全特性： - token 为 64 字符随机十六进制串（256 位熵） - 数据库仅存 SHA-256 哈希，防数据库泄露时被重放 - 15 分钟过期 - 一次性使用 - 生成新
 * token 时使该用户旧 token 失效
 *
 * @author KLord
 */
public interface IPasswordResetService {

  /**
   * 生成密码重置令牌
   *
   * @param userId 目标用户 ID（要重置密码的用户）
   * @param operatorId 操作者 ID（触发重置的管理员），可为空
   * @return Result 包含 token 明文和过期时间（token 明文仅在此时返回一次）
   */
  Result<Map<String, Object>> generateResetToken(Long userId, Long operatorId);

  /**
   * 验证重置令牌有效性（不消费）
   *
   * @param token 明文 token
   * @return Result 包含 token 元信息（用户名、过期时间）用于前端展示
   */
  Result<Map<String, Object>> validateResetToken(String token);

  /**
   * 消费重置令牌并设置新密码
   *
   * @param token 明文 token
   * @param newPassword 新密码（明文，由调用方通过 HTTPS 传入）
   * @return Result 操作结果
   */
  Result<Void> consumeResetToken(String token, String newPassword);
}
