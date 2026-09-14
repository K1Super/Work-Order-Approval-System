package com.workorder.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.entity.WorkOrder;

/**
 * 工单 Mapper 接口（OPTIMIZATION 一/二/四 改造）
 *
 * <p>变更说明： - 移除 selectByProcessInstanceId：work_order 表已删除 process_instance_id 列， 流程实例 ID 由
 * order_process_link 表承载（OPTIMIZATION 一 架构解耦） - 移除 updateAssignee：current_node/current_assignee
 * 等字段已从 work_order 表删除， 显示用数据由服务层动态查询 Flowable 填充（OPTIMIZATION 一） - status/order_type 参数从 String 改为
 * Integer（OPTIMIZATION 四.4.2 枚举字段字典化） - 新增 updateWithVersion：基于 version 字段的乐观锁更新（OPTIMIZATION 二
 * 数据一致性保障）
 *
 * @author KLord
 */
@Mapper
public interface WorkOrderMapper {

  /** 根据ID查询工单 */
  WorkOrder selectById(@Param("id") Long id);

  /** 根据工单编号查询 */
  WorkOrder selectByOrderNo(@Param("orderNo") String orderNo);

  /**
   * 分页查询工单列表
   *
   * @param status 工单状态码（Integer，对应 WorkOrderStatusEnum，null 表示全部）
   * @param orderType 工单类型码（Integer，对应 OrderTypeEnum，null 表示全部）
   */
  List<WorkOrder> selectPage(
      @Param("applicantId") Long applicantId,
      @Param("status") Integer status,
      @Param("orderType") Integer orderType,
      @Param("keyword") String keyword,
      @Param("departmentId") Long departmentId,
      @Param("offset") int offset,
      @Param("pageSize") int pageSize,
      @Param("sortField") String sortField,
      @Param("sortOrder") String sortOrder);

  /**
   * 统计总数
   *
   * @param status 工单状态码（Integer，对应 WorkOrderStatusEnum，null 表示全部）
   * @param orderType 工单类型码（Integer，对应 OrderTypeEnum，null 表示全部）
   */
  long countTotal(
      @Param("applicantId") Long applicantId,
      @Param("status") Integer status,
      @Param("orderType") Integer orderType,
      @Param("keyword") String keyword,
      @Param("departmentId") Long departmentId);

  /** 插入工单（新建时 version 默认为 0，由数据库 DEFAULT 0 填充） */
  int insert(WorkOrder workOrder);

  /** 更新工单（普通更新，不带乐观锁校验） 仅用于不涉及并发冲突的场景（如归档、删除等单操作员场景） */
  int update(WorkOrder workOrder);

  /**
   * 带乐观锁的更新（OPTIMIZATION 二 数据一致性保障）
   *
   * <p>UPDATE work_order SET ..., version = version + 1 WHERE id = #{id} AND version = #{version}
   * AND is_deleted = 0
   *
   * @return 受影响行数：1=更新成功；0=版本冲突，需重试或抛出 OptimisticLockException
   */
  int updateWithVersion(WorkOrder workOrder);

  /**
   * 更新工单状态（带乐观锁）
   *
   * @param id 工单ID
   * @param status 新状态码（Integer，对应 WorkOrderStatusEnum）
   * @param currentVersion 当前版本号（用于乐观锁校验）
   * @return 受影响行数：1=成功；0=版本冲突
   */
  int updateStatusWithVersion(
      @Param("id") Long id,
      @Param("status") Integer status,
      @Param("currentVersion") Long currentVersion);

  /** 根据ID逻辑删除工单 */
  int deleteById(@Param("id") Long id);

  /**
   * 获取事务级咨询锁（OPTIMIZATION 二 数据一致性保障）
   *
   * <p>调用 pg_advisory_xact_lock 在事务内获取行级锁， 防止并发提交/审批操作导致的数据不一致。 锁在事务提交/回滚后自动释放。
   *
   * @param key 锁键（建议使用 workOrderId）
   */
  void acquireAdvisoryLock(@Param("key") Long key);

  /**
   * 对账扫描：查询 applicant_id 指向已逻辑删除用户的工单（OPTIMIZATION 二）
   *
   * <p>用于定时对账任务发现悬空引用： - work_order.applicant_id 不在 sys_user（已逻辑删除）的记录 - 仅扫描 PENDING 状态（已完成的工单不再处理）
   *
   * @return 悬空引用工单列表
   */
  List<WorkOrder> selectOrphanApplicantWorkOrders();

  /**
   * 对账扫描：追加 remark 标记申请人已离职（OPTIMIZATION 二）
   *
   * @param workOrderId 工单ID
   * @param remarkAppend 追加的备注内容
   * @return 受影响行数
   */
  int appendRemarkForOrphanWorkOrder(
      @Param("workOrderId") Long workOrderId, @Param("remarkAppend") String remarkAppend);
}
