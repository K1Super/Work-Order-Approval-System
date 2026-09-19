package com.workorder.listener;


import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Parallel Approval Event Listener — 并行审批事件监听器
 *
 * <p>核心功能： 1. 监听 TASK_CREATED 事件：缓存任务候选人到 Redis（TTL 24 小时） - 用于
 * WorkOrderServiceImpl.validateTaskCandidate 的纵深防御校验 - 防止 Flowable API 被绕过时的任务哄抢 2. 监听
 * TASK_COMPLETED 事件：清理候选人缓存 3. 监听并行网关相关事件：并行审批状态同步（预留扩展点）
 *
 * <p>安全策略（§9 防任务哄抢）： - 候选人缓存与 Flowable API 形成 double-check - 缓存 key: task:candidates:{taskId} - 缓存
 * value: Set<String> 候选用户 ID 集合
 *
 * @author KLord
 */
@Component
public class ParallelApprovalEventListener implements FlowableEventListener {

  private static final Logger logger = LoggerFactory.getLogger(ParallelApprovalEventListener.class);

  /** 候选人缓存 Redis key 前缀 */
  private static final String CANDIDATE_CACHE_PREFIX = "task:candidates:";

  /** 候选人缓存 TTL（小时） */
  private static final long CANDIDATE_CACHE_TTL_HOURS = 24;

  /**
   * TaskService 惰性注入（W-14：避免注册期循环依赖）。
   *
   * <p>本监听器由 FlowableConfig 在引擎创建期间通过 setEventListeners 注册， 而 TaskService 依赖 ProcessEngine。
   * 若在构造期强依赖 TaskService，会在引擎创建阶段形成循环依赖。 使用 ObjectProvider 惰性获取，运行时才解析。
   */
  @Autowired private ObjectProvider<TaskService> taskServiceProvider;

  @Autowired private RedisTemplate<String, Object> redisTemplate;

  /** 事件处理入口：根据事件类型分发 */
  @Override
  public void onEvent(FlowableEvent event) {
    try {
      FlowableEventType type = event.getType();

      // 仅处理引擎实体事件（含 Task 实体）
      if (!(event instanceof FlowableEngineEntityEvent)) {
        return;
      }
      FlowableEngineEntityEvent entityEvent = (FlowableEngineEntityEvent) event;
      Object entity = entityEvent.getEntity();
      if (!(entity instanceof Task)) {
        return;
      }
      Task task = (Task) entity;

      // 任务创建 → 缓存候选人
      if (type == FlowableEngineEventType.TASK_CREATED) {
        cacheTaskCandidates(task);
        return;
      }

      // 任务完成/删除 → 清理缓存
      if (type == FlowableEngineEventType.TASK_COMPLETED
          || type == FlowableEngineEventType.ENTITY_DELETED) {
        evictTaskCandidates(task.getId());
      }
    } catch (Exception e) {
      logger.error(
          "Failed to process Flowable event: type={}, err={}", event.getType(), e.getMessage(), e);
    }
  }

  /**
   * 缓存任务候选人到 Redis 包括：已分配人（assignee）+ 候选用户（candidate userId） 注意：候选组（groupId）不缓存，由 Flowable API
   * 在校验时解析
   */
  private void cacheTaskCandidates(Task task) {
    if (task == null || task.getId() == null) {
      return;
    }
    try {
      String cacheKey = CANDIDATE_CACHE_PREFIX + task.getId();
      Set<String> candidateIds = new HashSet<>();

      // 1. 已分配人
      if (task.getAssignee() != null && !task.getAssignee().isEmpty()) {
        candidateIds.add(task.getAssignee());
      }

      // 2. 候选用户/组
      try {
        TaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService == null) {
          logger.warn("TaskService 尚未就绪，跳过候选人缓存: taskId={}", task.getId());
          return;
        }
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        if (links != null) {
          for (IdentityLink link : links) {
            if (!"candidate".equals(link.getType())) {
              continue;
            }
            // 仅缓存候选用户 ID（候选组由 Flowable API 实时解析角色）
            if (link.getUserId() != null && !link.getUserId().isEmpty()) {
              candidateIds.add(link.getUserId());
            }
          }
        }
      } catch (Exception linksEx) {
        logger.warn(
            "获取任务候选人链接失败（缓存仅含 assignee）: taskId={}, err={}", task.getId(), linksEx.getMessage());
      }

      // 写入 Redis（TTL 24 小时，防止任务长期未处理时缓存堆积）
      redisTemplate
          .opsForValue()
          .set(cacheKey, candidateIds, CANDIDATE_CACHE_TTL_HOURS, TimeUnit.HOURS);
      logger.debug("已缓存任务候选人: taskId={}, count={}", task.getId(), candidateIds.size());
    } catch (Exception e) {
      // 缓存失败不影响流程主链路（Flowable API 仍是主校验源）
      logger.warn("缓存任务候选人失败（不影响流程）: taskId={}, err={}", task.getId(), e.getMessage());
    }
  }

  /** 清理任务候选人缓存 */
  private void evictTaskCandidates(String taskId) {
    if (taskId == null || taskId.isEmpty()) {
      return;
    }
    try {
      redisTemplate.delete(CANDIDATE_CACHE_PREFIX + taskId);
      logger.debug("已清理任务候选人缓存: taskId={}", taskId);
    } catch (Exception e) {
      logger.debug("清理候选人缓存失败: taskId={}, err={}", taskId, e.getMessage());
    }
  }

  /** 是否在异常时抛出：false（事件处理异常不影响流程主链路） */
  @Override
  public boolean isFailOnException() {
    return false;
  }

  /** 是否支持事务提交后执行 */
  @Override
  public boolean isFireOnTransactionLifecycleEvent() {
    return false;
  }

  /**
   * 支持的事件类型：仅注册本监听器实际处理的三类任务事件。
   *
   * <p>修复说明（集成测试启动期暴露）：原实现返回 null 语义上表示「支持所有类型」，但 Flowable
   * 引擎在初始化时以 addEventListener(listener, listener.getTypes()) 方式注册，null 会被当作
   * null 数组展开 varargs 触发 NPE，导致引擎初始化失败。此处显式返回实际处理的
   * TASK_CREATED / TASK_COMPLETED / ENTITY_DELETED（见 onEvent 分发逻辑），从根上消除歧义。
   */
  @Override
  public Set<FlowableEventType> getTypes() {
    Set<FlowableEventType> types = new HashSet<>();
    types.add(FlowableEngineEventType.TASK_CREATED);
    types.add(FlowableEngineEventType.TASK_COMPLETED);
    types.add(FlowableEngineEventType.ENTITY_DELETED);
    return types;
  }

  /** 事务状态 */
  @Override
  public String getOnTransaction() {
    return null;
  }
}
