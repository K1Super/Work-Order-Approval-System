package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.constant.RoleConstants;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 员工查询服务组件（EmployeeQueryService）单元测试。
 *
 * <p>覆盖：列表部门数据隔离/关键字过滤/角色填充、详情的部门管理员拦截、导出过滤、字典与上级查询。
 */
@ExtendWith(MockitoExtension.class)
class EmployeeQueryServiceTest {

  @Mock private UserMapper userMapper;

  private EmployeeAccessGuard accessGuard;
  private EmployeeQueryService service;

  @BeforeEach
  void setUp() {
    accessGuard = new EmployeeAccessGuard();
    try {
      var guardField = EmployeeAccessGuard.class.getDeclaredField("userMapper");
      guardField.setAccessible(true);
      guardField.set(accessGuard, userMapper);
    } catch (Exception e) {
      throw new RuntimeException("注入 guard userMapper 失败", e);
    }

    service = new EmployeeQueryService();
    inject("userMapper", userMapper);
    inject("accessGuard", accessGuard);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = EmployeeQueryService.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private static User makeUser(Long id, String name, Long deptId) {
    User u = new User();
    u.setId(id);
    u.setRealName(name);
    u.setDepartmentId(deptId);
    return u;
  }

  @Nested
  @DisplayName("getEmployeeList - 列表与数据隔离")
  class GetEmployeeListTests {

    @Test
    @DisplayName("部门管理员仅能查看本部门员工")
    void dept_admin_list_filtered_by_department() {
      User current = makeUser(3L, "总监", 5L);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));
      User employee = makeUser(2L, "员工", 5L);
      when(userMapper.selectList(anyMap())).thenReturn(List.of(employee));
      when(userMapper.countTotal(anyMap())).thenReturn(1);
      when(userMapper.getUserRoleIds(2L)).thenReturn(null);

      Result<Map<String, Object>> r = service.getEmployeeList(1, 10, null, null, null, null, current);

      assertThat(r.isSuccess()).isTrue();
      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(userMapper).selectList(captor.capture());
      Map<String, Object> params = captor.getValue();
      assertThat(params).containsEntry("departmentId", 5L);
      assertThat(((List<?>) r.getData().get("list"))).hasSize(1);
      assertThat(r.getData().get("total")).isEqualTo(1);
      assertThat(r.getData().get("currentUserRole")).isEqualTo("DIRECTOR");
    }

    @Test
    @DisplayName("超管查看全量（不注入部门过滤）")
    void super_admin_list_without_department_filter() {
      User current = makeUser(1L, "超管", 5L);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      when(userMapper.selectList(anyMap())).thenReturn(Collections.emptyList());
      when(userMapper.countTotal(anyMap())).thenReturn(0);

      service.getEmployeeList(1, 10, null, null, null, null, current);

      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(userMapper).selectList(captor.capture());
      assertThat(captor.getValue()).doesNotContainKey("departmentId");
    }

    @Test
    @DisplayName("关键字/部门/职位/层级过滤条件转发")
    void filters_forwarded_to_mapper() {
      User current = makeUser(1L, "超管", 5L);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      when(userMapper.selectList(anyMap())).thenReturn(Collections.emptyList());
      when(userMapper.countTotal(anyMap())).thenReturn(0);

      service.getEmployeeList(2, 20, "张", 6, 8, 4, current);

      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(userMapper).selectList(captor.capture());
      Map<String, Object> params = captor.getValue();
      assertThat(params).containsEntry("pageNum", 2);
      assertThat(params).containsEntry("pageSize", 20);
      assertThat(params).containsEntry("keyword", "%张%");
      assertThat(params).containsEntry("departmentId", 6);
      assertThat(params).containsEntry("positionId", 8);
      assertThat(params).containsEntry("orgLevel", 4);
    }
  }

  @Nested
  @DisplayName("getEmployeeById - 详情与部门拦截")
  class GetEmployeeByIdTests {

    @Test
    @DisplayName("部门管理员访问其他部门员工被拒绝")
    void dept_admin_cross_department_rejected() {
      User employee = makeUser(2L, "员工", 9L);
      when(userMapper.selectById(2L)).thenReturn(employee);
      User current = makeUser(3L, "总监", 5L);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));

      Result<User> r = service.getEmployeeById(2L, current);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("Access denied");
    }

    @Test
    @DisplayName("部门管理员访问本部门员工放行")
    void dept_admin_same_department_allowed() {
      User employee = makeUser(2L, "员工", 5L);
      when(userMapper.selectById(2L)).thenReturn(employee);
      User current = makeUser(3L, "总监", 5L);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));

      Result<User> r = service.getEmployeeById(2L, current);

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).isSameAs(employee);
    }
  }

  @Nested
  @DisplayName("exportEmployees / 字典 / 上级")
  class ExportAndLookupTests {

    @Test
    @DisplayName("部门管理员导出仅本部门")
    void dept_admin_export_filtered_by_department() {
      User current = makeUser(3L, "总监", 5L);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));
      when(userMapper.selectList(anyMap())).thenReturn(Collections.emptyList());

      service.exportEmployees(current);

      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(userMapper).selectList(captor.capture());
      Map<String, Object> params = captor.getValue();
      assertThat(params).containsEntry("departmentId", 5L);
      // 导出全量（单页上限 1000*10）
      assertThat(params).containsEntry("pageSize", 1000 * 10);
    }

    @Test
    @DisplayName("超管导出全量不过滤部门")
    void super_admin_export_without_filter() {
      User current = makeUser(1L, "超管", 5L);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      when(userMapper.selectList(anyMap())).thenReturn(Collections.emptyList());

      service.exportEmployees(current);

      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(userMapper).selectList(captor.capture());
      assertThat(captor.getValue()).doesNotContainKey("departmentId");
    }

    @Test
    @DisplayName("上级列表：未指定层级默认查询管理层")
    void superiors_default_managers() {
      when(userMapper.selectManagers(2)).thenReturn(Collections.emptyList());
      Result<List<User>> r = service.getSuperiors(null);
      assertThat(r.isSuccess()).isTrue();
      verify(userMapper).selectManagers(2);
    }

    @Test
    @DisplayName("上级列表：按指定组织层级查询")
    void superiors_by_org_levels() {
      List<Integer> orgLevels = List.of(1, 2);
      when(userMapper.selectByOrgLevels(orgLevels)).thenReturn(Collections.emptyList());
      Result<List<User>> r = service.getSuperiors(orgLevels);
      assertThat(r.isSuccess()).isTrue();
      verify(userMapper).selectByOrgLevels(orgLevels);
    }

    @Test
    @DisplayName("字典查询直接透传")
    void dictionary_lookup_passthrough() {
      when(userMapper.selectAllDepartments()).thenReturn(Collections.emptyList());
      when(userMapper.selectAllPositions()).thenReturn(Collections.emptyList());
      when(userMapper.selectAllRoles()).thenReturn(Collections.emptyList());
      assertThat(service.getAllDepartments().isSuccess()).isTrue();
      assertThat(service.getAllPositions().isSuccess()).isTrue();
      assertThat(service.getAllRoles().isSuccess()).isTrue();
    }
  }
}