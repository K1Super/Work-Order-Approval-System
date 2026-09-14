package com.workorder.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.entity.OrderProcessLink;

/**
 * 工单-流程实例关联 Mapper（OPTIMIZATION 一 架构解耦）
 *
 * <p>承载 work_order_id ↔ process_instance_id 的映射关系， 替代原 work_order 表的
 * process_instance_id/process_definition_id 列。
 *
 * @author KLord
 */
@Mapper
public interface OrderProcessLinkMapper {

  /** 插入关联记录（提交工单时调用） */
  int insert(OrderProcessLink link);

  /** 根据工单ID查询关联记录 返回 process_instance_id 等运行时数据，用于服务层填充 WorkOrder 显示字段 */
  OrderProcessLink selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

  /** 根据流程实例ID查询工单ID（Flowable 事件回查工单用） */
  OrderProcessLink selectByProcessInstanceId(@Param("processInstanceId") String processInstanceId);

  /** 更新关联记录的流程实例ID（流程重启场景，如重新提交） */
  int updateProcessInstance(
      @Param("workOrderId") Long workOrderId,
      @Param("processInstanceId") String processInstanceId,
      @Param("processDefinitionId") String processDefinitionId);

  /** 逻辑删除关联记录（工单终止/撤回时调用） */
  int deleteByWorkOrderId(@Param("workOrderId") Long workOrderId);
}
