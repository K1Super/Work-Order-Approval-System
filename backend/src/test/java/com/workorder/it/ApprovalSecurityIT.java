package com.workorder.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.common.enums.WorkOrderStatusEnum;
import com.workorder.common.exception.ErrorCode;

/**
 * 审批安全越权场景集成测试。
 *
 * <p>覆盖非审批人越权审批、越权访问当前任务（W-25 回归）、工单编辑 Mass Assignment（W-04 回归）、
 * 非超管访问超管专属端点、防重放 nonce 重放拒绝等场景。
 */
public class ApprovalSecurityIT extends AbstractIT {

  @Autowired private ItSeedData seed;

  @Test
  void unrelatedUserCannotApproveOthersTodo() throws Exception {
    seed.specialist();
    ItSeedData.TestUser applicant = seed.applicant();
    ItSeedData.TestUser outsider = seed.outsider();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession outsiderSession = session(outsider.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = submitReimbursement(applicantSession);

    // 非审批人（跨部门无关用户）尝试审批 → 业务拒绝
    MvcResult denied = approve(outsiderSession, workOrderId, "APPROVE", "越权尝试");
    assertFalse(isBizSuccess(resultJson(denied)));

    // 工单状态保持不变（仍审批中）
    assertEquals(
        WorkOrderStatusEnum.PENDING.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));
  }

  @Test
  void currentTaskAccessDeniedForUnrelatedNonApprover() throws Exception {
    seed.specialist();
    ItSeedData.TestUser applicant = seed.applicant();
    ItSeedData.TestUser outsider = seed.outsider();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession outsiderSession = session(outsider.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = submitReimbursement(applicantSession);

    // 非申请人且非当前审批人访问 current-task → 被拒（W-25）
    MvcResult denied =
        mockMvc
            .perform(
                authedRead(get(BASE + "/work-orders/" + workOrderId + "/current-task"), outsiderSession))
            .andExpect(status().isOk())
            .andReturn();
    assertFalse(isBizSuccess(resultJson(denied)));

    // 申请人本人可访问且能拿到当前节点
    JsonNode data = currentTask(applicantSession, workOrderId);
    assertEquals("专项审核", data.path("taskName").asText());
  }

  @Test
  void massAssignmentForgedStatusIsIgnoredOnUpdate() throws Exception {
    ItSeedData.TestUser applicant = seed.applicant();
    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = createDraft(applicantSession);
    assertEquals(
        WorkOrderStatusEnum.DRAFT.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));

    // 携带非法 status/completeTime 的 Mass Assignment 更新 → 白名单 DTO 忽略，状态不被篡改
    Map<String, Object> forged = new LinkedHashMap<>();
    forged.put("title", "篡改后的标题");
    forged.put("content", "篡改后的内容");
    forged.put("status", WorkOrderStatusEnum.APPROVED.getCode());
    forged.put("completeTime", "2026-09-19T00:00:00Z");

    MvcResult update =
        mockMvc
            .perform(
                authedRead(put(BASE + "/work-orders/" + workOrderId), applicantSession)
                    .content(objectMapper.writeValueAsString(forged)))
            .andExpect(status().isOk())
            .andReturn();
    expectSuccess(update);

    assertEquals(
        WorkOrderStatusEnum.DRAFT.getCode().intValue(),
        workOrderStatus(applicantSession, workOrderId));
  }

  @Test
  void nonSuperAdminCannotCallSuperAdminEndpoint() throws Exception {
    ItSeedData.TestUser normal = seed.outsider();
    TestSession normalSession = session(normal.username, ItSeedData.TEST_PASSWORD);

    mockMvc
        .perform(authedRead(post(BASE + "/auth/users"), normalSession).content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void replayWithSameNonceIsRejected() throws Exception {
    seed.specialist();
    ItSeedData.TestUser applicant = seed.applicant();
    ItSeedData.TestUser outsider = seed.outsider();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession outsiderSession = session(outsider.username, ItSeedData.TEST_PASSWORD);

    long workOrderId = submitReimbursement(applicantSession);

    String nonce = newNonce();
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    Map<String, Object> body = approvalBody(workOrderId, "APPROVE", "重放测试");

    // 首次请求消耗 nonce（业务层因越权被拒，但 nonce 已记录）
    MvcResult first = performApproval(outsiderSession, nonce, timestamp, body);
    assertFalse(isBizSuccess(resultJson(first)));

    // 相同 nonce 重放 → 防重放拒绝
    MvcResult replay = performApproval(outsiderSession, nonce, timestamp, body);
    JsonNode replayJson = resultJson(replay);
    assertFalse(isBizSuccess(replayJson));
    assertEquals(ErrorCode.REPEAT_REQUEST.getBizCode(), replayJson.path("bizCode").asText());
  }

  /** 提交一个报销工单并返回工单 ID */
  private long submitReimbursement(TestSession applicant) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "报销安全测试工单");
    body.put("content", "用于安全越权集成测试的报销流程内容");
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
    return expectSuccess(result).path("id").asLong();
  }

  /** 创建草稿工单并返回工单 ID */
  private long createDraft(TestSession applicant) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "安全测试草稿");
    body.put("content", "用于 Mass Assignment 回归测试的草稿内容");
    body.put("orderType", "OTHER");
    body.put("priority", 1);

    MvcResult result =
        mockMvc
            .perform(
                authedRead(post(BASE + "/work-orders/draft"), applicant)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    return expectSuccess(result).path("id").asLong();
  }

  /** 构造审批请求体 */
  private Map<String, Object> approvalBody(long workOrderId, String action, String comment) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("workOrderId", workOrderId);
    body.put("action", action);
    body.put("comment", comment);
    return body;
  }

  /** 执行审批动作（默认每次生成新 nonce） */
  private MvcResult approve(TestSession approver, long workOrderId, String action, String comment)
      throws Exception {
    return mockMvc
        .perform(
            authedWrite(post(BASE + "/approvals"), approver)
                .content(objectMapper.writeValueAsString(approvalBody(workOrderId, action, comment))))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** 以指定 nonce/timestamp 执行审批请求（防重放测试用） */
  private MvcResult performApproval(
      TestSession approver, String nonce, String timestamp, Map<String, Object> body)
      throws Exception {
    return mockMvc
        .perform(
            post(BASE + "/approvals")
                .cookie(new Cookie(AUTH_COOKIE, approver.token))
                .cookie(new Cookie(XSRF_COOKIE, approver.csrf))
                .header(XSRF_HEADER, approver.csrf)
                .header(NONCE_HEADER, nonce)
                .header(TIMESTAMP_HEADER, timestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8.name())
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
}