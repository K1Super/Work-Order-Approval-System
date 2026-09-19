package com.workorder.config;


import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 数据库初始化器 — 应用启动时自动创建表、初始化基础数据
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

  /** sys_position 表预期记录数（初始化种子数据条数） */
  private static final int EXPECTED_POSITION_COUNT = 19;

  @Autowired private DataSource dataSource;

  @Value("${spring.profiles.active:dev}")
  private String activeProfile;

  @Value("${jwt.secret:}")
  private String jwtSecret;

  @Lazy
  @Autowired(required = false)
  private org.flowable.engine.RepositoryService repositoryService;

  @Override
  public void run(String... args) throws Exception {

    // 生产环境安全检查
    performSecurityChecks();

    try (Connection connection = dataSource.getConnection()) {
      setUtf8Encoding(connection);

      // Schema 事实源已统一为 Flyway（V1~V12），不再加载已废弃为空脚本的 sql/schema-init.sql。
      verifyDataIntegrity(connection);

      logSystemStatus(connection);

      deployFlowableProcessDefinitions();
    } catch (SQLException e) {
      logger.error("Database initialization failed: {}", e.getMessage(), e);
      throw e;
    }
  }

  private void setUtf8Encoding(Connection connection) throws SQLException {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("SET NAMES 'UTF-8'");
      stmt.execute("SET client_encoding TO 'UTF-8'");
    }
  }

  private void verifyDataIntegrity(Connection connection) throws Exception {
    try (Statement stmt = connection.createStatement()) {
      int fixes = 0;

      // Fix permission status
      try (var rs =
          stmt.executeQuery("SELECT COUNT(*) FROM sys_permission WHERE status NOT IN (0, 1)")) {
        if (rs.next() && rs.getInt(1) > 0) {
          stmt.executeUpdate("UPDATE sys_permission SET status = 1 WHERE status NOT IN (0, 1)");
          fixes++;
        }
      }

      // Ensure super admin has all permissions
      try (var rs =
          stmt.executeQuery(
              "SELECT COUNT(DISTINCT rp.permission_id) FROM sys_role_permission rp "
                  + "JOIN sys_permission p ON rp.permission_id = p.id "
                  + "WHERE rp.role_id = 1 AND p.status = 1")) {

        int existing = rs.next() ? rs.getInt(1) : 0;

        try (var rs2 = stmt.executeQuery("SELECT COUNT(*) FROM sys_permission WHERE status = 1")) {
          int total = rs2.next() ? rs2.getInt(1) : 0;

          if (existing < total) {
            stmt.executeUpdate(
                "INSERT INTO sys_role_permission (role_id, permission_id, create_time) "
                    + "SELECT 1, id, NOW() FROM sys_permission "
                    + "WHERE status = 1 AND id NOT IN ("
                    + "  SELECT permission_id FROM sys_role_permission WHERE role_id = 1"
                    + ")");
            fixes++;
          }
        }
      }

      // Clean orphaned roles
      try (var rs =
          stmt.executeQuery(
              "SELECT COUNT(*) FROM sys_user_role ur "
                  + "LEFT JOIN sys_role r ON ur.role_id = r.id WHERE r.id IS NULL")) {
        if (rs.next() && rs.getInt(1) > 0) {
          stmt.executeUpdate(
              "DELETE FROM sys_user_role ur WHERE NOT EXISTS (SELECT 1 FROM sys_role r WHERE r.id = ur.role_id)");
          stmt.executeUpdate(
              "DELETE FROM sys_role_permission rp WHERE NOT EXISTS (SELECT 1 FROM sys_role r WHERE r.id = rp.role_id)");
          fixes++;
        }
      }

      // Auto-assign STAFF role to users without any role
      java.util.List<Long> usersWithoutRole = new java.util.ArrayList<>();
      java.util.List<String> fixedUserNames = new java.util.ArrayList<>();
      try (var rs =
          stmt.executeQuery(
              "SELECT u.id, u.username, u.real_name FROM sys_user u "
                  + "LEFT JOIN sys_user_role ur ON u.id = ur.user_id "
                  + "WHERE u.status = 1 AND u.id != 1 AND ur.user_id IS NULL")) {
        while (rs.next()) {
          usersWithoutRole.add(rs.getLong("id"));
          fixedUserNames.add(rs.getString("username") + "(" + rs.getString("real_name") + ")");
        }
      }
      // Execute inserts after ResultSet is closed
      for (Long userId : usersWithoutRole) {
        stmt.executeUpdate(
            "INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES ("
                + userId
                + ", 40, NOW())");
        fixes++;
      }
      if (!fixedUserNames.isEmpty()) {
        logger.info(
            "Auto-assigned STAFF role to {} user(s): {}",
            fixedUserNames.size(),
            String.join(", ", fixedUserNames));
      }

      // Update departments and positions
      updateDepartments(stmt);
      updatePositions(stmt);

      // 自动同步 department 文本字段（从 sys_department.dept_name 根据 department_id 回填）
      final int[] fixesRef = {fixes};
      syncUserDepartmentNames(stmt, fixesRef);
      fixes = fixesRef[0];

      // 确保 work_order 表有 department_id 列（用于部门隔离审批）
      ensureWorkOrderDepartmentIdColumn(stmt, fixesRef);
      fixes = fixesRef[0];

      // 确保专员角色（org_level=3）拥有审批权限
      ensureSpecialistApprovePermission(stmt, fixesRef);
      fixes = fixesRef[0];

      // 确保管理级及以上角色（org_level<=3）拥有查看全部工单权限
      ensureManagerViewAllPermission(stmt, fixesRef);
      fixes = fixesRef[0];

      if (fixes > 0) {
        logger.info("Data integrity check: {} fixes applied", fixes);
      } else {
        logger.info("Data integrity check passed");
      }
    }
  }

  private void updateDepartments(Statement stmt) throws SQLException {
    try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM sys_department")) {
      if (rs.next() && rs.getInt(1) != 8) {
        stmt.execute("DELETE FROM sys_department");
        stmt.execute(
            "INSERT INTO sys_department (id, dept_name, dept_code, org_level, sort_order) VALUES "
                + "(1, '总经办', 'GENERAL_OFFICE', 1, 1), "
                + "(2, '人事部', 'HR', 3, 2), "
                + "(3, '财务部', 'FINANCE', 3, 3), "
                + "(4, '研发部', 'R&D', 2, 4), "
                + "(5, '销售部', 'SALES', 2, 5), "
                + "(6, '行政部', 'ADMIN', 3, 6), "
                + "(7, '采购部', 'PROCUREMENT', 3, 7), "
                + "(8, '运维部', 'OPS', 3, 8)");
      }
    }
  }

  private void updatePositions(Statement stmt) throws SQLException {
    try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM sys_position")) {
      if (rs.next() && rs.getInt(1) != EXPECTED_POSITION_COUNT) {
        stmt.execute("DELETE FROM sys_position");
        stmt.execute(
            "INSERT INTO sys_position (id, position_name, position_code, dept_id, org_level) VALUES "
                + "(1, '超级管理员', 'SUPER_ADMIN', 0, 0), "
                + "(2, '安全审计管理员', 'SECURITY_AUDIT', 0, 0), "
                + "(10, '董事长', 'CHAIRMAN', 1, 1), "
                + "(11, '总经理', 'GM', 1, 1), "
                + "(12, '副总经理', 'VP', 1, 1), "
                + "(20, '研发总监', 'RD_DIR', 4, 2), "
                + "(21, '销售总监', 'SALES_DIR', 5, 2), "
                + "(22, '财务总监', 'FIN_DIR', 3, 2), "
                + "(23, '行政总监', 'ADMIN_DIR', 6, 2), "
                + "(24, '人事总监', 'HR_DIR', 2, 2), "
                + "(25, '采购总监', 'PROCUREMENT_DIR', 7, 2), "
                + "(30, 'HR人事专员', 'HR_SPEC', 2, 3), "
                + "(31, '行政专员', 'ADMIN_SPEC', 6, 3), "
                + "(32, '费用会计专员', 'ACCOUNTANT_SPEC', 3, 3), "
                + "(33, '出纳专员', 'CASHIER_SPEC', 3, 3), "
                + "(34, '销售专员', 'SALES_SPEC', 5, 3), "
                + "(35, '研发工程师专员', 'RD_ENGINEER_SPEC', 4, 3), "
                + "(36, '采购专员', 'PROCUREMENT_SPEC', 7, 3), "
                + "(40, '普通员工', 'STAFF', 0, 4)");
      }
    }
  }

  /**
   * 自动同步 sys_user.department 文本字段 根据 department_id 关联 sys_department.dept_name 回填
   * 解决前端创建工单时部门无法自动回填的问题
   */
  private void syncUserDepartmentNames(Statement stmt, int[] fixes) throws SQLException {
    try (var rs =
        stmt.executeQuery(
            "SELECT u.id, u.department, u.department_id, d.dept_name "
                + "FROM sys_user u "
                + "LEFT JOIN sys_department d ON u.department_id = d.id "
                + "WHERE u.status = 1 AND (u.department IS NULL OR u.department = '' "
                + "OR u.department != d.dept_name)")) {

      java.util.List<Long> needFixIds = new java.util.ArrayList<>();
      while (rs.next()) {
        Long userId = rs.getLong("id");
        String currentDept = rs.getString("department");
        Long deptId = rs.getLong("department_id");
        String correctDeptName = rs.getString("dept_name");

        if (correctDeptName != null
            && !correctDeptName.isEmpty()
            && !correctDeptName.equals(currentDept)) {
          needFixIds.add(userId);
          logger.info(
              "  同步用户 {} 部门: '{}' -> '{}' (department_id={})",
              userId,
              currentDept,
              correctDeptName,
              deptId);
        }
      }

      for (Long userId : needFixIds) {
        // 通过 department_id 查出正确的 dept_name 并更新
        try (var rs2 =
            stmt.executeQuery(
                "SELECT d.dept_name FROM sys_user u "
                    + "JOIN sys_department d ON u.department_id = d.id "
                    + "WHERE u.id = "
                    + userId)) {
          if (rs2.next()) {
            String deptName = rs2.getString("dept_name");
            stmt.executeUpdate(
                "UPDATE sys_user SET department = '"
                    + deptName.replace("'", "''")
                    + "' WHERE id = "
                    + userId);
            fixes[0]++;
          }
        }
      }

      if (!needFixIds.isEmpty()) {
        logger.info("自动同步了 {} 个用户的部门名称", needFixIds.size());
      }
    } catch (SQLException e) {
      logger.warn("同步用户部门名称时出错: {}", e.getMessage());
    }
  }

  /** 确保 work_order 表有 department_id 列（用于部门隔离审批） */
  private void ensureWorkOrderDepartmentIdColumn(Statement stmt, int[] fixes) throws SQLException {
    try {
      // 检查列是否存在
      boolean columnExists = false;
      try (var rs =
          stmt.executeQuery(
              "SELECT column_name FROM information_schema.columns "
                  + "WHERE table_name = 'work_order' AND column_name = 'department_id'")) {
        columnExists = rs.next();
      }

      if (!columnExists) {
        stmt.executeUpdate("ALTER TABLE work_order ADD COLUMN department_id BIGINT");
        // 根据现有工单的申请人ID回填department_id
        stmt.executeUpdate(
            "UPDATE work_order wo SET department_id = ("
                + "  SELECT u.department_id FROM sys_user u WHERE u.id = wo.applicant_id"
                + ") WHERE wo.department_id IS NULL AND wo.applicant_id IS NOT NULL");
        fixes[0]++;
        logger.info("已为 work_order 表添加 department_id 列并回填数据");
      }
    } catch (SQLException e) {
      logger.warn("检查/添加 work_order.department_id 列时出错: {}", e.getMessage());
    }
  }

  /** 确保专员角色（org_level=3，ID: 30-36）拥有审批权限（workorder:approve） 专员可以审批本部门普通员工的工单 */
  private void ensureSpecialistApprovePermission(Statement stmt, int[] fixes) throws SQLException {
    try {
      // 查找 workorder:approve 权限ID
      Long approvePermId = null;
      try (var rs =
          stmt.executeQuery(
              "SELECT id FROM sys_permission WHERE permission_code = 'workorder:approve' AND status = 1")) {
        if (rs.next()) {
          approvePermId = rs.getLong("id");
        }
      }

      if (approvePermId == null) {
        logger.warn("未找到 workorder:approve 权限，跳过专员审批权限配置");
        return;
      }

      // 查询专员角色列表（org_level=3）
      java.util.List<Long> specialistRoleIds = new java.util.ArrayList<>();
      try (var rs =
          stmt.executeQuery("SELECT id FROM sys_role WHERE org_level = 3 AND status = 1")) {
        while (rs.next()) {
          specialistRoleIds.add(rs.getLong("id"));
        }
      }

      if (specialistRoleIds.isEmpty()) {
        return; // 无专员角色，跳过
      }

      // 为每个专员角色添加审批权限（如果还没有的话）
      for (Long roleId : specialistRoleIds) {
        try (var rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = "
                    + roleId
                    + " AND permission_id = "
                    + approvePermId)) {
          if (rs.next() && rs.getInt(1) == 0) {
            stmt.executeUpdate(
                "INSERT INTO sys_role_permission (role_id, permission_id, create_time) VALUES ("
                    + roleId
                    + ", "
                    + approvePermId
                    + ", NOW())");
            fixes[0]++;
            logger.info("已为专员角色 ID={} 添加 workorder:approve 权限", roleId);
          }
        }
      }

    } catch (SQLException e) {
      logger.warn("配置专员审批权限时出错: {}", e.getMessage());
    }
  }

  /**
   * 确保高管级角色（org_level<=1：董事长/总经理/副总）拥有查看全部工单权限（workorder:view-all）
   * 管理级(org_level=2：总监/经理)只能查看本部门工单，不在此范围 专员级(org_level=3)只能审批，无查看权限
   */
  private void ensureManagerViewAllPermission(Statement stmt, int[] fixes) throws SQLException {
    try {
      // 查找 workorder:view-all 权限ID
      Long viewAllPermId = null;
      try (var rs =
          stmt.executeQuery(
              "SELECT id FROM sys_permission WHERE permission_code = 'workorder:view-all' AND status = 1")) {
        if (rs.next()) {
          viewAllPermId = rs.getLong("id");
        }
      }

      if (viewAllPermId == null) {
        logger.warn("未找到 workorder:view-all 权限，跳过高管级查看权限配置");
        return;
      }

      // 仅查询高管级角色列表（org_level <= 1：董事长/总经理/副总）
      java.util.List<Long> executiveRoleIds = new java.util.ArrayList<>();
      try (var rs =
          stmt.executeQuery("SELECT id FROM sys_role WHERE org_level <= 1 AND status = 1")) {
        while (rs.next()) {
          executiveRoleIds.add(rs.getLong("id"));
        }
      }

      if (executiveRoleIds.isEmpty()) {
        return;
      }

      // 为每个高管级角色添加查看全部工单权限
      for (Long roleId : executiveRoleIds) {
        try (var rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = "
                    + roleId
                    + " AND permission_id = "
                    + viewAllPermId)) {
          if (rs.next() && rs.getInt(1) == 0) {
            stmt.executeUpdate(
                "INSERT INTO sys_role_permission (role_id, permission_id, create_time) VALUES ("
                    + roleId
                    + ", "
                    + viewAllPermId
                    + ", NOW())");
            fixes[0]++;
            logger.info("已为高管级角色 ID={} 添加 workorder:view-all 权限", roleId);
          }
        }
      }

      // 回收：移除管理级/专员级角色（org_level>=2）错误拥有的 view-all 权限
      try (var rs =
          stmt.executeQuery(
              "SELECT rp.role_id FROM sys_role_permission rp "
                  + "INNER JOIN sys_role r ON r.id = rp.role_id "
                  + "WHERE rp.permission_id = "
                  + viewAllPermId
                  + " AND r.org_level >= 2")) {
        while (rs.next()) {
          Long revokeRoleId = rs.getLong("role_id");
          stmt.executeUpdate(
              "DELETE FROM sys_role_permission WHERE role_id = "
                  + revokeRoleId
                  + " AND permission_id = "
                  + viewAllPermId);
          fixes[0]++;
          logger.info("已回收管理级/专员级角色 ID={} 的 workorder:view-all 权限", revokeRoleId);
        }
      }

    } catch (SQLException e) {
      logger.warn("配置管理级查看全部工单权限时出错: {}", e.getMessage());
    }
  }

  /** 生产环境安全启动检查 确保关键安全配置已正确设置 */
  private void performSecurityChecks() {
    if ("prod".equalsIgnoreCase(activeProfile)) {
      logger.info("-------------------------------------------");
      logger.info("  生产环境安全检查");
      logger.info("-------------------------------------------");

      // 检查JWT密钥
      if (jwtSecret == null || jwtSecret.isBlank()) {
        String errMsg = "FATAL: 生产环境未配置JWT密钥! 请设置环境变量 JWT_SECRET";
        logger.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
      if (jwtSecret.length() < 32) {
        String errMsg = "FATAL: JWT密钥长度不足32位! 当前: " + jwtSecret.length();
        logger.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
      logger.info("  [OK] JWT密钥已配置 (长度: {})", jwtSecret.length());

      // 检查CORS配置
      String corsOrigins = System.getenv().getOrDefault("CORS_ORIGINS", "");
      if (corsOrigins.isBlank()) {
        logger.warn("  [WARN] 生产环境未配置CORS允许来源，将仅允许localhost访问");
        logger.warn("  [WARN] 建议设置环境变量 CORS_ORIGINS=https://yourdomain.com");
      } else {
        logger.info("  [OK] CORS已配置允许来源");
      }

      logger.info("  安全检查通过，系统启动中...");
    }
  }

  private void logSystemStatus(Connection connection) throws SQLException {
    try (Statement stmt = connection.createStatement()) {
      logger.info("===========================================");
      logger.info("  Database Initialization Complete!");
      logger.info("  Environment: {}", activeProfile.toUpperCase());
      logger.info("-------------------------------------------");

      String[] tables = {
          "sys_department", "sys_position", "sys_permission", "sys_role", "sys_user"
      };
      for (String table : tables) {
        try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
          if (rs.next()) logger.info("  {}: {} records", table, rs.getInt(1));
        }
      }

      logger.info("-------------------------------------------");
      logger.info("  Admin Account:");
      try (var rs =
          stmt.executeQuery(
              "SELECT username, real_name, org_level FROM sys_user WHERE username = 'KLord'")) {
        if (rs.next()) {
          logger.info(
              "    - {} / {} (Level: {})",
              rs.getString("username"),
              rs.getString("real_name"),
              rs.getInt("org_level"));
        }
      }
      logger.info("===========================================");
      logger.info("  Login: KLord / 123456");
      logger.info("===========================================");
    }
  }

  private void deployFlowableProcessDefinitions() {
    try {
      if (repositoryService != null) {
        String processKey = "enterprise_approval_process";
        long count =
            repositoryService
                .createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .count();

        if (count == 0) {
          logger.info("Deploying Flowable process definition: {}", processKey);

          org.flowable.engine.repository.Deployment deployment =
              repositoryService
                  .createDeployment()
                  .addClasspathResource("processes/enterprise-approval-process.bpmn20.xml")
                  .name("Enterprise Approval Process")
                  .deploy();

          logger.info(
              "Process definition deployed successfully. Deployment ID: {}", deployment.getId());
        } else {
          logger.info(
              "Process definition '{}' already deployed ({} versions found)", processKey, count);
        }
      } else {
        logger.warn("Flowable RepositoryService not available, skipping process deployment");
      }
    } catch (Exception e) {
      logger.error("Failed to deploy Flowable process definitions: {}", e.getMessage());
    }
  }
}
