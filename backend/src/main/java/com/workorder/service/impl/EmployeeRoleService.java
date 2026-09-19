package com.workorder.service.impl;


import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;

/**
 * 员工角色服务组件。
 *
 * <p>职责：角色分配（全量替换）与基于最高权限角色自动计算组织层级（org_level）。
 *
 * <p>事务边界：{@code assignRoles} 保留 {@code @Transactional}（与拆分前 EmployeeServiceImpl 一致）。
 *
 * @author KLord
 */
@Component
public class EmployeeRoleService {

  private static final Logger logger = LoggerFactory.getLogger(EmployeeRoleService.class);

  /** 默认组织层级（基层员工） */
  private static final int DEFAULT_ORG_LEVEL = 4;

  @Autowired private UserMapper userMapper;

  /**
   * 给员工分配角色（删除旧角色后全量插入新角色）。
   *
   * <p>分配后根据最高权限角色自动计算并更新组织层级。
   *
   * @param id 员工 ID
   * @param roleIds 角色 ID 列表（全量替换）
   * @return 操作结果
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<Void> assignRoles(Long id, List<Long> roleIds) {
    User existing = userMapper.selectById(id);
    if (existing == null) {
      return Result.error("Employee not found");
    }

    // Delete existing role assignments
    userMapper.deleteUserRoles(id);

    // Insert new role assignments
    for (Long roleId : roleIds) {
      userMapper.insertUserRole(id, roleId);
    }

    // 自动计算并更新组织层级（根据最高权限角色）
    if (!roleIds.isEmpty()) {
      Integer newOrgLevel = calculateOrgLevelFromRoles(roleIds);
      if (newOrgLevel != null) {
        User update = new User();
        update.setId(id);
        update.setOrgLevel(newOrgLevel);
        userMapper.updateById(update);
        logger.info("用户 {} 的组织层级已自动更新为: {}", id, newOrgLevel);
      }
    }

    logger.info("权限分配成功: 用户={}, 角色列表={}", existing.getRealName(), roleIds);
    return Result.success(null);
  }

  /**
   * 根据角色 ID 列表计算最高的组织层级（组织层级值越小，权限越高）。
   *
   * @param roleIds 角色 ID 列表
   * @return 最高的组织层级（最小值）
   */
  private Integer calculateOrgLevelFromRoles(List<Long> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      return DEFAULT_ORG_LEVEL;
    }

    try {
      // 查询这些角色的 org_level
      List<Map<String, Object>> roles = userMapper.selectRolesByIds(roleIds);

      int minOrgLevel = 10; // 初始值设大
      for (Map<String, Object> role : roles) {
        Object orgLevelObj = role.get("org_level");
        if (orgLevelObj != null) {
          int level = ((Number) orgLevelObj).intValue();
          if (level < minOrgLevel) {
            minOrgLevel = level;
          }
        }
      }

      // 如果没有有效数据，返回默认值
      return minOrgLevel <= 9 ? minOrgLevel : DEFAULT_ORG_LEVEL;
    } catch (Exception e) {
      logger.warn("计算组织层级失败: {}", e.getMessage());
      return DEFAULT_ORG_LEVEL;
    }
  }
}