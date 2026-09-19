package com.workorder.controller;


import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workorder.common.result.Result;

/**
 * 系统维护控制器
 *
 * <p>将 Debug/修复/数据初始化端点从 EmployeeController 中分离， 避免生产代码与维护工具代码混合。
 *
 * <p>W-11：维护接口仅开发环境加载（@Profile("dev")，生产不加载此 Bean、端点完全不存在）， 且仅超级管理员可调用
 * （@PreAuthorize("hasRole('SUPER_ADMIN')")）； 内部破坏性重建已改为逻辑删除 + UPSERT 恢复，避免物理删除。
 */
@Profile("dev")
@RestController
@RequestMapping("/maintenance")
public class MaintenanceController {

  private static final Logger logger = LoggerFactory.getLogger(MaintenanceController.class);

  /** 字节掩码（用于 byte→int 无符号转换） */
  private static final int BYTE_MASK = 0xFF;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 修复部门/职位中文乱码数据（W-11：仅超管 + dev 环境；破坏性删除改为逻辑删除+UPSERT 重建） */
  @PostMapping("/fix-encoding")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<String> fixEncoding() {
    try {
      Properties props =
          PropertiesLoaderUtils.loadProperties(new ClassPathResource("chinese-data.properties"));

      // W-11：破坏性物理 DELETE 改为逻辑删除（sys_department/sys_position 均含 is_deleted 列）
      jdbcTemplate.update("UPDATE sys_department SET is_deleted = 1 WHERE is_deleted = 0");
      jdbcTemplate.update("UPDATE sys_position SET is_deleted = 1 WHERE is_deleted = 0");

      String[][] deptData = {
          {"1", "总经办", "GENERAL_OFFICE", "1", "1"},
          {"2", "人事部", "HR", "3", "2"},
          {"3", "财务部", "FINANCE", "3", "3"},
          {"4", "研发部", "R&D", "2", "4"},
          {"5", "销售部", "SALES", "2", "5"},
          {"6", "行政部", "ADMIN", "3", "6"},
          {"7", "采购部", "PROCUREMENT", "3", "7"},
          {"8", "运维部", "OPS", "3", "8"}
      };
      for (String[] d : deptData) {
        jdbcTemplate.update(
            "INSERT INTO sys_department (id, dept_name, dept_code, org_level, sort_order, is_deleted) "
                + "VALUES (?, ?, ?, ?, ?, 0) "
                + "ON CONFLICT (id) DO UPDATE SET dept_name = EXCLUDED.dept_name, "
                + "dept_code = EXCLUDED.dept_code, org_level = EXCLUDED.org_level, "
                + "sort_order = EXCLUDED.sort_order, is_deleted = 0",
            Integer.parseInt(d[0]),
            d[1],
            d[2],
            Integer.parseInt(d[3]),
            Integer.parseInt(d[4]));
      }

      String[][] posData = {
          {"1", "超级管理员", "SUPER_ADMIN", "0", "0"},
          {"2", "安全审计管理员", "SECURITY_AUDIT", "0", "0"},
          {"10", "董事长", "CHAIRMAN", "1", "1"},
          {"11", "总经理", "GM", "1", "1"},
          {"12", "副总经理", "VP", "1", "1"},
          {"20", "研发总监", "RD_DIR", "4", "2"},
          {"21", "销售总监", "SALES_DIR", "5", "2"},
          {"22", "财务总监", "FIN_DIR", "3", "2"},
          {"23", "行政总监", "ADMIN_DIR", "6", "2"},
          {"24", "人事总监", "HR_DIR", "2", "2"},
          {"25", "采购总监", "PROCUREMENT_DIR", "7", "2"},
          {"30", "HR人事专员", "HR_SPEC", "2", "3"},
          {"31", "行政专员", "ADMIN_SPEC", "6", "3"},
          {"32", "费用会计专员", "ACCOUNTANT_SPEC", "3", "3"},
          {"33", "出纳专员", "CASHIER_SPEC", "3", "3"},
          {"34", "销售专员", "SALES_SPEC", "5", "3"},
          {"35", "研发工程师专员", "RD_ENGINEER_SPEC", "4", "3"},
          {"36", "采购专员", "PROCUREMENT_SPEC", "7", "3"},
          {"40", "普通员工", "STAFF", "0", "4"}
      };
      for (String[] p : posData) {
        jdbcTemplate.update(
            "INSERT INTO sys_position (id, position_name, position_code, dept_id, org_level, is_deleted) "
                + "VALUES (?, ?, ?, ?, ?, 0) "
                + "ON CONFLICT (id) DO UPDATE SET position_name = EXCLUDED.position_name, "
                + "position_code = EXCLUDED.position_code, dept_id = EXCLUDED.dept_id, "
                + "org_level = EXCLUDED.org_level, is_deleted = 0",
            Integer.parseInt(p[0]),
            p[1],
            p[2],
            Integer.parseInt(p[3]),
            Integer.parseInt(p[4]));
      }

      return Result.success("OK");
    } catch (Exception e) {
      logger.error("[Maintenance] 修复部门/职位中文乱码数据失败", e);
      return Result.error("数据修复失败，请稍后重试");
    }
  }

