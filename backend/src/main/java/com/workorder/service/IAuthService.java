package com.workorder.service;


import java.util.Map;


import com.workorder.common.result.Result;
import com.workorder.dto.LoginDTO;

/**
 * 认证服务接口
 *
 * @author KLord
 */
public interface IAuthService {

  /** 用户登录 */
  Result<Map<String, Object>> login(LoginDTO loginDTO);

  /** 用户注册 */
  Result<?> register(Map<String, Object> registerInfo);

  /** 刷新Token */
  Result<Map<String, Object>> refreshToken(String token);

  /** 用户登出 */
  Result<?> logout(String token);

  /** 获取当前用户信息 */
  Result<Map<String, Object>> getCurrentUser(Long userId);

  /**
   * 修改密码（首次登录强制改密）
   *
   * @param userId 用户ID
   * @param oldPassword 旧密码
   * @param newPassword 新密码
   * @return 操作结果
   */
  Result<?> changePassword(Long userId, String oldPassword, String newPassword);
}
