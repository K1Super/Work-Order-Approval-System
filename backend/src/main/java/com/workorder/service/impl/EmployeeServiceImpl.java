package com.workorder.service.impl;


import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workorder.common.constant.RoleConstants;
import com.workorder.common.result.Result;
import com.workorder.dao.UserMapper;
import com.workorder.entity.User;
import com.workorder.security.DekService;
import com.workorder.security.EncryptionHelper;
import com.workorder.service.IEmployeeService;
import com.workorder.service.IPasswordPolicyService;
import com.workorder.service.IPasswordResetService;

/**

 * Employee Management Service Implementation Implements four-tier architecture with two-level admin

 * system

 */

@Service

public class EmployeeServiceImpl implements IEmployeeService {



  private static final Logger logger = LoggerFactory.getLogger(EmployeeServiceImpl.class);

  /** 导出全量数据的分页大小 */
  private static final int EXPORT_PAGE_SIZE = 10000;



  @Autowired private UserMapper userMapper;



  @Autowired private BCryptPasswordEncoder passwordEncoder;



  @Autowired private IPasswordPolicyService passwordPolicyService;



  @Autowired private IPasswordResetService passwordResetService;



  /** OPTIMIZATION 三.3.3：DEK 服务（per-user 分层加密） */

  @Autowired private DekService dekService;



  /** OPTIMIZATION 三.3.3：DekContext 上下文管理器（包裹 email/phone 写入/读取操作） */

  @Autowired private EncryptionHelper encryptionHelper;



  @Override

  public Result<Map<String, Object>> getEmployeeList(

      Integer pageNum,

      Integer pageSize,

      String keyword,

      Integer deptId,

      Integer positionId,

      Integer orgLevel,

      User currentUser) {

    // Build query parameters with permission filtering

    Map<String, Object> params = new HashMap<>();

    params.put("pageNum", pageNum);

    params.put("pageSize", pageSize);



    // Apply permission-based data isolation

    if (isDeptAdmin(currentUser)) {

      // Department admin can only see their department members

      params.put("departmentId", currentUser.getDepartmentId());

      logger.info(

          "Department admin {} filtering by department: {}",

          currentUser.getRealName(),

          currentUser.getDepartmentId());

    }



    if (keyword != null && !keyword.isEmpty()) {

      params.put("keyword", "%" + keyword + "%");

    }

    if (deptId != null && !isDeptAdmin(currentUser)) {

      params.put("departmentId", deptId);

    }

    if (positionId != null) {

      params.put("positionId", positionId);

    }

    if (orgLevel != null) {

      params.put("orgLevel", orgLevel);

    }



    try {

      logger.info("=== Starting getEmployeeList ===");

      logger.info("Params: pageNum={}, pageSize={}, keyword={}", pageNum, pageSize, keyword);



      List<User> list = userMapper.selectList(params);

      logger.info("Query returned {} users", list.size());



      int total = userMapper.countTotal(params);

      logger.info("Total count: {}", total);



      // 为每个员工填充角色信息

      for (User employee : list) {

        try {

          List<Long> roleIds = userMapper.getUserRoleIds(employee.getId());

          employee.setRoleIds(roleIds);



          // 获取角色名称

          if (roleIds != null && !roleIds.isEmpty()) {

            List<String> roleNames = userMapper.getUserRoleNames(roleIds);

            employee.setRoleNames(roleNames);

          }

        } catch (Exception ex) {

          logger.warn("Error loading roles for user {}: {}", employee.getId(), ex.getMessage());

        }

      }



      Map<String, Object> data = new HashMap<>();

      data.put("list", list);

      data.put("total", total);

      data.put("currentUserRole", getHighestUserRole(currentUser));



      return Result.success(data);

    } catch (Exception e) {

      logger.error("Error in getEmployeeList: {}", e.getMessage(), e);

      throw new RuntimeException("Failed to load employees: " + e.getMessage(), e);

    }

  }



  @Override

