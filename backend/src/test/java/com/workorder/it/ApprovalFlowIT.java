package com.workorder.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.common.enums.ApprovalActionEnum;
import com.workorder.common.enums.WorkOrderStatusEnum;

/**
 * 审批流关键路径集成测试。
 *
 * <p>覆盖多级审批链通过（W-02 回归）、驳回终止、驳回后重新提交（W-03 唯一键冲突回归）等关键路径。
 * 审批链选择「报销」类型：专项审核（费用会计）→ 部门经理审批（财务总监）→ 通过，正好两段式审批。
 */
public class ApprovalFlowIT extends AbstractIT {

  @Autowired private ItSeedData seed;

  @Test
  void multiLevelApprovalStaysPendingUntilTerminalNode() throws Exception {
    ItSeedData.TestUser manager = seed.manager();
    ItSeedData.TestUser applicant = seed.applicant();
    ItSeedData.TestUser specialist = seed.specialist();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession specialistSession = session(specialist.username, ItSeedData.TEST_PASSWORD);
    TestSession managerSession = session(manager.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = submitReimbursement(applicantSession);

    int pending = WorkOrderStatusEnum.PENDING.getCode();
    int approved = WorkOrderStatusEnum.APPROVED.getCode();
    assertEquals(pending, workOrderStatus(applicantSession, workOrderId));
    assertTrue(pendingContains(specialistSession, workOrderId));
    assertFalse(pendingContains(managerSession, workOrderId));

    // 中间节点（专项审核）通过：工单仍为审批中，下一节点（部门经理）出现待办
    expectSuccess(approve(specialistSession, workOrderId, "APPROVE", "财务初审通过"));
    assertEquals(pending, workOrderStatus(applicantSession, workOrderId));
    assertFalse(pendingContains(specialistSession, workOrderId));
    assertTrue(pendingContains(managerSession, workOrderId));

    // 末端节点（部门经理）通过：工单才变为已通过，所有人待办消失
    expectSuccess(approve(managerSession, workOrderId, "APPROVE", "同意报销"));
    assertEquals(approved, workOrderStatus(applicantSession, workOrderId));
    assertFalse(pendingContains(specialistSession, workOrderId));
    assertFalse(pendingContains(managerSession, workOrderId));

    // 审批日志完整：提交 1 条、通过 2 条（异步写入，等待落库）
    seed.awaitApprovalLogs(workOrderId, ApprovalActionEnum.SUBMIT.getNumericCode(), 1);
    seed.awaitApprovalLogs(workOrderId, ApprovalActionEnum.APPROVE.getNumericCode(), 2);
  }

  @Test
  void rejectTerminatesProcessAndClearsDownstreamTodo() throws Exception {
    ItSeedData.TestUser manager = seed.manager();
    ItSeedData.TestUser applicant = seed.applicant();
    ItSeedData.TestUser specialist = seed.specialist();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession specialistSession = session(specialist.username, ItSeedData.TEST_PASSWORD);
    TestSession managerSession = session(manager.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = submitReimbursement(applicantSession);

    // 专项审核驳回：必须携带原因
    expectSuccess(approve(specialistSession, workOrderId, "REJECT", "invalid invoice, resubmit"));

    assertEquals(
        WorkOrderStatusEnum.REJECTED.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));
    // 后续节点（部门经理）无待办
    assertFalse(pendingContains(managerSession, workOrderId));
    // 流程已终止：无活跃任务，current-task 返回空
    JsonNode task = currentTask(applicantSession, workOrderId);
    assertTrue(task.isNull());

    seed.awaitApprovalLogs(workOrderId, ApprovalActionEnum.REJECT.getNumericCode(), 1);
  }

  @Test
  void resubmitAfterRejectStartsNewProcessWithoutConflict() throws Exception {
    ItSeedData.TestUser manager = seed.manager();
    ItSeedData.TestUser applicant = seed.applicant();
    ItSeedData.TestUser specialist = seed.specialist();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession specialistSession = session(specialist.username, ItSeedData.TEST_PASSWORD);
    TestSession managerSession = session(manager.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = submitReimbursement(applicantSession);
    String firstInstance = seed.currentProcessInstanceId(workOrderId);

    expectSuccess(approve(specialistSession, workOrderId, "REJECT", "missing materials"));
    assertEquals(
        WorkOrderStatusEnum.REJECTED.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));

    // 重新提交：进入新流程实例，不触发 order_process_link 唯一键冲突
    MvcResult resubmit =
        mockMvc
            .perform(
                authedRead(post(BASE + "/work-orders/" + workOrderId + "/resubmit"), applicantSession)
                    .param("comment", "materials supplemented"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode resubmitData = expectSuccess(resubmit);
    assertEquals(
        WorkOrderStatusEnum.PENDING.getCode().intValue(), resubmitData.path("status").asInt());

    String secondInstance = seed.currentProcessInstanceId(workOrderId);
    assertNotEquals(firstInstance, secondInstance);

    // 新流程可重新审批通过
    expectSuccess(approve(specialistSession, workOrderId, "APPROVE", "复审通过"));
    assertEquals(
        WorkOrderStatusEnum.PENDING.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));
    expectSuccess(approve(managerSession, workOrderId, "APPROVE", "同意"));
    assertEquals(
        WorkOrderStatusEnum.APPROVED.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));
  }

  /** 提交一个报销工单并返回工单 ID */
  private long submitReimbursement(TestSession applicant) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "报销测试工单");
    body.put("content", "用于集成测试的报销流程内容");
    body.put("orderType", "REIMBURSEMENT");
    body.put("priority", 2);
    body.put("department", "财务部");
    body.put("departmentId", ItSeedData.DEPT_FINANCE);

    MvcResult result =
        mockMvc
            .perform(
                authedRead(post(BASE + "/work-orders"), applicant)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode data = expectSuccess(result);
    return data.path("id").asLong();
  }

  /** 执行审批动作（通过/驳回）并返回原始响应 */
  private MvcResult approve(TestSession approver, long workOrderId, String action, String comment)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("workOrderId", workOrderId);
    body.put("action", action);
    body.put("comment", comment);
    return mockMvc
        .perform(
            authedWrite(post(BASE + "/approvals"), approver)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** 查询工单当前状态码（以申请人身份） */
  private int workOrderStatus(TestSession viewer, long workOrderId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedRead(get(BASE + "/work-orders/" + workOrderId), viewer))
            .andExpect(status().isOk())
            .andReturn();
    return expectSuccess(result).path("status").asInt();
  }

  /** 查询当前任务信息（以申请人身份，返回内层 data） */
  private JsonNode currentTask(TestSession viewer, long workOrderId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedRead(get(BASE + "/work-orders/" + workOrderId + "/current-task"), viewer))
            .andExpect(status().isOk())
            .andReturn();
    return expectSuccess(result);
  }

  /** 判断某用户待办列表中是否包含指定工单 */
  private boolean pendingContains(TestSession user, long workOrderId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedRead(get(BASE + "/work-orders/pending"), user))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode data = expectSuccess(result);
    if (data == null || !data.isArray()) {
      return false;
    }
    for (JsonNode item : data) {
      if (item.path("id").asLong() == workOrderId) {
        return true;
      }
    }
    return false;
  }
}