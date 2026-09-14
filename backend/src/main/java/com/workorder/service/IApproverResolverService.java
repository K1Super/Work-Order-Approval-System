package com.workorder.service;



import com.workorder.entity.User;

/**
 * 审批人解析服务接口
 *
 * <p>将审批人查找逻辑从 Flowable Listener 中提取为独立 Service， 实现深模块设计：小接口 + 深实现，可独立测试。
 *
 * <p>职责： - 根据任务名称和申请人信息查找审批人 - 提供公共的审批人过滤/排除规则 - 支持高管跳转（自动跳过不需要的审批节点）
 */
public interface IApproverResolverService {

  /** 跳过节点的特殊标记 */
  String SIGNAL_SKIP_NODE = "__SKIP__";

  /**
   * 根据任务名称和业务规则解析审批人ID
   *
   * @param taskName 任务节点名称
   * @param applicantId 申请人ID
   * @param orderType 工单类型
   * @param amount 金额
   * @param leaveDays 请假天数
   * @param priority 优先级
   * @return 审批人用户ID字符串；如果应跳过该节点返回 SIGNAL_SKIP_NODE；未找到返回 null
   */
  String resolveAssignee(
      String taskName,
      String applicantId,
      String orderType,
      Double amount,
      Integer leaveDays,
      Integer priority);

  /** 判断用户是否应被排除在审批链之外 超级管理员和安全审计管理员禁止参与审批 */
  boolean isExcludedFromApproval(User user);
}
