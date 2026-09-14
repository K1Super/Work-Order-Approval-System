package com.workorder.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.entity.PasswordResetToken;

/**
 * 密码重置令牌 Mapper
 *
 * @author KLord
 */
@Mapper
public interface PasswordResetTokenMapper {

  /** 插入新的重置令牌 */
  int insert(PasswordResetToken token);

  /** 根据 token 哈希查询令牌（包含所有字段，用于验证） */
  PasswordResetToken selectByTokenHash(@Param("tokenHash") String tokenHash);

  /**
   * 标记令牌为已使用
   *
   * @param id 令牌 ID
   * @param usedTime 使用时间
   */
  int markAsUsed(@Param("id") Long id, @Param("usedTime") java.util.Date usedTime);

  /**
   * 清理过期且未使用的令牌（定期维护）
   *
   * @param beforeTime 此时间之前的过期令牌将被删除
   */
  int deleteExpiredBefore(@Param("beforeTime") java.util.Date beforeTime);

  /**
   * 使某用户的所有未使用令牌失效（生成新令牌时使旧令牌失效）
   *
   * @param userId 用户 ID
   */
  int invalidateByUserId(@Param("userId") Long userId);
}
