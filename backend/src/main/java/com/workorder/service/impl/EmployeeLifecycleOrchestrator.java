package com.workorder.service.impl;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import com.workorder.security.DekService;
import com.workorder.security.EncryptionHelper;
import com.workorder.security.TokenVersionCache;
import com.workorder.service.IPasswordPolicyService;

/**
 * 员工生命周期编排器。
 *
 * <p>职责：编排员工创建、更新、逻辑删除、启用/禁用、批量导入等写操作，
 * 含工号生成、DEK 分层加密（per-user）、禁用联动 tokenVersion 失效。
 * 权限判定委托 {@link EmployeeAccessGuard}，强密码生成委托 {@link EmployeePasswordService}。
 *
 * <p>事务边界：createEmployee / updateEmployee / deleteEmployee / toggleEmployeeStatus /
 * batchImport 全部保留 {@code @Transactional(rollbackFor = Exception.class)}（与拆分前
 * EmployeeServiceImpl 一致；原 updateById 的 transient 异常直抛也沿用）。
 *
 * <p>说明：freezeUserWorkOrders 沿袭拆分前的占位实现（仅日志），冻结工单能力待后续工单模块完善。
 *
 * @author KLord
 */
@Component
public class EmployeeLifecycleOrchestrator {

  private static final Logger logger = LoggerFactory.getLogger(EmployeeLifecycleOrchestrator.class);

  /** 工号生成冲突重试次数 */
  private static final int EMPLOYEE_ID_MAX_RETRIES = 10;

  /** 初始随机密码长度 */
  private static final int INITIAL_PASSWORD_LENGTH = 8;

  @Autowired private UserMapper userMapper;

  @Autowired private BCryptPasswordEncoder passwordEncoder;

  @Autowired private IPasswordPolicyService passwordPolicyService;

  @Autowired private DekService dekService;

  @Autowired private EncryptionHelper encryptionHelper;

  @Autowired private TokenVersionCache tokenVersionCache;

  @Autowired private EmployeeAccessGuard accessGuard;

  @Autowired private EmployeePasswordService passwordService;

