package com.workorder.service.impl;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.enums.UserStatusEnum;
import com.workorder.common.result.Result;
import com.workorder.dao.ResignedEmployeeMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.ResignedEmployee;
import com.workorder.entity.User;
import com.workorder.service.IResignedEmployeeService;

/**
 * 离职员工服务实现类
 */
@Service
public class ResignedEmployeeServiceImpl implements IResignedEmployeeService {

  private static final Logger logger = LoggerFactory.getLogger(ResignedEmployeeServiceImpl.class);

  @Autowired
  private ResignedEmployeeMapper resignedEmployeeMapper;

  @Autowired
  private UserMapper userMapper;

    /**
     * 员工离职处理（核心方法）
     * 1. 将员工信息备份到离职表
     * 2. 将原用户状态设为-2（已离职）
     * 3. 删除用户角色关联
     */
  @Override
  @Transactional
  public Result<ResignedEmployee> processResignation(Long userId, Integer resignType,
                           String resignReason, String remark,
                           Long operatorId) {
    logger.info("开始处理员工离职: userId={}, resignType={}, operatorId={}",
         userId, resignType, operatorId);

    // 1. 验证员工是否存在
    User employee = userMapper.selectById(userId);
    if (employee == null) {
      return Result.error("员工不存在");
    }

    // 2. 检查是否已离职或已删除（W-06 修复：枚举替代魔法数字）
    if (employee.getStatus() != null
        && (employee.getStatus() == UserStatusEnum.DELETED.getCode()
            || employee.getStatus() == UserStatusEnum.RESIGNED.getCode())) {
      return Result.error("该员工已离职或已删除，无法重复操作");
    }

    // 3. 获取操作人信息
    User operator = userMapper.selectById(operatorId);
    String operatorName = operator != null ? operator.getRealName() : "系统";

    // 4. 获取职位名称
    String positionName = getPositionName(employee.getPositionId());

    // 5. 创建离职记录对象
    ResignedEmployee resigned = new ResignedEmployee();
    resigned.setUserId(userId);
    resigned.setUsername(employee.getUsername());
    resigned.setRealName(employee.getRealName());
    resigned.setEmail(employee.getEmail());
    resigned.setPhone(employee.getPhone());
    resigned.setDepartment(employee.getDepartment());

    // 处理可能为null的ID字段
    if (employee.getDepartmentId() != null) {
      resigned.setDepartmentId(employee.getDepartmentId());
    }
    if (employee.getPositionId() != null) {
      resigned.setPositionId(employee.getPositionId());
    }

    resigned.setPositionName(positionName);

    // 处理组织层级
    if (employee.getOrgLevel() != null) {
      resigned.setOrgLevel(employee.getOrgLevel());
    }

    // 处理入职日期（String转LocalDate）
    if (employee.getHireDate() != null && !employee.getHireDate().isEmpty()) {
      try {
        // hireDate是String类型，需要解析
        String hireDateStr = employee.getHireDate();
        // 处理可能带时间的日期字符串
        if (hireDateStr.contains("T")) {
          hireDateStr = hireDateStr.split("T")[0];
        } else if (hireDateStr.contains(" ")) {
          hireDateStr = hireDateStr.split(" ")[0];
        }
        resigned.setHireDate(LocalDate.parse(hireDateStr));
      } catch (Exception e) {
        logger.warn("解析入职日期失败: {}", employee.getHireDate());
      }
    }

    resigned.setResignDate(LocalDate.now());
    resigned.setResignType(resignType);
    resigned.setResignReason(resignReason);
    resigned.setRemark(remark);
    resigned.setOperatorId(operatorId);
    resigned.setOperatorName(operatorName);
    resigned.setCreateTime(LocalDateTime.now());

    // 6. 插入离职记录
    int insertResult = resignedEmployeeMapper.insert(resigned);
    if (insertResult <= 0) {
      logger.error("插入离职记录失败: userId={}", userId);
      return Result.error("保存离职信息失败");
    }
    logger.info("离职记录已保存: id={}, userId={}", resigned.getId(), userId);

    // 7. 更新原用户状态为已离职（W-06 修复：枚举替代魔法数字 -2）
    User update = new User();
    update.setId(userId);
    update.setStatus(UserStatusEnum.RESIGNED.getCode()); // 已离职状态
    int updateResult = userMapper.updateById(update);
    if (updateResult <= 0) {
      logger.error("更新用户状态为离职失败: userId={}", userId);
      throw new RuntimeException("更新用户状态失败");
    }
    logger.info("用户状态已更新为离职: userId={}", userId);

    // 8. 删除用户角色关联（禁用账号）
    try {
      userMapper.deleteUserRoles(userId);
      logger.info("已删除离职用户的角色权限: userId={}", userId);
    } catch (Exception e) {
      logger.warn("删除用户角色时出错（不影响主流程）: userId={}, error={}", userId, e.getMessage());
    }

    logger.info("员工离职处理完成: userId={}, realName={}", userId, employee.getRealName());
    return Result.success(resigned);
  }

