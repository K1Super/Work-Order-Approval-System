package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.workorder.common.constant.RoleConstants;
import com.workorder.dao.UserMapper;
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
 * 员工访问控制组件（EmployeeAccessGuard）单元测试。
 *
 * <p>覆盖：部门管理员/超管判定（阶段 2 C-09/H-09 RoleConstants 统一映射）、最高角色展示、跨部门访问拦截。
 */
@ExtendWith(MockitoExtension.class)
class EmployeeAccessGuardTest {

  @Mock private UserMapper userMapper;

  private EmployeeAccessGuard guard;

  @BeforeEach
  void setUp() {
    guard = new EmployeeAccessGuard();
    try {
      var field = EmployeeAccessGuard.class.getDeclaredField("userMapper");
      field.setAccessible(true);
      field.set(guard, userMapper);
    } catch (Exception e) {
      throw new RuntimeException("注入 userMapper 失败", e);
    }
  }

  private static User makeUser(Long id, Long deptId) {
    User u = new User();
    u.setId(id);
    u.setDepartmentId(deptId);
    return u;
  }

  @Nested
  @DisplayName("isDeptAdmin - 部门管理员判定")
  class IsDeptAdminTests {

    @Test
    @DisplayName("超级管理员不视为部门管理员（全局管理员双保险短路）")
    void super_admin_is_not_dept_admin() {
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      assertThat(guard.isDeptAdmin(makeUser(1L, 5L))).isFalse();
    }

    @Test
    @DisplayName("总监级角色视为部门管理员")
    void director_is_dept_admin() {
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));
      assertThat(guard.isDeptAdmin(makeUser(3L, 5L))).isTrue();
    }

    @Test
    @DisplayName("普通员工不是部门管理员")
    void staff_is_not_dept_admin() {
      when(userMapper.getUserRoleIds(4L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      assertThat(guard.isDeptAdmin(makeUser(4L, 5L))).isFalse();
    }

    @Test
    @DisplayName("无角色返回 false")
    void null_roles_not_dept_admin() {
      when(userMapper.getUserRoleIds(4L)).thenReturn(null);
      assertThat(guard.isDeptAdmin(makeUser(4L, 5L))).isFalse();
    }

    @Test
    @DisplayName("用户或 id 为 null 返回 false")
    void empty_user_not_dept_admin() {
      assertThat(guard.isDeptAdmin(null)).isFalse();
      assertThat(guard.isDeptAdmin(makeUser(null, 5L))).isFalse();
    }
  }

  @Nested
  @DisplayName("isSuperAdmin - 超管判定")
  class IsSuperAdminTests {

    @Test
    @DisplayName("拥有 ROLE_SUPER_ADMIN 返回 true")
    void super_admin_role_true() {
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      assertThat(guard.isSuperAdmin(makeUser(1L, null))).isTrue();
    }

    @Test
    @DisplayName("普通角色返回 false")
    void staff_role_false() {
      when(userMapper.getUserRoleIds(4L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      assertThat(guard.isSuperAdmin(makeUser(4L, 5L))).isFalse();
    }

    @Test
    @DisplayName("用户为 null 返回 false")
    void null_user_false() {
      assertThat(guard.isSuperAdmin(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("getHighestUserRole - 最高角色映射")
  class HighestUserRoleTests {

    @Test
    @DisplayName("超管返回 SUPER_ADMIN")
    void super_admin_maps_to_code() {
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      assertThat(guard.getHighestUserRole(makeUser(1L, 5L))).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("总监返回 DIRECTOR")
    void director_maps_to_code() {
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));
      assertThat(guard.getHighestUserRole(makeUser(3L, 5L))).isEqualTo("DIRECTOR");
    }

    @Test
    @DisplayName("专员返回 FUNCTIONAL")
    void specialist_maps_to_code() {
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_HR_SPEC));
      assertThat(guard.getHighestUserRole(makeUser(3L, 5L))).isEqualTo("FUNCTIONAL");
    }

    @Test
    @DisplayName("普通员工或无角色返回 EMPLOYEE")
    void staff_or_empty_maps_to_employee() {
      when(userMapper.getUserRoleIds(4L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      assertThat(guard.getHighestUserRole(makeUser(4L, 5L))).isEqualTo("EMPLOYEE");
      when(userMapper.getUserRoleIds(5L)).thenReturn(null);
      assertThat(guard.getHighestUserRole(makeUser(5L, 5L))).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("高优先角色胜出（兼任专员与总监 → DIRECTOR）")
    void highest_level_wins() {
      when(userMapper.getUserRoleIds(3L))
          .thenReturn(List.of(RoleConstants.ROLE_HR_SPEC, RoleConstants.ROLE_RD_DIR));
      assertThat(guard.getHighestUserRole(makeUser(3L, 5L))).isEqualTo("DIRECTOR");
    }
  }

  @Nested
  @DisplayName("isCrossDepartmentAccess - 跨部门拦截")
  class CrossDepartmentTests {

    @Test
    @DisplayName("部门管理员访问不同部门 → 拦截")
    void dept_admin_different_dept_blocked() {
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));
      assertThat(guard.isCrossDepartmentAccess(makeUser(3L, 5L), 9L)).isTrue();
    }

    @Test
    @DisplayName("部门管理员访问本部门 → 放行")
    void dept_admin_same_dept_allowed() {
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));
      assertThat(guard.isCrossDepartmentAccess(makeUser(3L, 5L), 5L)).isFalse();
    }

    @Test
    @DisplayName("非部门管理员永不拦截")
    void non_dept_admin_never_blocked() {
      when(userMapper.getUserRoleIds(4L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      assertThat(guard.isCrossDepartmentAccess(makeUser(4L, 5L), 9L)).isFalse();
      assertThat(guard.isCrossDepartmentAccess(makeUser(4L, 5L), null)).isFalse();
    }
  }
}