package com.workorder.service.impl;

import static org.mockito.Mockito.verify;

import com.workorder.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 工单门面（EmployeeServiceImpl）委托单元测试。
 *
 * <p>验证薄门面纯委托：所有 {@link com.workorder.service.IEmployeeService} 方法原样转发至对应组件。
 * 业务逻辑由组件测试覆盖（QueryService / LifecycleOrchestrator / PasswordService / RoleService / AccessGuard）。
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceImplTest {

  @Mock private EmployeeQueryService queryService;
  @Mock private EmployeeLifecycleOrchestrator lifecycleOrchestrator;
  @Mock private EmployeePasswordService passwordService;
  @Mock private EmployeeRoleService roleService;

  private EmployeeServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new EmployeeServiceImpl();
    inject("queryService", queryService);
    inject("lifecycleOrchestrator", lifecycleOrchestrator);
    inject("passwordService", passwordService);
    inject("roleService", roleService);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = EmployeeServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private static User makeUser(Long id) {
    User u = new User();
    u.setId(id);
    return u;
  }

  @Nested
  @DisplayName("查询类方法委托 QueryService")
  class QueryDelegationTests {

    @Test
    @DisplayName("getEmployeeList 委托")
    void list_delegates() {
      User current = makeUser(99L);
      service.getEmployeeList(1, 10, "张", 2, 3, 4, current);
      verify(queryService).getEmployeeList(1, 10, "张", 2, 3, 4, current);
    }

    @Test
    @DisplayName("getEmployeeById 委托")
    void by_id_delegates() {
      User current = makeUser(99L);
      service.getEmployeeById(7L, current);
      verify(queryService).getEmployeeById(7L, current);
    }

    @Test
    @DisplayName("exportEmployees 委托")
    void export_delegates() {
      User current = makeUser(99L);
      service.exportEmployees(current);
      verify(queryService).exportEmployees(current);
    }

    @Test
    @DisplayName("字典查询委托")
    void dictionary_delegates() {
      service.getAllDepartments();
      service.getAllPositions();
      service.getAllRoles();
      verify(queryService).getAllDepartments();
      verify(queryService).getAllPositions();
      verify(queryService).getAllRoles();
    }

    @Test
    @DisplayName("getSuperiors 委托")
    void superiors_delegates() {
      List<Integer> orgLevels = List.of(1, 2);
      service.getSuperiors(orgLevels);
      verify(queryService).getSuperiors(orgLevels);
    }
  }

  @Nested
  @DisplayName("写操作委托 LifecycleOrchestrator")
  class LifecycleDelegationTests {

    @Test
    @DisplayName("createEmployee 委托")
    void create_delegates() {
      User employee = makeUser(1L);
      service.createEmployee(employee);
      verify(lifecycleOrchestrator).createEmployee(employee);
    }

    @Test
    @DisplayName("updateEmployee 委托")
    void update_delegates() {
      User employee = makeUser(2L);
      User current = makeUser(99L);
      service.updateEmployee(2L, employee, current);
      verify(lifecycleOrchestrator).updateEmployee(2L, employee, current);
    }

    @Test
    @DisplayName("deleteEmployee 委托")
    void delete_delegates() {
      service.deleteEmployee(2L);
      verify(lifecycleOrchestrator).deleteEmployee(2L);
    }

    @Test
    @DisplayName("toggleEmployeeStatus 委托")
    void toggle_delegates() {
      User current = makeUser(99L);
      service.toggleEmployeeStatus(2L, 0, current);
      verify(lifecycleOrchestrator).toggleEmployeeStatus(2L, 0, current);
    }

    @Test
    @DisplayName("batchImport 委托")
    void batch_import_delegates() {
      List<User> employees = List.of(makeUser(1L));
      service.batchImport(employees);
      verify(lifecycleOrchestrator).batchImport(employees);
    }
  }

  @Nested
  @DisplayName("密码与角色方法委托对应组件")
  class PasswordAndRoleDelegationTests {

    @Test
    @DisplayName("resetPassword 委托 PasswordService")
    void reset_password_delegates() {
      User current = makeUser(99L);
      service.resetPassword(2L, current, "opPass");
      verify(passwordService).resetPassword(2L, current, "opPass");
    }

    @Test
    @DisplayName("assignRoles 委托 RoleService")
    void assign_roles_delegates() {
      List<Long> roleIds = List.of(20L);
      service.assignRoles(2L, roleIds);
      verify(roleService).assignRoles(2L, roleIds);
    }
  }
}