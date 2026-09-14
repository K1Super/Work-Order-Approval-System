package com.workorder.dao;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.entity.ResignedEmployee;

/** 离职员工Mapper接口 */
@Mapper
public interface ResignedEmployeeMapper {

  /** 插入离职员工记录 */
  int insert(ResignedEmployee resignedEmployee);

  /** 根据ID查询离职员工 */
  ResignedEmployee selectById(@Param("id") Long id);

  /** 分页查询离职员工列表 */
  List<ResignedEmployee> selectList(Map<String, Object> params);

  /** 统计离职员工总数 */
  int countTotal(Map<String, Object> params);

  /** 根据原用户ID查询 */
  ResignedEmployee selectByUserId(@Param("userId") Long userId);
}
