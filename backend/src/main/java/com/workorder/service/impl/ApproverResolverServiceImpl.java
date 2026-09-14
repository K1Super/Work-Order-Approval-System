package com.workorder.service.impl;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.workorder.dao.UserMapper;
import com.workorder.dto.RoleInfoDTO;
import com.workorder.entity.User;
import com.workorder.service.IApproverResolverService;

/**

 * 审批人解析服务实现

 *

 * <p>深模块：将 DynamicTaskAssigner 的 616 行审批逻辑集中于此， Listener 层仅作为轻量适配器调用此 Service。

 */

@Service

public class ApproverResolverServiceImpl implements IApproverResolverService {

  private static final Logger logger = LoggerFactory.getLogger(ApproverResolverServiceImpl.class);

  // ========== 组织层级常量 ==========

  private static final int LEVEL_SUPER_ADMIN = 0;

  private static final int LEVEL_EXECUTIVE = 1;

  private static final int LEVEL_DIRECTOR = 2;

  private static final int LEVEL_SPECIALIST = 3;

  private static final int LEVEL_STAFF = 4;

  // ========== 部门总监用户 ID 常量 ==========
  private static final long DIRECTOR_ID_SALES = 21L;
  private static final long DIRECTOR_ID_FINANCE = 22L;
  private static final long DIRECTOR_ID_ADMIN = 23L;
  private static final long DIRECTOR_ID_PURCHASING = 25L;

  /** 禁止参与审批的角色编码 */

  private static final List<String> EXCLUDED_ROLE_CODES =

      Arrays.asList("SUPER_ADMIN", "SECURITY_AUDIT");

  @Autowired private UserMapper userMapper;

  @Override

  public String resolveAssignee(

      String taskName,

      String applicantId,

      String orderType,

      Double amount,

      Integer leaveDays,

      Integer priority) {

    logger.info("[ApproverResolver] 解析审批人 - 任务: {}, 申请人ID: {}", taskName, applicantId);

    if (applicantId == null) {

      logger.error("[ApproverResolver] 申请人ID为空");

      return null;

    }

    try {

      Long applicantLongId = Long.parseLong(applicantId);

      User applicant = userMapper.selectById(applicantLongId);

      if (applicant == null) {

        logger.error("[ApproverResolver] 未找到申请人 - ID: {}", applicantLongId);

        return null;

      }

      Integer applicantOrgLevel = applicant.getOrgLevel();

      logger.info(

          "[ApproverResolver] 申请人: {}, 部门: {}, 组织层级: {}",

          applicant.getRealName(),

          applicant.getDepartment(),

          applicantOrgLevel);

      return findApprover(

          taskName, orderType, amount, leaveDays, applicant, priority, applicantOrgLevel);

    } catch (Exception e) {

      logger.error("[ApproverResolver] 解析失败 - 任务: {}, 错误: {}", taskName, e.getMessage(), e);

      return null;

    }

  }

  @Override

  public boolean isExcludedFromApproval(User user) {

    if (user == null || user.getStatus() != 1) return true;

    if (user.getOrgLevel() != null && user.getOrgLevel() <= LEVEL_SUPER_ADMIN) {

      return true;

    }

    try {

      List<RoleInfoDTO> roles = userMapper.selectRolesByUserId(user.getId());

      if (roles != null) {

        for (RoleInfoDTO role : roles) {

          if (EXCLUDED_ROLE_CODES.contains(role.getRoleCode())) {

            logger.debug(

                "[排除] 用户 {} ({}) 角色={}",

                user.getRealName(),

                user.getUsername(),

                role.getRoleCode());

            return true;

          }

        }

      }

    } catch (Exception e) {

      logger.warn("查询用户角色失败，按组织层级判断: userId={}, error={}", user.getId(), e.getMessage());

    }

    return false;

  }

  // ==================== 核心查找方法 ====================

  private String findApprover(

      String taskName,

      String orderType,

      Double amount,

      Integer leaveDays,

      User applicant,

      Integer priority,

      Integer applicantOrgLevel) {

    switch (taskName) {

      case "Department Manager Approval":

      case "dept_manager_approve":

      case "部门经理审批":

        if (shouldSkipDeptManager(applicantOrgLevel)) {

          logger.info("[高管跳转] 申请人层级={} >= 总监级别，跳过部门经理", applicantOrgLevel);

          return SIGNAL_SKIP_NODE;

        }

        return findDepartmentManager(applicant, priority);

      case "HR Review":

      case "HR Attendance Check":

      case "HR Review Attendance":

      case "HR Attendance Calculation":

      case "hr_filing":

      case "hr_review":

      case "hr_filing_short":

      case "hr_verify":

      case "专项审核":

        return findHRStaff(applicant);

      case "CHRO Approval":

      case "chro_approve":

        return findCHRO(applicant);

      case "GM Final Approval":

      case "gm_final_approve":

      case "决策层终审":

        return findExecutiveApprover(applicant, priority);

      case "Finance Review":

      case "finance_review":

        return findFinanceAccountant(applicant);

      case "CFO Approval":

      case "cfo_approve":

        return findCFO(applicant);

      case "Cashier Payment Execution":

      case "cashier_payment_small":

      case "cashier_payment_medium":

      case "cashier_payment_large":

        return findCashier(applicant);

      case "IT Process Repair":

      case "it_process":

        return findITEngineer(applicant);

      case "Admin Distribute Supply":

      case "admin_distribute":

        return findAdminStaff(applicant);

      case "高管层审批":

        if (shouldSkipDirector(applicantOrgLevel)) {

          logger.info("[高管跳转] 申请人层级={} >= 总经理级别，跳过总监", applicantOrgLevel);

          return SIGNAL_SKIP_NODE;

        }

        return findDirector(applicant);

      default:

        logger.warn("未知任务名称: {}", taskName);

        return null;

    }

  }

