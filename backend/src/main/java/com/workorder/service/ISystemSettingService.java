package com.workorder.service;


import java.util.List;
import java.util.Map;


import com.workorder.entity.SystemSetting;

/**
 * 系统设置服务接口
 *
 * @author KLord
 */
public interface ISystemSettingService {

  /**
   * 获取所有系统设置（按分组返回）
   *
   * @return 分组后的设置数据
   */
  Map<String, Object> getAllSettings();

  /**
   * 获取指定分组的设置
   *
   * @param groupKey 分组键
   * @return 设置列表
   */
  List<SystemSetting> getSettingsByGroup(String groupKey);

  /**
   * 获取单个设置值
   *
   * @param settingKey Setting key (e.g., "maxLoginAttempts")
   * @param defaultValue Default value if not found
   * @return Setting value or defaultValue
   */
  String getSettingValue(String settingKey, String defaultValue);

  /**
   * 保存基本设置
   *
   * @param settings 基本设置Map
   * @return 是否成功
   */
  boolean saveBasicSettings(Map<String, Object> settings);

  /**
   * 保存工单设置
   *
   * @param settings 工单设置Map
   * @return 是否成功
   */
  boolean saveWorkorderSettings(Map<String, Object> settings);

  /**
   * 保存通知设置
   *
   * @param settings 通知设置Map
   * @return 是否成功
   */
  boolean saveNotificationSettings(Map<String, Object> settings);

  /**
   * 保存安全设置
   *
   * @param settings 安全设置Map
   * @return 是否成功
   */
  boolean saveSecuritySettings(Map<String, Object> settings);

  /** 初始化默认设置（首次启动时调用） */
  void initDefaultSettings();
}
