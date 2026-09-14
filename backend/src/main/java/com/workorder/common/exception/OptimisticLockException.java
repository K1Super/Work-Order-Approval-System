package com.workorder.common.exception;

/**
 * 乐观锁冲突异常（OPTIMIZATION 二 数据一致性保障）
 *
 * <p>当 updateWithVersion/updateStatusWithVersion 返回 0 行时抛出， 表示读取后被其他事务修改，version 不匹配。
 *
 * <p>调用方应捕获此异常并提示用户刷新后重试，避免覆盖他人修改。
 *
 * @author KLord
 */
public class OptimisticLockException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 资源ID（如工单ID） */
  private final Long resourceId;

  /** 资源类型（如 WorkOrder） */
  private final String resourceType;

  /** 构造函数，仅指定错误消息 */
  public OptimisticLockException(String message) {
    super(message);
    this.resourceId = null;
    this.resourceType = null;
  }

  /** 构造函数，指定错误消息、资源ID和资源类型 */
  public OptimisticLockException(String message, Long resourceId, String resourceType) {
    super(message);
    this.resourceId = resourceId;
    this.resourceType = resourceType;
  }

  /** 构造函数，指定错误消息、资源ID、资源类型和原始异常 */
  public OptimisticLockException(
      String message, Long resourceId, String resourceType, Throwable cause) {
    super(message, cause);
    this.resourceId = resourceId;
    this.resourceType = resourceType;
  }

  public Long getResourceId() {
    return resourceId;
  }

  public String getResourceType() {
    return resourceType;
  }
}
