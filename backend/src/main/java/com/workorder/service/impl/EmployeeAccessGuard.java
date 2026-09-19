package com.workorder.service.impl;


import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.common.constant.RoleConstants;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;

/**
 * 员工访问控制组件。
 *
 * <p>职责：集中承载员工管理相关权限判定——是否部门管理员、是否超级管理员、最高角色展示名，以及部门管理员跨部门访问拦截。
 * 供查询服务、生命周期编排器、密码服务复用，消除 {@code EmployeeServiceImpl} 拆分前的重复实现。
 *
 * <p>说明：{@code isCrossDepartmentAccess} 使用 {@link Objects#equals} 做空值安全比较（原实现
 * {@code existing.getDepartmentId().equals(...)} 在目标部门为空时会 NPE），判定语义不变。
 *
 * @author KLord
 */
@Component
public class EmployeeAccessGuard {

  @Autowired private UserMapper userMapper;

  /**
   * 判断当前用户是否为部门管理员（总监级及以上）。
   *
   * <p>超级管理员是全局管理员，不受部门数据权限限制，不视为部门管理员。此短路检查与
   * {@code RoleConstants.DEPT_ADMIN_ROLE_IDS} 不含 SUPER_ADMIN 形成双保险。
   *
   * @param user 当前用户
   * @return true 表示部门管理员
   */
  public boolean isDeptAdmin(User user) {
    if (user == null || user.getId() == null) return false;
    if (isSuperAdmin(user)) return false;

    List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
    if (roleIds == null) return false;
    for (Long roleId : roleIds) {
      if (RoleConstants.isDeptAdmin(roleId)) return true;
    }
    return false;
  }

  /**
   * 判断用户是否为超级管理员（拥有 ROLE_SUPER_ADMIN 角色）。
   *
   * <p>用于密码重置、删除保护等敏感操作的安全判定。
   *
   * @param user 目标用户
   * @return true 表示超级管理员
   */
  public boolean isSuperAdmin(User user) {
    if (user == null || user.getId() == null) return false;
    List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
    if (roleIds == null) return false;
    for (Long roleId : roleIds) {
      if (roleId == RoleConstants.ROLE_SUPER_ADMIN) return true;
    }
    return false;
  }

  /**
   * 部门管理员跨部门访问拦截判定。
   *
   * <p>规则：非部门管理员永不拦截；部门管理员仅可访问本部门员工（部门 ID 空值安全比较）。
   *
   * @param currentUser 当前操作者
   * @param targetDepartmentId 目标员工所属部门 ID
   * @return true 表示跨部门访问（应拒绝）
   */
  public boolean isCrossDepartmentAccess(User currentUser, Long targetDepartmentId) {
    if (!isDeptAdmin(currentUser)) return false;
    return !Objects.equals(targetDepartmentId, currentUser.getDepartmentId());
  }

  /**
   * 获取用户最高优先级角色代码（用于列表展示）。
   *
   * <p>取所有角色中最高层级（最小 orgLevel）对应角色的代码，映射规则与 RoleConstants 一致。
   *
   * @param user 目标用户
   * @return 角色代码（SUPER_ADMIN / DIRECTOR / FUNCTIONAL / EMPLOYEE 等）
   */
  public String getHighestUserRole(User user) {
    if (user == null || user.getId() == null) return "UNKNOWN";

    List<Long> roleIds = userMapper.getUserRoleIds(user.getId());
    if (roleIds == null || roleIds.isEmpty()) return "EMPLOYEE";

    // 取所有角色中最高层级（最小 orgLevel 值）对应的角色代码
    int minLevel = 4;
    long highestRoleId = RoleConstants.ROLE_STAFF;
    for (Long roleId : roleIds) {
      int level = RoleConstants.getOrgLevelByRoleId(roleId);
      if (level < minLevel) {
        minLevel = level;
        highestRoleId = roleId;
      }
    }

    if (highestRoleId == RoleConstants.ROLE_SUPER_ADMIN) return "SUPER_ADMIN";
    if (highestRoleId == RoleConstants.ROLE_SECURITY_AUDIT) return "SECURITY_AUDIT";
    if (highestRoleId == RoleConstants.ROLE_CHAIRMAN) return "CHAIRMAN";
    if (highestRoleId == RoleConstants.ROLE_GM) return "GM";
    if (highestRoleId == RoleConstants.ROLE_VP) return "VP";
    if (highestRoleId >= RoleConstants.ROLE_RD_DIR && highestRoleId <= RoleConstants.ROLE_PROCUREMENT_DIR) {
      return "DIRECTOR";
    }
    if (highestRoleId >= RoleConstants.ROLE_HR_SPEC
        && highestRoleId <= RoleConstants.ROLE_PROCUREMENT_SPEC) {
      return "FUNCTIONAL";
    }
    return "EMPLOYEE";
  }
}