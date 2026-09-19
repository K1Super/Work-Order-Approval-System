package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.OptimisticLockException;
import com.workorder.common.result.Result;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.dao.UserMapper;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.dto.WorkOrderUpdateDTO;
import com.workorder.entity.WorkOrder;
import com.workorder.service.IFlowableQueryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 工单门面（WorkOrderServiceImpl）单元测试。
 *
 * <p>覆盖：门面保留的 updateWorkOrder 白名单与乐观锁防护（W-04）。 审批、提交/撤回/终止等流程逻辑已下沉到编排器，由对应测试类覆盖。
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceImplTest {

  @Mock private WorkOrderMapper workOrderMapper;
  @Mock private UserMapper userMapper;
  @Mock private OrderProcessLinkMapper orderProcessLinkMapper;
  @Mock private TaskService taskService;
  @Mock private IFlowableQueryService flowableQueryService;
  @Mock private WorkOrderAccessService accessService;
  @Mock private WorkOrderDisplayAssembler displayAssembler;
  @Mock private WorkOrderApprovalOrchestrator approvalOrchestrator;
  @Mock private WorkOrderLifecycleOrchestrator lifecycleOrchestrator;

  private WorkOrderServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new WorkOrderServiceImpl();
    inject("workOrderMapper", workOrderMapper);
    inject("userMapper", userMapper);
    inject("orderProcessLinkMapper", orderProcessLinkMapper);
    inject("taskService", taskService);
    inject("flowableQueryService", flowableQueryService);
    inject("accessService", accessService);
    inject("displayAssembler", displayAssembler);
    inject("approvalOrchestrator", approvalOrchestrator);
    inject("lifecycleOrchestrator", lifecycleOrchestrator);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = WorkOrderServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject " + fieldName, e);
    }
  }

  private static WorkOrder makeWorkOrder(
      Long id, Long applicantId, Integer status, Long deptId, Long version) {
    WorkOrder wo = new WorkOrder();
    wo.setId(id);
    wo.setOrderNo("WO-TEST-" + id);
    wo.setTitle("测试工单");
    wo.setContent("测试内容");
    wo.setApplicantId(applicantId);
    wo.setApplicantName("申请人");
    wo.setDepartment("研发部");
    wo.setDepartmentId(deptId);
    wo.setPriority(2);
    wo.setStatus(status);
    wo.setVersion(version);
    return wo;
  }

  @Nested
  @DisplayName("updateWorkOrder - 白名单与 Mass Assignment 防护（W-04）")
  class UpdateWorkOrderTests {

    @Test
    @DisplayName("仅发起人可编辑，非本人应拒绝")
    void only_applicant_can_edit() {
      WorkOrder existing = makeWorkOrder(1L, 20L, WorkOrderStatusEnum.DRAFT.getCode(), 3L, 1L);
      when(workOrderMapper.selectById(1L)).thenReturn(existing);

      WorkOrderUpdateDTO dto = new WorkOrderUpdateDTO();
      dto.setTitle("新标题");

      assertThatThrownBy(() -> service.updateWorkOrder(1L, dto, 10L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只能编辑自己创建的工单");
      verify(workOrderMapper, never()).updateDraft(any(WorkOrder.class));
    }

    @Test
    @DisplayName("非草稿状态不可编辑")
    void only_draft_can_be_edited() {
      WorkOrder existing = makeWorkOrder(1L, 10L, WorkOrderStatusEnum.PENDING.getCode(), 3L, 1L);
      when(workOrderMapper.selectById(1L)).thenReturn(existing);
      WorkOrderUpdateDTO dto = new WorkOrderUpdateDTO();
      dto.setTitle("新标题");

      assertThatThrownBy(() -> service.updateWorkOrder(1L, dto, 10L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只能编辑草稿状态的工单");
    }

    @Test
    @DisplayName("更新内容为空应拒绝")
    void null_dto_rejected() {
      assertThatThrownBy(() -> service.updateWorkOrder(1L, null, 10L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("更新内容不能为空");
    }

    @Test
    @DisplayName("工单不存在应拒绝")
    void nonexistent_order_rejected() {
      when(workOrderMapper.selectById(1L)).thenReturn(null);
      WorkOrderUpdateDTO dto = new WorkOrderUpdateDTO();
      dto.setTitle("新标题");

      assertThatThrownBy(() -> service.updateWorkOrder(1L, dto, 10L))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("工单不存在");
    }

    @Test
    @DisplayName("成功更新仅传播白名单字段，受控字段(status/priority/departmentId/orderType)不写入")
    void success_only_persists_whitelisted_fields() {
      WorkOrder existing = makeWorkOrder(1L, 10L, WorkOrderStatusEnum.DRAFT.getCode(), 3L, 5L);
      when(workOrderMapper.selectById(1L)).thenReturn(existing);
      when(workOrderMapper.updateDraft(any(WorkOrder.class))).thenReturn(1);

      WorkOrderUpdateDTO dto = new WorkOrderUpdateDTO();
      dto.setTitle("新标题");
      dto.setContent("新内容");
      dto.setRemark("新备注");
      dto.setAttachmentUrl("http://file/a.pdf");
      dto.setDepartment("销售部");

      Result<WorkOrder> r = service.updateWorkOrder(1L, dto, 10L);

      assertThat(r.isSuccess()).isTrue();
      ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
      verify(workOrderMapper).updateDraft(captor.capture());
      WorkOrder saved = captor.getValue();
      assertThat(saved.getId()).isEqualTo(1L);
      assertThat(saved.getTitle()).isEqualTo("新标题");
      assertThat(saved.getContent()).isEqualTo("新内容");
      assertThat(saved.getVersion()).isEqualTo(5L);
      // Mass Assignment 防护：受控字段不得被写入
      assertThat(saved.getStatus()).isNull();
      assertThat(saved.getPriority()).isNull();
      assertThat(saved.getDepartmentId()).isNull();
      assertThat(saved.getOrderType()).isNull();
      assertThat(saved.getCompleteTime()).isNull();
    }

    @Test
    @DisplayName("乐观锁冲突（updateDraft 返回 0）抛出 OptimisticLockException")
    void version_conflict_throws_optimistic_lock_exception() {
      WorkOrder existing = makeWorkOrder(1L, 10L, WorkOrderStatusEnum.DRAFT.getCode(), 3L, 2L);
      when(workOrderMapper.selectById(1L)).thenReturn(existing);
      when(workOrderMapper.updateDraft(any(WorkOrder.class))).thenReturn(0);

      WorkOrderUpdateDTO dto = new WorkOrderUpdateDTO();
      dto.setTitle("新标题");

      assertThatThrownBy(() -> service.updateWorkOrder(1L, dto, 10L))
          .isInstanceOf(OptimisticLockException.class)
          .hasMessageContaining("乐观锁冲突");
    }
  }
}