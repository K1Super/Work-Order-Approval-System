package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 工单访问控制服务（WorkOrderAccessService）单元测试。
 *
 * <p>覆盖：审批权限判定（跨部门/跨级阻拦）、工单可见性（IDOR 防护）、审批人部门隔离（fail-closed）。
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderAccessServiceTest {

  @Mock private OrderProcessLinkMapper orderProcessLinkMapper;
  @Mock private TaskService taskService;
  @Mock private UserMapper userMapper;
  @Mock private RedisTemplate<String, Object> redisTemplate;

  private WorkOrderAccessService accessService;

  @BeforeEach
  void setUp() {
    accessService = new WorkOrderAccessService();
    inject("orderProcessLinkMapper", orderProcessLinkMapper);
    inject("taskService", taskService);
    inject("userMapper", userMapper);
    inject("redisTemplate", redisTemplate);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = WorkOrderAccessService.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(accessService, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private static WorkOrder makeWorkOrder(Long deptId) {
    WorkOrder wo = new WorkOrder();
    wo.setId(10L);
    wo.setApplicantId(7L);
    wo.setDepartmentId(deptId);
    wo.setDepartment("研发部");
    wo.setPriority(2);
    wo.setStatus(WorkOrderStatusEnum.PENDING.getCode());
    return wo;
  }

  private static User makeUser(int orgLevel, Long departmentId) {
    User user = new User();
    user.setId(100L);
    user.setUsername("u" + orgLevel + "_" + departmentId);
    user.setRealName("测试用户");
    user.setOrgLevel(orgLevel);
    user.setDepartmentId(departmentId);
    user.setDepartment("研发部");
    return user;
  }

  @Nested
  @DisplayName("canUserApproveWorkOrder - 审批权限判定")
  class CanApproveTests {

    @Test
    @DisplayName("高管级（orgLevel<=1）可审批任意部门工单")
    void executive_can_approve_any_department_order() {
      WorkOrder wo = makeWorkOrder(9L);
      User op = makeUser(1, 9L);
      assertThat(accessService.canUserApproveWorkOrder(wo, op)).isTrue();
    }

    @Test
    @DisplayName("部门ID=1 视为全局（后台部门）")
    void department_one_is_global() {
      WorkOrder wo = makeWorkOrder(9L);
      User op = makeUser(4, 1L);
      assertThat(accessService.canUserApproveWorkOrder(wo, op)).isTrue();
    }

    @Test
    @DisplayName("同部门且职级满足节点要求 → 允许")
    void same_department_allowed() {
      assertThat(accessService.canUserApproveWorkOrder(makeWorkOrder(3L), makeUser(4, 3L)))
          .isTrue();
    }

    @Test
    @DisplayName("跨部门 → 拒绝")
    void different_department_denied() {
      assertThat(accessService.canUserApproveWorkOrder(makeWorkOrder(2L), makeUser(4, 3L)))
          .isFalse();
    }

    @Test
    @DisplayName("当前节点要求更高职级（总监节点）→ 拒绝跨级审批")
    void node_requiring_higher_level_denied() {
      WorkOrder wo = makeWorkOrder(3L);
      wo.setCurrentNode("总监审核");
      assertThat(accessService.canUserApproveWorkOrder(wo, makeUser(4, 3L))).isFalse();
    }

    @Test
    @DisplayName("当前节点职级可覆盖（专项审核节点）→ 允许")
    void node_within_level_allowed() {
      WorkOrder wo = makeWorkOrder(3L);
      wo.setCurrentNode("专项审核");
      assertThat(accessService.canUserApproveWorkOrder(wo, makeUser(3, 3L))).isTrue();
    }

    @Test
    @DisplayName("操作人为 null → 拒绝（fail-closed）")
    void null_operator_rejected() {
      assertThat(accessService.canUserApproveWorkOrder(makeWorkOrder(3L), null)).isFalse();
    }
  }

  @Nested
  @DisplayName("canUserViewWorkOrder - 工单可见性（IDOR 防护）")
  class CanViewTests {

    @Test
    @DisplayName("申请人本人 → 允许")
    void applicant_can_view_own_order() {
      WorkOrder wo = makeWorkOrder(3L);
      assertThat(accessService.canUserViewWorkOrder(wo, wo.getApplicantId())).isTrue();
    }

    @Test
    @DisplayName("高管级 → 允许查看任意部门工单")
    void executive_can_view_any_order() {
      WorkOrder wo = makeWorkOrder(2L);
      when(userMapper.selectById(9L)).thenReturn(makeUser(1, 9L));
      assertThat(accessService.canUserViewWorkOrder(wo, 9L)).isTrue();
    }

    @Test
    @DisplayName("同部门用户 → 允许")
    void same_department_user_can_view() {
      when(userMapper.selectById(5L)).thenReturn(makeUser(4, 3L));
      assertThat(accessService.canUserViewWorkOrder(makeWorkOrder(3L), 5L)).isTrue();
    }

    @Test
    @DisplayName("跨部门用户 → 拒绝")
    void different_department_user_denied() {
      when(userMapper.selectById(5L)).thenReturn(makeUser(4, 8L));
      assertThat(accessService.canUserViewWorkOrder(makeWorkOrder(3L), 5L)).isFalse();
    }

    @Test
    @DisplayName("用户不存在 → 拒绝")
    void unknown_user_denied() {
      when(userMapper.selectById(5L)).thenReturn(null);
      assertThat(accessService.canUserViewWorkOrder(makeWorkOrder(3L), 5L)).isFalse();
    }
  }

  @Nested
  @DisplayName("isSameDepartmentOrSuperAdmin - 部门隔离（阶段 2 fail-closed）")
  class SameDepartmentTests {

    @Test
    @DisplayName("高管级 → 任意工单放行")
    void super_admin_allowed() {
      assertThat(accessService.isSameDepartmentOrSuperAdmin(makeUser(1, 9L), makeWorkOrder(2L)))
          .isTrue();
    }

    @Test
    @DisplayName("同部门 ID → 放行")
    void same_department_id_allowed() {
      assertThat(accessService.isSameDepartmentOrSuperAdmin(makeUser(4, 3L), makeWorkOrder(3L)))
          .isTrue();
    }

    @Test
    @DisplayName("部门 ID 缺失时按部门名称匹配 → 放行")
    void department_name_fallback_allowed() {
      WorkOrder wo = makeWorkOrder(3L);
      wo.setDepartmentId(null);
      User op = makeUser(4, 3L);
      op.setDepartmentId(null);
      assertThat(accessService.isSameDepartmentOrSuperAdmin(op, wo)).isTrue();
    }

    @Test
    @DisplayName("跨部门 → 拒绝")
    void different_department_denied() {
      assertThat(accessService.isSameDepartmentOrSuperAdmin(makeUser(4, 3L), makeWorkOrder(2L)))
          .isFalse();
    }

    @Test
    @DisplayName("审批人为 null → 拒绝（fail-closed）")
    void null_assignee_fail_closed() {
      assertThat(accessService.isSameDepartmentOrSuperAdmin(null, makeWorkOrder(2L))).isFalse();
    }
  }
}