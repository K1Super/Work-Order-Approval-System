package com.workorder.service.impl;


import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.exception.BusinessException;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.entity.OrderProcessLink;
import com.workorder.service.IOrderProcessLinkService;

/**
 * 工单-流程实例关联服务实现（OPTIMIZATION 一 架构解耦）
 *
 * <p>W-03/W-13/W-26 修复（fix-spec §3/§5.2）： - 主事务内同步持久化 link（W-13），禁止依赖异步事件导致提交后宕机失联 - 重提交场景：
 * 存在逻辑删除行时恢复并更新，存在有效行时更新流程实例，否则插入（W-03） - 禁止吞异常：createLink 失败必须抛业务异常（W-26），
 * 由上层事务回滚保证一致性
 *
 * @author KLord
 */
@Service
public class OrderProcessLinkServiceImpl implements IOrderProcessLinkService {

  private static final Logger logger = LoggerFactory.getLogger(OrderProcessLinkServiceImpl.class);

  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean createLink(Long workOrderId, String processInstanceId, String processDefinitionId) {
    OrderProcessLink link = new OrderProcessLink();
    link.setWorkOrderId(workOrderId);
    link.setProcessInstanceId(processInstanceId);
    link.setProcessDefinitionId(processDefinitionId);
    link.setCreateTime(Instant.now());
    link.setUpdateTime(Instant.now());
    return createLink(link);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean createLink(OrderProcessLink link) {
    if (link == null || link.getWorkOrderId() == null || link.getProcessInstanceId() == null) {
      throw new BusinessException("创建工单-流程关联记录失败：参数不完整");
    }

    // W-03：查询含逻辑删除行的记录，决定 恢复/更新/插入 策略
    OrderProcessLink existing = orderProcessLinkMapper.selectAnyByWorkOrderId(link.getWorkOrderId());

    int rows;
    if (existing != null) {
      if (existing.getIsDeleted() != null && existing.getIsDeleted() == 1) {
        // 恢复逻辑删除行并更新为新的流程实例（重新提交场景）
        rows =
            orderProcessLinkMapper.restoreAndUpdate(
                link.getWorkOrderId(), link.getProcessInstanceId(), link.getProcessDefinitionId());
        logger.info(
            "[OrderProcessLink] 恢复历史关联记录 - workOrderId={}, newProcessInstanceId={}",
            link.getWorkOrderId(),
            link.getProcessInstanceId());
      } else {
        // 已存在有效关联：更新为新的流程实例（重新提交场景，防全局唯一约束冲突）
        rows =
            orderProcessLinkMapper.updateProcessInstance(
                link.getWorkOrderId(), link.getProcessInstanceId(), link.getProcessDefinitionId());
        logger.info(
            "[OrderProcessLink] 更新有效关联记录 - workOrderId={}, newProcessInstanceId={}",
            link.getWorkOrderId(),
            link.getProcessInstanceId());
      }
    } else {
      rows = orderProcessLinkMapper.insert(link);
      if (rows > 0) {
        logger.info(
            "[OrderProcessLink] 创建成功 - workOrderId={}, processInstanceId={}",
            link.getWorkOrderId(),
            link.getProcessInstanceId());
      }
    }

    if (rows <= 0) {
      logger.error(
          "[OrderProcessLink] 持久化失败 - workOrderId={}, processInstanceId={}",
          link.getWorkOrderId(),
          link.getProcessInstanceId());
      throw new BusinessException("工单与流程关联记录持久化失败，请重试");
    }
    return true;
  }

  @Override
  public String getProcessInstanceId(Long workOrderId) {
    OrderProcessLink link = orderProcessLinkMapper.selectByWorkOrderId(workOrderId);
    return link != null ? link.getProcessInstanceId() : null;
  }

  @Override
  public OrderProcessLink getLinkByWorkOrderId(Long workOrderId) {
    return orderProcessLinkMapper.selectByWorkOrderId(workOrderId);
  }

  @Override
  public Long getWorkOrderIdByProcessInstanceId(String processInstanceId) {
    OrderProcessLink link = orderProcessLinkMapper.selectByProcessInstanceId(processInstanceId);
    return link != null ? link.getWorkOrderId() : null;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean updateProcessInstance(
      Long workOrderId, String processInstanceId, String processDefinitionId) {
    int rows =
        orderProcessLinkMapper.updateProcessInstance(
            workOrderId, processInstanceId, processDefinitionId);
    if (rows <= 0) {
      logger.error(
          "[OrderProcessLink] 更新失败（无有效记录） - workOrderId={}, newProcessInstanceId={}",
          workOrderId,
          processInstanceId);
      throw new BusinessException("工单与流程关联记录更新失败");
    }
    logger.info(
        "[OrderProcessLink] 更新成功 - workOrderId={}, newProcessInstanceId={}",
        workOrderId,
        processInstanceId);
    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean removeLink(Long workOrderId) {
    int rows = orderProcessLinkMapper.deleteByWorkOrderId(workOrderId);
    if (rows > 0) {
      logger.info("[OrderProcessLink] 逻辑删除成功 - workOrderId={}", workOrderId);
    }
    return true;
  }
}