  /**
   * 创建员工（生成唯一工号 + 强密码 + DEK 分层加密）。
   *
   * @param employee 员工信息（密码字段由系统生成并忽略外部输入）
   * @return 创建结果（不返回密码）
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<User> createEmployee(User employee) {
    String employeeId = generateUniqueEmployeeId();
    employee.setEmployeeId(employeeId);
    logger.info("为新生成员工分配工号: {}", employeeId);

    // 生成随机 8 位强密码（大小写+数字+特殊字符）；明文密码不入日志
    String initialPassword = passwordService.generateStrongPassword(INITIAL_PASSWORD_LENGTH);
    logger.info("为员工 {} 生成初始临时密码（明文不记录日志）", employee.getRealName());

    // 自动用真名生成用户名（如果未提供）
    if (employee.getUsername() == null || employee.getUsername().trim().isEmpty()) {
      String realName = employee.getRealName();
      if (realName != null && !realName.trim().isEmpty()) {
        String autoUsername = realName.trim().toLowerCase().replaceAll("\\s+", "_");
        employee.setUsername(autoUsername);
      } else {
        return Result.error("请提供真实姓名");
      }
    }

    // 检查用户名是否已存在（含软删除记录，因 sys_user_username_key 唯一约束不区分 is_deleted）
    User existing = userMapper.findByUsernameIncludeDeleted(employee.getUsername());
    if (existing != null) {
      if (existing.getIsDeleted() != null && existing.getIsDeleted() == 1) {
        // 用户名被已删除的用户占用 — 物理删除旧记录后允许重新使用
        logger.info(
            "用户名 {} 被已删除用户(id={})占用，物理删除旧记录以释放用户名",
            employee.getUsername(),
            existing.getId());
        userMapper.deleteUserRoles(existing.getId());
        userMapper.physicalDeleteById(existing.getId());
      } else {
        return Result.error("用户名已存在: " + employee.getUsername());
      }
    }

    employee.setStatus(1); // Active

    // 使用生成的强密码（忽略用户输入的密码）
    String password = initialPassword;

    IPasswordPolicyService.PasswordValidationResult validationResult =
        passwordPolicyService.validatePassword(password);
    if (!validationResult.isValid()) {
      return Result.error(validationResult.getMessage());
    }

    employee.setPassword(passwordEncoder.encode(password));

    // 标记为首次登录（需要强制改密）
    employee.setPasswordChanged(false);

    // 保存明文 email/phone 以便后续用 DEK 重新加密
    final String plainEmail = employee.getEmail();
    final String plainPhone = employee.getPhone();

    int result = insertUserWithDek(employee, plainEmail, plainPhone);

    if (result > 0) {
      logger.info(
          "Employee created successfully: {} (initial password delivered via secure channel)",
          employee.getUsername());

      User createdUser = userMapper.selectByUsername(employee.getUsername());
      if (createdUser != null) {
        createdUser.setPassword(null); // 不返回密码（即便是哈希）
        createdUser.setEmployeeId(employeeId);
        return Result.success("员工创建成功！初始密码已生成，请通过安全通道通知员工首次登录后修改密码", createdUser);
      }

      return Result.success("员工创建成功！", employee);
    } else {
      return Result.error("Failed to create employee");
    }
  }

  /**
   * 更新员工（禁止修改工号/密码，DEK 上下文内更新保证 email/phone 用该用户 DEK 加密）。
   *
   * @param id 员工 ID
   * @param employee 待更新字段（白名单由调用方约束）
   * @param currentUser 当前操作者
   * @return 更新后的员工
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<User> updateEmployee(Long id, User employee, User currentUser) {
    User existing = userMapper.selectById(id);
    if (existing == null) {
      return Result.error("Employee not found");
    }

    // Permission check for department admin
    if (accessGuard.isCrossDepartmentAccess(currentUser, existing.getDepartmentId())) {
      logger.warn(
          "Dept admin {} tried to update user from different department: {}",
          currentUser.getRealName(),
          id);
      return Result.error("Access denied: Cannot modify employees from other departments");
    }

    // Update fields (excluding password - use separate endpoint)
    employee.setId(id);
    employee.setPassword(null);

    // 禁止修改工号（工号为系统生成，不可更改）
    employee.setEmployeeId(null); // 清除前端传入的工号值，保持原值不变
    logger.debug("员工更新时禁止修改工号: userId={}", id);

    final User employeeToUpdate = employee;
    int result =
        encryptionHelper.executeWithDekContext(id, () -> userMapper.updateById(employeeToUpdate));

    if (result > 0) {
      logger.info("Employee updated by {}: userId={}", currentUser.getRealName(), id);
      User updated = encryptionHelper.executeWithDekContext(id, () -> userMapper.selectById(id));
      return Result.success(updated);
    } else {
      return Result.error("Failed to update employee");
    }
  }

  /**
   * 逻辑删除员工（禁止删除超管；删除后冻结其活跃工单）。
   *
   * @param id 员工 ID
   * @return 操作结果
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<Void> deleteEmployee(Long id) {
    // selectById 已带 is_deleted = 0 过滤，已删除员工会返回 null
    User existing = userMapper.selectById(id);
    if (existing == null) {
      return Result.error("员工不存在或已被删除");
    }

    // 企业级安全保护：禁止删除超级管理员账户
    if (accessGuard.isSuperAdmin(existing)) {
      logger.warn("[安全拦截] 禁止删除超级管理员账户: id={}, username={}", id, existing.getUsername());
      return Result.error("禁止删除超级管理员账户（系统根基账户受保护）");
    }

    // 逻辑删除：设置 is_deleted = 1（员工从列表消失），同时 status = 0（禁止登录）
    int result = userMapper.deleteById(id);

    if (result > 0) {
      logger.warn(
          "Employee logically deleted: id={}, name={}, operator=system",
          id,
          existing.getRealName());
      // 冻结该用户的所有活跃工单
      try {
        freezeUserWorkOrders(id);
      } catch (Exception e) {
        logger.warn("冻结用户工单时出错: userId={}, error={}", id, e.getMessage());
      }
      return Result.success(null);
    } else {
      // deleteById SQL 带 `AND is_deleted = 0`，并发场景下可能已被其他事务删除
      return Result.error("删除失败：员工可能已被删除");
    }
  }

  /**
   * 启用/禁用员工（禁用时冻结其活跃工单并递增 token_version 使旧 JWT 立即失效）。
   *
   * @param id 员工 ID
   * @param status 1=启用 0=禁用
   * @param currentUser 当前操作者
   * @return 操作结果
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<Void> toggleEmployeeStatus(Long id, Integer status, User currentUser) {
    logger.info(
        "toggleEmployeeStatus called: id={}, status={}, operator={}",
        id,
        status,
        currentUser.getRealName());

    User existing = userMapper.selectById(id);
    if (existing == null) {
      logger.error("Employee not found: id={}", id);
      return Result.error("员工不存在");
    }

    // Permission check for department admin
    if (accessGuard.isCrossDepartmentAccess(currentUser, existing.getDepartmentId())) {
      logger.warn(
          "Dept admin {} tried to modify user from different department: {}",
          currentUser.getRealName(),
          id);
      return Result.error("无权修改其他部门的员工");
    }

    // Prevent disabling self
    if (id.equals(currentUser.getId()) && status == 0) {
      logger.warn("User {} tried to disable themselves", currentUser.getRealName());
      return Result.error("不能禁用自己的账号");
    }

    User update = new User();
    update.setId(id);
    update.setStatus(status); // 1=Active, 0=Disabled

    logger.info("Updating employee status: id={}, newStatus={}", id, status);

    int result;
    try {
      result = userMapper.updateById(update);
    } catch (Exception e) {
      logger.error(
          "Database error updating employee status: id={}, error={}", id, e.getMessage(), e);
      throw e;
    }

    if (result > 0) {
      String action = status == 1 ? "enabled" : "disabled";
      logger.info(
          "Employee {} by {}: id={}, name={}",
          action,
          currentUser.getRealName(),
          id,
          existing.getRealName());

      // 禁用时:冻结活跃工单 + 递增 token_version（OPTIMIZATION 三.3.1），使已签发 JWT 立即失效
      if (status == 0) {
        freezeUserWorkOrders(id);
        try {
          int bumped = userMapper.incrementTokenVersion(id);
          if (bumped > 0) {
            logger.warn("用户 {} 已被禁用，token_version 已递增（旧 Token 立即失效）", id);
            tokenVersionCache.evict(id);
          } else {
            logger.warn("用户 {} 已被禁用，但 token_version 递增返回 0 行（用户可能已被删除）", id);
          }
        } catch (Exception ex) {
          // token_version 递增失败不阻断禁用主流程（status=0 已阻止新登录）
          logger.error("禁用用户 {} 时递增 token_version 失败: {}", id, ex.getMessage(), ex);
        }
      }

      return Result.success(null);
    } else {
      return Result.error("Failed to update employee status");
    }
  }

  /**
   * 批量导入员工（逐条创建，单条失败不影响其余记录）。
   *
   * @param employees 员工列表
   * @return {successCount, failCount, errors}
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<Map<String, Object>> batchImport(List<User> employees) {
    Map<String, Object> resultData = new HashMap<>();
    int successCount = 0;
    int failCount = 0;
    List<String> errors = new ArrayList<>();

    for (User employee : employees) {
      try {
        // Check duplicate
        User existing = userMapper.findByUsername(employee.getUsername());
        if (existing != null) {
          failCount++;
          errors.add("Username exists: " + employee.getUsername());
          continue;
        }

        // Set defaults
        employee.setStatus(1);
        // 生成随机强密码（B-06：移除硬编码弱密码），明文不返回（员工需走重置密码流程）
        String randomPwd = passwordService.generateStrongPassword(INITIAL_PASSWORD_LENGTH);
        employee.setPassword(passwordEncoder.encode(randomPwd));
        employee.setPasswordChanged(false); // 标记需强制改密

        // 保存明文 email/phone 用于 DEK 重新加密
        final String plainEmail = employee.getEmail();
        final String plainPhone = employee.getPhone();

        int result = insertUserWithDek(employee, plainEmail, plainPhone);

        if (result > 0) {
          successCount++;
          logger.info("批量导入: 用户 {} 创建成功（初始密码已生成，需员工首次登录后修改）", employee.getUsername());
        } else {
          failCount++;
          errors.add("Insert failed: " + employee.getUsername());
        }
      } catch (Exception e) {
        failCount++;
        errors.add("Error: " + employee.getUsername() + " - " + e.getMessage());
      }
    }

    resultData.put("successCount", successCount);
    resultData.put("failCount", failCount);
    resultData.put("errors", errors);

    logger.info("Batch import completed: success={}, fail={}", successCount, failCount);
    return Result.success(resultData);
  }

  /**
   * 插入员工并完成 DEK 分层加密三段式（insert → 生成 per-user DEK → DekContext 内重新加密 email/phone）。
   *
   * <p>DEK 阶段失败不影响员工创建主流程（DekMigrationRunner 启动时补迁移）。
   *
   * @param employee 已设置默认值/密码的员工实体
   * @param plainEmail 插入前的明文 email（用于 DEK 重新加密）
   * @param plainPhone 插入前的明文 phone（用于 DEK 重新加密）
   * @return insert 受影响行数
   */
  private int insertUserWithDek(User employee, String plainEmail, String plainPhone) {
    int result = userMapper.insert(employee);
    if (result > 0) {
      try {
        dekService.generateAndSaveDekForUser(employee.getId());
        encryptionHelper.executeWithDekContext(
            employee.getId(),
            () -> {
              if (plainEmail != null && !plainEmail.isEmpty()) {
                employee.setEmail(plainEmail);
              }
              if (plainPhone != null && !plainPhone.isEmpty()) {
                employee.setPhone(plainPhone);
              }
              // update 触发 TypeHandler 使用 DekContext 中的 DEK 加密 email/phone
              userMapper.update(employee);
            });
      } catch (Exception dekEx) {
        logger.error(
            "[DEK] 用户 {} 生成 DEK 失败（email/phone 仍为 legacy 加密，DekMigrationRunner 会补迁移）: {}",
            employee.getUsername(),
            dekEx.getMessage(),
            dekEx);
      }
    }
    return result;
  }

