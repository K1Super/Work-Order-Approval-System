package com.workorder.security;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.workorder.dao.UserMapper;
import com.workorder.dto.RoleInfoDTO;
import com.workorder.entity.User;

/**
 * Custom UserDetailsService Implementation 支持通过工号（employee_id）或用户名（username）登录
 *
 * <p>安全策略（阶段 1 修复 A-08 / 阶段 2 修复 H-03）： 加载 departmentId / orgLevel / positionId / roles 到
 * CustomUserDetails， 使 DataPermissionAspect 能执行部门级数据隔离与水平越权校验。
 *
 * @author KLord
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

  private static final Logger logger = LoggerFactory.getLogger(CustomUserDetailsService.class);

  private final UserMapper userMapper;

  /** 构造函数，注入用户Mapper用于查询用户数据 */
  public CustomUserDetailsService(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    // 1. 查询用户 - 优先按工号查询，如果找不到再按用户名查询
    User user = null;

    // 先尝试按工号查询
    try {
      user = userMapper.selectByEmployeeId(username);
      if (user != null) {
        logger.debug("用户通过工号登录: employeeId={}", username);
      }
    } catch (Exception e) {
      logger.debug("工号查询异常: {}", e.getMessage());
    }

    // 如果工号没找到，尝试按用户名查询
    if (user == null) {
      user = userMapper.selectByUsername(username);
      if (user != null) {
        logger.debug("用户通过用户名登录: username={}", username);
      }
    }

    if (user == null) {
      throw new UsernameNotFoundException("User not found: " + username);
    }

    // 2. Check user status
    if (user.getStatus() == 0) {
      throw new UsernameNotFoundException("User has been disabled: " + username);
    }

    // 3. Query user permission code list
    List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());

    // 4. 加载用户角色代码列表（用于数据权限判断）
    List<String> roleCodes;
    try {
      List<RoleInfoDTO> roleInfoList = userMapper.selectRolesByUserId(user.getId());
      roleCodes = roleInfoList.stream().map(RoleInfoDTO::getRoleCode).collect(Collectors.toList());
    } catch (Exception e) {
      logger.warn("加载用户角色列表失败，使用空列表: userId={}, error={}", user.getId(), e.getMessage());
      roleCodes = Collections.emptyList();
    }

    // 5. 构建 Spring Security authority 列表
    //
    // <p>关键修复：原实现仅将权限码加入 authorities，角色代码只保存到 CustomUserDetails.roles 字段，
    // 未加入 authorities 列表。导致所有 @PreAuthorize("hasRole('SUPER_ADMIN')") 注解失效
    // （hasRole('SUPER_ADMIN') 实际检查的是 ROLE_SUPER_ADMIN authority）。
    //
    // <p>修复方案：将角色代码以 ROLE_ 前缀加入 authorities，同时保留权限码作为 authority：
    // - 权限码直接作为 authority（支持 hasAuthority('system:user')）
    // - 角色代码加 ROLE_ 前缀（支持 hasRole('SUPER_ADMIN')，Spring Security 默认前缀）
    // - 双重授权：KLord/SUPER_ADMIN 同时拥有权限码 authority + ROLE_SUPER_ADMIN authority
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    // 添加权限码作为 authority（模块级细粒度授权）
    for (String permissionCode : permissionCodes) {
      authorities.add(new SimpleGrantedAuthority(permissionCode));
    }
    // 添加角色代码（带 ROLE_ 前缀）作为 authority（角色级粗粒度授权）
    for (String roleCode : roleCodes) {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
    }

    // 6. 返回包含数据权限字段的 CustomUserDetails
    return new CustomUserDetails(
        user.getId(),
        user.getUsername(),
        user.getPassword(),
        user.getRealName(),
        user.getStatus(),
        user.getDepartmentId(),
        user.getOrgLevel() != null ? user.getOrgLevel() : 4,
        user.getPositionId(),
        roleCodes,
        authorities);
  }
}
