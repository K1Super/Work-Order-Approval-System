package com.workorder.common.event;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * sys_user 软删除 CDC 监听器（OPTIMIZATION 二 CDC 模拟）
 *
 * <p>通过 PostgreSQL 原生 LISTEN/NOTIFY 机制监听 sys_user_deleted 频道， 捕获 sys_user 软删除事件并发布
 * SysUserLogicallyDeletedEvent。
 *
 * <p>实现原理： 1. V7 SQL 已创建触发器 trg_sys_user_soft_delete，在 sys_user.is_deleted 从 0→1 时 调用
 * pg_notify('sys_user_deleted', json_payload) 2. 本监听器启动一个后台线程，保持 LISTEN 连接，收到通知后解析 JSON 并发布事件 3.
 * SysUserDeleteEventHandler 监听事件，标记该用户所有 PENDING 工单 remark
 *
 * <p>实现细节： - 使用反射访问 org.postgresql.PGConnection（避免编译时依赖 postgresql 驱动， pom.xml 中 postgresql 为
 * runtime scope） - 后台线程为 daemon，JVM 退出时自动结束
 *
 * <p>这是 Postgres 原生 CDC 等价实现（替代 Debezium 等外部工具）。
 *
 * @author KLord
 */
@Component
public class SysUserDeleteListener {

  private static final Logger logger = LoggerFactory.getLogger(SysUserDeleteListener.class);
  private static final String CHANNEL_NAME = "sys_user_deleted";

  /** CDC 重试最大等待时间（毫秒） */
  private static final long CDC_RETRY_MAX_WAIT_MS = 30000L;
  /** CDC 轮询通知超时（毫秒） */
  private static final int CDC_POLL_TIMEOUT_MS = 5000;

  @Autowired private DataSource dataSource;

  @Autowired private ApplicationEventPublisher eventPublisher;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;
  private Connection listenConnection;

  // 反射缓存：org.postgresql.PGConnection 类与方法
  private Class<?> pgConnectionClass;
  private Method getNotificationsMethod;

  @PostConstruct
  public void start() {
    running.set(true);
    executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "pg-cdc-sys-user-listener");
              t.setDaemon(true);
              return t;
            });
    executor.submit(this::listenLoop);
    logger.info("[CDC] sys_user 软删除监听已启动，监听频道: {}", CHANNEL_NAME);
  }

  /** LISTEN 后台循环 保持连接开启，定期检查是否有 NOTIFY 通知 */
  private void listenLoop() {
    int retryCount = 0;
    while (running.get()) {
      try {
        listenConnection = dataSource.getConnection();
        listenConnection.setAutoCommit(false);

        try (Statement stmt = listenConnection.createStatement()) {
          stmt.execute("LISTEN " + CHANNEL_NAME);
        }
        listenConnection.commit();
        logger.info("[CDC] LISTEN 已注册到频道: {}", CHANNEL_NAME);

        // 初始化反射方法（仅首次）
        initReflection();

        while (running.get()) {
          // 等待通知（带超时，便于优雅关闭）
          // PostgreSQL JDBC 驱动通过 PGConnection.getNotifications(timeout) 接收
          if (!pollNotifications()) {
            break;
          }
        }
      } catch (Exception e) {
        if (running.get()) {
          retryCount++;
          long waitMs = Math.min(CDC_RETRY_MAX_WAIT_MS, 1000L * retryCount);
          logger.error(
              "[CDC] LISTEN 连接异常，{}ms 后重试 (第 {} 次): {}", waitMs, retryCount, e.getMessage());
          try {
            Thread.sleep(waitMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      } finally {
        closeQuietly(listenConnection);
        listenConnection = null;
      }
    }
    logger.info("[CDC] LISTEN 循环已退出");
  }

  /** 初始化反射方法（避免编译时依赖 postgresql 驱动） */
  private void initReflection() throws Exception {
    if (pgConnectionClass != null) {
      return;
    }
    // 通过 unwrap 获取 PGConnection，反射加载
    Object pgConn = listenConnection.unwrap(Class.forName("org.postgresql.PGConnection"));
    pgConnectionClass = pgConn.getClass();
    // getNotifications(int) - 带超时参数的方法
    getNotificationsMethod = pgConnectionClass.getMethod("getNotifications", int.class);
    logger.debug("[CDC] 反射初始化完成 - PGConnection: {}", pgConnectionClass.getName());
  }

  /**
   * 通过反射轮询 PostgreSQL 通知
   *
   * @return true 继续轮询；false 退出
   */
  private boolean pollNotifications() {
    try {
      Object pgConn = listenConnection.unwrap(Class.forName("org.postgresql.PGConnection"));
      Object[] notifications = (Object[]) getNotificationsMethod.invoke(pgConn, CDC_POLL_TIMEOUT_MS);

      if (notifications != null && notifications.length > 0) {
        // 通过反射访问 PGNotification.getParameter()
        Method getParameterMethod = notifications[0].getClass().getMethod("getParameter");
        for (Object n : notifications) {
          String payload = (String) getParameterMethod.invoke(n);
          processNotification(payload);
        }
      }
      return true;
    } catch (Exception e) {
      if (running.get()) {
        logger.error("[CDC] 轮询通知异常: {}", e.getMessage());
      }
      return false;
    }
  }

  /**
   * 处理 NOTIFY 通知 payload payload 格式：{"user_id":123,"username":"KLord","timestamp":1234567890.123}
   */
  private void processNotification(String payload) {
    try {
      JsonNode node = objectMapper.readTree(payload);
      Long userId = node.has("user_id") ? node.get("user_id").asLong() : null;
      String username = node.has("username") ? node.get("username").asText() : "unknown";

      if (userId == null) {
        logger.warn("[CDC] 收到无效通知（user_id 为空）: {}", payload);
        return;
      }

      logger.info("[CDC] 收到 sys_user 软删除通知 - userId={}, username={}", userId, username);

      SysUserLogicallyDeletedEvent event = new SysUserLogicallyDeletedEvent(userId, username);
      eventPublisher.publishEvent(event);
    } catch (Exception e) {
      logger.error("[CDC] 处理通知失败 - payload: {}, 错误: {}", payload, e.getMessage(), e);
    }
  }

  private void closeQuietly(Connection conn) {
    if (conn != null) {
      try {
        conn.close();
      } catch (Exception ignored) {
      }
    }
  }

  @PreDestroy
  public void stop() {
    logger.info("[CDC] 正在停止 sys_user 软删除监听...");
    running.set(false);
    closeQuietly(listenConnection);
    if (executor != null) {
      executor.shutdownNow();
      try {
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.warn("[CDC] 监听线程未在 5 秒内退出");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    logger.info("[CDC] sys_user 软删除监听已停止");
  }
}
