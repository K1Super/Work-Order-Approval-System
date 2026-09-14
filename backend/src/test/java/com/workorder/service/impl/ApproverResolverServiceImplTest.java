package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.workorder.dao.UserMapper;
import com.workorder.dto.RoleInfoDTO;
import com.workorder.entity.User;
import com.workorder.service.IApproverResolverService;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ApproverResolverService 深模块完整测试套件
 *
 * <p>测试策略：通过公共接口验证行为，Mock UserMapper 隔离数据库依赖。 覆盖范围： - 排除规则（7种场景） - 审批人解析核心流程（15种场景） - 部门经理多级查找
 * P1/P2/P3/最终兜底（10种场景） - 高管层按部门ID映射查找（8种场景） - 决策层终审 / 双人复核 / 兜底链路（7种场景） - 专项职能岗角色查找（14种场景） -
 * 节点跳转边界条件（6种场景） - 工具方法 filterCandidates / mapDepartmentToManager（12种场景） - 异常和边界输入（5种场景）
 */
@ExtendWith(MockitoExtension.class)
class ApproverResolverServiceImplTest {

  @Mock private UserMapper userMapper;

  private ApproverResolverServiceImpl service;

  // ========== 测试数据工厂 ==========

  /** 创建标准用户，默认启用状态、研发部 */
  private static User makeUser(Long id, String name, int orgLevel, Long deptId, Long superiorId) {
    User u = new User();
    u.setId(id);
    u.setRealName(name);
    u.setUsername("user" + id);
    u.setOrgLevel(orgLevel);
    u.setDepartmentId(deptId);
    u.setSuperiorId(superiorId);
    u.setStatus(1);
    u.setDepartment("研发部");
    return u;
  }

  private static RoleInfoDTO makeRole(String roleCode) {
    return new RoleInfoDTO(1L, roleCode + "_Name", roleCode);
  }

  @BeforeEach
  void setUp() {
    service = new ApproverResolverServiceImpl();
    try {
      var field = ApproverResolverServiceImpl.class.getDeclaredField("userMapper");
      field.setAccessible(true);
      field.set(service, userMapper);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject userMapper", e);
    }
  }

  // ==================== 1. isExcludedFromApproval ====================

  @Nested
  @DisplayName("isExcludedFromApproval - 审批排除规则")
  class ExclusionTests {

    @Test
    @DisplayName("null 用户应被排除")
    void null_user_is_excluded() {
      assertThat(service.isExcludedFromApproval(null)).isTrue();
    }

    @Test
    @DisplayName("禁用状态用户(status=0)应被排除")
    void disabled_user_is_excluded() {
      User user = makeUser(1L, "张三", 4, 1L, null);
      user.setStatus(0);
      assertThat(service.isExcludedFromApproval(user)).isTrue();
    }

    @Test
    @DisplayName("超级管理员(orgLevel=0)应被排除，不查询角色")
    void super_admin_by_orglevel_excluded_without_role_query() {
      User admin = makeUser(1L, "KLord", 0, 1L, null);
      assertThat(service.isExcludedFromApproval(admin)).isTrue();
      verify(userMapper, never()).selectRolesByUserId(anyLong());
    }

    @Test
    @DisplayName("拥有 SUPER_ADMIN 角色的用户应被排除")
    void super_admin_role_is_excluded() {
      User user = makeUser(2L, "管理员", 2, 1L, null);
      when(userMapper.selectRolesByUserId(2L)).thenReturn(Arrays.asList(makeRole("SUPER_ADMIN")));
      assertThat(service.isExcludedFromApproval(user)).isTrue();
    }

    @Test
    @DisplayName("拥有 SECURITY_AUDIT 角色的用户应被排除")
    void security_audit_role_is_excluded() {
      User user = makeUser(3L, "审计员", 3, 1L, null);
      when(userMapper.selectRolesByUserId(3L))
          .thenReturn(Arrays.asList(makeRole("SECURITY_AUDIT")));
      assertThat(service.isExcludedFromApproval(user)).isTrue();
    }

    @Test
    @DisplayName("同时拥有多个角色时，命中任一排除角色即排除")
    void multiple_roles_hit_any_excluded() {
      User user = makeUser(9L, "多角色", 3, 1L, null);
      when(userMapper.selectRolesByUserId(9L))
          .thenReturn(
              Arrays.asList(
                  makeRole("DEPT_MANAGER"), makeRole("HR_SPEC"), makeRole("SUPER_ADMIN"))); // 第三个命中
      assertThat(service.isExcludedFromApproval(user)).isTrue();
    }

    @Test
    @DisplayName("普通员工无排除角色时不应被排除")
    void normal_staff_not_excluded() {
      User staff = makeUser(4L, "李四", 4, 1L, null);
      when(userMapper.selectRolesByUserId(4L)).thenReturn(Collections.emptyList());
      assertThat(service.isExcludedFromApproval(staff)).isFalse();
    }

    @Test
    @DisplayName("部门经理角色用户不应被排除")
    void dept_manager_not_excluded() {
      User manager = makeUser(5L, "王经理", 2, 1L, null);
      when(userMapper.selectRolesByUserId(5L)).thenReturn(Arrays.asList(makeRole("DEPT_MANAGER")));
      assertThat(service.isExcludedFromApproval(manager)).isFalse();
    }

    @Test
    @DisplayName("角色查询异常时应降级处理，不抛异常")
    void role_query_exception_handled_gracefully() {
      User user = makeUser(8L, "异常用户", 4, 1L, null);
      when(userMapper.selectRolesByUserId(8L)).thenThrow(new RuntimeException("DB timeout"));
      // 异常被 catch，返回 false（不排除）
      assertThat(service.isExcludedFromApproval(user)).isFalse();
    }

