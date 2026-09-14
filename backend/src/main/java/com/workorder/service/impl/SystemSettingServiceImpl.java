package com.workorder.service.impl;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.dao.SystemSettingMapper;
import com.workorder.entity.SystemSetting;
import com.workorder.service.ISystemSettingService;

/**
 * System Setting Service Implementation
 *
 * @author KLord
 */
@Service
public class SystemSettingServiceImpl implements ISystemSettingService {

  private static final Logger logger = LoggerFactory.getLogger(SystemSettingServiceImpl.class);

  /** SMTP 默认端口（SMTPS） */
  private static final int SMTP_PORT_SMTPS = 465;

  @Autowired private SystemSettingMapper systemSettingMapper;

  /** Default settings definitions */
  private static final Map<String, Object> DEFAULT_BASIC_SETTINGS =
      new LinkedHashMap<String, Object>() {
        {
          put("systemName", "工单审批流转系统");
          put("systemDescription", "企业级工单管理与审批流转平台");
          put("version", "v2.8.2");
        }
      };

  private static final Map<String, Object> DEFAULT_WORKORDER_SETTINGS =
      new LinkedHashMap<String, Object>() {
        {
          put("defaultTimeout", 24);
          put("allowWithdraw", true);
          put("allowReturn", true);
          put("dailySubmitLimit", 20);
          put("autoApproveLowAmount", false);
          put("lowAmountThreshold", 1000);
          put("requireAttachment", false);
          put("allowUrgent", true);
          put("urgentTimeoutMultiplier", 2);
          put("enableAutoArchive", true);
          put("archiveDaysAfterComplete", 30);
        }
      };

  private static final Map<String, Object> DEFAULT_NOTIFICATION_SETTINGS =
      new LinkedHashMap<String, Object>() {
        {
          put("enableInternalMsg", true);
          put("notifyNewOrder", true);
          put("notifyApprovalResult", true);
          put("notifyTimeoutWarning", true);
          put("notifyOrderReturn", true);
          put("notifyWithdraw", false);
          put("enableEmail", false);
          put("smtpHost", "");
          put("smtpPort", SMTP_PORT_SMTPS);
          put("smtpUsername", "");
          put("smtpPassword", "");
          put("senderEmail", "");
          put("senderName", "工单审批系统");
          put("emailTemplate", "default");
        }
      };

  private static final Map<String, Object> DEFAULT_SECURITY_SETTINGS =
      new LinkedHashMap<String, Object>() {
        {
          put("minPasswordLength", 8);
          put("requireLowercase", false); // 要求小写字母
          put("requireUppercase", false); // 要求大写字母
          put("requireDigit", false);
          put("requireSpecialChar", false);
          put("passwordExpiryDays", 0); // 0表示不限制
          put("maxPasswordHistory", 5); // 密码历史记录数
          put("tokenExpiryHours", 24);
          put("maxLoginAttempts", 5);
          put("lockoutDuration", 30); // 锁定时间(分钟)
        }
      };