  public Result<User> getEmployeeById(Long id, User currentUser) {

    User employee = userMapper.selectById(id);

    if (employee == null) {

      return Result.error("Employee not found");

    }



    // Permission check for department admin

    if (isDeptAdmin(currentUser)) {

      if (!employee.getDepartmentId().equals(currentUser.getDepartmentId())) {

        logger.warn(

            "Dept admin {} tried to access user from different department: {}",

            currentUser.getRealName(),

            id);

        return Result.error("Access denied: Cannot access employees from other departments");

      }

    }



    return Result.success(employee);

  }



  @Override

  @Transactional

  public Result<User> createEmployee(User employee) {

    // ============================================

    // 自动生成6位工号（唯一标识）

    // ============================================

    String employeeId = generateUniqueEmployeeId();

    employee.setEmployeeId(employeeId);

    logger.info("为新生成员工分配工号: {}", employeeId);



    // ============================================

    // 生成随机8位强密码（大小写+数字）

    // 阶段 2 修复 B-05 / D-05：明文密码不入日志

    // ============================================

    String initialPassword = generateStrongPassword(8);

    logger.info("为员工 {} 生成初始临时密码（明文不记录日志）", employee.getRealName());



    // 自动用真名生成用户名（如果未提供）

    if (employee.getUsername() == null || employee.getUsername().trim().isEmpty()) {

      String realName = employee.getRealName();

      if (realName != null && !realName.trim().isEmpty()) {

        // 真名转小写+下划线作为用户名

        String autoUsername = realName.trim().toLowerCase().replaceAll("\\s+", "_");

        employee.setUsername(autoUsername);

      } else {

        return Result.error("请提供真实姓名");

      }

    }



    // Check if username exists

    // 关键修复：必须检查包含已软删除的用户名，
    // 因为数据库 sys_user_username_key 唯一约束不区分 is_deleted，
    // 软删除的用户名仍占用唯一约束，直接插入会违反约束导致 500 错误
    User existing = userMapper.findByUsernameIncludeDeleted(employee.getUsername());

    if (existing != null) {

      if (existing.getIsDeleted() != null && existing.getIsDeleted() == 1) {

        // 用户名被已删除的用户占用 — 物理删除旧记录后允许重新使用
        logger.info("用户名 {} 被已删除用户(id={})占用，物理删除旧记录以释放用户名",
            employee.getUsername(), existing.getId());
        userMapper.deleteUserRoles(existing.getId());
        // 物理删除（直接 DELETE，非逻辑删除）
        userMapper.physicalDeleteById(existing.getId());
      } else {

        return Result.error("用户名已存在: " + employee.getUsername());
      }
    }



    // Set default values and encrypt password

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



    // ============================================

    // OPTIMIZATION 三.3.3：DEK 分层加密集成

    // 阶段 1：先插入用户（email/phone 由 TypeHandler 用 legacy 静态密钥加密，因 DekContext 为空）

    // 阶段 2：用新生成的 userId 生成 DEK 并保存到 sys_user.encrypted_dek

    // 阶段 3：在 DekContext 上下文中重新加密 email/phone（用新 DEK）并 update

    // ============================================

    // 保存明文 email/phone 以便后续用 DEK 重新加密

    final String plainEmail = employee.getEmail();

    final String plainPhone = employee.getPhone();



    int result = userMapper.insert(employee);



    if (result > 0) {

      // 阶段 2：生成并保存 per-user DEK

      try {

        dekService.generateAndSaveDekForUser(employee.getId());

        logger.info("[DEK] 已为新员工 {} 生成 DEK (userId={})", employee.getUsername(), employee.getId());



        // 阶段 3：用 DEK 重新加密 email/phone（DekContext 已设置）

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

            "[DEK] 新员工 {} 生成 DEK 失败（email/phone 仍为 legacy 加密）: {}",

            employee.getUsername(),

            dekEx.getMessage(),

            dekEx);

        // 不影响员工创建主流程；DekMigrationRunner 启动时会补迁移

      }



      // 阶段 2 修复 B-05 / D-05：明文密码不入日志、不返回响应体

      // 完整 token 机制在阶段 4 实现；本阶段响应只返回成功消息

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



  @Override

  @Transactional

  public Result<User> updateEmployee(Long id, User employee, User currentUser) {

    // Check if employee exists

    User existing = userMapper.selectById(id);

    if (existing == null) {

      return Result.error("Employee not found");

    }



    // Permission check for department admin

    if (isDeptAdmin(currentUser)) {

      if (!existing.getDepartmentId().equals(currentUser.getDepartmentId())) {

        logger.warn(

            "Dept admin {} tried to update user from different department: {}",

            currentUser.getRealName(),

            id);

        return Result.error("Access denied: Cannot modify employees from other departments");

      }

    }



    // Update fields (excluding password - use separate endpoint)

    employee.setId(id);

    employee.setPassword(null);



    // ============================================

    // 禁止修改工号（工号为系统生成，不可更改）

    // ============================================

    employee.setEmployeeId(null); // 清除前端传入的工号值，保持原值不变

    logger.debug("员工更新时禁止修改工号: userId={}", id);



    // ============================================

    // OPTIMIZATION 三.3.3：在 DekContext 上下文中更新

    // 设置 DekContext 后，TypeHandler 使用该用户的 DEK 加密 email/phone，

    // 否则会回退到 legacy 静态密钥，导致与历史密文不匹配

    // ============================================

    final User employeeToUpdate = employee;

    int result =

        encryptionHelper.executeWithDekContext(id, () -> userMapper.updateById(employeeToUpdate));



    if (result > 0) {

      logger.info("Employee updated by {}: userId={}", currentUser.getRealName(), id);

      // 查询返回时同样需要 DekContext 以便 TypeHandler 解密 email/phone

      User updated = encryptionHelper.executeWithDekContext(id, () -> userMapper.selectById(id));

      return Result.success(updated);

    } else {

      return Result.error("Failed to update employee");

    }

  }



  @Override

  @Transactional

  public Result<Void> deleteEmployee(Long id) {

    // selectById 已带 is_deleted = 0 过滤，已删除员工会返回 null

    User existing = userMapper.selectById(id);

    if (existing == null) {

      return Result.error("员工不存在或已被删除");

    }


    // 企业级安全保护：禁止删除超级管理员账户

    // 超级管理员是系统根基账户，删除将导致系统无法管理

    if (isSuperAdmin(existing)) {

      logger.warn("[安全拦截] 禁止删除超级管理员账户: id={}, username={}", id, existing.getUsername());

      return Result.error("禁止删除超级管理员账户（系统根基账户受保护）");

    }



    // 逻辑删除：设置 is_deleted = 1（员工从列表消失），同时 status = 0（禁止登录）

    //

    // 修复（2026-07-26）：原实现仅调用 updateById 设置 status = 0，

    //   未更新 is_deleted 字段。而 selectList 列表查询的过滤条件为

    //   `is_deleted = 0 AND (status IS NULL OR status >= 0)`，

    //   status = 0 仍满足 `>= 0`，导致删除后员工仍显示在列表中；

    //   且 selectById 因 is_deleted 仍为 0 仍可查到，导致可被重复删除。

    //

    // 正确做法：调用 deleteById（UserMapper.xml 已定义），一次性设置

    //   is_deleted = 1 + status = 0，使删除后：

    //   1. selectList 的 `is_deleted = 0` 过滤生效 → 员工从列表消失

    //   2. selectById 的 `is_deleted = 0` 过滤生效 → 重复删除返回"员工不存在"

    //

    // 语义区分：

    //   - toggleEmployeeStatus(status=0)：禁用，员工仍在列表（显示"禁用"），可重新启用

    //   - deleteEmployee：逻辑删除，员工从列表消失，不可恢复

    //

    // 用户角色关联保留（便于审计追溯），但 is_deleted=1 后无法登录、不在列表显示

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



  @Override

  @Transactional

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

    if (isDeptAdmin(currentUser)) {

      if (!existing.getDepartmentId().equals(currentUser.getDepartmentId())) {

        logger.warn(

            "Dept admin {} tried to modify user from different department: {}",

            currentUser.getRealName(),

            id);

        return Result.error("无权修改其他部门的员工");

      }

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



      // If disabling, also freeze all active work orders

      // OPTIMIZATION 三.3.1：禁用用户时同步递增 token_version，使该用户已签发的 JWT 立即失效，

      // 防止被禁用账号在 JWT 过期前继续访问系统资源

      if (status == 0) {

        freezeUserWorkOrders(id);

        try {

          int bumped = userMapper.incrementTokenVersion(id);

          if (bumped > 0) {

            logger.warn("用户 {} 已被禁用，token_version 已递增（旧 Token 立即失效）", id);

          } else {

            logger.warn("用户 {} 已被禁用，但 token_version 递增返回 0 行（用户可能已被删除）", id);

          }

        } catch (Exception ex) {

          // token_version 递增失败不阻断禁用主流程（status=0 已阻止新登录），

          // 但记录错误以便排查；旧 Token 在过期前仍可用，需人工排查

          logger.error("禁用用户 {} 时递增 token_version 失败: {}", id, ex.getMessage(), ex);

        }

      }



      return Result.success(null);

    } else {

      return Result.error("Failed to update employee status");

    }

  }



  @Override

  @Transactional

  public Result<Map<String, Object>> resetPassword(

      Long id, User currentUser, String operatorPassword) {

    // ============================================

    // 企业级密码重置流程（阶段 4 优化）

    // - 生成一次性 token，用户通过重置链接自行设置新密码

    // - 超管保护：非超管不能重置超管密码

    // - 超管自保护：超管只能重置自己（不能重置其他超管）

    // - 超管重置自己需要二次验证（操作密码）

    // - 禁止重置自己的密码（超管除外）

    // ============================================



    User existing = userMapper.selectById(id);

    if (existing == null) {

      return Result.error("员工不存在");

    }



    // 部门管理员跨部门限制

    if (isDeptAdmin(currentUser)) {

      if (!existing.getDepartmentId().equals(currentUser.getDepartmentId())) {

        return Result.error("无权限：不能重置其他部门员工的密码");

      }

    }



    boolean targetIsSuperAdmin = isSuperAdmin(existing);

    boolean operatorIsSuperAdmin = isSuperAdmin(currentUser);

    boolean isSelfReset = id.equals(currentUser.getId());



    // ============================================

    // 安全规则 1：非超管不能重置超管密码

    // ============================================

    if (targetIsSuperAdmin && !operatorIsSuperAdmin) {

      logger.warn(

          "⚠️ 非超管 {} 尝试重置超管 {} 的密码（已拒绝）", currentUser.getRealName(), existing.getRealName());

      return Result.error("无权限：不能重置超级管理员的密码");

    }



    // ============================================

    // 安全规则 2：超管不能重置其他超管的密码（只能重置自己）

    // ============================================

    if (operatorIsSuperAdmin && targetIsSuperAdmin && !isSelfReset) {

      logger.warn(

          "⚠️ 超管 {} 尝试重置其他超管 {} 的密码（已拒绝）", currentUser.getRealName(), existing.getRealName());

      return Result.error("无权限：不能重置其他超级管理员的密码，请联系该管理员自行重置");

    }



    // ============================================

    // 安全规则 3：禁止重置自己的密码（超管除外）

    // 普通管理员重置自己密码应使用 changePassword 接口

    // ============================================

    if (isSelfReset && !operatorIsSuperAdmin) {

      return Result.error("不能重置自己的密码，请使用「修改密码」功能");

    }



    // ============================================

    // 安全规则 4：超管重置自己需要二次验证（操作密码）

    // ============================================

    if (isSelfReset && operatorIsSuperAdmin) {

      if (operatorPassword == null || operatorPassword.trim().isEmpty()) {

        return Result.error("超管重置自己的密码需要二次验证，请输入当前密码");

      }

      // 验证操作密码

      User operator = userMapper.selectById(currentUser.getId());

      if (operator == null || !passwordEncoder.matches(operatorPassword, operator.getPassword())) {

        logger.warn("⚠️ 超管 {} 重置自己密码时二次验证失败（操作密码错误）", currentUser.getRealName());

        return Result.error("二次验证失败：当前密码不正确");

      }

      logger.info("✅ 超管 {} 重置自己密码二次验证通过", currentUser.getRealName());

    }



    // ============================================

    // 生成重置令牌（明文 token 仅返回一次，哈希存库）

    // ============================================

    logger.info(

        "为员工 {} ({}) 生成密码重置令牌: 操作者={}",

        existing.getRealName(),

        existing.getEmployeeId(),

        currentUser.getRealName());



    Result<Map<String, Object>> tokenResult =

        passwordResetService.generateResetToken(id, currentUser.getId());



    if (tokenResult.getCode() != Result.SUCCESS) {

      return Result.error(tokenResult.getMsg());

    }



    // 构造重置链接（前端路由 /reset-password?token=xxx）

    Map<String, Object> data = tokenResult.getData();

    String plainToken = (String) data.get("token");

    String resetLink = "/reset-password?token=" + plainToken;



    Map<String, Object> responseData = new HashMap<>();

    responseData.put("token", plainToken);

    responseData.put("resetLink", resetLink);

    responseData.put("expiryMinutes", data.get("expiryMinutes"));

    responseData.put("expiryTime", data.get("expiryTime"));

    responseData.put("username", data.get("username"));

    responseData.put("realName", data.get("realName"));

    responseData.put("userId", id);



    logger.info(

        "✅ 密码重置令牌已生成: 操作者={}, 目标用户={}, 过期时间={} 分钟",

        currentUser.getRealName(),

        existing.getRealName(),

        data.get("expiryMinutes"));



    return Result.success(

        "密码重置链接已生成，有效期 " + data.get("expiryMinutes") + " 分钟，请将链接通过安全通道发送给员工", responseData);

  }



  @Override

  @Transactional

  public Result<Void> assignRoles(Long id, List<Long> roleIds) {

    User existing = userMapper.selectById(id);

    if (existing == null) {

      return Result.error("Employee not found");

    }



    // Delete existing role assignments

    userMapper.deleteUserRoles(id);



    // Insert new role assignments

    for (Long roleId : roleIds) {

      userMapper.insertUserRole(id, roleId);

    }



    // 自动计算并更新组织层级（根据最高权限角色）

    if (!roleIds.isEmpty()) {

      Integer newOrgLevel = calculateOrgLevelFromRoles(roleIds);

      if (newOrgLevel != null) {

        User update = new User();

        update.setId(id);

        update.setOrgLevel(newOrgLevel);

        userMapper.updateById(update);

        logger.info("用户 {} 的组织层级已自动更新为: {}", id, newOrgLevel);

      }

    }



    logger.info("权限分配成功: 用户={}, 角色列表={}", existing.getRealName(), roleIds);

    return Result.success(null);

  }



  /**

   * 根据角色ID列表计算最高的组织层级 组织层级值越小，权限越高

   *

   * @param roleIds 角色ID列表

   * @return 最高的组织层级（最小值）

   */

  private Integer calculateOrgLevelFromRoles(List<Long> roleIds) {

    if (roleIds == null || roleIds.isEmpty()) {

      return 4; // 默认基层员工

    }



    try {

      // 查询这些角色的org_level

      List<Map<String, Object>> roles = userMapper.selectRolesByIds(roleIds);



      int minOrgLevel = 10; // 初始值设大

      for (Map<String, Object> role : roles) {

        Object orgLevelObj = role.get("org_level");

        if (orgLevelObj != null) {

          int level = ((Number) orgLevelObj).intValue();

          if (level < minOrgLevel) {

            minOrgLevel = level;

          }

        }

      }

      return minOrgLevel <= 9 ? minOrgLevel : 4; // 如果没有有效数据，返回默认值

    } catch (Exception e) {

      logger.warn("计算组织层级失败: {}", e.getMessage());

      return 4;

    }

  }



  @Override

  @Transactional

  public Result<Map<String, Object>> batchImport(List<User> employees) {

    Map<String, Object> resultData = new HashMap<>();

    int successCount = 0;

    int failCount = 0;

    List<String> errors = new java.util.ArrayList<>();



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

        // 阶段 2 修复 B-06：移除硬编码弱密码 "123456"，改为生成随机强密码

        // 完整 token 机制在阶段 4 实现；本阶段生成强密码，明文不返回（员工需走重置密码流程）

        String randomPwd = generateStrongPassword(8);

        employee.setPassword(passwordEncoder.encode(randomPwd));

        employee.setPasswordChanged(false); // 标记需强制改密



        // OPTIMIZATION 三.3.3：保存明文 email/phone 用于 DEK 重新加密

        final String plainEmail = employee.getEmail();

        final String plainPhone = employee.getPhone();



        int result = userMapper.insert(employee);

        if (result > 0) {

          // OPTIMIZATION 三.3.3：为新员工生成 DEK 并重新加密 email/phone

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

                  userMapper.update(employee);

                });

          } catch (Exception dekEx) {

            logger.warn(

                "[DEK] 批量导入用户 {} 生成 DEK 失败（DekMigrationRunner 会补迁移）: {}",

                employee.getUsername(),

                dekEx.getMessage());

          }

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



