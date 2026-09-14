package com.workorder.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.entity.ApprovalLog;

/**
 * 审批日志 Mapper 接口（OPTIMIZATION 四 改造）
 *
 * <p>变更说明： - action 参数从 String 改为 Integer（对应 ApprovalActionEnum，OPTIMIZATION 四.4.2） - 移除 is_deleted
 * 过滤（OPTIMIZATION 四.4.1：日志表为纯追加写，无逻辑删除）
 *
 * @author KLord
 */
@Mapper
public interface ApprovalLogMapper {

  /** 根据 ID 查询日志 */
  ApprovalLog selectById(@Param("id") Long id);

  /** 根据工单ID查询日志列表（按时间升序） */
  List<ApprovalLog> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

  /** 根据流程实例ID查询日志列表 */
  List<ApprovalLog> selectByProcessInstanceId(@Param("processInstanceId") String processInstanceId);

  /**
   * 分页查询日志
   *
   * @param action 操作类型码（Integer，对应 ApprovalActionEnum，null 表示全部）
   */
  List<ApprovalLog> selectPage(
      @Param("workOrderId") Long workOrderId,
      @Param("action") Integer action,
      @Param("operatorId") Long operatorId,
      @Param("offset") int offset,
      @Param("pageSize") int pageSize);

  /**
   * 统计总数
   *
   * @param action 操作类型码（Integer，对应 ApprovalActionEnum，null 表示全部）
   */
  long countTotal(
      @Param("workOrderId") Long workOrderId,
      @Param("action") Integer action,
      @Param("operatorId") Long operatorId);

  /** 插入日志（追加写，无 is_deleted） */
  int insert(ApprovalLog approvalLog);
}
