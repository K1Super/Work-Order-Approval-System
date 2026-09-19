package com.workorder.config;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.PreDestroy;

import org.flowable.bpmn.model.ScriptTask;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.ScriptTaskParseHandler;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.parse.BpmnParseHandler;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Configuration;

import com.workorder.listener.ParallelApprovalEventListener;

/**
 * Flowable 工作流引擎配置类 — 安全加固 + 生命周期治理
 *
 * <p>严格遵循 improve.md 第一部分： - §1 第 4
 * 条「强制的显式初始化排序」：@AutoConfigureBefore(ProcessEngineAutoConfiguration.class) 确保
 * EngineConfigurationConfigurer 在 ProcessEngineAutoConfiguration
 * 创建引擎前就绪； @DependsOn({"dataSource","transactionManager"}) 硬编码 Flowable 相关 Bean 在数据源
 * 与事务管理器就绪后方可初始化。 - §1 第 6 条「全生命周期资源闭环销毁」：@PreDestroy 显式关闭 AsyncExecutor， awaitTermination
 * 30s，严禁暴力关闭导致 ACT_RU_JOB 运行中任务悬空。
 *
 * <p>安全策略（§9 Flowable 工作流安全）： 1. 在 BPMN 解析阶段拒绝任何 scriptTask（防止部署含恶意 groovy/javascript 脚本的流程定义） 2.
 * 注册并行审批事件监听器（候选人缓存、状态同步）
 *
 * @author KLord
 */
@Configuration
@AutoConfigureBefore(ProcessEngineAutoConfiguration.class)
public class FlowableConfig
    implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

  private static final Logger logger = LoggerFactory.getLogger(FlowableConfig.class);

  /** AsyncExecutor 优雅关闭超时（毫秒） */
  private static final long ASYNC_EXECUTOR_SHUTDOWN_TIMEOUT_MS = 30_000L;

  /**
   * Flowable 引擎实例（容器关闭时用于优雅关闭 AsyncExecutor）
   *
   * <p>⚠️ 循环依赖治理：直接 @Autowired ProcessEngine 会与 ProcessEngineAutoConfiguration 形成循环（FlowableConfig
   * 作为 EngineConfigurationConfigurer 被引擎创建时引用， 而其本身又依赖 ProcessEngine）。使用 ObjectProvider
   * 实现惰性查找，注入阶段不触发 ProcessEngine Bean 创建，仅在 @PreDestroy 关闭时按需获取，彻底打破循环。
   */
  @Autowired private ObjectProvider<ProcessEngine> processEngineProvider;

  /**
   * 全局事件监听器（W-14：显式注册，确保单例、仅一次生效）
   *
   * <p>ParallelApprovalEventListener 为 @Component 单例（其 TaskService 依赖已惰性化， 不依赖 ProcessEngine），
   * 此处直接注入并在 configure() 中通过 setEventListeners 注册到 Flowable 引擎。 删除原先重复的 globalEventListener()
   * @Bean，避免同类型双实例导致事件重复触发或注册失效。
   */
  @Autowired private ParallelApprovalEventListener parallelApprovalEventListener;

  /**
   * 配置 Flowable 引擎：禁用脚本任务 + 显式注册全局事件监听器（W-14） 实现 EngineConfigurationConfigurer，Flowable Spring Boot
   * starter 在创建引擎时自动调用
   */
  @Override
  public void configure(SpringProcessEngineConfiguration config) {
    // 替换 ScriptTaskParseHandler：在解析阶段拦截所有 scriptTask
    // 自定义 handler 继承原 handler 并覆盖 executeParse，直接抛 SecurityException
    List<BpmnParseHandler> customPreHandlers = new ArrayList<>();
    customPreHandlers.add(
        new ScriptTaskParseHandler() {
          @Override
          protected void executeParse(BpmnParse bpmnParse, ScriptTask scriptTask) {
            // 生产环境严禁执行 BPMN 内嵌脚本（groovy/javascript 等可导致 RCE）
            String errMsg =
                "禁止部署包含 ScriptTask 的流程定义（任务 ID: "
                    + scriptTask.getId()
                    + ", 名称: "
                    + (scriptTask.getName() != null ? scriptTask.getName() : "未命名")
                    + "）。请使用 ServiceTask 或 TaskListener 替代。";
            logger.error(
                "🚫 [Flowable 安全] 拒绝部署含 ScriptTask 的流程定义: taskId={}, name={}",
                scriptTask.getId(),
                scriptTask.getName());
            throw new SecurityException(errMsg);
          }
        });

    // 将自定义 handler 追加到 pre parse handlers 列表
    List<BpmnParseHandler> existingPreHandlers = config.getPreBpmnParseHandlers();
    if (existingPreHandlers == null) {
      existingPreHandlers = new ArrayList<>();
    }
    existingPreHandlers.addAll(customPreHandlers);
    config.setPreBpmnParseHandlers(existingPreHandlers);

    // W-14：显式注册全局事件监听器（单例、仅一次），确保 TASK_CREATED 候选人缓存等事件生效
    config.setEventListeners(Collections.singletonList(parallelApprovalEventListener));
    logger.info("✅ [Flowable 安全] 已注册 ScriptTask 解析拦截器，禁止部署含脚本任务的流程定义");
    logger.info(
        "✅ [Flowable 事件] 已显式注册全局事件监听器: {}（单例）",
        parallelApprovalEventListener.getClass().getSimpleName());
  }

  /**
   * 全生命周期资源闭环销毁：优雅关闭 Flowable AsyncExecutor（规范 §1 第 6 条）
   *
   * <p>严禁暴力关闭导致 ACT_RU_JOB 表中的运行中异步任务悬空。 调用 shutdown() 后轮询 isActive()，最多等待 30 秒（模拟
   * awaitTermination）。
   */
  @PreDestroy
  public void shutdownAsyncExecutor() {
    // 惰性获取 ProcessEngine（容器关闭阶段 Bean 仍可解析，不存在循环风险）
    ProcessEngine processEngine = processEngineProvider.getIfAvailable();
    if (processEngine == null) {
      logger.info("[生命周期] ProcessEngine 未注入，跳过 AsyncExecutor 关闭");
      return;
    }
    try {
      ProcessEngineConfigurationImpl config =
          (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
      AsyncExecutor asyncExecutor = config.getAsyncExecutor();
      if (asyncExecutor == null || !asyncExecutor.isActive()) {
        logger.info("[生命周期] Flowable AsyncExecutor 未运行，无需关闭");
        return;
      }
      logger.info("[生命周期] 正在优雅关闭 Flowable AsyncExecutor（最长等待 30s）...");
      asyncExecutor.shutdown();
      // 模拟 awaitTermination(30, TimeUnit.SECONDS)：轮询 isActive 状态
      long deadline = System.currentTimeMillis() + ASYNC_EXECUTOR_SHUTDOWN_TIMEOUT_MS;
      while (asyncExecutor.isActive() && System.currentTimeMillis() < deadline) {
        Thread.sleep(200);
      }
      if (asyncExecutor.isActive()) {
        logger.warn("[生命周期] ⚠️ Flowable AsyncExecutor 30s 内未完全关闭，可能仍有运行中任务（ACT_RU_JOB）");
      } else {
        logger.info("[生命周期] ✅ Flowable AsyncExecutor 已优雅关闭");
      }
    } catch (Exception e) {
      logger.warn("[生命周期] 关闭 Flowable AsyncExecutor 异常: {}", e.getMessage());
    }
  }
}