  /** 修复角色名称为中文（5级权限架构） */
  @PostMapping("/fix-roles")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<String> fixRoles() {
    try {
      String[][] roleUpdates = {
          {"1", "超级管理员", "系统完全访问权限 - 系统级"},
          {"2", "安全审计管理员", "安全审计管理权限 - 系统级"},
          {"10", "董事长", "董事会总负责人 - 高管级"},
          {"11", "总经理", "公司总经理 - 高管级"},
          {"12", "副总经理", "公司副总经理 - 高管级"},
          {"20", "研发总监", "研发部总监 - 管理级"},
          {"21", "销售总监", "销售部总监 - 管理级"},
          {"22", "财务总监", "财务部总监 - 管理级"},
          {"23", "行政总监", "行政部总监 - 管理级"},
          {"24", "人事总监", "人事部总监 - 管理级"},
          {"25", "采购总监", "采购部总监 - 管理级"},
          {"30", "HR人事专员", "人力资源专员 - 专员级"},
          {"31", "行政专员", "行政人员 - 专员级"},
          {"32", "费用会计专员", "财务会计 - 专员级"},
          {"33", "出纳专员", "出纳人员 - 专员级"},
          {"34", "销售专员", "销售人员 - 专员级"},
          {"35", "研发工程师专员", "研发工程师 - 专员级"},
          {"36", "采购专员", "采购人员 - 专员级"},
          {"40", "普通员工", "基层员工 - 员工级"}
      };
      for (String[] r : roleUpdates) {
        jdbcTemplate.update(
            "UPDATE sys_role SET role_name = ?, description = ? WHERE id = ?",
            r[1],
            r[2],
            Integer.parseInt(r[0]));
      }
      return Result.success("角色名称已更新为中文（共19个角色，5级权限架构）");
    } catch (Exception e) {
      logger.error("[Maintenance] 修复角色名称失败", e);
      return Result.error("角色修复失败，请稍后重试");
    }
  }

  /** 调试编码问题 */
  @GetMapping("/debug-encoding")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Result<Map<String, Object>> debugEncoding() {
    Map<String, Object> result = new java.util.HashMap<>();
    try {
      String name =
          jdbcTemplate.queryForObject(
              "SELECT dept_name FROM sys_department WHERE id = 1", String.class);
      result.put("db_string", name);
      result.put("db_bytes_hex", bytesToHex(name.getBytes("UTF-8")));
      result.put("db_bytes_iso", bytesToHex(name.getBytes("ISO-8859-1")));
      result.put("jvm_encoding", System.getProperty("file.encoding"));
      return Result.success(result);
    } catch (Exception e) {
      logger.error("[Maintenance] 调试编码信息失败", e);
      return Result.error("调试信息获取失败，请稍后重试");
    }
  }

  private String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X ", b & BYTE_MASK));
    }
    return sb.toString().trim();
  }
}