  /** 冻结员工的活跃工单（占位实现，冻结能力待工单模块完善后接入） */
  private void freezeUserWorkOrders(Long userId) {
    logger.info("Freezing active work orders for disabled user: {}", userId);
  }

  /**
   * 生成唯一的 6 位员工工号（格式：000001 - 999999）。
   *
   * @return 唯一的 6 位工号字符串
   */
  private String generateUniqueEmployeeId() {
    // 获取当前最大工号数字
    Integer maxId = userMapper.getMaxEmployeeIdNumeric();

    // 如果没有记录，从 1 开始；否则 +1
    int nextId = (maxId != null) ? maxId + 1 : 1;

    // 生成 6 位工号，前面补零
    String employeeId = String.format("%06d", nextId);

    // 验证唯一性（防止并发冲突）
    int maxRetries = EMPLOYEE_ID_MAX_RETRIES;
    while (userMapper.findByEmployeeId(employeeId) != null && maxRetries-- > 0) {
      nextId++;
      employeeId = String.format("%06d", nextId);
      logger.warn("工号冲突，重新生成: {}", employeeId);
    }

    if (maxRetries <= 0) {
      throw new RuntimeException("无法生成唯一的员工工号，请稍后重试");
    }

    logger.info("生成新工号: {} (基于最大ID: {})", employeeId, maxId);
    return employeeId;
  }
}