  // ==================== 节点跳转判断 ====================

  private boolean shouldSkipDeptManager(Integer orgLevel) {

    return orgLevel != null && orgLevel <= LEVEL_DIRECTOR;

  }

  private boolean shouldSkipDirector(Integer orgLevel) {

    return orgLevel != null && orgLevel <= LEVEL_EXECUTIVE;

  }

  // ==================== 审批人查找（带排除规则）====================

  private String findDepartmentManager(User applicant, Integer priority) {

    logger.info("查找部门经理 - 申请人: {}", applicant.getRealName());

    // P1: 通过 departmentId 查询该部门管理层用户

    if (applicant.getDepartmentId() != null && applicant.getDepartmentId() > 0) {

      List<User> deptManagers = userMapper.selectDeptManagers(applicant.getDepartmentId());

      List<User> candidates = filterCandidates(deptManagers, applicant);

      if (!candidates.isEmpty()) {

        User manager = candidates.get(0);

        logger.info("[P1命中] 本部门经理: {} (ID: {})", manager.getRealName(), manager.getId());

        return manager.getId().toString();

      }

    }

    // P2: 使用直属上级ID

    if (applicant.getSuperiorId() != null && applicant.getSuperiorId() > 0) {

      Long superiorId = applicant.getSuperiorId();

      User superior = userMapper.selectById(superiorId);

      if (superior != null

          && superior.getStatus() == 1

          && !isExcludedFromApproval(superior)

          && !superior.getId().equals(applicant.getId())) {

        logger.info("[P2命中] 直属上级: {} (ID: {})", superior.getRealName(), superiorId);

        return superiorId.toString();

      }

    }

    // P3: 按部门名称映射兜底

    String department = applicant.getDepartment();

    if (department != null) {

      Long fallbackId = mapDepartmentToManager(department, applicant);

      if (fallbackId != null) {

        User fallback = userMapper.selectById(fallbackId);

        if (fallback != null

            && !isExcludedFromApproval(fallback)

            && !fallback.getId().equals(applicant.getId())) {

          logger.info("[P3兜底] 部门映射: 部门={}, 经理ID={}", department, fallbackId);

          return fallbackId.toString();

        }

      }

    }

    // 最终兜底

    User defaultApprover = userMapper.selectById(1L);

    if (defaultApprover != null

        && !isExcludedFromApproval(defaultApprover)

        && !defaultApprover.getId().equals(applicant.getId())) {

      return "1";

    }

    logger.error("无法找到任何可用的部门经理审批人");

    return null;

  }

  private String findDirector(User applicant) {

    logger.info(

        "查找高管层审批人 - 申请人: {}, 部门ID: {}", applicant.getRealName(), applicant.getDepartmentId());

    if (applicant.getDepartmentId() != null) {

      Long directorId = null;

      switch (applicant.getDepartmentId().intValue()) {

        case 4:

          directorId = 20L;

          break;

        case 5:

          directorId = DIRECTOR_ID_SALES;

          break;

        case 3:

          directorId = DIRECTOR_ID_FINANCE;

          break;

        case 6:

          directorId = DIRECTOR_ID_ADMIN;

          break;

        case 2:

          directorId = 24L;

          break;

        case 7:

          directorId = DIRECTOR_ID_PURCHASING;

          break;

        default:

          break;

      }

      if (directorId != null) {

        User director = userMapper.selectById(directorId);

        if (director != null

            && director.getStatus() == 1

            && !isExcludedFromApproval(director)

            && !director.getId().equals(applicant.getId())) {

          logger.info("找到本条线总监: {} (ID: {})", director.getRealName(), directorId);

          return directorId.toString();

        }

      }

    }

    // 兜底

    List<User> vps =

        userMapper.selectUsersWithRoles(Arrays.asList(LEVEL_EXECUTIVE, LEVEL_DIRECTOR));

    List<User> filtered = filterCandidates(vps, applicant);

    if (!filtered.isEmpty()) {

      return filtered.get(0).getId().toString();

    }

    logger.warn("未找到可用的高管层审批人");

    return null;

  }

