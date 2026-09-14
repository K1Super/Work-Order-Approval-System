package com.workorder.common.event;


import java.time.Instant;

/**
 * 用户逻辑删除事件（OPTIMIZATION 二 CDC 模拟）
 *
 * <p>由 PostgreSQL 触发器 + LISTEN/NOTIFY 捕获 sys_user 软删除事件， SysUserDeleteListener 通过
 * PGNotificationListener 接收后发布此事件。
 *
 * <p>SysUserDeleteEventHandler 监听此事件，标记该用户所有 PENDING 工单 remark， 实现"准实时补偿"，而不仅依赖定时对账任务。
 *
 * @author KLord
 */
public class SysUserLogicallyDeletedEvent {

  private final Long userId;
  private final String username;
  private final Instant occurredAt;

  /** 构造函数，初始化用户逻辑删除事件 */
  public SysUserLogicallyDeletedEvent(Long userId, String username) {
    this.userId = userId;
    this.username = username;
    this.occurredAt = Instant.now();
  }

  public Long getUserId() {
    return userId;
  }

  public String getUsername() {
    return username;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  @Override
  public String toString() {
    return "SysUserLogicallyDeletedEvent{"
        + "userId="
        + userId
        + ", username='"
        + username
        + '\''
        + ", occurredAt="
        + occurredAt
        + '}';
  }
}