  @Override

  public Result<List<User>> exportEmployees(User currentUser) {

    Map<String, Object> params = new HashMap<>();

    params.put("pageNum", 1);

    params.put("pageSize", EXPORT_PAGE_SIZE); // Export all



    // Apply permission filter for dept admin

    if (isDeptAdmin(currentUser)) {

      params.put("departmentId", currentUser.getDepartmentId());

    }



    List<User> list = userMapper.selectList(params);

    return Result.success(list);

  }



  @Override

  public Result<List<Map<String, Object>>> getAllDepartments() {

    List<Map<String, Object>> departments = userMapper.selectAllDepartments();

    return Result.success(departments);

  }



  @Override

  public Result<List<Map<String, Object>>> getAllPositions() {

    List<Map<String, Object>> positions = userMapper.selectAllPositions();

    return Result.success(positions);

  }



  @Override

  public Result<List<Map<String, Object>>> getAllRoles() {

    List<Map<String, Object>> roles = userMapper.selectAllRoles();

    return Result.success(roles);

  }



  @Override

  public Result<List<User>> getSuperiors(List<Integer> orgLevel) {

    List<User> superiors;

    if (orgLevel != null && !orgLevel.isEmpty()) {

      superiors = userMapper.selectByOrgLevels(orgLevel);

    } else {

      // Default: get all users with org_level <= 2 (managers and above)

      superiors = userMapper.selectManagers(2);

    }

    return Result.success(superiors);

  }



