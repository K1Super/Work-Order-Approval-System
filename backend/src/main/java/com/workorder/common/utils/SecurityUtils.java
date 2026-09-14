package com.workorder.common.utils;


import org.apache.ibatis.annotations.Param;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.workorder.security.CustomUserDetails;

/**
 * 安全上下文工具类（规范 §2.1.1 common/utils 通用工具类）
 *
 * <p>封装 Spring Security 上下文的常用操作，消除各 Service 中重复的 SecurityContextHolder 获取逻辑（规范 §4.6.2 重复代码识别标准）。
 *
 * @author KLord
 */
public final class SecurityUtils {

  private SecurityUtils() {
    // 工具类，禁止实例化
  }

  /**
   * 获取当前认证用户 ID
   *
   * @return 当前用户 ID，无认证上下文时返回 null
   */
  public static Long getCurrentUserId() {
    CustomUserDetails userDetails = getCurrentUserDetails();
    return userDetails != null ? userDetails.getUserId() : null;
  }

  /**
   * 获取当前认证用户名
   *
   * @return 当前用户名，无认证上下文时返回 null
   */
  public static String getCurrentUsername() {
    CustomUserDetails userDetails = getCurrentUserDetails();
    return userDetails != null ? userDetails.getUsername() : null;
  }

  /**
   * 获取当前认证用户真实姓名
   *
   * @return 当前用户真实姓名，无认证上下文时返回 null
   */
  public static String getCurrentRealName() {
    CustomUserDetails userDetails = getCurrentUserDetails();
    return userDetails != null ? userDetails.getRealName() : null;
  }

  /**
   * 获取当前认证用户详情
   *
   * @return CustomUserDetails 实例，无认证上下文时返回 null
   */
  public static CustomUserDetails getCurrentUserDetails() {
    try {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !auth.isAuthenticated()) {
        return null;
      }
      Object principal = auth.getPrincipal();
      if (principal instanceof CustomUserDetails) {
        return (CustomUserDetails) principal;
      }
    } catch (Exception e) {
      // 无认证上下文（系统任务、定时任务等场景）
    }
    return null;
  }

  /**
   * 判断当前用户是否为超级管理员
   *
   * @return true 如果当前用户拥有 SUPER_ADMIN 角色
   */
  public static boolean isSuperAdmin() {
    CustomUserDetails userDetails = getCurrentUserDetails();
    return userDetails != null && userDetails.isSuperAdmin();
  }

  /**
   * 判断当前用户是否拥有指定角色
   *
   * @param roleCode 角色代码
   * @return true 如果当前用户拥有该角色
   */
  public static boolean hasRole(String roleCode) {
    CustomUserDetails userDetails = getCurrentUserDetails();
    return userDetails != null && userDetails.hasRole(roleCode);
  }
}
