package com.workorder.service;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import com.workorder.entity.User;

/**
 * 员工数据导出服务接口
 *
 * <p>将 CSV 导出格式化逻辑从 Controller 中提取为独立 Service， 使 Controller 保持纯路由职责。
 */
public interface IEmployeeExportService {

  /** 导出员工列表为 CSV 文件 */
  void exportToCsv(List<User> employees, HttpServletResponse response) throws IOException;

  /** 根据职位ID获取中文名称 */
  String getPositionName(Integer positionId);

  /** 根据组织层级获取中文名称 */
  String getOrgLevelName(Integer orgLevel);
}