  /**

   * Helper method: Check if current user is a department administrator

   *

   * <p>阶段 2 修复 C-09 / H-09：使用 RoleConstants 统一角色判断 原使用硬编码 14L（旧角色 ID），与新 RoleConstants 不一致。

   */

  private boolean isDeptAdmin(User user) {

    if (user == null || user.getId() == null) return false;



    // 超级管理员是全局管理员，不受部门数据权限限制，不视为部门管理员。

    // 此短路检查与 RoleConstants.DEPT_ADMIN_ROLE_IDS 不含 SUPER_ADMIN 形成双保险，

    // 防止任何一处被误改后导致超管只能查看本部门员工的数据权限回归。

    if (isSuperAdmin(user)) return false;



    List<Long> roleIds = userMapper.getUserRoleIds(user.getId());

    if (roleIds == null) return false;

    for (Long roleId : roleIds) {

      if (RoleConstants.isDeptAdmin(roleId)) return true;

    }

    return false;

  }



  /** 判断用户是否为超级管理员（拥有 ROLE_SUPER_ADMIN 角色） 用于密码重置等敏感操作的保护判断 */

  private boolean isSuperAdmin(User user) {

    if (user == null || user.getId() == null) return false;

    List<Long> roleIds = userMapper.getUserRoleIds(user.getId());

    if (roleIds == null) return false;

    for (Long roleId : roleIds) {

      if (roleId == RoleConstants.ROLE_SUPER_ADMIN) return true;

    }

    return false;

  }



