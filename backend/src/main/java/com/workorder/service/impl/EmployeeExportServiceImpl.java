package com.workorder.service.impl;


import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Service;

import com.workorder.entity.User;
import com.workorder.service.IEmployeeExportService;

/**
 * 员工数据导出服务实现
 *
 * <p>深模块：集中处理 CSV 格式化、编码转换、BOM 头等导出相关逻辑。
 */
@Service
public class EmployeeExportServiceImpl implements IEmployeeExportService {

  // ========== 职位 ID 常量 ==========
  private static final int POSITION_SALES_DIRECTOR = 21;
  private static final int POSITION_FINANCE_DIRECTOR = 22;
  private static final int POSITION_ADMIN_DIRECTOR = 23;
  private static final int POSITION_PURCHASING_DIRECTOR = 25;
  private static final int POSITION_ADMIN_SPECIALIST = 31;
  private static final int POSITION_CASHIER_SPECIALIST = 33;
  private static final int POSITION_SALES_SPECIALIST = 34;
  private static final int POSITION_RND_ENGINEER_SPECIALIST = 35;
  private static final int POSITION_PURCHASING_SPECIALIST = 36;
  private static final int POSITION_REGULAR_EMPLOYEE = 40;

  @Override
  public void exportToCsv(List<User> employees, HttpServletResponse response) throws IOException {
    response.setContentType("text/csv;charset=UTF-8");
    response.setCharacterEncoding("UTF-8");
    String fileName = "员工数据_" + LocalDate.now() + ".csv";
    response.setHeader(
        "Content-Disposition", "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8"));

    try (PrintWriter writer = response.getWriter()) {
      writer.write('\ufeff'); // BOM头，解决Excel中文编码问题
      writer.println("ID,姓名,手机号,邮箱,部门,职位,组织层级,状态,入职时间");

      for (User emp : employees) {
        StringBuilder sb = new StringBuilder();
        sb.append(emp.getId()).append(",");
        sb.append(csvEscape(emp.getRealName())).append(",");
        sb.append(csvEscape(emp.getPhone())).append(",");
        sb.append(csvEscape(emp.getEmail())).append(",");
        sb.append(csvEscape(emp.getDepartment())).append(",");
        sb.append(
                getPositionName(
                    emp.getPositionId() != null ? emp.getPositionId().intValue() : null))
            .append(",");
        sb.append(getOrgLevelName(emp.getOrgLevel() != null ? emp.getOrgLevel().intValue() : null))
            .append(",");
        sb.append(emp.getStatus() != null && emp.getStatus() == 1 ? "启用" : "禁用").append(",");
        sb.append(emp.getHireDate() != null ? emp.getHireDate().toString() : "");
        writer.println(sb.toString());
      }
      writer.flush();
    }
  }

  @Override
  public String getPositionName(Integer positionId) {
    if (positionId == null) return "-";
    switch (positionId) {
      case 1:
        return "超级管理员";
      case 2:
        return "安全审计管理员";
      case 10:
        return "董事长";
      case 11:
        return "总经理";
      case 12:
        return "副总经理";
      case 20:
        return "研发总监";
      case POSITION_SALES_DIRECTOR:
        return "销售总监";
      case POSITION_FINANCE_DIRECTOR:
        return "财务总监";
      case POSITION_ADMIN_DIRECTOR:
        return "行政总监";
      case 24:
        return "人事总监";
      case POSITION_PURCHASING_DIRECTOR:
        return "采购总监";
      case 30:
        return "HR人事专员";
      case POSITION_ADMIN_SPECIALIST:
        return "行政专员";
      case 32:
        return "费用会计专员";
      case POSITION_CASHIER_SPECIALIST:
        return "出纳专员";
      case POSITION_SALES_SPECIALIST:
        return "销售专员";
      case POSITION_RND_ENGINEER_SPECIALIST:
        return "研发工程师专员";
      case POSITION_PURCHASING_SPECIALIST:
        return "采购专员";
      case POSITION_REGULAR_EMPLOYEE:
        return "普通员工";
      default:
        return "-";
    }
  }

  @Override
  public String getOrgLevelName(Integer orgLevel) {
    if (orgLevel == null) return "-";
    switch (orgLevel) {
      case 0:
        return "系统级";
      case 1:
        return "高管级";
      case 2:
        return "管理级";
      case 3:
        return "专员级";
      case 4:
        return "员工级";
      default:
        return "-";
    }
  }

  private String csvEscape(String value) {
    if (value == null) return "";
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }
}
