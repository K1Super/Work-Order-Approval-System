package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.exception.BusinessException;
import com.workorder.dao.OrderProcessLinkMapper;
import com.workorder.entity.OrderProcessLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OrderProcessLinkService 单元测试：创建/查询/恢复/更新/删除 link。
 */
@ExtendWith(MockitoExtension.class)
class OrderProcessLinkServiceImplTest {

  @Mock private OrderProcessLinkMapper orderProcessLinkMapper;

  private OrderProcessLinkServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new OrderProcessLinkServiceImpl();
    try {
      var field = OrderProcessLinkServiceImpl.class.getDeclaredField("orderProcessLinkMapper");
      field.setAccessible(true);
      field.set(service, orderProcessLinkMapper);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject orderProcessLinkMapper", e);
    }
  }

  private static OrderProcessLink makeLink(Long workOrderId, String processInstanceId) {
    OrderProcessLink link = new OrderProcessLink();
    link.setWorkOrderId(workOrderId);
    link.setProcessInstanceId(processInstanceId);
    link.setProcessDefinitionId("def");
    return link;
  }

  @Nested
  @DisplayName("createLink - 创建/恢复/更新")
  class CreateLinkTests {

    @Test
    @DisplayName("参数不完整应拒绝")
    void incomplete_link_rejected() {
      assertThatThrownBy(() -> service.createLink((OrderProcessLink) null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("参数不完整");

      OrderProcessLink missing = new OrderProcessLink();
      missing.setProcessInstanceId("p1");
      assertThatThrownBy(() -> service.createLink(missing))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("参数不完整");
    }

    @Test
    @DisplayName("无历史记录时插入新 link")
    void insert_new_link() {
      when(orderProcessLinkMapper.selectAnyByWorkOrderId(1L)).thenReturn(null);
      when(orderProcessLinkMapper.insert(any(OrderProcessLink.class))).thenReturn(1);

      assertThat(service.createLink(makeLink(1L, "p1"))).isTrue();
      verify(orderProcessLinkMapper).insert(any(OrderProcessLink.class));
    }

    @Test
    @DisplayName("历史逻辑删除行则恢复并更新")
    void restore_deleted_link() {
      OrderProcessLink existing = makeLink(1L, "old");
      existing.setIsDeleted(1);
      when(orderProcessLinkMapper.selectAnyByWorkOrderId(1L)).thenReturn(existing);
      when(orderProcessLinkMapper.restoreAndUpdate(1L, "p2", "def")).thenReturn(1);

      assertThat(service.createLink(makeLink(1L, "p2"))).isTrue();
      verify(orderProcessLinkMapper).restoreAndUpdate(1L, "p2", "def");
    }

    @Test
    @DisplayName("有效历史行则更新流程实例")
    void update_active_link() {
      OrderProcessLink existing = makeLink(1L, "old");
      existing.setIsDeleted(0);
      when(orderProcessLinkMapper.selectAnyByWorkOrderId(1L)).thenReturn(existing);
      when(orderProcessLinkMapper.updateProcessInstance(1L, "p2", "def")).thenReturn(1);

      assertThat(service.createLink(makeLink(1L, "p2"))).isTrue();
      verify(orderProcessLinkMapper).updateProcessInstance(1L, "p2", "def");
    }

    @Test
    @DisplayName("持久化失败应抛业务异常（W-26 禁止吞异常）")
    void persist_failure_throws() {
      when(orderProcessLinkMapper.selectAnyByWorkOrderId(1L)).thenReturn(null);
      when(orderProcessLinkMapper.insert(any(OrderProcessLink.class))).thenReturn(0);

      assertThatThrownBy(() -> service.createLink(makeLink(1L, "p1")))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("关联记录持久化失败");
    }
  }

  @Nested
  @DisplayName("查询与更新方法")
  class QueryAndUpdateTests {

    @Test
    @DisplayName("按工单查询流程实例ID")
    void get_process_instance_id() {
      when(orderProcessLinkMapper.selectByWorkOrderId(1L)).thenReturn(makeLink(1L, "p1"));
      assertThat(service.getProcessInstanceId(1L)).isEqualTo("p1");

      when(orderProcessLinkMapper.selectByWorkOrderId(2L)).thenReturn(null);
      assertThat(service.getProcessInstanceId(2L)).isNull();
    }

    @Test
    @DisplayName("按工单查询 link 与按流程实例反查工单")
    void get_link_and_reverse_lookup() {
      OrderProcessLink link = makeLink(9L, "p9");
      when(orderProcessLinkMapper.selectByWorkOrderId(9L)).thenReturn(link);
      assertThat(service.getLinkByWorkOrderId(9L)).isSameAs(link);

      when(orderProcessLinkMapper.selectByProcessInstanceId("p9")).thenReturn(link);
      assertThat(service.getWorkOrderIdByProcessInstanceId("p9")).isEqualTo(9L);

      when(orderProcessLinkMapper.selectByProcessInstanceId("nope")).thenReturn(null);
      assertThat(service.getWorkOrderIdByProcessInstanceId("nope")).isNull();
    }

    @Test
    @DisplayName("更新流程实例成功与失败")
    void update_process_instance() {
      when(orderProcessLinkMapper.updateProcessInstance(1L, "p2", "def")).thenReturn(1);
      assertThat(service.updateProcessInstance(1L, "p2", "def")).isTrue();

      when(orderProcessLinkMapper.updateProcessInstance(2L, "p2", "def")).thenReturn(0);
      assertThatThrownBy(() -> service.updateProcessInstance(2L, "p2", "def"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("关联记录更新失败");
    }

    @Test
    @DisplayName("removeLink 逻辑删除")
    void remove_link() {
      assertThat(service.removeLink(1L)).isTrue();
      verify(orderProcessLinkMapper).deleteByWorkOrderId(1L);
    }
  }
}