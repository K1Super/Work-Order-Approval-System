package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.OrderProcessLink;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 工单展示组装器（WorkOrderDisplayAssembler）单元测试。
 *
 * <p>覆盖：非持久化显示字段的填充/清空、下一节点信息组装（assignee / 候选组）、审批层级名称解析。
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderDisplayAssemblerTest {

  @Mock private OrderProcessLinkMapper orderProcessLinkMapper;
  @Mock private TaskService taskService;
  @Mock private UserMapper userMapper;
  @Mock private TaskQuery taskQuery;

  private WorkOrderDisplayAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new WorkOrderDisplayAssembler();
    inject("orderProcessLinkMapper", orderProcessLinkMapper);
    inject("taskService", taskService);
    inject("userMapper", userMapper);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = WorkOrderDisplayAssembler.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(assembler, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private static WorkOrder makePendingOrder() {
    WorkOrder wo = new WorkOrder();
    wo.setId(10L);
    wo.setOrderNo("WO-TEST-10");
    wo.setApplicantId(7L);
    wo.setStatus(WorkOrderStatusEnum.PENDING.getCode());
    return wo;
  }

  @Nested
  @DisplayName("populateProcessDisplayFields - 展示字段填充")
  class PopulateFieldsTests {

    @Test
    @DisplayName("非 PENDING 工单清空展示字段")
    void non_pending_status_clears_display_fields() {
      WorkOrder wo = makePendingOrder();
      wo.setStatus(WorkOrderStatusEnum.APPROVED.getCode());
      wo.setCurrentNode("历史节点");
      wo.setProcessInstanceId("proc-old");
      wo.setCurrentAssigneeName("旧审批人");

      assembler.populateProcessDisplayFields(wo);

      assertThat(wo.getCurrentNode()).isNull();
      assertThat(wo.getCurrentAssignee()).isNull();
      assertThat(wo.getCurrentAssigneeName()).isNull();
      assertThat(wo.getProcessInstanceId()).isNull();
    }

    @Test
    @DisplayName("PENDING 但无 order_process_link 关联 → 展示字段保持空")
    void pending_without_link_keeps_fields_empty() {
      when(orderProcessLinkMapper.selectByWorkOrderId(10L)).thenReturn(null);
      WorkOrder wo = makePendingOrder();

      assembler.populateProcessDisplayFields(wo);

      assertThat(wo.getProcessInstanceId()).isNull();
      assertThat(wo.getCurrentNode()).isNull();
    }

    @Test
    @DisplayName("PENDING 且已关联流程 → 填充当前节点与审批人")
    void pending_with_link_fills_node_and_assignee() {
      OrderProcessLink link = new OrderProcessLink();
      link.setProcessInstanceId("proc-1");
      when(orderProcessLinkMapper.selectByWorkOrderId(10L)).thenReturn(link);

      Task task = mock(Task.class);
      when(task.getName()).thenReturn("部门经理审批");
      when(task.getAssignee()).thenReturn("100");
      when(taskService.createTaskQuery()).thenReturn(taskQuery);
      when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
      when(taskQuery.active()).thenReturn(taskQuery);
      when(taskQuery.list()).thenReturn(java.util.List.of(task));

      User assignee = new User();
      assignee.setId(100L);
      assignee.setRealName("审批人");
      when(userMapper.selectById(100L)).thenReturn(assignee);

      WorkOrder wo = makePendingOrder();
      assembler.populateProcessDisplayFields(wo);

      assertThat(wo.getProcessInstanceId()).isEqualTo("proc-1");
      assertThat(wo.getCurrentNode()).isEqualTo("部门经理审批");
      assertThat(wo.getCurrentAssignee()).isEqualTo(100L);
      assertThat(wo.getCurrentAssigneeName()).isEqualTo("审批人");
    }
  }

  @Nested
  @DisplayName("buildNextNodeInfo - 下一节点信息组装")
  class NextNodeInfoTests {

    @Test
    @DisplayName("下一节点有 assignee → 返回负责人姓名")
    void next_node_with_assignee_resolves_name() {
      Task nextTask = mock(Task.class);
      when(nextTask.getName()).thenReturn("高管层审批");
      when(nextTask.getAssignee()).thenReturn("200");
      when(taskService.createTaskQuery()).thenReturn(taskQuery);
      when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
      when(taskQuery.active()).thenReturn(taskQuery);
      when(taskQuery.list()).thenReturn(java.util.List.of(nextTask));

      User director = new User();
      director.setId(200L);
      director.setRealName("总监");
      when(userMapper.selectById(200L)).thenReturn(director);

      WorkOrderDisplayAssembler.NextNodeInfo info = assembler.buildNextNodeInfo("proc-1");

      assertThat(info.getNodeName()).isEqualTo("高管层审批");
      assertThat(info.getAssigneeId()).isEqualTo(200L);
      assertThat(info.getAssigneeName()).isEqualTo("总监");
    }

    @Test
    @DisplayName("下一节点为候选组 → 待认领（展示候选组名）")
    void next_node_with_candidate_group_shows_group() {
      Task nextTask = mock(Task.class);
      when(nextTask.getName()).thenReturn("部门经理审批");
      when(nextTask.getAssignee()).thenReturn(null);
      when(nextTask.getId()).thenReturn("t1");
      when(taskService.createTaskQuery()).thenReturn(taskQuery);
      when(taskQuery.processInstanceId(anyString())).thenReturn(taskQuery);
      when(taskQuery.active()).thenReturn(taskQuery);
      when(taskQuery.list()).thenReturn(java.util.List.of(nextTask));

      IdentityLink candidate = mock(IdentityLink.class);
      when(candidate.getType()).thenReturn("candidate");
      when(candidate.getGroupId()).thenReturn("DEPT_MANAGER");
      when(taskService.getIdentityLinksForTask("t1")).thenReturn(java.util.List.of(candidate));

      WorkOrderDisplayAssembler.NextNodeInfo info = assembler.buildNextNodeInfo("proc-1");

      assertThat(info.getNodeName()).isEqualTo("部门经理审批");
      assertThat(info.getAssigneeId()).isNull();
      assertThat(info.getAssigneeName()).isEqualTo("待认领(DEPT_MANAGER)");
    }
  }

  @Nested
  @DisplayName("getApprovalLevelName - 审批层级名称")
  class ApprovalLevelNameTests {

    @Test
    @DisplayName("有层级变量 → 返回对应层级名")
    void known_level_returns_name() {
      Task task = mock(Task.class);
      when(task.getId()).thenReturn("t1");
      when(taskService.getVariable("t1", "currentApprovalLevel")).thenReturn(2);

      assertThat(assembler.getApprovalLevelName(task)).isEqualTo("Director Level");
    }

    @Test
    @DisplayName("无层级变量 → 返回 Unknown")
    void missing_level_returns_unknown() {
      Task task = mock(Task.class);
      when(task.getId()).thenReturn("t1");
      when(taskService.getVariable("t1", "currentApprovalLevel")).thenReturn(null);

      assertThat(assembler.getApprovalLevelName(task)).isEqualTo("Unknown");
    }
  }
}