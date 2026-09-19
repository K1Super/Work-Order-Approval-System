package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.constant.RoleConstants;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import com.workorder.service.IPasswordResetService;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 员工密码服务组件（EmployeePasswordService）单元测试。
 *
 * <p>覆盖：企业级密码重置安全规则（超管保护/二次验证）、令牌生成响应组装、随机强密码生成质量。
 */
@ExtendWith(MockitoExtension.class)
class EmployeePasswordServiceTest {

  @Mock private UserMapper userMapper;
  @Mock private BCryptPasswordEncoder passwordEncoder;
  @Mock private IPasswordResetService passwordResetService;

  private EmployeeAccessGuard accessGuard;
  private EmployeePasswordService service;

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

    service = new EmployeePasswordService();
    inject("userMapper", userMapper);
    inject("passwordEncoder", passwordEncoder);
    inject("passwordResetService", passwordResetService);
    inject("accessGuard", accessGuard);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = EmployeePasswordService.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private static User makeUser(Long id, String realName, Long deptId) {
    User u = new User();
    u.setId(id);
    u.setRealName(realName);
    u.setDepartmentId(deptId);
    return u;
  }

  private static Map<String, Object> resetTokenData(String token) {
    Map<String, Object> data = new HashMap<>();
    data.put("token", token);
    data.put("expiryMinutes", 15);
    data.put("expiryTime", "2026-09-19T18:00:00Z");
    data.put("username", "admin");
    data.put("realName", "管理员");
    return data;
  }

  @Nested
  @DisplayName("resetPassword - 安全规则")
  class ResetPasswordTests {

    @Test
    @DisplayName("部门管理员不能重置其他部门的员工")
    void dept_admin_cross_department_rejected() {
      User existing = makeUser(2L, "员工", 9L);
      when(userMapper.selectById(2L)).thenReturn(existing);
      User current = makeUser(3L, "总监", 5L);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_RD_DIR));

      Result<Map<String, Object>> r = service.resetPassword(2L, current, null);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("不能重置其他部门");
      verify(passwordResetService, never()).generateResetToken(any(), any());
    }

    @Test
    @DisplayName("非超管不能重置超管密码")
    void non_super_admin_cannot_reset_super_admin() {
      User existing = makeUser(2L, "超管", 5L);
      when(userMapper.selectById(2L)).thenReturn(existing);
      when(userMapper.getUserRoleIds(2L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      User current = makeUser(3L, "专员", 5L);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_HR_SPEC));

      Result<Map<String, Object>> r = service.resetPassword(2L, current, null);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("不能重置超级管理员的密码");
    }

    @Test
    @DisplayName("超管不能重置其他超管的密码")
    void super_admin_cannot_reset_other_super_admin() {
      User existing = makeUser(2L, "超管B", 5L);
      when(userMapper.selectById(2L)).thenReturn(existing);
      when(userMapper.getUserRoleIds(2L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      User current = makeUser(1L, "超管A", 5L);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));

      Result<Map<String, Object>> r = service.resetPassword(2L, current, null);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("不能重置其他超级管理员的密码");
    }

    @Test
    @DisplayName("非超管禁止重置自己的密码")
    void non_super_admin_cannot_reset_self() {
      User current = makeUser(3L, "专员", 5L);
      when(userMapper.selectById(3L)).thenReturn(current);
      when(userMapper.getUserRoleIds(3L)).thenReturn(List.of(RoleConstants.ROLE_HR_SPEC));

      Result<Map<String, Object>> r = service.resetPassword(3L, current, null);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("不能重置自己的密码");
    }

    @Test
    @DisplayName("超管重置自己缺少操作密码被拒绝")
    void super_admin_self_reset_requires_operator_password() {
      User current = makeUser(1L, "超管", 5L);
      current.setPassword("hash");
      when(userMapper.selectById(1L)).thenReturn(current);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));

      Result<Map<String, Object>> r = service.resetPassword(1L, current, null);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("二次验证");
    }

    @Test
    @DisplayName("超管重置自己操作密码错误被拒绝")
    void super_admin_self_reset_wrong_operator_password() {
      User current = makeUser(1L, "超管", 5L);
      current.setPassword("hash");
      when(userMapper.selectById(1L)).thenReturn(current);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

      Result<Map<String, Object>> r = service.resetPassword(1L, current, "wrong");

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("二次验证失败");
    }

    @Test
    @DisplayName("超管重置自己二次验证通过且生成令牌")
    void super_admin_self_reset_success() {
      User current = makeUser(1L, "超管", 5L);
      current.setPassword("hash");
      when(userMapper.selectById(1L)).thenReturn(current);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));
      when(passwordEncoder.matches("opPass", "hash")).thenReturn(true);
      when(passwordResetService.generateResetToken(1L, 1L))
          .thenReturn(Result.success(resetTokenData("token-abc")));

      Result<Map<String, Object>> r = service.resetPassword(1L, current, "opPass");

      assertThat(r.isSuccess()).isTrue();
      Map<String, Object> data = r.getData();
      assertThat(data.get("token")).isEqualTo("token-abc");
      assertThat(data.get("resetLink")).isEqualTo("/reset-password?token=token-abc");
      assertThat(data.get("userId")).isEqualTo(1L);
      verify(passwordResetService).generateResetToken(1L, 1L);
    }
  }

  @Nested
  @DisplayName("generateStrongPassword - 强密码生成")
  class GenerateStrongPasswordTests {

    @Test
    @DisplayName("长度 8 且包含大写/小写/数字/特殊字符")
    void length_8_with_all_character_classes() {
      String pwd = service.generateStrongPassword(8);
      assertThat(pwd).hasSize(8);
      assertThat(pwd).matches(".*[A-Z].*");
      assertThat(pwd).matches(".*[a-z].*");
      assertThat(pwd).matches(".*\\d.*");
      assertThat(pwd).matches(".*[!@#$%^&*].*");
    }

    @Test
    @DisplayName("指定长度 12 时生成 12 位")
    void length_12_generated() {
      String pwd = service.generateStrongPassword(12);
      assertThat(pwd).hasSize(12);
    }
  }
}