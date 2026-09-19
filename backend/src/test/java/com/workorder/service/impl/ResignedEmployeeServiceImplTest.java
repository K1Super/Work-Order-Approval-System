package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.enums.UserStatusEnum;
import com.workorder.common.result.Result;
import com.workorder.dao.ResignedEmployeeMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.ResignedEmployee;
import com.workorder.entity.User;
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
 * ResignedEmployeeService 单元测试：离职建档、状态流转、列表统计参数透传。
 */
@ExtendWith(MockitoExtension.class)
class ResignedEmployeeServiceImplTest {

  @Mock private ResignedEmployeeMapper resignedEmployeeMapper;
  @Mock private UserMapper userMapper;

  private ResignedEmployeeServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new ResignedEmployeeServiceImpl();
    inject("resignedEmployeeMapper", resignedEmployeeMapper);
    inject("userMapper", userMapper);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = ResignedEmployeeServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject " + fieldName, e);
    }
  }

  private static User makeUser(Long id, Integer status) {
    User u = new User();
    u.setId(id);
    u.setUsername("user" + id);
    u.setRealName("用户" + id);
    u.setEmail("u" + id + "@example.com");
    u.setPhone("1380000000" + id);
    u.setDepartment("研发部");
    u.setDepartmentId(3L);
    u.setPositionId(1L);
    u.setOrgLevel(4);
    u.setHireDate("2023-01-15");
    u.setStatus(status);
    return u;
  }

  @Nested
  @DisplayName("processResignation - 离职建档")
  class ProcessResignationTests {

    @Test
    @DisplayName("员工不存在")
    void nonexistent_employee() {
      when(userMapper.selectById(1L)).thenReturn(null);
      Result<ResignedEmployee> r = service.processResignation(1L, 1, "原因", "备注", 9L);
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).isEqualTo("员工不存在");
    }

    @Test
    @DisplayName("已离职或已删除的员工重复操作被拒绝")
    void already_resigned_rejected() {
      when(userMapper.selectById(1L)).thenReturn(makeUser(1L, UserStatusEnum.RESIGNED.getCode()));
      Result<ResignedEmployee> r = service.processResignation(1L, 1, "原因", "备注", 9L);
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("已离职或已删除");
      verify(resignedEmployeeMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    @DisplayName("成功建档并迁移用户状态为离职")
    void process_success() {
      User employee = makeUser(1L, UserStatusEnum.ACTIVE.getCode());
      when(userMapper.selectById(1L)).thenReturn(employee);
      when(userMapper.selectById(9L)).thenReturn(null); // 操作人不存在 → "系统"
      when(resignedEmployeeMapper.insert(any(ResignedEmployee.class))).thenReturn(1);
      when(userMapper.updateById(any(User.class))).thenReturn(1);

      Result<ResignedEmployee> r = service.processResignation(1L, 2, "被动辞退", "备注", 9L);

      assertThat(r.isSuccess()).isTrue();

      ArgumentCaptor<ResignedEmployee> captor = ArgumentCaptor.forClass(ResignedEmployee.class);
      verify(resignedEmployeeMapper).insert(captor.capture());
      ResignedEmployee saved = captor.getValue();
      assertThat(saved.getUserId()).isEqualTo(1L);
      assertThat(saved.getUsername()).isEqualTo("user1");
      assertThat(saved.getResignType()).isEqualTo(2);
      assertThat(saved.getOperatorName()).isEqualTo("系统");

      ArgumentCaptor<User> updateCaptor = ArgumentCaptor.forClass(User.class);
      verify(userMapper).updateById(updateCaptor.capture());
      assertThat(updateCaptor.getValue().getStatus()).isEqualTo(UserStatusEnum.RESIGNED.getCode());
      verify(userMapper).deleteUserRoles(1L);
    }

    @Test
    @DisplayName("更新用户状态失败抛运行时异常")
    void update_status_failure_throws() {
      when(userMapper.selectById(1L)).thenReturn(makeUser(1L, UserStatusEnum.ACTIVE.getCode()));
      when(userMapper.selectById(9L)).thenReturn(null);
      when(resignedEmployeeMapper.insert(any(ResignedEmployee.class))).thenReturn(1);
      when(userMapper.updateById(any(User.class))).thenReturn(0);

      assertThatThrownBy(() -> service.processResignation(1L, 1, "原因", "备注", 9L))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("更新用户状态失败");
    }
  }

  @Nested
  @DisplayName("离职员工查询")
  class ResignedQueryTests {

    @Test
    @DisplayName("getResignedList 默认分页与参数透传（keyword 加通配符）")
    void resigned_list_params() {
      ResignedEmployee item = new ResignedEmployee();
      item.setId(1L);
      when(resignedEmployeeMapper.selectList(any())).thenReturn(List.of(item));
      when(resignedEmployeeMapper.countTotal(any())).thenReturn(3);

      Result<Map<String, Object>> r = service.getResignedList(null, null, "张", 2, 1);

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).containsEntry("total", 3);

      ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
      verify(resignedEmployeeMapper).selectList(captor.capture());
      Map<String, Object> params = captor.getValue();
      assertThat(params).containsEntry("pageNum", 1);
      assertThat(params).containsEntry("pageSize", 10);
      assertThat(params).containsEntry("keyword", "%张%");
      assertThat(params).containsEntry("departmentId", 2);
      assertThat(params).containsEntry("resignType", 1);
    }

    @Test
    @DisplayName("getResignedById 存在与不存在")
    void resigned_by_id() {
      when(resignedEmployeeMapper.selectById(1L)).thenReturn(null);
      assertThat(service.getResignedById(1L).isSuccess()).isFalse();

      ResignedEmployee item = new ResignedEmployee();
      item.setId(2L);
      when(resignedEmployeeMapper.selectById(2L)).thenReturn(item);
      assertThat(service.getResignedById(2L).getData()).isSameAs(item);
    }

    @Test
    @DisplayName("getResignTypes 返回 5 种类型")
    void resign_types() {
      Result<List<Map<String, Object>>> r = service.getResignTypes();
      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).hasSize(5);
    }
  }
}