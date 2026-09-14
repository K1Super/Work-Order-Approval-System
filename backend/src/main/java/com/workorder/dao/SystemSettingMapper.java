package com.workorder.dao;


import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.workorder.entity.SystemSetting;

/**
 * 系统设置数据访问接口
 *
 * @author KLord
 */
@Mapper
public interface SystemSettingMapper {

  /**
   * 根据分组查询设置列表
   *
   * @param groupKey 分组键
   * @return 设置列表
   */
  @Select(
      "SELECT id, group_key, setting_key, setting_value, description, create_time, update_time "
          + "FROM sys_system_setting WHERE group_key = #{groupKey} ORDER BY id")
  List<SystemSetting> selectByGroup(@Param("groupKey") String groupKey);

  /**
   * 根据分组和键查询单个设置
   *
   * @param groupKey 分组键
   * @param settingKey 设置键
   * @return 设置对象
   */
  @Select(
      "SELECT id, group_key, setting_key, setting_value, description, create_time, update_time "
          + "FROM sys_system_setting WHERE group_key = #{groupKey} AND setting_key = #{settingKey}")
  SystemSetting selectByKey(
      @Param("groupKey") String groupKey, @Param("settingKey") String settingKey);

  /**
   * 插入设置
   *
   * @param setting 设置对象
   * @return 影响行数
   */
  @Insert(
      "INSERT INTO sys_system_setting (group_key, setting_key, setting_value, description, create_time, update_time) "
          + "VALUES (#{groupKey}, #{settingKey}, #{settingValue}, #{description}, #{createTime}, #{updateTime})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(SystemSetting setting);

  /**
   * 更新设置
   *
   * @param setting 设置对象
   * @return 影响行数
   */
  @Update(
      "UPDATE sys_system_setting SET setting_value = #{settingValue}, description = #{description}, "
          + "update_time = #{updateTime} WHERE id = #{id}")
  int update(SystemSetting setting);

  /**
   * 统计所有设置数量
   *
   * @return 数量
   */
  @Select("SELECT COUNT(*) FROM sys_system_setting")
  Long countAll();
}
