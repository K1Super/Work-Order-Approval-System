package com.workorder.listener;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.workorder.service.IApproverResolverService;
import org.flowable.engine.TaskService;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Listener 适配器完整测试套件
 *
 * <p>验证 Listener 作为轻量适配器的行为： - 正确提取流程变量并委托给 Service - 处理跳过信号（自动完成任务） - 设置审批人 - 异常不影响流程继续运行 -
 * 类型转换和边界条件处理
 *
 * <p>覆盖范围： - DynamicTaskAssigner 正常流程（5种场景） - DynamicTaskAssigner 异常边界（8种场景） - DynamicTaskAssigner
 * 类型转换（6种场景） - HrTaskListener 完整行为（3种场景） - FinanceTaskListener 完整行为（2种场景） - ManagerTaskListener
 * 完整行为（3种场景） - 跳过信号边界条件（4种场景）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListenerAdapterTests {

  @Mock private IApproverResolverService approverResolverService;

  @Mock private TaskService taskService;

  @Mock private DelegateTask delegateTask;

  // ==================== DynamicTaskAssigner ====================

  @Nested
  @DisplayName("DynamicTaskAssigner - 通用任务分配器")
  class DynamicTaskAssignerTests {

    private DynamicTaskAssigner assigner;

    @BeforeEach
    void setUp() {
      assigner = new DynamicTaskAssigner();
      injectFields(assigner);
    }

    @Test
    @DisplayName("正常分配：设置审批人")
    void sets_assignee_when_service_returns_id() {
      // Given: 流程变量已设置，Service 返回审批人ID
      setupDelegateTask("Department Manager Approval", "100", "leave", 5000.0, 3, 2);
      when(approverResolverService.resolveAssignee(
              eq("Department Manager Approval"),
              eq("100"),
              eq("leave"),
              anyDouble(),
              anyInt(),
              eq(2)))
          .thenReturn("6");

      // When
      assigner.notify(delegateTask);

      // Then: 审批人被设置
      verify(delegateTask).setAssignee("6");
      verify(taskService, never()).complete(anyString());
    }

    @Test
    @DisplayName("跳过节点：自动完成任务")
    void completes_task_on_skip_signal() {
      // Given: Service 返回跳过信号
      setupDelegateTask("部门经理审批", "10", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);
      when(delegateTask.getId()).thenReturn("task-123");

      // When
      assigner.notify(delegateTask);

      // Then: 任务被自动完成，不设置审批人
      verify(taskService).complete("task-123");
      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("Service 返回 null：仅记录日志，不崩溃")
    void handles_null_assignee_gracefully() {
      // Given: Service 返回 null（未找到审批人）
      setupDelegateTask("Unknown Task", "999", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(null);

      // When / Then: 不抛异常
      assigner.notify(delegateTask);
      verify(delegateTask, never()).setAssignee(anyString());
      verify(taskService, never()).complete(anyString());
    }

    @Test
    @DisplayName("Service 抛出异常：捕获并记录，不传播")
    void catches_service_exception() {
      // Given: Service 抛出异常
      setupDelegateTask("HR Review", "100", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenThrow(new RuntimeException("DB connection failed"));

      // When / Then: 不抛异常给 Flowable
      assigner.notify(delegateTask);
      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("priority 为字符串时正确解析为整数")
    void parses_string_priority_to_integer() {
      // Given: priority 是 String 类型（某些流程引擎场景）
      setupDelegateTask(
          "Department Manager Approval",
          "100",
          "reimbursement",
          10000.0,
          null,
          "3"); // priority as string

      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), anyDouble(), isNull(), eq(3)))
          .thenReturn("6");

      // When
      assigner.notify(delegateTask);

      // Then: priority 被正确解析为 Integer(3)
      verify(approverResolverService)
          .resolveAssignee(
              eq("Department Manager Approval"),
              eq("100"),
              eq("reimbursement"),
              eq(10000.0),
              isNull(),
              eq(3));
    }
  }

  // ==================== DynamicTaskAssigner - 异常边界 ====================

  @Nested
  @DisplayName("DynamicTaskAssigner - 异常边界处理")
  class DynamicTaskAssignerExceptionTests {

    private DynamicTaskAssigner assigner;

    @BeforeEach
    void setUp() {
      assigner = new DynamicTaskAssigner();
      injectFields(assigner);
    }

    @Test
    @DisplayName("taskService.complete() 抛出异常时仅记录警告，不传播")
    void task_complete_exception_caught_and_logged() {
      // Given: 返回跳过信号，但 complete 操作失败
      setupDelegateTask("HR Review", "100", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);
      when(delegateTask.getId()).thenReturn("task-err-001");
      doThrow(new RuntimeException("Flowable engine error"))
          .when(taskService)
          .complete("task-err-001");

      // When / Then: 不抛异常
      assigner.notify(delegateTask);

      // Then: 仍然尝试完成任务（异常被内部捕获）
      verify(taskService).complete("task-err-001");
      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("delegateTask.getName() 返回 null 时使用 null 任务名调用 Service")
    void handles_null_task_name() {
      // Given: 任务名为 null
      when(delegateTask.getName()).thenReturn(null);
      when(delegateTask.getVariable("applicantId")).thenReturn("100");
      when(delegateTask.getVariable("orderType")).thenReturn(null);
      when(delegateTask.getVariable("amount")).thenReturn(null);
      when(delegateTask.getVariable("leaveDays")).thenReturn(null);
      when(delegateTask.getVariable("priority")).thenReturn(null);
      when(delegateTask.getProcessInstanceId()).thenReturn("proc-null");

      when(approverResolverService.resolveAssignee(
              isNull(), eq("100"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn(null);

      // When / Then: 不崩溃
      assigner.notify(delegateTask);
    }

    @Test
    @DisplayName("不同类型的变量能正常传递给 Service")
    void various_variable_types_handled() {
      // 测试正常的字符串类型变量（最常见的场景）
      setupDelegateTask("Test Task", "100", "leave", 5000.0, 3, 2);

      when(approverResolverService.resolveAssignee(
              eq("Test Task"), eq("100"), eq("leave"), eq(5000.0), eq(3), eq(2)))
          .thenReturn("5");

      assigner.notify(delegateTask);

      verify(delegateTask).setAssignee("5");
    }

    @Test
    @DisplayName("priority 为 Long 类型时正确转换")
    void handles_long_priority() {
      setupDelegateTask("Director Approval", "400", null, null, null, 2L);

      when(approverResolverService.resolveAssignee(
              eq("Director Approval"), eq("400"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn("20");

      assigner.notify(delegateTask);

      verify(delegateTask).setAssignee("20");
    }

    @Test
    @DisplayName("priority 为无法解析的字符串时降级为默认值 2")
    void unparseable_priority_falls_back_to_default() {
      setupDelegateTask("GM Approval", "500", null, null, null, "high");

      when(approverResolverService.resolveAssignee(
              eq("GM Approval"), eq("500"), isNull(), isNull(), isNull(), eq(2))) // 默认值
          .thenReturn("1");

      assigner.notify(delegateTask);

      verify(approverResolverService)
          .resolveAssignee(eq("GM Approval"), eq("500"), isNull(), isNull(), isNull(), eq(2));
    }

    @Test
    @DisplayName("priority 为 Double 类型时截断为整数")
    void handles_double_priority_truncates_to_int() {
      setupDelegateTask("Executive Approval", "600", null, null, null, 2.7);

      when(approverResolverService.resolveAssignee(
              eq("Executive Approval"),
              eq("600"),
              isNull(),
              isNull(),
              isNull(),
              eq(2))) // (int)2.7 = 2
          .thenReturn("11");

      assigner.notify(delegateTask);

      verify(delegateTask).setAssignee("11");
    }

    @Test
    @DisplayName("所有变量都为 null 时仍能正常调用 Service")
    void all_null_variables_still_calls_service() {
      setupDelegateTask(null, null, null, null, null, null);

      when(approverResolverService.resolveAssignee(
              isNull(), isNull(), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn(null);

      // When / Then: 不崩溃
      assigner.notify(delegateTask);
    }
  }

  // ==================== DynamicTaskAssigner - 任务名变体 ====================

  @Nested
  @DisplayName("DynamicTaskAssigner - BPMN 任务名变体透传")
  class DynamicTaskAssignerTaskNameVariants {

    private DynamicTaskAssigner assigner;

    @BeforeEach
    void setUp() {
      assigner = new DynamicTaskAssigner();
      injectFields(assigner);
    }

    @Test
    @DisplayName("中文任务名：部门经理审批")
    void chinese_dept_manager_task_name() {
      setupDelegateTask("部门经理审批", "100", "leave", 3.0, 5, 1);
      when(approverResolverService.resolveAssignee(
              eq("部门经理审批"), eq("100"), eq("leave"), eq(3.0), eq(5), eq(1)))
          .thenReturn("6");

      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("6");
    }

    @Test
    @DisplayName("中文任务名：高管层审批")
    void chinese_director_task_name() {
      setupDelegateTask("高管层审批", "200", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("高管层审批"), eq("200"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn("20");

      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("20");
    }

    @Test
    @DisplayName("中文任务名：总经理审批")
    void chinese_gm_task_name() {
      setupDelegateTask("总经理审批", "300", "reimbursement", 50000.0, null, 3);
      when(approverResolverService.resolveAssignee(
              eq("总经理审批"), eq("300"), eq("reimbursement"), eq(50000.0), isNull(), eq(3)))
          .thenReturn("1");

      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("1");
    }

    @Test
    @DisplayName("英文任务名：Director Approval")
    void english_director_task_name() {
      setupDelegateTask("Director Approval", "400", null, null, null, 2);
      when(approverResolverService.resolveAssignee(
              eq("Director Approval"), eq("400"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn("21");

      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("21");
    }

    @Test
    @DisplayName("英文任务名：GM Approval")
    void english_gm_task_name() {
      setupDelegateTask("GM Approval", "500", "purchase", 100000.0, null, 4);
      when(approverResolverService.resolveAssignee(
              eq("GM Approval"), eq("500"), eq("purchase"), eq(100000.0), isNull(), eq(4)))
          .thenReturn("10");

      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("10");
    }

    @Test
    @DisplayName("混合大小写任务名：finance REVIEW")
    void mixed_case_task_name() {
      setupDelegateTask("finance REVIEW", "600", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("finance REVIEW"), eq("600"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn("13");

      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("13");
    }
  }

  // ==================== HrTaskListener ====================

  @Nested
  @DisplayName("HrTaskListener - HR 审批分配器")
  class HrTaskListenerTests {

    private HrTaskListener listener;

    @BeforeEach
    void setUp() {
      listener = new HrTaskListener();
      injectFields(listener);
    }

    @Test
    @DisplayName("HR 任务使用固定的 'HR Review' 任务名调用 Service")
    void uses_fixed_hr_review_task_name() {
      setupDelegateTask(null, "200", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("HR Review"), eq("200"), isNull(), isNull(), isNull(), isNull()))
          .thenReturn("12");

      listener.notify(delegateTask);

      verify(delegateTask).setAssignee("12");
    }

    @Test
    @DisplayName("HR 返回跳过信号时不设置审批人")
    void skip_signal_does_not_set_assignee() {
      setupDelegateTask(null, "200", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);

      listener.notify(delegateTask);

      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("HR Listener 的 Service 抛出异常时捕获不传播")
    void catches_service_exception_gracefully() {
      setupDelegateTask(null, "200", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenThrow(new RuntimeException("DB error"));

      // When / Then: 不抛异常
      listener.notify(delegateTask);
      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("HR 返回 null 时不设置审批人，不崩溃")
    void handles_null_return_from_service() {
      setupDelegateTask(null, "999", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(null);

      listener.notify(delegateTask);

      verify(delegateTask, never()).setAssignee(anyString());
    }
  }

  // ==================== FinanceTaskListener ====================

  @Nested
  @DisplayName("FinanceTaskListener - 财务审核分配器")
  class FinanceTaskListenerTests {

    private FinanceTaskListener listener;

    @BeforeEach
    void setUp() {
      listener = new FinanceTaskListener();
      injectFields(listener);
    }

    @Test
    @DisplayName("财务任务使用固定的 'Finance Review' 任务名调用 Service")
    void uses_fixed_finance_review_task_name() {
      setupDelegateTask(null, "300", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("Finance Review"), eq("300"), isNull(), isNull(), isNull(), isNull()))
          .thenReturn("13");

      listener.notify(delegateTask);

      verify(delegateTask).setAssignee("13");
    }

    @Test
    @DisplayName("财务返回跳过信号时不设置审批人")
    void skip_signal_no_assignee() {
      setupDelegateTask(null, "300", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);

      listener.notify(delegateTask);

      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("财务 Listener 的 Service 抛出异常时捕获")
    void catches_service_exception() {
      setupDelegateTask(null, "300", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("System error"));

      // When / Then: 不崩溃
      listener.notify(delegateTask);
    }
  }

  // ==================== ManagerTaskListener ====================

  @Nested
  @DisplayName("ManagerTaskListener - 部门经理审批分配器")
  class ManagerTaskListenerTests {

    private ManagerTaskListener listener;

    @BeforeEach
    void setUp() {
      listener = new ManagerTaskListener();
      injectFields(listener);
    }

    @Test
    @DisplayName("部门经理任务使用固定的 'Department Manager Approval' 任务名")
    void uses_fixed_dept_manager_task_name() {
      setupDelegateTask(null, "400", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("Department Manager Approval"), eq("400"), isNull(), isNull(), isNull(), isNull()))
          .thenReturn("7");

      listener.notify(delegateTask);

      verify(delegateTask).setAssignee("7");
    }

    @Test
    @DisplayName("返回跳过信号时不设置审批人")
    void skip_signal_no_assignee() {
      setupDelegateTask(null, "400", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);

      listener.notify(delegateTask);

      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("Manager Listener 的 Service 抛出异常时捕获")
    void catches_service_exception() {
      setupDelegateTask(null, "400", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenThrow(new RuntimeException("Network timeout"));

      // When / Then: 不崩溃
      listener.notify(delegateTask);
    }

    @Test
    @DisplayName("返回 null 时不设置审批人")
    void handles_null_return() {
      setupDelegateTask(null, "404", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(null);

      listener.notify(delegateTask);

      verify(delegateTask, never()).setAssignee(anyString());
    }
  }

  // ==================== 跳过信号边界条件 ====================

  @Nested
  @DisplayName("跳过信号边界条件 - 所有 Listener 共享行为")
  class SkipSignalBoundaryTests {

    @Test
    @DisplayName("DynamicTaskAssigner：跳过信号 + complete 成功")
    void dynamic_assigner_skip_with_successful_complete() {
      DynamicTaskAssigner assigner = new DynamicTaskAssigner();
      injectFields(assigner);

      setupDelegateTask("Any Task", "100", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);
      when(delegateTask.getId()).thenReturn("task-skip-001");
      // complete 正常执行（无异常）

      assigner.notify(delegateTask);

      verify(taskService).complete("task-skip-001");
      verify(delegateTask, never()).setAssignee(anyString());
    }

    @Test
    @DisplayName("DynamicTaskAssigner：连续多次调用都正常工作")
    void multiple_consecutive_calls() {
      DynamicTaskAssigner assigner = new DynamicTaskAssigner();
      injectFields(assigner);

      // 第一次调用
      setupDelegateTask("Task1", "100", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("Task1"), eq("100"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn("6");
      assigner.notify(delegateTask);
      verify(delegateTask).setAssignee("6");

      // 第二次调用（重置 mock）
      reset(delegateTask);
      setupDelegateTask("Task2", "200", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              eq("Task2"), eq("200"), isNull(), isNull(), isNull(), eq(2)))
          .thenReturn(IApproverResolverService.SIGNAL_SKIP_NODE);
      when(delegateTask.getId()).thenReturn("task-skip-002");
      assigner.notify(delegateTask);
      verify(taskService).complete("task-skip-002");
    }

    @Test
    @DisplayName("空字符串返回值会设置审批人（因为非 null）")
    void empty_string_sets_assignee() {
      DynamicTaskAssigner assigner = new DynamicTaskAssigner();
      injectFields(assigner);

      setupDelegateTask("Test", "100", null, null, null, null);
      when(approverResolverService.resolveAssignee(
              anyString(), anyString(), any(), any(), any(), any()))
          .thenReturn(""); // 空字符串

      assigner.notify(delegateTask);

      // 空字符串不是 null，所以会设置 assignee（虽然值为空）
      verify(delegateTask).setAssignee("");
      verify(taskService, never()).complete(anyString());
    }
  }

  // ==================== 工具方法 ====================

  /** 配置 DelegateTask mock 返回标准流程变量 */
  private void setupDelegateTask(
      String taskName,
      String applicantId,
      String orderType,
      Double amount,
      Integer leaveDays,
      Object priority) {
    when(delegateTask.getName()).thenReturn(taskName != null ? taskName : "Test Task");
    when(delegateTask.getVariable("applicantId")).thenReturn(applicantId);
    when(delegateTask.getVariable("orderType")).thenReturn(orderType);
    when(delegateTask.getVariable("amount")).thenReturn(amount);
    when(delegateTask.getVariable("leaveDays")).thenReturn(leaveDays);
    when(delegateTask.getVariable("priority")).thenReturn(priority);
    when(delegateTask.getProcessInstanceId()).thenReturn("proc-001");
  }

  /** 通过反射注入 mock 依赖到 Listener 中 */
  private void injectFields(Object target) {
    try {
      var resolverField = target.getClass().getDeclaredField("approverResolverService");
      resolverField.setAccessible(true);
      resolverField.set(target, approverResolverService);

      // DynamicTaskAssigner 还有 taskService
      try {
        var taskServiceField = target.getClass().getDeclaredField("taskService");
        taskServiceField.setAccessible(true);
        taskServiceField.set(target, taskService);
      } catch (NoSuchFieldException ignored) {
        // 其他 Listener 没有 taskService 字段
      }
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to inject mocks into " + target.getClass().getSimpleName(), e);
    }
  }
}