    /**
     * 获取离职员工列表（分页）
     */
  @Override
  public Result<Map<String, Object>> getResignedList(Integer pageNum, Integer pageSize,
                           String keyword, Integer departmentId,
                           Integer resignType) {
    Map<String, Object> params = new HashMap<>();
    params.put("pageNum", pageNum != null ? pageNum : 1);
    params.put("pageSize", pageSize != null ? pageSize : 10);

    if (keyword != null && !keyword.isEmpty()) {
      params.put("keyword", "%" + keyword + "%");
    }
    if (departmentId != null) {
      params.put("departmentId", departmentId);
    }
    if (resignType != null) {
      params.put("resignType", resignType);
    }

    List<ResignedEmployee> list = resignedEmployeeMapper.selectList(params);
    int total = resignedEmployeeMapper.countTotal(params);

    Map<String, Object> data = new HashMap<>();
    data.put("list", list);
    data.put("total", total);

    return Result.success(data);
  }

    /**
     * 获取离职员工详情
     */
  @Override
  public Result<ResignedEmployee> getResignedById(Long id) {
    ResignedEmployee resigned = resignedEmployeeMapper.selectById(id);
    if (resigned == null) {
      return Result.error("离职记录不存在");
    }
    return Result.success(resigned);
  }

    /**
     * 获取离职类型列表
     */
  @Override
  public Result<List<Map<String, Object>>> getResignTypes() {
    List<Map<String, Object>> types = new ArrayList<>();

    Map<String, Object> type1 = new HashMap<>();
    type1.put("value", 1);
    type1.put("label", "主动辞职");
    types.add(type1);

    Map<String, Object> type2 = new HashMap<>();
    type2.put("value", 2);
    type2.put("label", "被动辞退");
    types.add(type2);

    Map<String, Object> type3 = new HashMap<>();
    type3.put("value", 3);
    type3.put("label", "合同到期");
    types.add(type3);

    Map<String, Object> type4 = new HashMap<>();
    type4.put("value", 4);
    type4.put("label", "退休");
    types.add(type4);

    Map<String, Object> type5 = new HashMap<>();
    type5.put("value", 5);
    type5.put("label", "其他");
    types.add(type5);

    return Result.success(types);
  }

    /**
     * 根据职位ID获取职位名称
     */
  private String getPositionName(Long positionId) {
    if (positionId == null) return "-";
    switch (positionId.intValue()) {
      case 1: return "董事长";
      case 2: return "总经理";
      case 3: return "运营副总裁";
      case 4: return "CFO";
      case 5: return "CHRO";
      case 6: return "研发总监";
      case 7: return "销售总监";
      case 8: return "财务总监";
      case 9: return "HR专员";
      case 10: return "会计";
      case 11: return "IT支持";
      case 12: return "行政专员";
      case 13: return "普通员工";
      default: return "-";
    }
  }
}
