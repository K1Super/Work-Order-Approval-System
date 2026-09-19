package com.workorder.service.impl;


import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.workorder.common.result.Result;
import com.workorder.entity.User;
import com.workorder.service.IEmployeeService;

/**
 * 员工服务门面（薄门面）。
 *
 * <p>职责：实现 {@link IEmployeeService} 公开契约，各能力委托给职责单一的组件——
 * 查询 {@link EmployeeQueryService}、写操作 {@link EmployeeLifecycleOrchestrator}、
 * 密码 {@link EmployeePasswordService}、角色 {@link EmployeeRoleService}。
 * 事务边界由各组件的 {@code @Transactional} 声明，与拆分前 EmployeeServiceImpl 一致。
 *
 * @author KLord
 */
@Service
public class EmployeeServiceImpl implements IEmployeeService {

  @Autowired private EmployeeQueryService queryService;

  @Autowired private EmployeeLifecycleOrchestrator lifecycleOrchestrator;

  @Autowired private EmployeePasswordService passwordService;

  @Autowired private EmployeeRoleService roleService;

  @Override
  public Result<Map<String, Object>> getEmployeeList(
      Integer pageNum,
      Integer pageSize,
      String keyword,
      Integer deptId,
      Integer positionId,
      Integer orgLevel,
      User currentUser) {
    return queryService.getEmployeeList(pageNum, pageSize, keyword, deptId, positionId, orgLevel, currentUser);
  }

  @Override
  public Result<User> getEmployeeById(Long id, User currentUser) {
    return queryService.getEmployeeById(id, currentUser);
  }

  @Override
  public Result<User> createEmployee(User employee) {
    return lifecycleOrchestrator.createEmployee(employee);
  }

  @Override
  public Result<User> updateEmployee(Long id, User employee, User currentUser) {
    return lifecycleOrchestrator.updateEmployee(id, employee, currentUser);
  }

  @Override
  public Result<Void> deleteEmployee(Long id) {
    return lifecycleOrchestrator.deleteEmployee(id);
  }

  @Override
  public Result<Void> toggleEmployeeStatus(Long id, Integer status, User currentUser) {
    return lifecycleOrchestrator.toggleEmployeeStatus(id, status, currentUser);
  }

  @Override
  public Result<Map<String, Object>> resetPassword(Long id, User currentUser, String operatorPassword) {
    return passwordService.resetPassword(id, currentUser, operatorPassword);
  }

  @Override
  public Result<Void> assignRoles(Long id, List<Long> roleIds) {
    return roleService.assignRoles(id, roleIds);
  }

  @Override
  public Result<Map<String, Object>> batchImport(List<User> employees) {
    return lifecycleOrchestrator.batchImport(employees);
  }

  @Override
  public Result<List<User>> exportEmployees(User currentUser) {
    return queryService.exportEmployees(currentUser);
  }

  @Override
  public Result<List<Map<String, Object>>> getAllDepartments() {
    return queryService.getAllDepartments();
  }

  @Override
  public Result<List<Map<String, Object>>> getAllPositions() {
    return queryService.getAllPositions();
  }

  @Override
  public Result<List<Map<String, Object>>> getAllRoles() {
    return queryService.getAllRoles();
  }

  @Override
  public Result<List<User>> getSuperiors(List<Integer> orgLevel) {
    return queryService.getSuperiors(orgLevel);
  }
}