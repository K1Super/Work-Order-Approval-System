package com.workorder.service.impl;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;

/**
 * 员工查询服务组件。
 *
 * <p>职责：员工分页列表（含角色填充与部门数据隔离）、按 ID 详情、全量导出、字典查询（部门/职位/角色）、上级列表。
 * 部门数据隔离判定委托 {@link EmployeeAccessGuard}。
 *
 * @author KLord
 */
@Component
public class EmployeeQueryService {

  private static final Logger logger = LoggerFactory.getLogger(EmployeeQueryService.class);

  /** 导出全量数据的分页大小 */
  private static final int EXPORT_PAGE_SIZE = 10000;

  @Autowired private UserMapper userMapper;

  @Autowired private EmployeeAccessGuard accessGuard;

  /**
   * 分页查询员工列表（含权限过滤与角色填充）。
   *
   * @param pageNum 页码
   * @param pageSize 每页条数
   * @param keyword 关键字（姓名/用户名模糊）
   * @param deptId 部门 ID 过滤
   * @param positionId 职位 ID 过滤
   * @param orgLevel 组织层级过滤
   * @param currentUser 当前用户（部门数据隔离依据）
   * @return 分页结果与当前用户最高角色
   */
  public Result<Map<String, Object>> getEmployeeList(
      Integer pageNum,
      Integer pageSize,
      String keyword,
      Integer deptId,
      Integer positionId,
      Integer orgLevel,
      User currentUser) {
    // Build query parameters with permission filtering
    Map<String, Object> params = new HashMap<>();
    params.put("pageNum", pageNum);
    params.put("pageSize", pageSize);

    // Apply permission-based data isolation
    if (accessGuard.isDeptAdmin(currentUser)) {
      // Department admin can only see their department members
      params.put("departmentId", currentUser.getDepartmentId());
      logger.info(
          "Department admin {} filtering by department: {}",
          currentUser.getRealName(),
          currentUser.getDepartmentId());
    }

    if (keyword != null && !keyword.isEmpty()) {
      params.put("keyword", "%" + keyword + "%");
    }
    if (deptId != null && !accessGuard.isDeptAdmin(currentUser)) {
      params.put("departmentId", deptId);
    }
    if (positionId != null) {
      params.put("positionId", positionId);
    }
    if (orgLevel != null) {
      params.put("orgLevel", orgLevel);
    }

    try {
      logger.info("=== Starting getEmployeeList ===");
      logger.info("Params: pageNum={}, pageSize={}, keyword={}", pageNum, pageSize, keyword);

      List<User> list = userMapper.selectList(params);
      logger.info("Query returned {} users", list.size());

      int total = userMapper.countTotal(params);
      logger.info("Total count: {}", total);

      // 为每个员工填充角色信息
      for (User employee : list) {
        try {
          List<Long> roleIds = userMapper.getUserRoleIds(employee.getId());
          employee.setRoleIds(roleIds);

          // 获取角色名称
          if (roleIds != null && !roleIds.isEmpty()) {
            List<String> roleNames = userMapper.getUserRoleNames(roleIds);
            employee.setRoleNames(roleNames);
          }
        } catch (Exception ex) {
          logger.warn("Error loading roles for user {}: {}", employee.getId(), ex.getMessage());
        }
      }

      Map<String, Object> data = new HashMap<>();
      data.put("list", list);
      data.put("total", total);
      data.put("currentUserRole", accessGuard.getHighestUserRole(currentUser));

      return Result.success(data);
    } catch (Exception e) {
      logger.error("Error in getEmployeeList: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to load employees: " + e.getMessage(), e);
    }
  }

  /**
   * 按 ID 查询员工（部门管理员仅可访问本部门员工）。
   *
   * @param id 员工 ID
   * @param currentUser 当前用户
   * @return 员工信息
   */
  public Result<User> getEmployeeById(Long id, User currentUser) {
    User employee = userMapper.selectById(id);
    if (employee == null) {
      return Result.error("Employee not found");
    }

    // Permission check for department admin
    if (accessGuard.isCrossDepartmentAccess(currentUser, employee.getDepartmentId())) {
      logger.warn(
          "Dept admin {} tried to access user from different department: {}",
          currentUser.getRealName(),
          id);
      return Result.error("Access denied: Cannot access employees from other departments");
    }

    return Result.success(employee);
  }

  /**
   * 导出员工列表（部门管理员仅导出本部门）。
   *
   * @param currentUser 当前用户
   * @return 员工列表
   */
  public Result<List<User>> exportEmployees(User currentUser) {
    Map<String, Object> params = new HashMap<>();
    params.put("pageNum", 1);
    params.put("pageSize", EXPORT_PAGE_SIZE); // Export all

    // Apply permission filter for dept admin
    if (accessGuard.isDeptAdmin(currentUser)) {
      params.put("departmentId", currentUser.getDepartmentId());
    }

    List<User> list = userMapper.selectList(params);
    return Result.success(list);
  }

  /** 查询全部分部门（下拉框） */
  public Result<List<Map<String, Object>>> getAllDepartments() {
    return Result.success(userMapper.selectAllDepartments());
  }

  /** 查询全部职位（下拉框） */
  public Result<List<Map<String, Object>>> getAllPositions() {
    return Result.success(userMapper.selectAllPositions());
  }

  /** 查询全部角色（下拉框） */
  public Result<List<Map<String, Object>>> getAllRoles() {
    return Result.success(userMapper.selectAllRoles());
  }

  /**
   * 查询上级列表（管理层及以上的用户）。
   *
   * @param orgLevel 指定组织层级过滤；为空时默认查询 org_level &lt;= 2（经理及以上）
   * @return 上级用户列表
   */
  public Result<List<User>> getSuperiors(List<Integer> orgLevel) {
    List<User> superiors;
    if (orgLevel != null && !orgLevel.isEmpty()) {
      superiors = userMapper.selectByOrgLevels(orgLevel);
    } else {
      // Default: get all users with org_level <= 2 (managers and above)
      superiors = userMapper.selectManagers(2);
    }
    return Result.success(superiors);
  }
}