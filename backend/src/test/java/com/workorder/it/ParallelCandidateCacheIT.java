package com.workorder.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 并行审批候选人缓存集成测试。
 *
 * <p>系统 BPMN 无并行网关，其「并行审批」能力落地为 {@code ParallelApprovalEventListener} 的候选人缓存同步
 * （Redis database 1，key 前缀 {@code task:candidates:}），供 {@code WorkOrderServiceImpl.validateTaskCandidate}
 * 做纵深防御的二次校验。本测试直接读 Redis 验证：任务创建后缓存写入、任务完成后缓存清理、下一节点缓存写入。
 */
public class ParallelCandidateCacheIT extends AbstractIT {

  private static final String CANDIDATE_KEY_PREFIX = "task:candidates:";

  @Autowired private ItSeedData seed;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  @Test
  void candidateCacheWrittenOnTaskCreateAndContainsAssignee() throws Exception {
    ItSeedData.TestUser specialist = seed.specialist();
    ItSeedData.TestUser applicant = seed.applicant();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    long workOrderId = submitReimbursement(applicantSession);

    // 专项审核任务创建后，候选人缓存应已写入且包含审批人 ID
    String specialistTaskId = currentTaskId(applicantSession, workOrderId);
    Set<?> cache = candidateCache(specialistTaskId);
    assertNotNull(cache, "任务创建后应写入候选人缓存");
    assertTrue(cache.contains(String.valueOf(specialist.id)), "候选人缓存应包含审批人 ID");
  }

  @Test
  void candidateCacheEvictedOnCompleteAndNextNodeCached() throws Exception {
    ItSeedData.TestUser specialist = seed.specialist();
    ItSeedData.TestUser applicant = seed.applicant();

    TestSession applicantSession = session(applicant.username, ItSeedData.TEST_PASSWORD);
    TestSession specialistSession = session(specialist.username, ItSeedData.TEST_PASSWORD);
    long workOrderId = submitReimbursement(applicantSession);

    String specialistTaskId = currentTaskId(applicantSession, workOrderId);
    assertNotNull(candidateCache(specialistTaskId));

    // 审批人通过后任务完成，候选人缓存被清理
    expectSuccess(approve(specialistSession, workOrderId, "APPROVE", "approve"));
    assertNull(candidateCache(specialistTaskId), "任务完成后候选人缓存应被清理");

    // 下一节点（部门经理）创建后写入新的候选人缓存
    String managerTaskId = currentTaskId(applicantSession, workOrderId);
    Set<?> nextCache = candidateCache(managerTaskId);
    assertNotNull(nextCache, "下一节点创建后应写入候选人缓存");
  }

  /** 提交一个报销工单并返回工单 ID */
  private long submitReimbursement(TestSession applicant) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "并行候选人缓存测试工单");
    body.put("content", "用于验证 ParallelApprovalEventListener 候选人缓存");
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

  /** 执行审批通过并返回原始响应 */
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

  /** 查询工单当前任务的 taskId */
  private String currentTaskId(TestSession viewer, long workOrderId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedRead(get(BASE + "/work-orders/" + workOrderId + "/current-task"), viewer))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode data = expectSuccess(result);
    return data.path("taskId").asText();
  }

  /** 读取指定任务的候选人缓存（不存在时返回 null） */
  private Set<?> candidateCache(String taskId) {
    Object cached = redisTemplate.opsForValue().get(CANDIDATE_KEY_PREFIX + taskId);
    if (cached == null) {
      return null;
    }
    if (cached instanceof Set) {
      return (Set<?>) cached;
    }
    throw new AssertionError("候选人缓存类型异常: " + cached.getClass());
  }
}