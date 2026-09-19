package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.constant.RoleConstants;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 员工角色服务组件（EmployeeRoleService）单元测试。
 *
 * <p>覆盖：角色全量替换分配、基于最高权限角色自动计算组织层级、默认层级回退。
 */
@ExtendWith(MockitoExtension.class)
class EmployeeRoleServiceTest {

  @Mock private UserMapper userMapper;

  private EmployeeRoleService service;

  @BeforeEach
  void setUp() {
    service = new EmployeeRoleService();
    try {
      var field = EmployeeRoleService.class.getDeclaredField("userMapper");
      field.setAccessible(true);
      field.set(service, userMapper);
    } catch (Exception e) {
      throw new RuntimeException("注入 userMapper 失败", e);
    }
  }

  private static Map<String, Object> roleWithLevel(Object orgLevel) {
    Map<String, Object> role = new HashMap<>();
    role.put("org_level", orgLevel);
    return role;
  }

  @Test
  @DisplayName("员工不存在时拒绝分配")
  void employee_not_found_rejected() {
    when(userMapper.selectById(9L)).thenReturn(null);

    Result<Void> r = service.assignRoles(9L, List.of(RoleConstants.ROLE_RD_DIR));

    assertThat(r.isSuccess()).isFalse();
    assertThat(r.getMsg()).contains("Employee not found");
    verify(userMapper, never()).deleteUserRoles(any());
  }

  @Test
  @DisplayName("分配角色后按最高权限角色自动更新组织层级")
  void assign_roles_updates_org_level_from_highest_role() {
    User existing = new User();
    existing.setId(2L);
    existing.setRealName("张三");
    when(userMapper.selectById(2L)).thenReturn(existing);
    when(userMapper.selectRolesByIds(List.of(RoleConstants.ROLE_RD_DIR)))
        .thenReturn(List.of(roleWithLevel(2)));

    Result<Void> r = service.assignRoles(2L, List.of(RoleConstants.ROLE_RD_DIR));

    assertThat(r.isSuccess()).isTrue();
    verify(userMapper).deleteUserRoles(2L);
    verify(userMapper).insertUserRole(2L, RoleConstants.ROLE_RD_DIR);
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userMapper).updateById(captor.capture());
    assertThat(captor.getValue().getId()).isEqualTo(2L);
    assertThat(captor.getValue().getOrgLevel()).isEqualTo(2);
  }

  @Test
  @DisplayName("角色列表为空时不更新组织层级")
  void empty_role_list_skips_org_level_update() {
    User existing = new User();
    existing.setId(2L);
    existing.setRealName("张三");
    when(userMapper.selectById(2L)).thenReturn(existing);

    Result<Void> r = service.assignRoles(2L, List.of());

    assertThat(r.isSuccess()).isTrue();
    verify(userMapper, never()).updateById(any(User.class));
  }

  @Test
  @DisplayName("角色无有效层级时回退默认层级 4")
  void invalid_level_falls_back_to_default() {
    User existing = new User();
    existing.setId(2L);
    existing.setRealName("张三");
    when(userMapper.selectById(2L)).thenReturn(existing);
    when(userMapper.selectRolesByIds(List.of(-1L)))
        .thenReturn(List.of(roleWithLevel(null)));

    Result<Void> r = service.assignRoles(2L, List.of(-1L));

    assertThat(r.isSuccess()).isTrue();
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userMapper).updateById(captor.capture());
    assertThat(captor.getValue().getOrgLevel()).isEqualTo(4);
  }
}