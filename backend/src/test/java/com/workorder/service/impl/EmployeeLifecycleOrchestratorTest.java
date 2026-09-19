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
import com.workorder.security.DekService;
import com.workorder.security.EncryptionHelper;
import com.workorder.security.TokenVersionCache;
import com.workorder.service.IPasswordPolicyService;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 员工生命周期编排器（EmployeeLifecycleOrchestrator）单元测试。
 *
 * <p>覆盖：创建（唯一工号 W-35/强密码/DEK）、更新白名单、删除超管保护、禁用自护与 tokenVersion 联动、批量导入容错。
 */
@ExtendWith(MockitoExtension.class)
class EmployeeLifecycleOrchestratorTest {

  @Mock private UserMapper userMapper;
  @Mock private BCryptPasswordEncoder passwordEncoder;
  @Mock private IPasswordPolicyService passwordPolicyService;
  @Mock private DekService dekService;
  @Mock private TokenVersionCache tokenVersionCache;
  @Mock private EmployeePasswordService passwordService;

  private EmployeeAccessGuard accessGuard;
  private EmployeeLifecycleOrchestrator orchestrator;

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

    orchestrator = new EmployeeLifecycleOrchestrator();
    inject("userMapper", userMapper);
    inject("passwordEncoder", passwordEncoder);
    inject("passwordPolicyService", passwordPolicyService);
    inject("dekService", dekService);
    inject("encryptionHelper", new EncryptionHelper());
    inject("tokenVersionCache", tokenVersionCache);
    inject("accessGuard", accessGuard);
    inject("passwordService", passwordService);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = EmployeeLifecycleOrchestrator.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(orchestrator, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private static User makeUser(Long id, String realName, String username, Long deptId) {
    User u = new User();
    u.setId(id);
    u.setRealName(realName);
    u.setUsername(username);
    u.setDepartmentId(deptId);
    return u;
  }

  private IPasswordPolicyService.PasswordValidationResult validPasswordResult() {
    return new IPasswordPolicyService.PasswordValidationResult(true, "ok");
  }

  private void stubStrongPassword() {
    when(passwordService.generateStrongPassword(8)).thenReturn("Abcd1234!");
  }

  @Nested
  @DisplayName("createEmployee - 创建员工")
  class CreateEmployeeTests {

    @Test
    @DisplayName("生成唯一 6 位工号（W-35）且密码加密、强制首次改密")
    void create_success_with_unique_employee_id() {
      User employee = makeUser(1L, "张三", "zhangsan", 5L);
      stubStrongPassword();
      when(userMapper.getMaxEmployeeIdNumeric()).thenReturn(5);
      when(userMapper.findByUsernameIncludeDeleted("zhangsan")).thenReturn(null);
      when(passwordPolicyService.validatePassword(anyString())).thenReturn(validPasswordResult());
      when(passwordEncoder.encode(anyString())).thenReturn("encoded");
      when(userMapper.insert(any(User.class))).thenReturn(1);

      User created = makeUser(1L, "张三", "zhangsan", 5L);
      when(userMapper.selectByUsername("zhangsan")).thenReturn(created);

      Result<User> r = orchestrator.createEmployee(employee);

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData().getEmployeeId()).isEqualTo("000006");
      assertThat(r.getData().getPassword()).isNull();

      ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
      verify(userMapper).insert(captor.capture());
      User saved = captor.getValue();
      assertThat(saved.getEmployeeId()).isEqualTo("000006");
      assertThat(saved.getPassword()).isEqualTo("encoded");
      assertThat(saved.getPasswordChanged()).isFalse();
      assertThat(saved.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("未提供真实姓名被拒绝")
    void missing_real_name_rejected() {
      User employee = new User();
      employee.setId(1L);
      Result<User> r = orchestrator.createEmployee(employee);
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("请提供真实姓名");
    }

    @Test
    @DisplayName("用户名被活跃用户占用拒绝")
    void active_username_conflict_rejected() {
      User employee = makeUser(1L, "张三", "zhangsan", 5L);
      User existing = makeUser(2L, "李四", "zhangsan", 5L);
      existing.setIsDeleted(0);
      when(userMapper.findByUsernameIncludeDeleted("zhangsan")).thenReturn(existing);

      Result<User> r = orchestrator.createEmployee(employee);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("用户名已存在");
      verify(userMapper, never()).physicalDeleteById(2L);
    }

    @Test
    @DisplayName("用户名被软删除用户占用时物理删除旧记录后复用")
    void deleted_username_reused() {
      User employee = makeUser(1L, "张三", "zhangsan", 5L);
      User deleted = makeUser(99L, "已删除", "zhangsan", 5L);
      deleted.setIsDeleted(1);
      stubStrongPassword();
      when(userMapper.findByUsernameIncludeDeleted("zhangsan")).thenReturn(deleted);
      when(passwordPolicyService.validatePassword(anyString())).thenReturn(validPasswordResult());
      when(passwordEncoder.encode(anyString())).thenReturn("encoded");
      when(userMapper.insert(any(User.class))).thenReturn(1);
      when(userMapper.selectByUsername("zhangsan")).thenReturn(makeUser(1L, "张三", "zhangsan", 5L));

      Result<User> r = orchestrator.createEmployee(employee);

      assertThat(r.isSuccess()).isTrue();
      verify(userMapper).deleteUserRoles(99L);
      verify(userMapper).physicalDeleteById(99L);
    }
  }

  @Nested
  @DisplayName("updateEmployee / deleteEmployee / toggleEmployeeStatus")
  class UpdateAndDeleteTests {

    @Test
    @DisplayName("更新员工时禁止修改工号并清除密码")
    void update_clears_employee_id_and_password() {
      User existing = makeUser(2L, "旧名", "old", 5L);
      User updated = makeUser(2L, "新名", "old", 5L);
      when(userMapper.selectById(2L)).thenReturn(existing, updated);
      User currentUser = makeUser(99L, "管理员", "admin", 5L);
      when(userMapper.getUserRoleIds(99L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      when(userMapper.updateById(any(User.class))).thenReturn(1);

      User employee = new User();
      employee.setRealName("新名");
      employee.setEmployeeId("999999");
      employee.setPassword("secret");

      Result<User> r = orchestrator.updateEmployee(2L, employee, currentUser);

      assertThat(r.isSuccess()).isTrue();
      assertThat(employee.getId()).isEqualTo(2L);
      assertThat(employee.getEmployeeId()).isNull();
      assertThat(employee.getPassword()).isNull();
    }

    @Test
    @DisplayName("禁止删除超级管理员账户")
    void super_admin_delete_protected() {
      User existing = makeUser(1L, "超管", "admin", null);
      when(userMapper.selectById(1L)).thenReturn(existing);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));

      Result<Void> r = orchestrator.deleteEmployee(1L);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("禁止删除超级管理员账户");
      verify(userMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("普通员工逻辑删除成功")
    void delete_success() {
      User existing = makeUser(2L, "张三", "zhangsan", 5L);
      when(userMapper.selectById(2L)).thenReturn(existing);
      when(userMapper.getUserRoleIds(2L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      when(userMapper.deleteById(2L)).thenReturn(1);

      Result<Void> r = orchestrator.deleteEmployee(2L);

      assertThat(r.isSuccess()).isTrue();
      verify(userMapper).deleteById(2L);
    }

    @Test
    @DisplayName("禁止禁用自己的账号")
    void cannot_disable_self() {
      User existing = makeUser(1L, "管理员", "admin", 5L);
      when(userMapper.selectById(1L)).thenReturn(existing);
      when(userMapper.getUserRoleIds(1L)).thenReturn(List.of(RoleConstants.ROLE_STAFF));
      User currentUser = makeUser(1L, "管理员", "admin", 5L);

      Result<Void> r = orchestrator.toggleEmployeeStatus(1L, 0, currentUser);

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("不能禁用自己的账号");
    }

    @Test
    @DisplayName("禁用员工时递增 token_version 使旧 JWT 失效")
    void disable_bumps_token_version_and_evicts_cache() {
      User existing = makeUser(2L, "张三", "zhangsan", 5L);
      when(userMapper.selectById(2L)).thenReturn(existing);
      when(userMapper.updateById(any(User.class))).thenReturn(1);
      when(userMapper.incrementTokenVersion(2L)).thenReturn(1);
      User currentUser = makeUser(99L, "超管", "admin", 5L);
      when(userMapper.getUserRoleIds(99L)).thenReturn(List.of(RoleConstants.ROLE_SUPER_ADMIN));

      Result<Void> r = orchestrator.toggleEmployeeStatus(2L, 0, currentUser);

      assertThat(r.isSuccess()).isTrue();
      verify(userMapper).incrementTokenVersion(2L);
      verify(tokenVersionCache).evict(2L);
    }
  }

  @Nested
  @DisplayName("batchImport - 批量导入容错")
  class BatchImportTests {

    @Test
    @DisplayName("重复用户名跳过并计入失败，新用户创建成功")
    void duplicate_skipped_new_created() {
      stubStrongPassword();
      User newUser = makeUser(1L, "新人", "newbie", 5L);
      newUser.setUsername("newbie");
      User dup = makeUser(2L, "旧人", "existing", 5L);
      dup.setUsername("existing");
      when(userMapper.findByUsername("newbie")).thenReturn(null);
      when(userMapper.findByUsername("existing")).thenReturn(dup);
      when(passwordEncoder.encode(anyString())).thenReturn("encoded");
      when(userMapper.insert(any(User.class))).thenReturn(1);

      Result<Map<String, Object>> r = orchestrator.batchImport(List.of(newUser, dup));

      assertThat(r.isSuccess()).isTrue();
      assertThat(((Integer) r.getData().get("successCount"))).isEqualTo(1);
      assertThat(((Integer) r.getData().get("failCount"))).isEqualTo(1);
      assertThat(((List<?>) r.getData().get("errors"))).hasSize(1);
    }

    @Test
    @DisplayName("单条插入异常被捕获，不影响整体结果")
    void insert_exception_caught_per_item() {
      stubStrongPassword();
      User user = makeUser(1L, "张三", "zhangsan", 5L);
      when(userMapper.findByUsername("zhangsan")).thenReturn(null);
      when(passwordEncoder.encode(anyString())).thenReturn("encoded");
      when(userMapper.insert(any(User.class))).thenThrow(new RuntimeException("db down"));

      Result<Map<String, Object>> r = orchestrator.batchImport(List.of(user));

      assertThat(r.isSuccess()).isTrue();
      assertThat(((Integer) r.getData().get("successCount"))).isEqualTo(0);
      assertThat(((Integer) r.getData().get("failCount"))).isEqualTo(1);
    }
  }
}