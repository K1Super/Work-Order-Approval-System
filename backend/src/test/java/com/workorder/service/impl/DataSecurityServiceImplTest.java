package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.common.constant.RoleConstants;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DataSecurityService 单元测试：数据权限过滤、所有权/部门范围/超管放行判断。
 */
@ExtendWith(MockitoExtension.class)
class DataSecurityServiceImplTest {

  @Mock private UserMapper userMapper;
  @Mock private WorkOrderMapper workOrderMapper;
  @Mock private ObjectMapper objectMapper;

  private DataSecurityServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new DataSecurityServiceImpl();
    inject("userMapper", userMapper);
    inject("workOrderMapper", workOrderMapper);
    inject("objectMapper", objectMapper);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = DataSecurityServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject " + fieldName, e);
    }
  }

  private static User makeUser(Long id, Long deptId) {
    User u = new User();
    u.setId(id);
    u.setUsername("user" + id);
    u.setRealName("用户" + id);
    u.setDepartmentId(deptId);
    return u;
  }

  private static WorkOrder makeWorkOrder(Long id, Long applicantId, Long deptId) {
    WorkOrder wo = new WorkOrder();
    wo.setId(id);
    wo.setApplicantId(applicantId);
    wo.setDepartmentId(deptId);
    return wo;
  }

  private void givenRoles(Long userId, List<Long> roleIds) {
    when(userMapper.getUserRoleIds(userId)).thenReturn(roleIds);
  }

  @Nested
  @DisplayName("applyDataFilter - 按组织层级过滤")
  class ApplyDataFilterTests {

    @Test
    @DisplayName("无用户上下文直接透传")
    void null_user_passthrough() {
      Map<String, Object> params = new HashMap<>();
      params.put("k", "v");
      assertThat(service.applyDataFilter(params, null)).isSameAs(params);
    }

    @Test
    @DisplayName("超管不过滤")
    void super_admin_no_filter() {
      User u = makeUser(1L, null);
      givenRoles(1L, List.of(RoleConstants.ROLE_SUPER_ADMIN));
      Map<String, Object> params = new HashMap<>();

      Map<String, Object> r = service.applyDataFilter(params, u);

      assertThat(r).doesNotContainKey("decisionLayerAccess");
      assertThat(r).doesNotContainKey("departmentId");
      assertThat(r).doesNotContainKey("ownDataOnly");
    }

    @Test
    @DisplayName("决策层全公司范围")
    void decision_layer_company_wide() {
      User u = makeUser(2L, null);
      givenRoles(2L, List.of(RoleConstants.ROLE_CHAIRMAN));
      Map<String, Object> r = service.applyDataFilter(new HashMap<>(), u);
      assertThat(r).containsEntry("decisionLayerAccess", true);
    }

    @Test
    @DisplayName("管理层按部门过滤且允许跨部门概览")
    void management_layer_dept_filter() {
      User u = makeUser(3L, 5L);
      givenRoles(3L, List.of(RoleConstants.ROLE_RD_DIR));
      Map<String, Object> r = service.applyDataFilter(new HashMap<>(), u);
      assertThat(r).containsEntry("departmentId", 5L);
      assertThat(r).containsEntry("crossDeptAccess", true);
    }

    @Test
    @DisplayName("职能层按职能类型过滤")
    void functional_layer_type_filter() {
      User u = makeUser(4L, null);
      givenRoles(4L, List.of(RoleConstants.ROLE_HR_SPEC));
      Map<String, Object> r = service.applyDataFilter(new HashMap<>(), u);
      assertThat(r).containsEntry("functionalType", "HR");
      assertThat(r).containsEntry("companyWideFunctional", true);
    }

    @Test
    @DisplayName("普通员工仅可见本人数据")
    void employee_own_data_only() {
      User u = makeUser(5L, 5L);
      givenRoles(5L, List.of(RoleConstants.ROLE_STAFF));
      Map<String, Object> r = service.applyDataFilter(new HashMap<>(), u);
      assertThat(r).containsEntry("userId", 5L);
      assertThat(r).containsEntry("ownDataOnly", true);
    }
  }

  @Nested
  @DisplayName("canAccessData - 数据访问判断")
  class CanAccessDataTests {

    @Test
    @DisplayName("无用户或数据ID拒绝")
    void null_context_rejected() {
      assertThat(service.canAccessData(1L, "workorder", null)).isFalse();
      assertThat(service.canAccessData(null, "workorder", makeUser(1L, 1L))).isFalse();
    }

    @Test
    @DisplayName("超管放行")
    void super_admin_granted() {
      User u = makeUser(1L, null);
      givenRoles(1L, List.of(RoleConstants.ROLE_SUPER_ADMIN));
      assertThat(service.canAccessData(1L, "workorder", u)).isTrue();
    }

    @Test
    @DisplayName("决策层可访问全部工单")
    void decision_layer_granted() {
      User u = makeUser(2L, null);
      givenRoles(2L, List.of(RoleConstants.ROLE_CHAIRMAN));
      assertThat(service.canAccessData(99L, "workorder", u)).isTrue();
    }

    @Test
    @DisplayName("本人发起的工单可访问")
    void own_work_order_granted() {
      User u = makeUser(5L, 5L);
      givenRoles(5L, List.of(RoleConstants.ROLE_STAFF));
      when(workOrderMapper.selectById(1L)).thenReturn(makeWorkOrder(1L, 5L, 5L));
      assertThat(service.canAccessData(1L, "workorder", u)).isTrue();
    }

    @Test
    @DisplayName("非本人发起的工单拒绝")
    void others_work_order_denied() {
      User u = makeUser(5L, 5L);
      givenRoles(5L, List.of(RoleConstants.ROLE_STAFF));
      when(workOrderMapper.selectById(1L)).thenReturn(makeWorkOrder(1L, 9L, 5L));
      assertThat(service.canAccessData(1L, "workorder", u)).isFalse();
    }

    @Test
    @DisplayName("工单不存在时拒绝（fail-closed）")
    void nonexistent_work_order_denied() {
      User u = makeUser(5L, 5L);
      givenRoles(5L, List.of(RoleConstants.ROLE_STAFF));
      when(workOrderMapper.selectById(1L)).thenReturn(null);
      assertThat(service.canAccessData(1L, "workorder", u)).isFalse();
    }

    @Test
    @DisplayName("管理层可访问同部门员工数据")
    void management_same_dept_employee() {
      User u = makeUser(3L, 5L);
      givenRoles(3L, List.of(RoleConstants.ROLE_RD_DIR));
      User target = makeUser(10L, 5L);
      when(userMapper.selectById(10L)).thenReturn(target);
      assertThat(service.canAccessData(10L, "employee", u)).isTrue();
    }

    @Test
    @DisplayName("管理层访问跨部门员工数据拒绝")
    void management_cross_dept_employee_denied() {
      User u = makeUser(3L, 5L);
      givenRoles(3L, List.of(RoleConstants.ROLE_RD_DIR));
      User target = makeUser(10L, 9L);
      when(userMapper.selectById(10L)).thenReturn(target);
      assertThat(service.canAccessData(10L, "employee", u)).isFalse();
    }
  }

  @Nested
  @DisplayName("canViewSensitiveData / getAccessibleScope")
  class SensitiveAndScopeTests {

    @Test
    @DisplayName("无用户不可见敏感数据")
    void null_user_no_sensitive() {
      assertThat(service.canViewSensitiveData(null)).isFalse();
    }

    @Test
    @DisplayName("超管可见敏感数据")
    void super_admin_can_view_sensitive() {
      User u = makeUser(1L, null);
      givenRoles(1L, List.of(RoleConstants.ROLE_SUPER_ADMIN));
      assertThat(service.canViewSensitiveData(u)).isTrue();
    }

    @Test
    @DisplayName("财务角色可见敏感数据")
    void finance_can_view_sensitive() {
      User u = makeUser(4L, null);
      givenRoles(4L, List.of(RoleConstants.ROLE_ACCOUNTANT_SPEC));
      assertThat(service.canViewSensitiveData(u)).isTrue();
    }

    @Test
    @DisplayName("普通员工不可见敏感数据")
    void staff_cannot_view_sensitive() {
      User u = makeUser(5L, 5L);
      givenRoles(5L, List.of(RoleConstants.ROLE_STAFF));
      assertThat(service.canViewSensitiveData(u)).isFalse();
    }

    @Test
    @DisplayName("访问范围映射")
    void accessible_scope() {
      assertThat(service.getAccessibleScope(null)).isEqualTo("NONE");

      User admin = makeUser(1L, null);
      givenRoles(1L, List.of(RoleConstants.ROLE_SUPER_ADMIN));
      assertThat(service.getAccessibleScope(admin)).isEqualTo("FULL_ACCESS");

      User staff = makeUser(5L, 5L);
      givenRoles(5L, List.of(RoleConstants.ROLE_STAFF));
      assertThat(service.getAccessibleScope(staff)).isEqualTo("OWN_DATA_ONLY");
    }
  }
}