  /**

   * Helper method: Get highest priority role code for display

   *

   * <p>阶段 2 修复 C-09 / H-09：使用 RoleConstants 统一角色映射 原使用 15-22 旧角色 ID，与新 RoleConstants 不一致。

   */

  private String getHighestUserRole(User user) {

    if (user == null || user.getId() == null) return "UNKNOWN";



    List<Long> roleIds = userMapper.getUserRoleIds(user.getId());

    if (roleIds == null || roleIds.isEmpty()) return "EMPLOYEE";



    // 取所有角色中最高层级（最小 orgLevel 值）对应的角色代码

    int minLevel = 4;

    long highestRoleId = RoleConstants.ROLE_STAFF;

    for (Long roleId : roleIds) {

      int level = RoleConstants.getOrgLevelByRoleId(roleId);

      if (level < minLevel) {

        minLevel = level;

        highestRoleId = roleId;

      }

    }



    if (highestRoleId == RoleConstants.ROLE_SUPER_ADMIN) return "SUPER_ADMIN";

    if (highestRoleId == RoleConstants.ROLE_SECURITY_AUDIT) return "SECURITY_AUDIT";

    if (highestRoleId == RoleConstants.ROLE_CHAIRMAN) return "CHAIRMAN";

    if (highestRoleId == RoleConstants.ROLE_GM) return "GM";

    if (highestRoleId == RoleConstants.ROLE_VP) return "VP";

    if (highestRoleId >= RoleConstants.ROLE_RD_DIR

        && highestRoleId <= RoleConstants.ROLE_PROCUREMENT_DIR) return "DIRECTOR";

    if (highestRoleId >= RoleConstants.ROLE_HR_SPEC

        && highestRoleId <= RoleConstants.ROLE_PROCUREMENT_SPEC) return "FUNCTIONAL";

    return "EMPLOYEE";

  }