  private static final Map<String, String> SETTING_DESCRIPTIONS =
      new HashMap<String, String>() {
        {
          // Basic Settings
          put("basic.systemName", "系统名称");
          put("basic.systemDescription", "系统描述");
          put("basic.version", "版本号");
          // WorkOrder Settings
          put("workorder.defaultTimeout", "默认超时时间(小时)");
          put("workorder.allowWithdraw", "允许撤回已提交工单");
          put("workorder.allowReturn", "允许退回审批");
          put("workorder.dailySubmitLimit", "每日提交上限");
          put("workorder.autoApproveLowAmount", "低金额自动审批");
          put("workorder.lowAmountThreshold", "低金额阈值(元)");
          put("workorder.requireAttachment", "要求必须上传附件");
          put("workorder.allowUrgent", "允许加急处理");
          put("workorder.urgentTimeoutMultiplier", "加急超时倍数");
          put("workorder.enableAutoArchive", "启用自动归档");
          put("workorder.archiveDaysAfterComplete", "归档天数(完成后)");
          // Notification Settings
          put("notification.enableInternalMsg", "启用站内消息");
          put("notification.notifyNewOrder", "新工单通知");
          put("notification.notifyApprovalResult", "审批结果通知");
          put("notification.notifyTimeoutWarning", "超时预警通知");
          put("notification.notifyOrderReturn", "工单退回通知");
          put("notification.notifyWithdraw", "工单撤回通知");
          put("notification.enableEmail", "启用邮件通知");
          put("notification.smtpHost", "SMTP服务器");
          put("notification.smtpPort", "SMTP端口");
          put("notification.smtpUsername", "SMTP用户名");
          put("notification.smtpPassword", "SMTP密码");
          put("notification.senderEmail", "发件人邮箱");
          put("notification.senderName", "发件人名称");
          put("notification.emailTemplate", "邮件模板");
          // Security Settings
          put("security.minPasswordLength", "最小密码长度");
          put("security.requireLowercase", "要求包含小写字母");
          put("security.requireUppercase", "要求包含大写字母");
          put("security.requireDigit", "要求包含数字");
          put("security.requireSpecialChar", "要求包含特殊字符");
          put("security.passwordExpiryDays", "密码有效期(天)");
          put("security.maxPasswordHistory", "密码历史记录数");
          put("security.tokenExpiryHours", "Token过期时间(小时)");
          put("security.maxLoginAttempts", "最大登录失败次数");
          put("security.lockoutDuration", "锁定时间(分钟)");
        }
      };

  @Override
  public Map<String, Object> getAllSettings() {
    Map<String, Object> result = new LinkedHashMap<>();

    result.put("basic", getGroupSettingsMap("basic", DEFAULT_BASIC_SETTINGS));
    result.put("workorder", getGroupSettingsMap("workorder", DEFAULT_WORKORDER_SETTINGS));
    result.put("notification", getGroupSettingsMap("notification", DEFAULT_NOTIFICATION_SETTINGS));
    result.put("security", getGroupSettingsMap("security", DEFAULT_SECURITY_SETTINGS));

    return result;
  }

  @Override
  public List<SystemSetting> getSettingsByGroup(String groupKey) {
    return systemSettingMapper.selectByGroup(groupKey);
  }

  @Override
  public String getSettingValue(String settingKey, String defaultValue) {
    // Try to get from security settings first (most common use case)
    Map<String, Object> allSettings = getAllSettings();
    if (allSettings != null && allSettings.containsKey("security")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> securitySettings = (Map<String, Object>) allSettings.get("security");
      if (securitySettings != null && securitySettings.containsKey(settingKey)) {
        Object value = securitySettings.get(settingKey);
        return value != null ? value.toString() : defaultValue;
      }
    }

    // If not found in security settings, try basic settings
    if (allSettings != null && allSettings.containsKey("basic")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> basicSettings = (Map<String, Object>) allSettings.get("basic");
      if (basicSettings != null && basicSettings.containsKey(settingKey)) {
        Object value = basicSettings.get(settingKey);
        return value != null ? value.toString() : defaultValue;
      }
    }

    logger.debug("Setting '{}' not found, using default value: {}", settingKey, defaultValue);
    return defaultValue;
  }

  @Override
  @Transactional
  public boolean saveBasicSettings(Map<String, Object> settings) {
    saveSettingsMap("basic", settings, DEFAULT_BASIC_SETTINGS);
    logger.info("基本设置保存成功");
    return true;
  }

  @Override
  @Transactional
  public boolean saveWorkorderSettings(Map<String, Object> settings) {
    saveSettingsMap("workorder", settings, DEFAULT_WORKORDER_SETTINGS);
    logger.info("工单设置保存成功");
    return true;
  }

  @Override
  @Transactional
  public boolean saveNotificationSettings(Map<String, Object> settings) {
    saveSettingsMap("notification", settings, DEFAULT_NOTIFICATION_SETTINGS);
    logger.info("通知设置保存成功");
    return true;
  }

  @Override
  @Transactional
  public boolean saveSecuritySettings(Map<String, Object> settings) {
    saveSettingsMap("security", settings, DEFAULT_SECURITY_SETTINGS);
    logger.info("安全设置保存成功");
    return true;
  }