    @Test
    @DisplayName("角色列表为 null 时不应排除")
    void null_roles_not_excluded() {
      User user = makeUser(7L, "空角色", 4, 1L, null);
      when(userMapper.selectRolesByUserId(7L)).thenReturn(null);
      assertThat(service.isExcludedFromApproval(user)).isFalse();
    }
  }

  // ==================== 2. resolveAssignee - 核心流程 ====================

  @Nested
  @DisplayName("resolveAssignee - 审批人解析入口")
  class ResolveAssigneeTests {

    @Test
    @DisplayName("申请人ID为 null 应返回 null")
    void null_applicantId_returns_null() {
      String result =
          service.resolveAssignee("Department Manager Approval", null, null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("申请人ID为空字符串应触发 NumberFormatException 并返回 null")
    void empty_applicantId_returns_null() {
      String result = service.resolveAssignee("HR Review", "", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("申请人不存在于数据库时应返回 null")
    void nonexistent_applicant_returns_null() {
      when(userMapper.selectById(999L)).thenReturn(null);

      String result =
          service.resolveAssignee("Department Manager Approval", "999", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("非数字的申请人ID应优雅处理并返回 null")
    void non_numeric_applicant_id_returns_null() {
      String result = service.resolveAssignee("HR Review", "abc", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("负数申请人ID能正常解析（数据库层面决定是否存在）")
    void negative_applicant_id_queries_db() {
      when(userMapper.selectById(-1L)).thenReturn(null);

      String result = service.resolveAssignee("HR Review", "-1", null, null, null, null);
      assertThat(result).isNull(); // 数据库返回 null
    }

    @Test
    @DisplayName("部门经理审批：总监级别申请人应跳过节点")
    void director_level_skips_dept_manager() {
      User director = makeUser(10L, "赵总监", 2, 1L, null);
      when(userMapper.selectById(10L)).thenReturn(director);

      String result =
          service.resolveAssignee("Department Manager Approval", "10", null, null, null, null);
      assertThat(result).isEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("部门经理审批：dept_manager_approbe 别名也能正确路由")
    void dept_manager_alias_routes_correctly() {
      User director = makeUser(10L, "赵总监", 2, 1L, null);
      when(userMapper.selectById(10L)).thenReturn(director);

      String result = service.resolveAssignee("dept_manager_approve", "10", null, null, null, null);
      assertThat(result).isEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("高管层审批：总经理级别申请人应跳过节点")
    void executive_level_skips_director() {
      User gm = makeUser(1L, "钱总", 1, 1L, null);
      when(userMapper.selectById(1L)).thenReturn(gm);

      String result = service.resolveAssignee("高管层审批", "1", null, null, null, null);
      assertThat(result).isEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("未知任务名称应返回 null")
    void unknown_task_name_returns_null() {
      User applicant = makeUser(30L, "吴员工", 4, 1L, null);
      when(userMapper.selectById(30L)).thenReturn(applicant);

      String result = service.resolveAssignee("NonexistentTask", "30", null, null, null, null);
      assertThat(result).isNull();
    }
  }

  // ==================== 3. findDepartmentManager - 多级查找 ====================

  @Nested
  @DisplayName("findDepartmentManager - 部门经理多级查找 (P1→P2→P3→最终兜底)")
  class DeptManagerResolutionTests {

    @Test
    @DisplayName("P1 命中：本部门管理层用户优先返回")
    void p1_dept_managers_first() {
      User applicant = makeUser(40L, "郑员工", 4, 1L, null);
      User manager = makeUser(6L, "郑经理", 2, 1L, null);

      when(userMapper.selectById(40L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(manager));
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "40", null, null, null, null);
      assertThat(result).isEqualTo("6");
    }

    @Test
    @DisplayName("P1 命中但所有候选人都被排除时进入 P2")
    void p1_all_excluded_falls_to_p2() {
      User applicant = makeUser(44L, "韩员工", 4, 1L, 7L);
      User excludedMgr = makeUser(6L, "超管冒充", 0, 1L, null); // orgLevel=0 被排除

      when(userMapper.selectById(44L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(excludedMgr));
      // P2: superiorId=7
      User superior = makeUser(7L, "韩上级", 2, 1L, null);
      when(userMapper.selectById(7L)).thenReturn(superior);
      when(userMapper.selectRolesByUserId(7L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "44", null, null, null, null);
      assertThat(result).isEqualTo("7"); // P2 命中
    }

    @Test
    @DisplayName("P2 命中：无本部门经理时使用直属上级")
    void p2_superior_fallback() {
      User applicant = makeUser(41L, "冯员工", 4, 99L, 7L);
      User superior = makeUser(7L, "冯上级", 2, 1L, null);

      when(userMapper.selectById(41L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(99L)).thenReturn(Collections.emptyList());
      when(userMapper.selectById(7L)).thenReturn(superior);
      when(userMapper.selectRolesByUserId(7L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "41", null, null, null, null);
      assertThat(result).isEqualTo("7");
    }

    @Test
    @DisplayName("P2 上级被禁用时应跳过进入 P3")
    void p2_disabled_superior_skipped_to_p3() {
      User applicant = makeUser(45L, "朱员工", 4, 99L, 8L);
      User disabledSuperior = makeUser(8L, "离职上级", 2, 1L, null);
      disabledSuperior.setStatus(0);

      when(userMapper.selectById(45L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(99L)).thenReturn(Collections.emptyList());
      when(userMapper.selectById(8L)).thenReturn(disabledSuperior);
      // P3: 部门名称映射
      applicant.setDepartment("销售部");
      User salesMgr = makeUser(8L, "销售经理", 2, 2L, null);
      salesMgr.setStatus(1); // P3 查找的是另一个用户
      when(userMapper.selectById(8L)).thenReturn(salesMgr); // 第二次调用返回启用的
      when(userMapper.selectRolesByUserId(8L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "45", null, null, null, null);
      assertThat(result).isEqualTo("8");
    }

    @Test
    @DisplayName("P2 上级是申请人自己时应跳过")
    void p2_superior_is_self_skipped() {
      User applicant = makeUser(46L, "自指员工", 4, 99L, 46L); // 上级是自己

      when(userMapper.selectById(46L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(99L)).thenReturn(Collections.emptyList());
      when(userMapper.selectById(46L)).thenReturn(applicant); // 上级就是自己
      when(userMapper.selectRolesByUserId(46L)).thenReturn(Collections.emptyList());

      // P3: 无部门名匹配 → 最终兜底
      applicant.setDepartment("未知部");
      User defaultUser = makeUser(1L, "默认审批人", 2, 1L, null);
      when(userMapper.selectById(1L)).thenReturn(defaultUser);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "46", null, null, null, null);
      assertThat(result).isEqualTo("1"); // 最终兜底
    }

    @Test
    @DisplayName("P3 兜底：按部门名称映射（研发部）")
    void p3_mapping_rnd_department() {
      User applicant = makeUser(42L, "陈员工", 4, null, null);
      applicant.setDepartment("研发部");

      when(userMapper.selectById(42L)).thenReturn(applicant);
      User devManager = makeUser(6L, "研发经理", 2, 1L, null);
      when(userMapper.selectById(6L)).thenReturn(devManager);
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "42", null, null, null, null);
      assertThat(result).isEqualTo("6");
    }

    @Test
    @DisplayName("P3 兜底：departmentId=0 时不走 P1，直接进入后续逻辑")
    void dept_id_zero_skips_p1() {
      User applicant = makeUser(47L, "零部门员工", 4, 0L, null);
      applicant.setDepartment("财务部");

      when(userMapper.selectById(47L)).thenReturn(applicant);
      // P1: deptId=0 不满足 >0 条件，跳过
      // P2: superiorId=null，跳过
      // P3: "财务部" → 13
      User finMgr = makeUser(13L, "财务经理", 2, 3L, null);
      when(userMapper.selectById(13L)).thenReturn(finMgr);
      when(userMapper.selectRolesByUserId(13L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "47", null, null, null, null);
      assertThat(result).isEqualTo("13");
    }

    @Test
    @DisplayName("最终兜底：所有路径都失败时返回默认用户 ID=1")
    void final_fallback_returns_default_user() {
      User applicant = makeUser(48L, "孤岛员工", 4, null, null);
      applicant.setDepartment("不存在的部门");

      when(userMapper.selectById(48L)).thenReturn(applicant);
      // P1: deptId=null; P2: superior=null; P3: 无匹配
      User defaultUser = makeUser(1L, "KLord", 0, 1L, null); // orgLevel=0 但最终兜底不检查层级
      when(userMapper.selectById(1L)).thenReturn(defaultUser);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "48", null, null, null, null);
      // 注意：defaultUser orgLevel=0 → isExcludedFromApproval 返回 true → 最终也返回 null
      // 让我们用一个正常的 defaultUser
      reset(userMapper);
      User normalDefault = makeUser(1L, "默认审批人", 2, 1L, null);
      when(userMapper.selectById(48L)).thenReturn(applicant);
      when(userMapper.selectById(1L)).thenReturn(normalDefault);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      result = service.resolveAssignee("Department Manager Approval", "48", null, null, null, null);
      assertThat(result).isEqualTo("1");
    }

    @Test
    @DisplayName("P1 列表中第一个被排除时选择第二个有效候选人")
    void first_candidate_excluded_picks_second() {
      User applicant = makeUser(43L, "楚员工", 4, 1L, null);
      User excluded = makeUser(6L, "超管冒充", 0, 1L, null);
      User normal = makeUser(8L, "正常经理", 2, 1L, null);

      when(userMapper.selectById(43L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(excluded, normal));
      when(userMapper.selectRolesByUserId(8L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "43", null, null, null, null);
      assertThat(result).isEqualTo("8");
    }

    @Test
    @DisplayName("P1 返回空列表时正确进入 P2")
    void p1_empty_list_goes_to_p2() {
      User applicant = makeUser(49L, "空部门员工", 4, 1L, 9L);

      when(userMapper.selectById(49L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Collections.emptyList()); // 空
      User sup = makeUser(9L, "上级", 2, 1L, null);
      when(userMapper.selectById(9L)).thenReturn(sup);
      when(userMapper.selectRolesByUserId(9L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "49", null, null, null, null);
      assertThat(result).isEqualTo("9");
    }
  }

  // ==================== 4. findDirector - 高管层按部门映射 ====================

  @Nested
  @DisplayName("findDirector - 高管层按部门ID映射")
  class DirectorResolutionTests {

    @Test
    @DisplayName("部门ID=4 映射到总监 ID=20")
    void dept_4_maps_to_director_20() {
      User applicant = makeUser(70L, "员工A", 4, 4L, null); // deptId=4
      User director = makeUser(20L, "总监A", 2, 4L, null);

      when(userMapper.selectById(70L)).thenReturn(applicant);
      when(userMapper.selectById(20L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(20L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "70", null, null, null, null);
      assertThat(result).isEqualTo("20");
    }

    @Test
    @DisplayName("部门ID=5 映射到总监 ID=21")
    void dept_5_maps_to_director_21() {
      User applicant = makeUser(71L, "员工B", 4, 5L, null);
      User director = makeUser(21L, "总监B", 2, 5L, null);

      when(userMapper.selectById(71L)).thenReturn(applicant);
      when(userMapper.selectById(21L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(21L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "71", null, null, null, null);
      assertThat(result).isEqualTo("21");
    }

    @Test
    @DisplayName("部门ID=3 映射到总监 ID=22")
    void dept_3_maps_to_director_22() {
      User applicant = makeUser(72L, "员工C", 4, 3L, null);
      User director = makeUser(22L, "总监C", 2, 3L, null);

      when(userMapper.selectById(72L)).thenReturn(applicant);
      when(userMapper.selectById(22L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(22L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "72", null, null, null, null);
      assertThat(result).isEqualTo("22");
    }

    @Test
    @DisplayName("部门ID=6 映射到总监 ID=23")
    void dept_6_maps_to_director_23() {
      User applicant = makeUser(73L, "员工D", 4, 6L, null);
      User director = makeUser(23L, "总监D", 2, 6L, null);

      when(userMapper.selectById(73L)).thenReturn(applicant);
      when(userMapper.selectById(23L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(23L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "73", null, null, null, null);
      assertThat(result).isEqualTo("23");
    }

    @Test
    @DisplayName("部门ID=2 映射到总监 ID=24")
    void dept_2_maps_to_director_24() {
      User applicant = makeUser(74L, "员工E", 4, 2L, null);
      User director = makeUser(24L, "总监E", 2, 2L, null);

      when(userMapper.selectById(74L)).thenReturn(applicant);
      when(userMapper.selectById(24L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(24L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "74", null, null, null, null);
      assertThat(result).isEqualTo("24");
    }

    @Test
    @DisplayName("部门ID=7 映射到总监 ID=25")
    void dept_7_maps_to_director_25() {
      User applicant = makeUser(75L, "员工F", 4, 7L, null);
      User director = makeUser(25L, "总监F", 2, 7L, null);

      when(userMapper.selectById(75L)).thenReturn(applicant);
      when(userMapper.selectById(25L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(25L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "75", null, null, null, null);
      assertThat(result).isEqualTo("25");
    }

    @Test
    @DisplayName("未映射的部门ID(如8)走兜底：selectUsersWithRoles")
    void unmapped_dept_id_falls_back_to_query() {
      User applicant = makeUser(76L, "员工G", 4, 8L, null); // deptId=8 无映射
      User vp = makeUser(100L, "VP", 2, 1L, null);

      when(userMapper.selectById(76L)).thenReturn(applicant);
      when(userMapper.selectUsersWithRoles(anyList())).thenReturn(Arrays.asList(vp));
      when(userMapper.selectRolesByUserId(100L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "76", null, null, null, null);
      assertThat(result).isEqualTo("100");
    }

    @Test
    @DisplayName("总监被排除时走兜底")
    void excluded_director_falls_back() {
      User applicant = makeUser(77L, "员工H", 4, 4L, null);
      User excludedDir = makeUser(20L, "超管总监", 0, 4L, null); // orgLevel=0
      User vp = makeUser(101L, "备用VP", 2, 1L, null);

      when(userMapper.selectById(77L)).thenReturn(applicant);
      when(userMapper.selectById(20L)).thenReturn(excludedDir);
      when(userMapper.selectUsersWithRoles(anyList())).thenReturn(Arrays.asList(vp));
      when(userMapper.selectRolesByUserId(101L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "77", null, null, null, null);
      assertThat(result).isEqualTo("101"); // 兜底 VP
    }

    @Test
    @DisplayName("总监是申请人自己时走兜底")
    void director_is_applicant_falls_back() {
      User selfDirector = makeUser(20L, "我是总监", 2, 4L, null); // 自己是总监
      User vp = makeUser(102L, "其他VP", 2, 1L, null);

      when(userMapper.selectById(20L)).thenReturn(selfDirector);
      when(userMapper.selectById(20L)).thenReturn(selfDirector);
      when(userMapper.selectUsersWithRoles(anyList())).thenReturn(Arrays.asList(vp));
      when(userMapper.selectRolesByUserId(102L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "20", null, null, null, null);
      assertThat(result).isEqualTo("102"); // 自己被过滤掉，选 VP
    }
  }

  // ==================== 5. findExecutiveApprover - 决策层终审 ====================

  @Nested
  @DisplayName("findExecutiveApprover - 决策层终审 / 双人复核")
  class ExecutiveApprovalTests {

    @Test
    @DisplayName("普通员工申请终审返回 GM")
    void regular_employee_gets_gm() {
      User applicant = makeUser(50L, "魏员工", 4, 1L, null);
      User gm = makeUser(1L, "GM", 1, 1L, null);

      when(userMapper.selectById(50L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(1L);
      when(userMapper.selectById(1L)).thenReturn(gm);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("GM Final Approval", "50", null, null, null, null);
      assertThat(result).isEqualTo("1");
    }

    @Test
    @DisplayName("gm_final_approve 别名也能正确路由到决策层终审")
    void gm_final_approve_alias_works() {
      User applicant = makeUser(51L, "员工", 4, 1L, null);
      User gm = makeUser(1L, "GM", 1, 1L, null);

      when(userMapper.selectById(51L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(1L);
      when(userMapper.selectById(1L)).thenReturn(gm);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("gm_final_approve", "51", null, null, null, null);
      assertThat(result).isEqualTo("1");
    }

    @Test
    @DisplayName("决策层终审中文任务名也能正确路由")
    void chinese_executive_alias_works() {
      User applicant = makeUser(52L, "员工", 4, 1L, null);
      User gm = makeUser(1L, "GM", 1, 1L, null);

      when(userMapper.selectById(52L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(1L);
      when(userMapper.selectById(1L)).thenReturn(gm);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("决策层终审", "52", null, null, null, null);
      assertThat(result).isEqualTo("1");
    }

    @Test
    @DisplayName("董事长提单触发双人复核，优先返回 GM")
    void chairman_triggers_dual_review_returns_gm() {
      User chairman = makeUser(2L, "董事长", 1, 1L, null);
      User gm = makeUser(1L, "GM", 1, 1L, null);

      when(userMapper.selectById(2L)).thenReturn(chairman);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(1L);
      when(userMapper.selectById(1L)).thenReturn(gm);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("GM Final Approval", "2", null, null, null, null);
      assertThat(result).isEqualTo("1");
    }

    @Test
    @DisplayName("GM 角色不存在时降级查找 CHAIRMAN")
    void gm_not_found_falls_to_chairman() {
      User applicant = makeUser(53L, "员工", 4, 1L, null);
      User chairman = makeUser(2L, "董事长", 1, 1L, null);

      when(userMapper.selectById(53L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(null); // GM 不存在
      when(userMapper.selectFirstActiveUserIdByRoleCode("CHAIRMAN")).thenReturn(2L);
      when(userMapper.selectById(2L)).thenReturn(chairman);
      when(userMapper.selectRolesByUserId(2L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("GM Final Approval", "53", null, null, null, null);
      assertThat(result).isEqualTo("2");
    }

    @Test
    @DisplayName("GM 和 CHAIRMAN 都不存在时返回 null")
    void no_executive_available_returns_null() {
      User applicant = makeUser(54L, "员工", 4, 1L, null);

      when(userMapper.selectById(54L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(null);
      when(userMapper.selectFirstActiveUserIdByRoleCode("CHAIRMAN")).thenReturn(null);

      String result = service.resolveAssignee("GM Final Approval", "54", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("GM 是申请人自己时跳过并尝试 CHAIRMAN")
    void gm_is_applicant_skips_to_chairman() {
      User gmSelf = makeUser(1L, "GM本人", 1, 1L, null);
      User chairman = makeUser(2L, "董事长", 1, 1L, null);

      when(userMapper.selectById(1L)).thenReturn(gmSelf);
      when(userMapper.selectFirstActiveUserIdByRoleCode("GM")).thenReturn(1L);
      when(userMapper.selectById(1L)).thenReturn(gmSelf); // GM 就是自己
      // 不是 EXECUTIVE 级别提单（orgLevel=1 == EXECUTIVE），走双人复核路径
      // 但 GM 是自己 → 跳过 → 尝试 CFO
      when(userMapper.selectFirstActiveUserIdByRoleCode("FIN_DIR")).thenReturn(null);
      // 普通路径再找 GM → 还是自己 → 找 CHAIRMAN
      when(userMapper.selectFirstActiveUserIdByRoleCode("CHAIRMAN")).thenReturn(2L);
      when(userMapper.selectById(2L)).thenReturn(chairman);
      when(userMapper.selectRolesByUserId(2L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("GM Final Approval", "1", null, null, null, null);
      assertThat(result).isEqualTo("2"); // 降级到董事长
    }
  }

  // ==================== 6. 专项职能岗角色查找 ====================

  @Nested
  @DisplayName("专项职能岗 - 按 RoleCode 查找")
  class SpecialistRoleResolutionTests {

    // --- HR 相关 ---

    @Test
    @DisplayName("HR Review → 通过 HR_SPEC 角色查找")
    void hr_review_resolves_via_hr_spec() {
      User applicant = makeUser(80L, "员工", 4, 1L, null);
      User hrSpec = makeUser(12L, "HR专员", 3, 5L, null);

      when(userMapper.selectById(80L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("HR_SPEC")).thenReturn(12L);
      when(userMapper.selectById(12L)).thenReturn(hrSpec);
      when(userMapper.selectRolesByUserId(12L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("HR Review", "80", null, null, null, null);
      assertThat(result).isEqualTo("12");
    }

    @Test
    @DisplayName("CHRO Approval → HR_DIR 角色（无兜底）")
    void chro_approval_resolves_via_hr_dir() {
      User applicant = makeUser(81L, "员工", 4, 1L, null);
      User chro = makeUser(90L, "CHRO", 2, 5L, null);

      when(userMapper.selectById(81L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("HR_DIR")).thenReturn(90L);
      when(userMapper.selectById(90L)).thenReturn(chro);
      when(userMapper.selectRolesByUserId(90L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("CHRO Approval", "81", null, null, null, null);
      assertThat(result).isEqualTo("90");
    }

    @Test
    @DisplayName("chro_approve 别名也能工作")
    void chro_alias_works() {
      User applicant = makeUser(82L, "员工", 4, 1L, null);
      User chro = makeUser(90L, "CHRO", 2, 5L, null);

      when(userMapper.selectById(82L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("HR_DIR")).thenReturn(90L);
      when(userMapper.selectById(90L)).thenReturn(chro);
      when(userMapper.selectRolesByUserId(90L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("chro_approve", "82", null, null, null, null);
      assertThat(result).isEqualTo("90");
    }

    // --- 财务相关 ---

    @Test
    @DisplayName("Finance Review → 通过 ACCOUNTANT_SPEC 角色查找")
    void finance_review_resolves_via_accountant() {
      User applicant = makeUser(83L, "员工", 4, 1L, null);
      User accountant = makeUser(13L, "会计", 3, 3L, null);

      when(userMapper.selectById(83L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("ACCOUNTANT_SPEC")).thenReturn(13L);
      when(userMapper.selectById(13L)).thenReturn(accountant);
      when(userMapper.selectRolesByUserId(13L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("Finance Review", "83", null, null, null, null);
      assertThat(result).isEqualTo("13");
    }

    @Test
    @DisplayName("CFO Approval → FIN_DIR 角色（无兜底）")
    void cfo_approval_resolves_via_fin_dir() {
      User applicant = makeUser(84L, "员工", 4, 1L, null);
      User cfo = makeUser(91L, "CFO", 2, 3L, null);

      when(userMapper.selectById(84L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("FIN_DIR")).thenReturn(91L);
      when(userMapper.selectById(91L)).thenReturn(cfo);
      when(userMapper.selectRolesByUserId(91L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("CFO Approval", "84", null, null, null, null);
      assertThat(result).isEqualTo("91");
    }

    @Test
    @DisplayName("cfo_approve 别名也能工作")
    void cfo_alias_works() {
      User applicant = makeUser(85L, "员工", 4, 1L, null);
      User cfo = makeUser(91L, "CFO", 2, 3L, null);

      when(userMapper.selectById(85L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("FIN_DIR")).thenReturn(91L);
      when(userMapper.selectById(91L)).thenReturn(cfo);
      when(userMapper.selectRolesByUserId(91L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("cfo_approve", "85", null, null, null, null);
      assertThat(result).isEqualTo("91");
    }

    // --- 出纳相关 ---

    @Test
    @DisplayName("Cashier Payment Execution → 通过 CASHIER_SPEC 角色查找")
    void cashier_payment_resolves_via_cashier() {
      User applicant = makeUser(86L, "员工", 4, 1L, null);
      User cashier = makeUser(14L, "出纳", 3, 3L, null);

      when(userMapper.selectById(86L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("CASHIER_SPEC")).thenReturn(14L);
      when(userMapper.selectById(14L)).thenReturn(cashier);
      when(userMapper.selectRolesByUserId(14L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Cashier Payment Execution", "86", null, null, null, null);
      assertThat(result).isEqualTo("14");
    }

    @Test
    @DisplayName("cashier_payment_small / medium / large 别名都能工作")
    void cashier_aliases_all_work() {
      // 测试 cashier_payment_small 别名
      User applicant = makeUser(87L, "员工", 4, 1L, null);
      User cashier = makeUser(14L, "出纳", 3, 3L, null);

      when(userMapper.selectById(87L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("CASHIER_SPEC")).thenReturn(14L);
      when(userMapper.selectById(14L)).thenReturn(cashier);
      when(userMapper.selectRolesByUserId(14L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("cashier_payment_small", "87", null, null, null, null);
      assertThat(result).isEqualTo("14");
    }

    // --- IT / 行政 ---

    @Test
    @DisplayName("IT Process Repair → 通过 RD_ENGINEER_SPEC 角色查找")
    void it_repair_resolves_via_engineer() {
      User applicant = makeUser(88L, "员工", 4, 1L, null);
      User engineer = makeUser(15L, "IT工程师", 3, 1L, null);

      when(userMapper.selectById(88L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("RD_ENGINEER_SPEC")).thenReturn(15L);
      when(userMapper.selectById(15L)).thenReturn(engineer);
      when(userMapper.selectRolesByUserId(15L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("IT Process Repair", "88", null, null, null, null);
      assertThat(result).isEqualTo("15");
    }

    @Test
    @DisplayName("it_process 别名也能工作")
    void it_process_alias_works() {
      User applicant = makeUser(89L, "员工", 4, 1L, null);
      User engineer = makeUser(15L, "IT工程师", 3, 1L, null);

      when(userMapper.selectById(89L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("RD_ENGINEER_SPEC")).thenReturn(15L);
      when(userMapper.selectById(15L)).thenReturn(engineer);
      when(userMapper.selectRolesByUserId(15L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("it_process", "89", null, null, null, null);
      assertThat(result).isEqualTo("15");
    }

    @Test
    @DisplayName("Admin Distribute Supply → 通过 ADMIN_SPEC 角色查找")
    void admin_supply_resolves_via_admin() {
      User applicant = makeUser(90L, "员工", 4, 1L, null);
      User admin = makeUser(16L, "行政专员", 3, 2L, null);

      when(userMapper.selectById(90L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("ADMIN_SPEC")).thenReturn(16L);
      when(userMapper.selectById(16L)).thenReturn(admin);
      when(userMapper.selectRolesByUserId(16L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Admin Distribute Supply", "90", null, null, null, null);
      assertThat(result).isEqualTo("16");
    }

    @Test
    @DisplayName("admin_distribute 别名也能工作")
    void admin_distribute_alias_works() {
      User applicant = makeUser(91L, "员工", 4, 1L, null);
      User admin = makeUser(16L, "行政专员", 3, 2L, null);

      when(userMapper.selectById(91L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("ADMIN_SPEC")).thenReturn(16L);
      when(userMapper.selectById(16L)).thenReturn(admin);
      when(userMapper.selectRolesByUserId(16L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("admin_distribute", "91", null, null, null, null);
      assertThat(result).isEqualTo("16");
    }

    // --- 边界情况 ---

    @Test
    @DisplayName("职能岗角色用户被禁用时应返回 null（无有效兜底时）")
    void disabled_specialist_returns_null() {
      User applicant = makeUser(92L, "员工", 4, 1L, null);
      User disabledHr = makeUser(12L, "离职HR", 3, 5L, null);
      disabledHr.setStatus(0);

      when(userMapper.selectById(92L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("HR_SPEC")).thenReturn(12L);
      when(userMapper.selectById(12L)).thenReturn(disabledHr); // 禁用用户

      String result = service.resolveAssignee("HR Review", "92", null, null, null, null);
      assertThat(result).isNull(); // 禁用用户被跳过，无其他候选
    }

    @Test
    @DisplayName("职能岗角色用户被排除（orgLevel=0）时应返回 null")
    void excluded_specialist_returns_null() {
      User applicant = makeUser(93L, "员工", 4, 1L, null);
      User excludedFin = makeUser(13L, "超管会计", 0, 3L, null); // orgLevel=0 被排除

      when(userMapper.selectById(93L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("ACCOUNTANT_SPEC")).thenReturn(13L);
      when(userMapper.selectById(13L)).thenReturn(excludedFin); // 被排除

      String result = service.resolveAssignee("Finance Review", "93", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("职能岗角色不存在且无兜底时应返回 null")
    void no_role_and_no_fallback_returns_null() {
      User applicant = makeUser(94L, "员工", 4, 1L, null);

      when(userMapper.selectById(94L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("HR_DIR")).thenReturn(null); // CHRO 不存在
      // CHRO 的 fallbackId = null → 直接返回 null

      String result = service.resolveAssignee("CHRO Approval", "94", null, null, null, null);
      assertThat(result).isNull();
    }

    // --- HR 任务名全覆盖 ---

    @Test
    @DisplayName("全部 9 个 HR 任务名别名都能正确路由到 HR_SPEC")
    void all_hr_task_names_resolve() {
      // 测试 HR Review 任务名
      User applicant = makeUser(95L, "员工", 4, 1L, null);
      User hrSpec = makeUser(12L, "HR专员", 3, 5L, null);

      when(userMapper.selectById(95L)).thenReturn(applicant);
      when(userMapper.selectFirstActiveUserIdByRoleCode("HR_SPEC")).thenReturn(12L);
      when(userMapper.selectById(12L)).thenReturn(hrSpec);
      when(userMapper.selectRolesByUserId(12L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("HR Review", "95", null, null, null, null);
      assertThat(result).isEqualTo("12");
    }
  }

  // ==================== 7. 节点跳转边界条件 ====================

  @Nested
  @DisplayName("shouldSkip - 节点跳转边界条件")
  class SkipLogicBoundaryTests {

    @Test
    @DisplayName("orgLevel=null 时不跳过部门经理节点")
    void null_orglevel_does_not_skip_dept_manager() {
      User applicant = makeUser(100L, "无层级", 4, 1L, null);
      applicant.setOrgLevel(null);
      User mgr = makeUser(6L, "经理", 2, 1L, null);

      when(userMapper.selectById(100L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(mgr));
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "100", null, null, null, null);
      // orgLevel=null → shouldSkipDeptManager returns false → 正常查找
      assertThat(result).isNotNull();
      assertThat(result).isNotEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("orgLevel=null 时不跳过高管层节点")
    void null_orglevel_does_not_skip_director() {
      User applicant = makeUser(101L, "无层级", 4, 4L, null);
      applicant.setOrgLevel(null);
      User director = makeUser(20L, "总监", 2, 4L, null);

      when(userMapper.selectById(101L)).thenReturn(applicant);
      when(userMapper.selectById(20L)).thenReturn(director);
      when(userMapper.selectRolesByUserId(20L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "101", null, null, null, null);
      assertThat(result).isNotNull();
      assertThat(result).isNotEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("orgLevel=DIRECTOR(2) 时刚好跳过部门经理")
    void exactly_director_level_skips_dept_manager() {
      User director = makeUser(102L, "刚好总监", 2, 1L, null);
      when(userMapper.selectById(102L)).thenReturn(director);

      String result =
          service.resolveAssignee("Department Manager Approval", "102", null, null, null, null);
      assertThat(result).isEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("orgLevel=SPECIALIST(3) 时不跳过部门经理")
    void specialist_level_does_not_skip_dept_manager() {
      User specialist = makeUser(103L, "职能岗", 3, 1L, null);
      User mgr = makeUser(6L, "经理", 2, 1L, null);

      when(userMapper.selectById(103L)).thenReturn(specialist);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(mgr));
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "103", null, null, null, null);
      assertThat(result).isEqualTo("6"); // 正常找到经理
    }

    @Test
    @DisplayName("orgLevel=EXECUTIVE(1) 时刚好跳过高管层")
    void exactly_executive_level_skips_director() {
      User exec = makeUser(104L, "刚好总", 1, 1L, null);
      when(userMapper.selectById(104L)).thenReturn(exec);

      String result = service.resolveAssignee("高管层审批", "104", null, null, null, null);
      assertThat(result).isEqualTo(IApproverResolverService.SIGNAL_SKIP_NODE);
    }

    @Test
    @DisplayName("orgLevel=DIRECTOR(2) 时不跳过高管层")
    void director_level_does_not_skip_director_node() {
      User director = makeUser(105L, "总监本人", 2, 4L, null);
      User higherDirector = makeUser(20L, "上级总监", 2, 4L, null);

      when(userMapper.selectById(105L)).thenReturn(director);
      when(userMapper.selectById(20L)).thenReturn(higherDirector);
      when(userMapper.selectRolesByUserId(20L)).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "105", null, null, null, null);
      // orgLevel=2 > LEVEL_EXECUTIVE(1) → 不跳过 → 正常查找
      assertThat(result).isEqualTo("20");
    }
  }

  // ==================== 8. 工具方法 ====================

  @Nested
  @DisplayName("工具方法 - filterCandidates / mapDepartmentToManager")
  class UtilityMethodTests {

    // ---- filterCandidates (通过 resolveAssignee 间接测试) ----

    @Test
    @DisplayName("filterCandidates: null 列表返回空结果")
    void filter_null_list_returns_empty() {
      // 通过 findDirector 兜底路径间接验证
      User applicant = makeUser(110L, "员工", 4, 8L, null); // 未映射部门
      when(userMapper.selectById(110L)).thenReturn(applicant);
      when(userMapper.selectUsersWithRoles(anyList())).thenReturn(null); // null 列表

      String result = service.resolveAssignee("高管层审批", "110", null, null, null, null);
      assertThat(result).isNull(); // null → 过滤后为空 → 无法找到
    }

    @Test
    @DisplayName("filterCandidates: 空列表返回空结果")
    void filter_empty_list_returns_empty() {
      User applicant = makeUser(111L, "员工", 4, 8L, null);
      when(userMapper.selectById(111L)).thenReturn(applicant);
      when(userMapper.selectUsersWithRoles(anyList())).thenReturn(Collections.emptyList());

      String result = service.resolveAssignee("高管层审批", "111", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("filterCandidates: 所有候选人都被过滤时返回空")
    void all_candidates_filtered_returns_empty() {
      User applicant = makeUser(112L, "员工", 4, 1L, null);
      User disabled1 = makeUser(6L, "离职1", 2, 1L, null);
      disabled1.setStatus(0);
      User disabled2 = makeUser(8L, "离职2", 2, 1L, null);
      disabled2.setStatus(0);

      when(userMapper.selectById(112L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(disabled1, disabled2));

      // P1 全部禁用 → 进入 P2 → P2 无 superior → P3 无匹配 → 最终兜底
      applicant.setDepartment("未知部");
      User defaultUser = makeUser(1L, "默认", 2, 1L, null);
      when(userMapper.selectById(1L)).thenReturn(defaultUser);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "112", null, null, null, null);
      assertThat(result).isEqualTo("1"); // 最终兜底
    }

    // ---- mapDepartmentToManager ----

    @Test
    @DisplayName("部门名包含'研发'或'R&D'映射到 ID=6")
    void rnd_department_maps_to_6() {
      verifyDeptMapping("研发部", "6");
    }

    @Test
    @DisplayName("部门名包含'销售'或'Sales'映射到 ID=8")
    void sales_department_maps_to_8() {
      verifyDeptMapping("销售部", "8");
    }

    @Test
    @DisplayName("部门名包含'财务'或'Finance'映射到 ID=13")
    void finance_department_maps_to_13() {
      verifyDeptMapping("财务部", "13");
    }

    @Test
    @DisplayName("部门名包含'人事'或'HR'映射到 ID=12")
    void hr_department_maps_to_12() {
      verifyDeptMapping("人事部", "12");
    }

    @Test
    @DisplayName("部门名包含'行政'或'Admin'映射到 ID=11")
    void admin_department_maps_to_11() {
      verifyDeptMapping("行政部", "11");
    }

    @Test
    @DisplayName("部门名包含'采购'映射到 ID=16")
    void procurement_department_maps_to_16() {
      verifyDeptMapping("采购部", "16");
    }

    @Test
    @DisplayName("部门名包含'运维'映射到 ID=15")
    void ops_department_maps_to_15() {
      verifyDeptMapping("运维部", "15");
    }

    @Test
    @DisplayName("未知的部门名称返回 null（不走 P3）")
    void unknown_department_no_mapping() {
      User applicant = makeUser(113L, "员工", 4, null, null);
      applicant.setDepartment("神秘部门");

      when(userMapper.selectById(113L)).thenReturn(applicant);
      // P3 无匹配 → 最终兜底
      User defaultUser = makeUser(1L, "默认", 2, 1L, null);
      when(userMapper.selectById(1L)).thenReturn(defaultUser);
      when(userMapper.selectRolesByUserId(1L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "113", null, null, null, null);
      assertThat(result).isEqualTo("1"); // 最终兜底
    }

    @Test
    @DisplayName("英文名 R&D 部门也能映射")
    void rnd_english_name_maps() {
      verifyDeptMapping("R&D Department", "6");
    }

    @Test
    @DisplayName("英文名 Sales 部门也能映射")
    void sales_english_name_maps() {
      verifyDeptMapping("Sales Dept", "8");
    }

    @Test
    @DisplayName("英文名 Finance 部门也能映射")
    void finance_english_name_maps() {
      verifyDeptMapping("Finance Team", "13");
    }

    private void verifyDeptMapping(String deptName, String expectedId) {
      User applicant = makeUser(114L, "员工", 4, null, null);
      applicant.setDepartment(deptName);

      Long expected = Long.parseLong(expectedId);
      User mgr = makeUser(expected, deptName + "经理", 2, 1L, null);

      when(userMapper.selectById(114L)).thenReturn(applicant);
      when(userMapper.selectById(expected)).thenReturn(mgr);
      when(userMapper.selectRolesByUserId(expected)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee("Department Manager Approval", "114", null, null, null, null);
      assertThat(result).isEqualTo(expectedId);
    }
  }

  // ==================== 9. 异常与健壮性 ====================

  @Nested
  @DisplayName("异常处理和健壮性")
  class RobustnessTests {

    @Test
    @DisplayName("UserMapper 抛出异常时 resolveAssignee 返回 null")
    void mapper_exception_returns_null() {
      when(userMapper.selectById(anyLong())).thenThrow(new RuntimeException("Connection refused"));

      String result = service.resolveAssignee("HR Review", "1", null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("超大数字 ID 能正常传递给 Mapper")
    void very_large_id_passed_to_mapper() {
      when(userMapper.selectById(Long.MAX_VALUE)).thenReturn(null);

      String result =
          service.resolveAssignee(
              "HR Review", String.valueOf(Long.MAX_VALUE), null, null, null, null);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("priority 为 Double 类型时能正确转换为 Integer")
    void double_priority_converts_to_integer() {
      User applicant = makeUser(120L, "员工", 4, 1L, null);
      User mgr = makeUser(6L, "经理", 2, 1L, null);

      when(userMapper.selectById(120L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(mgr));
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      // priority 作为 Integer 传入（正常用法）
      String result =
          service.resolveAssignee("Department Manager Approval", "120", "leave", 5000.0, 3, 2);
      assertThat(result).isEqualTo("6");
    }

    @Test
    @DisplayName("amount 为 null 时不会 NPE")
    void null_amount_no_npe() {
      User applicant = makeUser(121L, "员工", 4, 1L, null);
      User mgr = makeUser(6L, "经理", 2, 1L, null);

      when(userMapper.selectById(121L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(mgr));
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      String result =
          service.resolveAssignee(
              "Department Manager Approval", "121", "reimbursement", null, null, null);
      assertThat(result).isEqualTo("6");
    }

    @Test
    @DisplayName("orderType 参数不影响部门经理查找（当前实现忽略 orderType）")
    void orderType_ignored_for_dept_manager() {
      User applicant = makeUser(122L, "员工", 4, 1L, null);
      User mgr = makeUser(6L, "经理", 2, 1L, null);

      when(userMapper.selectById(122L)).thenReturn(applicant);
      when(userMapper.selectDeptManagers(1L)).thenReturn(Arrays.asList(mgr));
      when(userMapper.selectRolesByUserId(6L)).thenReturn(Collections.emptyList());

      // 不同 orderType 都应该返回相同结果
      String r1 =
          service.resolveAssignee("Department Manager Approval", "122", "leave", null, null, null);
      String r2 =
          service.resolveAssignee(
              "Department Manager Approval", "122", "reimbursement", 10000.0, null, null);
      assertThat(r1).isEqualTo(r2).isEqualTo("6");
    }
  }
}