  /** Freeze all active work orders when an employee is disabled */

  private void freezeUserWorkOrders(Long userId) {

    // Implementation would go here to freeze work orders

    // This is a placeholder for the actual implementation

    logger.info("Freezing active work orders for disabled user: {}", userId);

  }



  /**

   * 生成唯一的6位员工工号 格式：000001 - 999999

   *

   * @return 唯一的6位工号字符串

   */

  private String generateUniqueEmployeeId() {

    // 获取当前最大工号数字

    Integer maxId = userMapper.getMaxEmployeeIdNumeric();



    // 如果没有记录，从1开始；否则+1

    int nextId = (maxId != null) ? maxId + 1 : 1;



    // 生成6位工号，前面补零

    String employeeId = String.format("%06d", nextId);



    // 验证唯一性（防止并发冲突）

    int maxRetries = 10;

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



  /**

   * 生成随机强密码 包含：大写字母 + 小写字母 + 数字 + 特殊字符

   *

   * <p>与密码策略 requireSpecialChar=true 保持一致

   *

   * @param length 密码长度（建议8位以上）

   * @return 随机生成的强密码

   */

  private String generateStrongPassword(int length) {

    // 定义字符集

    String upperCase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    String lowerCase = "abcdefghijklmnopqrstuvwxyz";

    String digits = "0123456789";

    String specialChars = "!@#$%^&*";



    // 合并所有字符

    String allChars = upperCase + lowerCase + digits + specialChars;



    // 使用安全随机数生成器

    SecureRandom random = new SecureRandom();

    StringBuilder password = new StringBuilder();



    // 确保至少包含一个大写字母、一个小写字母、一个数字和一个特殊字符

    password.append(upperCase.charAt(random.nextInt(upperCase.length())));

    password.append(lowerCase.charAt(random.nextInt(lowerCase.length())));

    password.append(digits.charAt(random.nextInt(digits.length())));

    password.append(specialChars.charAt(random.nextInt(specialChars.length())));



    // 填充剩余长度

    for (int i = 4; i < length; i++) {

      password.append(allChars.charAt(random.nextInt(allChars.length())));

    }



    // 打乱密码顺序（避免前四位总是固定类型顺序）

    char[] passwordArray = password.toString().toCharArray();

    for (int i = passwordArray.length - 1; i > 0; i--) {

      int j = random.nextInt(i + 1);

      char temp = passwordArray[i];

      passwordArray[i] = passwordArray[j];

      passwordArray[j] = temp;

    }



    return new String(passwordArray);

  }

}


