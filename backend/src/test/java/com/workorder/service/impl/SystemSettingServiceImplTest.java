package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.dao.SystemSettingMapper;
import com.workorder.entity.SystemSetting;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SystemSettingService 单元测试：默认值读取、类型转换、分组查询、初始化与保存。
 */
@ExtendWith(MockitoExtension.class)
class SystemSettingServiceImplTest {

  @Mock private SystemSettingMapper systemSettingMapper;

  private SystemSettingServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new SystemSettingServiceImpl();
    try {
      var field = SystemSettingServiceImpl.class.getDeclaredField("systemSettingMapper");
      field.setAccessible(true);
      field.set(service, systemSettingMapper);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject systemSettingMapper", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> group(Map<String, Object> all, String key) {
    return (Map<String, Object>) all.get(key);
  }

  @Nested
  @DisplayName("getAllSettings / getSettingValue")
  class ReadSettingsTests {

    @Test
    @DisplayName("无数据库数据时返回四组默认值")
    void default_settings() {
      Map<String, Object> all = service.getAllSettings();
      assertThat(all).containsKeys("basic", "workorder", "notification", "security");
      assertThat(group(all, "basic")).containsEntry("systemName", "工单审批流转系统");
      assertThat(group(all, "security")).containsEntry("tokenExpiryHours", 24);
      assertThat(group(all, "security")).containsEntry("maxLoginAttempts", 5);
    }

    @Test
    @DisplayName("数据库值覆盖默认值并按默认值类型转换")
    void db_value_overrides_with_type_conversion() {
      lenient().when(systemSettingMapper.selectByGroup("security"))
          .thenReturn(List.of(new SystemSetting("security", "minPasswordLength", "12", "最小密码长度")));
      lenient().when(systemSettingMapper.selectByGroup("workorder"))
          .thenReturn(List.of(new SystemSetting("workorder", "allowWithdraw", "false", "允许撤回")));

      Map<String, Object> all = service.getAllSettings();

      assertThat(group(all, "security")).containsEntry("minPasswordLength", 12);
      assertThat(group(all, "workorder")).containsEntry("allowWithdraw", false);
    }

    @Test
    @DisplayName("getSettingValue 从安全分组读取")
    void value_from_security_group() {
      assertThat(service.getSettingValue("tokenExpiryHours", "24")).isEqualTo("24");
    }

    @Test
    @DisplayName("getSettingValue 回退到 basic 分组")
    void value_from_basic_group() {
      assertThat(service.getSettingValue("systemName", "default"))
          .isEqualTo("工单审批流转系统");
    }

    @Test
    @DisplayName("getSettingValue 未命中返回默认值")
    void value_fallback_to_default() {
      assertThat(service.getSettingValue("nonexistentKey", "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("getSettingsByGroup 委托 mapper")
    void settings_by_group() {
      SystemSetting s = new SystemSetting("basic", "version", "v1", "版本");
      when(systemSettingMapper.selectByGroup("basic")).thenReturn(List.of(s));
      assertThat(service.getSettingsByGroup("basic")).containsExactly(s);
    }
  }

  @Nested
  @DisplayName("initDefaultSettings / save")
  class InitAndSaveTests {

    @Test
    @DisplayName("已存在数据跳过初始化")
    void skip_init_when_exists() {
      when(systemSettingMapper.countAll()).thenReturn(5L);
      service.initDefaultSettings();
      verify(systemSettingMapper, never()).insert(any(SystemSetting.class));
    }

    @Test
    @DisplayName("空库初始化插入全部默认设置")
    void init_inserts_all_defaults() {
      when(systemSettingMapper.countAll()).thenReturn(null);
      service.initDefaultSettings();
      verify(systemSettingMapper, times(38)).insert(any(SystemSetting.class));
    }

    @Test
    @DisplayName("saveBasicSettings 新增设置")
    void save_basic_inserts_new() {
      when(systemSettingMapper.selectByKey("basic", "systemName")).thenReturn(null);

      assertThat(service.saveBasicSettings(Map.of("systemName", "新系统"))).isTrue();
      verify(systemSettingMapper).insert(any(SystemSetting.class));
    }
  }
}