package com.workorder.common.constant;

/**
 * 角色常量定义
 *
 * <p>统一角色 ID 映射（阶段 2 修复 C-09 / H-09）： 原项目中 EmployeeServiceImpl 的三处方法使用了不一致的角色 ID 区间： -
 * getHighestUserRole: 15-22 - isDeptAdmin: 14 - getOrgLevel: 1-14 全部改用本常量类，消除映射不一致导致的权限判断错误。
 *
 * <p>角色 ID 与 schema-init.sql 中的 sys_role 表一致。
 *
 * @author KLord
 */
public final class RoleConstants {

  private RoleConstants() {
    // 工具类，禁止实例化
  }

  // ========== 系统级角色（orgLevel=0）==========
  public static final long ROLE_SUPER_ADMIN = 1L;
  public static final long ROLE_SECURITY_AUDIT = 2L;

  // ========== 高管层角色（orgLevel=1）==========
  public static final long ROLE_CHAIRMAN = 10L;
  public static final long ROLE_GM = 11L; // 总经理
  public static final long ROLE_VP = 12L; // 副总经理

  // ========== 总监层角色（orgLevel=2）==========
  public static final long ROLE_RD_DIR = 20L; // 研发总监
  public static final long ROLE_SALES_DIR = 21L; // 销售总监
  public static final long ROLE_FIN_DIR = 22L; // 财务总监
  public static final long ROLE_ADMIN_DIR = 23L; // 行政总监
  public static final long ROLE_HR_DIR = 24L; // 人事总监
  public static final long ROLE_PROCUREMENT_DIR = 25L; // 采购总监

  // ========== 专员层角色（orgLevel=3）==========
  public static final long ROLE_HR_SPEC = 30L;
  public static final long ROLE_ADMIN_SPEC = 31L;
  public static final long ROLE_ACCOUNTANT_SPEC = 32L;
  public static final long ROLE_CASHIER_SPEC = 33L;
  public static final long ROLE_SALES_SPEC = 34L;
  public static final long ROLE_RD_ENGINEER_SPEC = 35L;
  public static final long ROLE_PROCUREMENT_SPEC = 36L;

  // ========== 普通员工（orgLevel=4）==========
  public static final long ROLE_STAFF = 40L;

  // ========== 角色代码常量 ==========
  public static final String CODE_SUPER_ADMIN = "SUPER_ADMIN";
  public static final String CODE_SECURITY_AUDIT = "SECURITY_AUDIT";
  public static final String CODE_HR_DIR = "HR_DIR";
  public static final String CODE_HR_SPEC = "HR_SPEC";
  public static final String CODE_FIN_DIR = "FIN_DIR";
  public static final String CODE_FINANCE = "FINANCE";

  // ========== 部门管理员角色集合（用于 isDeptAdmin 判断）==========
  // 总监级及以上角色视为部门管理员（仅能查看/管理本部门数据）
  // 注意：超级管理员（ROLE_SUPER_ADMIN）是全局管理员，不列入此集合 ——
  // 它不受部门数据过滤限制，可查看/管理所有部门的员工与工单。
  public static final long[] DEPT_ADMIN_ROLE_IDS = {
      ROLE_CHAIRMAN, ROLE_GM, ROLE_VP,
      ROLE_RD_DIR, ROLE_SALES_DIR, ROLE_FIN_DIR,
      ROLE_ADMIN_DIR, ROLE_HR_DIR, ROLE_PROCUREMENT_DIR
  };

  // ========== 管理层角色集合（orgLevel <= 2）==========
  public static final long[] MANAGEMENT_ROLE_IDS = {
      ROLE_SUPER_ADMIN,
      ROLE_SECURITY_AUDIT,
      ROLE_CHAIRMAN,
      ROLE_GM,
      ROLE_VP,
      ROLE_RD_DIR,
      ROLE_SALES_DIR,
      ROLE_FIN_DIR,
      ROLE_ADMIN_DIR,
      ROLE_HR_DIR,
      ROLE_PROCUREMENT_DIR
  };

  // ========== HR 角色集合（可跨部门查看员工信息）==========
  public static final long[] HR_ROLE_IDS = {ROLE_SUPER_ADMIN, ROLE_HR_DIR, ROLE_HR_SPEC};

  // ========== 财务角色集合（可查看工单金额）==========
  public static final long[] FINANCE_ROLE_IDS = {
      ROLE_SUPER_ADMIN, ROLE_FIN_DIR, ROLE_ACCOUNTANT_SPEC, ROLE_CASHIER_SPEC
  };

  /** 判断角色 ID 是否为部门管理员 */
  public static boolean isDeptAdmin(long roleId) {
    for (long id : DEPT_ADMIN_ROLE_IDS) {
      if (id == roleId) return true;
    }
    return false;
  }

  /** 判断角色 ID 是否为管理层（orgLevel <= 2） */
  public static boolean isManagement(long roleId) {
    for (long id : MANAGEMENT_ROLE_IDS) {
      if (id == roleId) return true;
    }
    return false;
  }

  /** 判断角色 ID 是否为 HR 角色 */
  public static boolean isHrRole(long roleId) {
    for (long id : HR_ROLE_IDS) {
      if (id == roleId) return true;
    }
    return false;
  }

  /** 判断角色 ID 是否为财务角色 */
  public static boolean isFinanceRole(long roleId) {
    for (long id : FINANCE_ROLE_IDS) {
      if (id == roleId) return true;
    }
    return false;
  }

  /**
   * 根据角色 ID 获取组织层级
   *
   * @return 0-超管 1-高管 2-总监 3-专员 4-普通员工；未知返回 4
   */
  public static int getOrgLevelByRoleId(long roleId) {
    if (roleId == ROLE_SUPER_ADMIN || roleId == ROLE_SECURITY_AUDIT) return 0;
    if (roleId == ROLE_CHAIRMAN || roleId == ROLE_GM || roleId == ROLE_VP) return 1;
    if (roleId >= ROLE_RD_DIR && roleId <= ROLE_PROCUREMENT_DIR) return 2;
    if (roleId >= ROLE_HR_SPEC && roleId <= ROLE_PROCUREMENT_SPEC) return 3;
    if (roleId == ROLE_STAFF) return 4;
    return 4;
  }
}