  private String findExecutiveApprover(User applicant, Integer priority) {

    logger.info("查找决策层审批人 - 申请人: {}, 层级: {}", applicant.getRealName(), applicant.getOrgLevel());

    Integer applicantLevel = applicant.getOrgLevel();

    // 董事长提单：双人复核

    if (applicantLevel != null && applicantLevel <= LEVEL_EXECUTIVE) {

      Long gmId = userMapper.selectFirstActiveUserIdByRoleCode("GM");

      if (gmId != null) {

        User gm = userMapper.selectById(gmId);

        if (gm != null

            && gm.getStatus() == 1

            && !isExcludedFromApproval(gm)

            && !gm.getId().equals(applicant.getId())) {

          logger.info("[双人复核-第一人] GM {} (ID: {})", gm.getRealName(), gmId);

          return gmId.toString();

        }

      }

      Long cfoId = userMapper.selectFirstActiveUserIdByRoleCode("FIN_DIR");

      if (cfoId != null) {

        User cfo = userMapper.selectById(cfoId);

        if (cfo != null

            && cfo.getStatus() == 1

            && !isExcludedFromApproval(cfo)

            && !cfo.getId().equals(applicant.getId())) {

          logger.info("[双人复核-第一人] CFO {} (ID: {})", cfo.getRealName(), cfoId);

          return cfoId.toString();

        }

      }

    }

    // 普通情况：找总经理

    Long gmId = userMapper.selectFirstActiveUserIdByRoleCode("GM");

    if (gmId != null) {

      User gm = userMapper.selectById(gmId);

      if (gm != null

          && gm.getStatus() == 1

          && !isExcludedFromApproval(gm)

          && !gm.getId().equals(applicant.getId())) {

        return gmId.toString();

      }

    }

    // 兜底：找董事长

    Long chairmanId = userMapper.selectFirstActiveUserIdByRoleCode("CHAIRMAN");

    if (chairmanId != null) {

      User chairman = userMapper.selectById(chairmanId);

      if (chairman != null

          && chairman.getStatus() == 1

          && !isExcludedFromApproval(chairman)

          && !chairman.getId().equals(applicant.getId())) {

        return chairmanId.toString();

      }

    }

    logger.warn("未找到可用的决策层审批人");

    return null;

  }

  // ==================== 专项职能岗查找 ====================

  private String findByRoleCode(String roleCode, Long fallbackId, User applicant, String roleName) {

    logger.info("查找{} - 角色: {}", roleName, roleCode);

    Long userId = userMapper.selectFirstActiveUserIdByRoleCode(roleCode);

    if (userId != null) {

      User user = userMapper.selectById(userId);

      if (user != null

          && user.getStatus() == 1

          && !isExcludedFromApproval(user)

          && !user.getId().equals(applicant.getId())) {

        return userId.toString();

      }

    }

    if (fallbackId != null) {

      User fallback = userMapper.selectById(fallbackId);

      if (fallback != null

          && !isExcludedFromApproval(fallback)

          && !fallback.getId().equals(applicant.getId())) {

        return fallbackId.toString();

      }

    }

    return null;

  }

  private String findHRStaff(User applicant) {

    return findByRoleCode("HR_SPEC", 12L, applicant, "HR专员");

  }

  private String findCHRO(User applicant) {

    return findByRoleCode("HR_DIR", null, applicant, "CHRO");

  }

  private String findFinanceAccountant(User applicant) {

    return findByRoleCode("ACCOUNTANT_SPEC", 13L, applicant, "财务会计");

  }

  private String findCFO(User applicant) {

    return findByRoleCode("FIN_DIR", null, applicant, "CFO");

  }

  private String findCashier(User applicant) {

    return findByRoleCode("CASHIER_SPEC", 14L, applicant, "出纳");

  }

  private String findITEngineer(User applicant) {

    return findByRoleCode("RD_ENGINEER_SPEC", 15L, applicant, "IT工程师");

  }

  private String findAdminStaff(User applicant) {

    return findByRoleCode("ADMIN_SPEC", 16L, applicant, "行政专员");

  }

  // ==================== 工具方法 ====================

  private List<User> filterCandidates(List<User> candidates, User applicant) {

    List<User> result = new ArrayList<>();

    if (candidates == null) return result;

    for (User u : candidates) {

      if (u.getStatus() != 1) continue;

      if (isExcludedFromApproval(u)) continue;

      if (applicant != null && u.getId().equals(applicant.getId())) continue;

      result.add(u);

    }

    return result;

  }

  private Long mapDepartmentToManager(String department, User applicant) {

    if (department.contains("研发") || department.contains("R&D")) return 6L;

    if (department.contains("销售") || department.contains("Sales")) return 8L;

    if (department.contains("财务") || department.contains("Finance")) return 13L;

    if (department.contains("人事") || department.contains("HR")) return 12L;

    if (department.contains("行政") || department.contains("Admin")) return 11L;

    if (department.contains("采购")) return 16L;

    if (department.contains("运维")) return 15L;

    return null;

  }

}
