package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.dao.WorkOrderMapper;
import com.workorder.entity.WorkOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 工单状态机（WorkOrderStateMachine）单元测试。
 *
 * <p>覆盖：流程结束终态判定（W-01/W-02 联动修复）、乐观锁更新原语委托、状态码描述映射。
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderStateMachineTest {

  @Mock private WorkOrderMapper workOrderMapper;

  private WorkOrderStateMachine stateMachine;

  @BeforeEach
  void setUp() {
    stateMachine = new WorkOrderStateMachine();
    try {
      var field = WorkOrderStateMachine.class.getDeclaredField("workOrderMapper");
      field.setAccessible(true);
      field.set(stateMachine, workOrderMapper);
    } catch (Exception e) {
      throw new RuntimeException("注入 workOrderMapper 失败", e);
    }
  }

  @Nested
  @DisplayName("resolveApprovalFinalStatus - 流程结束终态判定（W-01/W-02）")
  class FinalStatusTests {

    @Test
    @DisplayName("流程结束且审批通过 → APPROVED")
    void finished_and_approved_maps_to_approved() {
      assertThat(stateMachine.resolveApprovalFinalStatus(true, true))
          .isEqualTo(WorkOrderStatusEnum.APPROVED.getCode());
    }

    @Test
    @DisplayName("流程结束且审批驳回 → REJECTED")
    void finished_and_rejected_maps_to_rejected() {
      assertThat(stateMachine.resolveApprovalFinalStatus(true, false))
          .isEqualTo(WorkOrderStatusEnum.REJECTED.getCode());
    }

    @Test
    @DisplayName("流程未结束且审批通过 → 保持 PENDING（中间节点不落终态）")
    void unfinished_and_approved_keeps_pending() {
      assertThat(stateMachine.resolveApprovalFinalStatus(false, true))
          .isEqualTo(WorkOrderStatusEnum.PENDING.getCode());
    }

    @Test
    @DisplayName("流程未结束且审批驳回 → 保持 PENDING")
    void unfinished_and_rejected_keeps_pending() {
      assertThat(stateMachine.resolveApprovalFinalStatus(false, false))
          .isEqualTo(WorkOrderStatusEnum.PENDING.getCode());
    }
  }

  @Nested
  @DisplayName("updateWithVersion - 乐观锁更新原语")
  class UpdateWithVersionTests {

    @Test
    @DisplayName("委托 Mapper 并透传受影响行数（1=成功）")
    void delegates_to_mapper_and_returns_rows() {
      WorkOrder workOrder = new WorkOrder();
      workOrder.setId(10L);
      when(workOrderMapper.updateWithVersion(workOrder)).thenReturn(1);

      assertThat(stateMachine.updateWithVersion(workOrder)).isEqualTo(1);
      verify(workOrderMapper).updateWithVersion(workOrder);
    }

    @Test
    @DisplayName("版本冲突时透传 0（由调用方抛乐观锁异常）")
    void delegates_conflict_row_count() {
      WorkOrder workOrder = new WorkOrder();
      workOrder.setId(10L);
      when(workOrderMapper.updateWithVersion(workOrder)).thenReturn(0);

      assertThat(stateMachine.updateWithVersion(workOrder)).isZero();
    }
  }

  @Nested
  @DisplayName("resolveStatusDesc - 状态码描述映射")
  class ResolveStatusDescTests {

    @Test
    @DisplayName("已知状态码返回带码描述")
    void known_status_returns_description() {
      assertThat(stateMachine.resolveStatusDesc(WorkOrderStatusEnum.PENDING.getCode()))
          .isEqualTo("审批中(" + WorkOrderStatusEnum.PENDING.getCode() + ")");
    }

    @Test
    @DisplayName("未知状态码返回 UNKNOWN")
    void unknown_status_returns_unknown() {
      assertThat(stateMachine.resolveStatusDesc(100)).isEqualTo("UNKNOWN(100)");
    }

    @Test
    @DisplayName("null 状态码返回 UNKNOWN")
    void null_status_returns_unknown() {
      assertThat(stateMachine.resolveStatusDesc(null)).isEqualTo("UNKNOWN");
    }
  }
}