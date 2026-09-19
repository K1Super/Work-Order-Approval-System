package com.workorder.it;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.workorder.common.constant.RoleConstants;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import com.workorder.service.IEmployeeService;

/**
 * 集成测试种子数据工厂。
 *
 * <p>员工创建走 {@link IEmployeeService#createEmployee(User)}（保证工号、DEK 等初始化逻辑与生产一致），
 * 密码在创建后重置为测试专用值并标记已改密。审批人解析依赖「按角色取首个用户 / 按部门取管理层」，
 * 因此审批人（专项审核、部门经理、越权无关用户）在每次测试运行中全局只创建一次并缓存，避免同名角色被
 * 多个测试重复创建后产生误匹配；申请人按测试隔离，每次 {@link #applicant()} 都生成新的 STAFF 用户。
 */
@Component
public class ItSeedData {

  /** 测试专用登录密码（满足测试库密码策略） */
  public static final String TEST_PASSWORD = "Wos@Test123";

  /** 财务部门 ID（Flyway V1 种子） */
  public static final long DEPT_FINANCE = 3L;

  /** 销售部门 ID（Flyway V1 种子） */
  public static final long DEPT_SALES = 5L;

  private static final AtomicLong SEQ = new AtomicLong(0L);

  private static final long AWAIT_LOG_TIMEOUT_MS = 10000L;

  private static final long AWAIT_LOG_INTERVAL_MS = 100L;

  @Autowired private IEmployeeService employeeService;

  @Autowired private UserMapper userMapper;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private JdbcTemplate jdbcTemplate;

  private TestUser manager;

  private TestUser specialist;

  private TestUser outsider;

  /** 测试用户身份（用户 ID + 登录用户名） */
  static final class TestUser {
    final long id;
    final String username;
    final String realName;

    TestUser(long id, String username, String realName) {
      this.id = id;
      this.username = username;
      this.realName = realName;
    }
  }

  /** 部门经理（财务总监，全局唯一）：每次运行仅创建一次 */
  TestUser manager() {
    if (manager == null) {
      manager = createUser("mgr", DEPT_FINANCE, "财务部", RoleConstants.ROLE_FIN_DIR);
    }
    return manager;
  }

  /** 专项审核人（费用会计，全局唯一）：每次运行仅创建一次 */
  TestUser specialist() {
    if (specialist == null) {
      specialist = createUser("spec", DEPT_FINANCE, "财务部", RoleConstants.ROLE_ACCOUNTANT_SPEC);
    }
    return specialist;
  }

  /** 越权无关用户（销售部 STAFF，全局唯一）：每次运行仅创建一次 */
  TestUser outsider() {
    if (outsider == null) {
      outsider = createUser("outsider", DEPT_SALES, "销售部", RoleConstants.ROLE_STAFF);
    }
    return outsider;
  }

  /** 新建一个申请人（财务部 STAFF，直属上级为部门经理）：按测试隔离 */
  TestUser applicant() {
    return createUser(
        "appl", DEPT_FINANCE, "财务部", RoleConstants.ROLE_STAFF, manager().id);
  }

  /** 创建一个测试员工（无直属上级）并分配角色 */
  TestUser createUser(String roleTag, long departmentId, String departmentName, long roleId) {
    return createUser(roleTag, departmentId, departmentName, roleId, null);
  }

  /** 创建一个测试员工并设置直属上级 */
  TestUser createUser(
      String roleTag, long departmentId, String departmentName, long roleId, Long superiorId) {
    long seq = SEQ.incrementAndGet();
    String username = "it_" + roleTag + "_" + seq;
    String realName = "测试" + roleTag + seq;

    User employee = new User();
    employee.setUsername(username);
    employee.setRealName(realName);
    employee.setDepartment(departmentName);
    employee.setDepartmentId(departmentId);
    employee.setSuperiorId(superiorId);

    Result<User> result = employeeService.createEmployee(employee);
    if (result.getData() == null) {
      throw new IllegalStateException("创建测试用户失败: " + result.getMsg());
    }
    long userId = result.getData().getId();
    resetPassword(userId);
    employeeService.assignRoles(userId, List.of(roleId));
    return new TestUser(userId, username, realName);
  }

  /** 将用户密码重置为测试专用密码，并标记为已改密避免触发首次登录强制改密 */
  void resetPassword(long userId) {
    User password = new User();
    password.setId(userId);
    password.setPassword(passwordEncoder.encode(TEST_PASSWORD));
    password.setPasswordChanged(true);
    userMapper.updatePassword(password);
  }

  /** 查询工单当前有效流程实例 ID（重新提交后应发生变化） */
  String currentProcessInstanceId(long workOrderId) {
    return jdbcTemplate.queryForObject(
        "SELECT process_instance_id FROM order_process_link"
            + " WHERE work_order_id = ? AND is_deleted = 0",
        String.class,
        workOrderId);
  }

  /** 统计工单指定动作的审批日志数量 */
  int countApprovalLogs(long workOrderId, int action) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM approval_log WHERE work_order_id = ? AND action = ?",
            Integer.class,
            workOrderId,
            action);
    return count == null ? 0 : count;
  }

  /** 等待工单指定动作的审批日志达到指定数量（审计日志由 @Async 监听器异步写入，需重试等待） */
  void awaitApprovalLogs(long workOrderId, int action, int minCount) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_LOG_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (countApprovalLogs(workOrderId, action) >= minCount) {
        return;
      }
      Thread.sleep(AWAIT_LOG_INTERVAL_MS);
    }
    throw new AssertionError(
        "等待审批日志超时: workOrderId=" + workOrderId + ", action=" + action);
  }
}