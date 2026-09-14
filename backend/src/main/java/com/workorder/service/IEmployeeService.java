package com.workorder.service;


import java.util.List;
import java.util.Map;


import com.workorder.common.result.Result;
import com.workorder.entity.User;

/** Employee Management Service Interface - Four-Tier Architecture */
public interface IEmployeeService {

  /**
   * Get paginated employee list with permission filtering Super Admin: All employees Dept Admin:
   * Only department members
   */
  Result<Map<String, Object>> getEmployeeList(
      Integer pageNum,
      Integer pageSize,
      String keyword,
      Integer deptId,
      Integer positionId,
      Integer orgLevel,
      User currentUser);

  /** Get employee by ID with permission check */
  Result<User> getEmployeeById(Long id, User currentUser);

  /** Create new employee (Super Admin only) */
  Result<User> createEmployee(User employee);

  /** Update employee with permission validation */
  Result<User> updateEmployee(Long id, User employee, User currentUser);

  /** Delete employee (soft delete) - Super Admin only */
  Result<Void> deleteEmployee(Long id);

  /** Toggle employee status (enable/disable) */
  Result<Void> toggleEmployeeStatus(Long id, Integer status, User currentUser);

  /**
   * 重置员工密码（企业级安全流程）
   *
   * <p>生成一次性重置令牌，返回令牌明文和过期时间。 用户通过重置链接（/reset-password?token=xxx）自行设置新密码。
   *
   * <p>安全策略： - 非超管不能重置超管密码 - 超管不能重置其他超管密码（只能重置自己） - 超管重置自己需要二次验证（operatorPassword 为超管当前密码） -
   * 普通管理员重置普通员工不需要二次验证 - 禁止重置自己的密码（超管除外，超管重置自己需要二次验证）
   *
   * @param id Employee ID（目标用户）
   * @param currentUser Current user（操作者）
   * @param operatorPassword 操作者当前密码（超管重置自己时必填，其他场景可空）
   * @return Result 包含 token、expiryMinutes、username、realName、resetLink
   */
  Result<Map<String, Object>> resetPassword(Long id, User currentUser, String operatorPassword);

  /** Assign roles to employee */
  Result<Void> assignRoles(Long id, List<Long> roleIds);

  /** Batch import employees from Excel */
  Result<Map<String, Object>> batchImport(List<User> employees);

  /** Export employee list with permission filtering */
  Result<List<User>> exportEmployees(User currentUser);

  /** Get all departments for dropdown */
  Result<List<Map<String, Object>>> getAllDepartments();

  /** Get all positions for dropdown */
  Result<List<Map<String, Object>>> getAllPositions();

  /** Get all roles for assignment */
  Result<List<Map<String, Object>>> getAllRoles();

  /** Get superior list (managers and above) */
  Result<List<User>> getSuperiors(List<Integer> orgLevel);
}
