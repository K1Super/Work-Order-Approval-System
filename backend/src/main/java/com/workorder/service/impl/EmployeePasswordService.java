package com.workorder.service.impl;


import java.security.SecureRandom;
import java.util.HashMap;
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
import com.workorder.service.IPasswordResetService;

/**
 * 员工密码服务组件。
 *
 * <p>职责：企业级密码重置流程（超管保护/二次验证/一次性令牌）与随机强密码生成。
 * 访问控制判定委托 {@link EmployeeAccessGuard};令牌生成委托 {@link IPasswordResetService}。
 *
 * <p>事务边界：{@code resetPassword} 保留 {@code @Transactional}（与拆分前 EmployeeServiceImpl 一致）。
 *
 * @author KLord
 */
@Component
public class EmployeePasswordService {

  private static final Logger logger = LoggerFactory.getLogger(EmployeePasswordService.class);

  @Autowired private UserMapper userMapper;

  @Autowired private BCryptPasswordEncoder passwordEncoder;

  @Autowired private IPasswordResetService passwordResetService;

  @Autowired private EmployeeAccessGuard accessGuard;

  /**
   * 重置员工密码（企业级安全流程）。
   *
   * <p>安全规则： 1. 非超管不能重置超管密码 2. 超管不能重置其他超管密码（只能重置自己） 3. 禁止重置自己的密码（超管除外） 4. 超管重置自己需要二次验证（操作密码）
   *
   * @param id 目标用户 ID
   * @param currentUser 当前操作者
   * @param operatorPassword 操作者当前密码（超管重置自己时必填）
   * @return Result 包含 token、expiryMinutes、username、realName、resetLink
   */
  @Transactional(rollbackFor = Exception.class)
  public Result<Map<String, Object>> resetPassword(Long id, User currentUser, String operatorPassword) {
    User existing = userMapper.selectById(id);
    if (existing == null) {
      return Result.error("员工不存在");
    }

    // 部门管理员跨部门限制
    if (accessGuard.isCrossDepartmentAccess(currentUser, existing.getDepartmentId())) {
      return Result.error("无权限：不能重置其他部门员工的密码");
    }

    boolean targetIsSuperAdmin = accessGuard.isSuperAdmin(existing);
    boolean operatorIsSuperAdmin = accessGuard.isSuperAdmin(currentUser);
    boolean isSelfReset = id.equals(currentUser.getId());

    // 安全规则 1：非超管不能重置超管密码
    if (targetIsSuperAdmin && !operatorIsSuperAdmin) {
      logger.warn(
          "⚠️ 非超管 {} 尝试重置超管 {} 的密码（已拒绝）", currentUser.getRealName(), existing.getRealName());
      return Result.error("无权限：不能重置超级管理员的密码");
    }

    // 安全规则 2：超管不能重置其他超管的密码（只能重置自己）
    if (operatorIsSuperAdmin && targetIsSuperAdmin && !isSelfReset) {
      logger.warn(
          "⚠️ 超管 {} 尝试重置其他超管 {} 的密码（已拒绝）", currentUser.getRealName(), existing.getRealName());
      return Result.error("无权限：不能重置其他超级管理员的密码，请联系该管理员自行重置");
    }

    // 安全规则 3：禁止重置自己的密码（超管除外）
    if (isSelfReset && !operatorIsSuperAdmin) {
      return Result.error("不能重置自己的密码，请使用「修改密码」功能");
    }

    // 安全规则 4：超管重置自己需要二次验证（操作密码）
    if (isSelfReset && operatorIsSuperAdmin) {
      if (operatorPassword == null || operatorPassword.trim().isEmpty()) {
        return Result.error("超管重置自己的密码需要二次验证，请输入当前密码");
      }
      User operator = userMapper.selectById(currentUser.getId());
      if (operator == null || !passwordEncoder.matches(operatorPassword, operator.getPassword())) {
        logger.warn("⚠️ 超管 {} 重置自己密码时二次验证失败（操作密码错误）", currentUser.getRealName());
        return Result.error("二次验证失败：当前密码不正确");
      }
      logger.info("✅ 超管 {} 重置自己密码二次验证通过", currentUser.getRealName());
    }

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

  /**
   * 生成随机强密码。
   *
   * <p>包含大写字母 + 小写字母 + 数字 + 特殊字符，与密码策略 requireSpecialChar=true 保持一致。
   *
   * @param length 密码长度（建议 8 位以上）
   * @return 随机生成的强密码
   */
  public String generateStrongPassword(int length) {
    String upperCase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    String lowerCase = "abcdefghijklmnopqrstuvwxyz";
    String digits = "0123456789";
    String specialChars = "!@#$%^&*";

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