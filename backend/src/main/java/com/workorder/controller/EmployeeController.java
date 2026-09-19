package com.workorder.controller;


import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.annotation.RequireReAuth;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.ResignedEmployee;
import com.workorder.entity.User;
import com.workorder.security.CustomUserDetails;
import com.workorder.service.IEmployeeExportService;
import com.workorder.service.IEmployeeService;
import com.workorder.service.IResignedEmployeeService;

/**
 * Employee Management Controller - Four-Tier Architecture
 *
 * <p>职责：纯路由层，所有业务逻辑委托给 Service 层。
 *
 * <p>Permission Matrix: - Super Admin (ROLE_SUPER_ADMIN): Full CRUD for all employees - Dept Admin
 * (ROLE_DEPT_ADMIN): CRUD only for department members - Decision/Manager/Functional: View only (no
 * management) - Employee: No access
 */
@RestController
@RequestMapping("/employees")
public class EmployeeController {

  @Autowired private IEmployeeService employeeService;

  @Autowired private UserMapper userMapper;

  @Autowired private IResignedEmployeeService resignedEmployeeService;

  @Autowired private IEmployeeExportService exportService;

  @Autowired private Environment environment;

  // ==================== CRUD 接口 ====================

  @GetMapping("/list")
  @PreAuthorize("hasAuthority('system:user') or hasAuthority('workorder:view-all')")
  public Result<Map<String, Object>> getEmployeeList(
      @RequestParam(defaultValue = "1") Integer pageNum,
      @RequestParam(defaultValue = "10") Integer pageSize,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer deptId,
      @RequestParam(required = false) Integer positionId,
      @RequestParam(required = false) Integer orgLevel,
      Authentication authentication) {

    User currentUser = getCurrentUser(authentication);
    return employeeService.getEmployeeList(
        pageNum, pageSize, keyword, deptId, positionId, orgLevel, currentUser);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('system:user') or hasAuthority('workorder:view-all')")
  public Result<User> getEmployeeDetail(@PathVariable Long id, Authentication authentication) {
    User currentUser = getCurrentUser(authentication);
    return employeeService.getEmployeeById(id, currentUser);
  }

  @PostMapping
  @PreAuthorize("hasAuthority('system:user')")
  public Result<User> createEmployee(@RequestBody User employee) {
    return employeeService.createEmployee(employee);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('system:user')")
  public Result<User> updateEmployee(
      @PathVariable Long id, @RequestBody User employee, Authentication authentication) {
    User currentUser = getCurrentUser(authentication);
    return employeeService.updateEmployee(id, employee, currentUser);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @RequireReAuth(description = "删除员工")
  public Result<Void> deleteEmployee(@PathVariable Long id) {
    assertMaintenanceAllowed();
    return employeeService.deleteEmployee(id);
  }

  @PutMapping("/{id}/status")
  @PreAuthorize("hasAuthority('system:user')")
  public Result<Void> toggleEmployeeStatus(
      @PathVariable Long id, @RequestParam Integer status, Authentication authentication) {
    User currentUser = getCurrentUser(authentication);
    return employeeService.toggleEmployeeStatus(id, status, currentUser);
  }

  @PutMapping("/{id}/reset-password")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @RequireReAuth(description = "重置密码")
  public Result<Map<String, Object>> resetPassword(
      @PathVariable Long id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    assertMaintenanceAllowed();
    User currentUser = getCurrentUser(authentication);
    // 超管重置自己密码时的二次验证密码（其他场景可空）
    String operatorPassword = body != null ? body.get("operatorPassword") : null;
    return employeeService.resetPassword(id, currentUser, operatorPassword);
  }

  @PutMapping("/{id}/roles")
  @PreAuthorize("hasAuthority('system:user')")
  public Result<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
    return employeeService.assignRoles(id, roleIds);
  }

  @PostMapping("/batch-import")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<Map<String, Object>> batchImport(@RequestBody List<User> employees) {
    assertMaintenanceAllowed();
    return employeeService.batchImport(employees);
  }

  // ==================== 导出接口 ====================

  @GetMapping("/export")
  @PreAuthorize("hasAuthority('system:user')")
  public void exportEmployees(HttpServletResponse response, Authentication authentication)
      throws IOException {
    User currentUser = getCurrentUser(authentication);
    Result<List<User>> result = employeeService.exportEmployees(currentUser);

    if (result.getCode() != Result.SUCCESS || result.getData() == null) {
      response.setStatus(500);
      return;
    }
    exportService.exportToCsv(result.getData(), response);
  }

  // ==================== 下拉选项接口 ====================

  @GetMapping("/departments")
  @PreAuthorize("isAuthenticated()")
  public Result<List<Map<String, Object>>> getDepartments() {
    return employeeService.getAllDepartments();
  }

  @GetMapping("/positions")
  @PreAuthorize("isAuthenticated()")
  public Result<List<Map<String, Object>>> getPositions() {
    return employeeService.getAllPositions();
  }

  @GetMapping("/roles")
  @PreAuthorize("hasAuthority('system:user')")
  public Result<List<Map<String, Object>>> getRoles() {
    return employeeService.getAllRoles();
  }

  @GetMapping("/superiors")
  @PreAuthorize("isAuthenticated()")
  public Result<List<User>> getSuperiors(@RequestParam(required = false) List<Integer> orgLevel) {
    return employeeService.getSuperiors(orgLevel);
  }

  // ==================== 离职管理接口 ====================

  @PostMapping("/{id}/resign")
  @PreAuthorize("hasAuthority('system:user')")
  public Result<ResignedEmployee> resignEmployee(
      @PathVariable Long id,
      @RequestBody Map<String, Object> params,
      Authentication authentication) {

    Long operatorId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
    Integer resignType =
        params.get("resignType") != null
            ? Integer.parseInt(params.get("resignType").toString())
            : 1;
    String resignReason = (String) params.get("resignReason");
    String remark = (String) params.get("remark");

    return resignedEmployeeService.processResignation(
        id, resignType, resignReason, remark, operatorId);
  }

  @GetMapping("/resigned")
  @PreAuthorize("hasAuthority('system:user') or hasAuthority('workorder:view-all')")
  public Result<Map<String, Object>> getResignedList(
      @RequestParam(defaultValue = "1") Integer pageNum,
      @RequestParam(defaultValue = "10") Integer pageSize,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer departmentId,
      @RequestParam(required = false) Integer resignType) {
    return resignedEmployeeService.getResignedList(
        pageNum, pageSize, keyword, departmentId, resignType);
  }

  @GetMapping("/resigned/{id}")
  @PreAuthorize("hasAuthority('system:user') or hasAuthority('workorder:view-all')")
  public Result<ResignedEmployee> getResignedDetail(@PathVariable Long id) {
    return resignedEmployeeService.getResignedById(id);
  }

  @GetMapping("/resign-types")
  @PreAuthorize("isAuthenticated()")
  public Result<List<Map<String, Object>>> getResignTypes() {
    return resignedEmployeeService.getResignTypes();
  }

  // ==================== 工具方法 ====================

  /**
   * W-11：维护类接口生产禁用开关（等价 @Profile("dev") 的单端点守卫）
   *
   * <p>删除员工/重置密码/批量导入属维护类管理端点，仅超级管理员（@PreAuthorize 已限定）且仅在 dev
   * 环境可用；非 dev（含 prod）环境直接拒绝，保证生产不可用。
   */
  private void assertMaintenanceAllowed() {
    if (!environment.acceptsProfiles("dev")) {
      throw new BusinessException(ErrorCode.ACCESS_DENIED, "维护接口仅开发环境可用");
    }
  }

  /** 从 Spring Security 认证信息获取当前用户实体 消除每个方法中重复的样板代码 */
  private User getCurrentUser(Authentication authentication) {
    CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
    return userMapper.selectById(userDetails.getUserId());
  }
}
