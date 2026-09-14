package com.workorder.dao;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.dto.RoleInfoDTO;
import com.workorder.entity.PasswordHistory;
import com.workorder.entity.User;

/**
 * 用户Mapper接口
 *
 * @author KLord
 */
@Mapper
public interface UserMapper {

  /** 根据ID查询用户 */
  User selectById(@Param("id") Long id);

  /** 根据用户名查询用户 */
  User selectByUsername(@Param("username") String username);

  /** 根据工号查询用户（用于登录认证） */
  User selectByEmployeeId(@Param("employeeId") String employeeId);

  /** 根据工号查询用户（用于检查工号唯一性） */
  User findByEmployeeId(@Param("employeeId") String employeeId);

  /** 获取当前最大工号的数字部分 */
  Integer getMaxEmployeeIdNumeric();

  /** 查询所有用户 */
  List<User> selectAll();

  /** 插入用户 */
  int insert(User user);

  /** 更新用户 */
  int update(User user);

  /** 根据ID删除用户 */
  int deleteById(@Param("id") Long id);

  /** 按角色编码查找第一个启用用户ID（负责人兜底） */
  Long selectFirstActiveUserIdByRoleCode(@Param("roleCode") String roleCode);

  /** 根据用户ID查询角色列表 */
  List<RoleInfoDTO> selectRolesByUserId(@Param("userId") Long userId);

  /** 根据用户ID查询权限编码列表 */
  List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);

  /** 根据用户名查询用户（仅活跃用户 is_deleted=0，用于登录认证） */
  User findByUsername(@Param("username") String username);

  /**
   * 根据用户名查询用户（包含已软删除的用户，用于创建员工时检查用户名重复）
   *
   * <p>数据库的 sys_user_username_key 唯一约束不区分 is_deleted， 因此即使用户已被软删除
   * (is_deleted=1)，其用户名仍占用唯一约束。 创建员工时必须用此方法检查全部用户名，
   * 避免插入时违反唯一约束导致 500 错误。
   */
  User findByUsernameIncludeDeleted(@Param("username") String username);

  /**
   * 物理删除用户记录（直接 DELETE，非逻辑删除）
   *
   * <p>用于创建员工时释放已软删除用户占用的用户名。 仅在用户 is_deleted=1 时调用，
   * 活跃用户禁止物理删除。
   *
   * @param userId 用户 ID
   * @return 影响行数
   */
  int physicalDeleteById(@Param("userId") Long userId);

  /** 分页查询用户列表 */
  List<User> selectList(Map<String, Object> params);

  /** 统计用户总数 */
  int countTotal(Map<String, Object> params);

  /** 更新用户信息（根据ID） */
  int updateById(User user);

  /** 删除用户角色关联 */
  int deleteUserRoles(@Param("userId") Long userId);

  /** 插入用户角色关联 */
  int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

  /** 查询所有部门 */
  List<Map<String, Object>> selectAllDepartments();

  /** 查询所有职位 */
  List<Map<String, Object>> selectAllPositions();

  /** 查询所有角色 */
  List<Map<String, Object>> selectAllRoles();

  /** 根据组织层级列表查询用户（上级选择） */
  List<User> selectByOrgLevels(@Param("orgLevels") List<Integer> orgLevels);

  /** 查询管理层及以上的用户 */
  List<User> selectManagers(@Param("maxOrgLevel") Integer maxOrgLevel);

  /** 根据部门ID查询该部门的管理层用户（org_level <= 2） 用于动态任务分配器查找部门经理 */
  List<User> selectDeptManagers(@Param("departmentId") Long departmentId);

  /** 根据组织层级列表查询用户 用于动态任务分配器查找高管层审批人 */
  List<User> selectUsersWithRoles(@Param("orgLevels") List<Integer> orgLevels);

  /** 根据用户ID查询角色ID列表 */
  List<Long> getUserRoleIds(@Param("userId") Long userId);

  /** 根据角色ID列表查询角色名称 */
  List<String> getUserRoleNames(@Param("list") List<Long> roleIds);

  /** 根据角色ID列表查询角色信息（包含组织层级） */
  List<Map<String, Object>> selectRolesByIds(@Param("list") List<Long> roleIds);

  /** 更新用户密码 */
  int updatePassword(User user);

  /** Insert password history record 插入密码历史记录 */
  int insertPasswordHistory(PasswordHistory passwordHistory);

  /** Select recent password history for a user 查询用户最近的密码历史 */
  List<PasswordHistory> selectPasswordHistory(
      @Param("userId") Long userId, @Param("limit") int limit);

  /** Select all password history for a user 查询用户所有密码历史 */
  List<PasswordHistory> selectAllPasswordHistory(@Param("userId") Long userId);

  /** Delete password history records by IDs 根据ID列表删除密码历史记录 */
  int deletePasswordHistoryByIds(@Param("ids") List<Long> ids);

  // ============================================================
  // OPTIMIZATION 三.3.1 JWT Token 版本号管理
  // ============================================================

  /**
   * 查询用户当前 token_version
   *
   * <p>用于 JwtAuthenticationFilter 校验 JWT 荷载中的版本号是否与数据库一致。 不一致直接拒绝请求（用户改密/被禁用后旧 Token 立即失效）。
   *
   * @param userId 用户ID
   * @return 当前 token_version；用户不存在或已删除返回 null
   */
  Long selectTokenVersion(@Param("userId") Long userId);

  /**
   * 递增 token_version
   *
   * <p>触发场景：用户改密 / 被管理员禁用 / 主动注销 / 强制下线。 递增后所有携带旧版本号的 JWT 立即失效。
   *
   * @param userId 用户ID
   * @return 受影响行数（1=成功，0=用户不存在或已删除）
   */
  int incrementTokenVersion(@Param("userId") Long userId);

  // ============================================================
  // OPTIMIZATION 三.3.3 DEK/KEK 分层加密
  // ============================================================

  /**
   * 查询用户加密后的 DEK
   *
   * <p>用于敏感字段（手机号/邮箱）解密时取回 DEK： 1) 从数据库读取 encrypted_dek 2) 用 KEK 解密得到 DEK 明文 3) 用 DEK 解密手机号/邮箱字段
   *
   * @param userId 用户ID
   * @return Base64 编码的加密 DEK；用户无 DEK 返回 null（需调用 DekService 生成）
   */
  String selectEncryptedDek(@Param("userId") Long userId);

  /**
   * 更新用户 encrypted_dek
   *
   * <p>用于：1) 新用户首次生成 DEK；2) KEK 轮换时重新加密 DEK
   *
   * @param userId 用户ID
   * @param encryptedDek Base64 编码的加密 DEK
   * @return 受影响行数
   */
  int updateEncryptedDek(@Param("userId") Long userId, @Param("encryptedDek") String encryptedDek);
}
