package com.workorder.service.impl;


import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.entity.OrderProcessLink;
import com.workorder.service.IOrderProcessLinkService;

/**


 * 工单-流程实例关联服务实现（OPTIMIZATION 一 架构解耦）


 *


 * <p>由领域事件监听器在工单提交事件后异步调用 createLink 创建关联记录。 主业务流程（work_order 状态更新）不依赖此关联记录的创建结果。


 *


 * @author KLord


 */


@Service


public class OrderProcessLinkServiceImpl implements IOrderProcessLinkService {





  private static final Logger logger = LoggerFactory.getLogger(OrderProcessLinkServiceImpl.class);





  @Autowired private OrderProcessLinkMapper orderProcessLinkMapper;





  @Override


  public boolean createLink(


      Long workOrderId, String processInstanceId, String processDefinitionId) {


    try {


      OrderProcessLink link = new OrderProcessLink();


      link.setWorkOrderId(workOrderId);


      link.setProcessInstanceId(processInstanceId);


      link.setProcessDefinitionId(processDefinitionId);


      link.setCreateTime(Instant.now());


      link.setUpdateTime(Instant.now());


      return createLink(link);


    } catch (Exception e) {


      logger.error(


          "[OrderProcessLink] 创建失败 - workOrderId={}, processInstanceId={}, 错误: {}",


          workOrderId,


          processInstanceId,


          e.getMessage(),


          e);


      return false;


    }


  }





  @Override


  public boolean createLink(OrderProcessLink link) {


    try {


      // 幂等性：若已存在未删除的关联记录，先逻辑删除再重新插入（重新提交场景）


      OrderProcessLink existing = orderProcessLinkMapper.selectByWorkOrderId(link.getWorkOrderId());


      if (existing != null) {


        orderProcessLinkMapper.deleteByWorkOrderId(link.getWorkOrderId());


        logger.info(


            "[OrderProcessLink] 检测到已存在关联记录，已逻辑删除旧记录 - workOrderId={}", link.getWorkOrderId());


      }


      int rows = orderProcessLinkMapper.insert(link);


      if (rows > 0) {


        logger.info(


            "[OrderProcessLink] 创建成功 - workOrderId={}, processInstanceId={}",


            link.getWorkOrderId(),


            link.getProcessInstanceId());


        return true;


      }


      logger.warn("[OrderProcessLink] 插入返回 0 行 - workOrderId={}", link.getWorkOrderId());


      return false;


    } catch (Exception e) {


      logger.error(


          "[OrderProcessLink] 创建异常 - workOrderId: {}, 错误: {}",


          link.getWorkOrderId(),


          e.getMessage(),


          e);


      return false;


    }


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


  public boolean updateProcessInstance(


      Long workOrderId, String processInstanceId, String processDefinitionId) {


    try {


      int rows =


          orderProcessLinkMapper.updateProcessInstance(


              workOrderId, processInstanceId, processDefinitionId);


      if (rows > 0) {


        logger.info(


            "[OrderProcessLink] 更新成功 - workOrderId={}, newProcessInstanceId={}",


            workOrderId,


            processInstanceId);


        return true;


      }


      logger.warn("[OrderProcessLink] 更新返回 0 行 - workOrderId={}", workOrderId);


      return false;


    } catch (Exception e) {


      logger.error(


          "[OrderProcessLink] 更新异常 - workOrderId: {}, 错误: {}", workOrderId, e.getMessage(), e);


      return false;


    }


  }





  @Override


  public boolean removeLink(Long workOrderId) {


    try {


      int rows = orderProcessLinkMapper.deleteByWorkOrderId(workOrderId);


      if (rows > 0) {


        logger.info("[OrderProcessLink] 逻辑删除成功 - workOrderId={}", workOrderId);


        return true;


      }


      logger.warn("[OrderProcessLink] 逻辑删除返回 0 行 - workOrderId={}", workOrderId);


      return false;


    } catch (Exception e) {


      logger.error(


          "[OrderProcessLink] 逻辑删除异常 - workOrderId: {}, 错误: {}", workOrderId, e.getMessage(), e);


      return false;


    }


  }


}



