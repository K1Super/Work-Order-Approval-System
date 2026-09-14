package com.workorder.service;


import java.util.List;


import com.workorder.dto.TaskInfoDTO;

/**
 * Flowable 流程查询服务接口（OPTIMIZATION 一 架构解耦）
 *
 * <p>封装 Flowable TaskService/RuntimeService/HistoryService 的查询调用， 工单业务层通过此接口获取流程运行时数据，避免直接依赖
 * Flowable API。
 *
 * @author KLord
 */
public interface IFlowableQueryService {

  /**
   * 获取工单的当前任务信息（含审批人姓名）
   *
   * @param workOrderId 工单ID
   * @return 当前任务信息；流程已结束或不存在返回 null
   */
  TaskInfoDTO getCurrentTask(Long workOrderId);

  /** 获取流程实例的所有活跃任务 */
  List<TaskInfoDTO> getActiveTasks(String processInstanceId);

  /**
   * 判断流程实例是否已完成
   *
   * @param processInstanceId 流程实例ID
   * @return 已完成返回 true
   */
  boolean isProcessFinished(String processInstanceId);

  /** 根据流程实例ID获取当前任务（首个活跃任务） */
  TaskInfoDTO getFirstActiveTask(String processInstanceId);
}