  @Override
  @Transactional
  public void initDefaultSettings() {
    // Check if data already exists
    Long count = systemSettingMapper.countAll();
    if (count != null && count > 0) {
      logger.info("系统设置已存在，跳过初始化");
      return;
    }

    LocalDateTime now = LocalDateTime.now();
    List<SystemSetting> settings = new ArrayList<>();

    // Add all default settings
    addDefaultSettings(settings, "basic", DEFAULT_BASIC_SETTINGS, now);
    addDefaultSettings(settings, "workorder", DEFAULT_WORKORDER_SETTINGS, now);
    addDefaultSettings(settings, "notification", DEFAULT_NOTIFICATION_SETTINGS, now);
    addDefaultSettings(settings, "security", DEFAULT_SECURITY_SETTINGS, now);

    for (SystemSetting setting : settings) {
      systemSettingMapper.insert(setting);
    }

    logger.info("系统设置初始化完成，共{}条记录", settings.size());
  }

  /** Get group settings map (merge database values with defaults) */
  private Map<String, Object> getGroupSettingsMap(String groupKey, Map<String, Object> defaults) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<SystemSetting> dbSettings = systemSettingMapper.selectByGroup(groupKey);

    // Build quick lookup map for database settings
    Map<String, String> dbValues = new HashMap<>();
    if (dbSettings != null) {
      for (SystemSetting s : dbSettings) {
        dbValues.put(s.getSettingKey(), s.getSettingValue());
      }
    }

    // Merge: prefer database values, fallback to defaults
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      String key = entry.getKey();
      Object defaultValue = entry.getValue();

      if (dbValues.containsKey(key)) {
        // Database has value, convert type and return
        result.put(key, convertValue(dbValues.get(key), defaultValue));
      } else {
        // Use default value
        result.put(key, defaultValue);
      }
    }

    return result;
  }

  /** Save settings map to database */
  private void saveSettingsMap(
      String groupKey, Map<String, Object> settings, Map<String, Object> defaults) {
    for (Map.Entry<String, Object> entry : settings.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      String strValue = value != null ? value.toString() : "";

      String descKey = groupKey + "." + key;
      String description = SETTING_DESCRIPTIONS.getOrDefault(descKey, "");

      // Try to update or insert
      SystemSetting existing = systemSettingMapper.selectByKey(groupKey, key);
      if (existing != null) {
        existing.setSettingValue(strValue);
        existing.setUpdateTime(LocalDateTime.now());
        systemSettingMapper.update(existing);
      } else {
        SystemSetting newSetting = new SystemSetting();
        newSetting.setGroupKey(groupKey);
        newSetting.setSettingKey(key);
        newSetting.setSettingValue(strValue);
        newSetting.setDescription(description);
        newSetting.setCreateTime(LocalDateTime.now());
        newSetting.setUpdateTime(LocalDateTime.now());
        systemSettingMapper.insert(newSetting);
      }
    }
  }

  /** Add default settings list */
  private void addDefaultSettings(
      List<SystemSetting> list, String groupKey, Map<String, Object> defaults, LocalDateTime now) {
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      String key = entry.getKey();
      String descKey = groupKey + "." + key;

      SystemSetting setting = new SystemSetting();
      setting.setGroupKey(groupKey);
      setting.setSettingKey(key);
      setting.setSettingValue(entry.getValue() != null ? entry.getValue().toString() : "");
      setting.setDescription(SETTING_DESCRIPTIONS.getOrDefault(descKey, ""));
      setting.setCreateTime(now);
      setting.setUpdateTime(now);

      list.add(setting);
    }
  }

  /** Type conversion: convert string value to same type as default value */
  private Object convertValue(String stringValue, Object defaultValue) {
    if (defaultValue == null || stringValue == null) {
      return stringValue;
    }

    if (defaultValue instanceof Boolean) {
      return "true".equalsIgnoreCase(stringValue) || "1".equals(stringValue);
    } else if (defaultValue instanceof Integer) {
      try {
        return Integer.parseInt(stringValue);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    } else if (defaultValue instanceof Long) {
      try {
        return Long.parseLong(stringValue);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    } else if (defaultValue instanceof Double) {
      try {
        return Double.parseDouble(stringValue);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }

    return stringValue;
  }
}
