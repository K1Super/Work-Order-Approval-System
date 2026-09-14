package com.workorder.service;



import com.workorder.entity.OrderProcessLink;

/**
 * 工单-流程实例关联服务接口（OPTIMIZATION 一 架构解耦）
 *
 * <p>封装 order_process_link 表的操作，避免业务层直接依赖 Mapper。 由事件监听器在工单提交事件后异步创建关联记录， 主业务流程不等待关联记录创建完成。
 *
 * @author KLord
 */
public interface IOrderProcessLinkService {

  /**
   * 创建工单-流程实例关联记录
   *
   * @param workOrderId 工单ID
   * @param processInstanceId 流程实例ID
   * @param processDefinitionId 流程定义ID
   * @return 创建成功返回 true
   */
  boolean createLink(Long workOrderId, String processInstanceId, String processDefinitionId);

  /** 创建工单-流程实例关联记录（直接传入实体） */
  boolean createLink(OrderProcessLink link);

  /** 根据工单ID查询流程实例ID */
  String getProcessInstanceId(Long workOrderId);

  /** 根据工单ID查询关联记录 */
  OrderProcessLink getLinkByWorkOrderId(Long workOrderId);

  /** 根据流程实例ID查询工单ID（Flowable 事件回查工单用） */
  Long getWorkOrderIdByProcessInstanceId(String processInstanceId);

  /** 更新关联记录的流程实例ID（重新提交场景） */
  boolean updateProcessInstance(
      Long workOrderId, String processInstanceId, String processDefinitionId);

  /** 逻辑删除关联记录（工单终止/撤回时调用） */
  boolean removeLink(Long workOrderId);
}
