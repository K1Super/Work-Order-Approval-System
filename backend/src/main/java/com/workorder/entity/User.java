package com.workorder.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 用户实体类
 *
 * @author KLord
 */
public class User implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 用户ID */
  private Long id;

  /** 员工工号（6位数字唯一标识） */
  private String employeeId;

  /** 用户名 */
  private String username;

  /** 密码（BCrypt加密） */
  private String password;

  /** 真实姓名 */
  private String realName;

  /** 邮箱 */
  private String email;

  /** 手机号 */
  private String phone;

  /** 部门 */
  private String department;

  /** 职位ID（企业级组织架构） */
  private Long positionId;

  /** 所属部门ID（企业级组织架构） */
  private Long departmentId;

  /** 直属上级ID（用于管理链路查询） */
  private Long superiorId;

  /** 组织层级：0-超管 1-决策层 2-管理层 3-职能层 4-基层 */
  private Integer orgLevel;

  /** 状态：0-禁用 1-启用 */
  private Integer status;

  /** 入职时间 */
  private String hireDate;

  /** 头像URL */
  private String avatar;

  /** 密码最后修改时间（用于90天密码过期策略） */
  private Date passwordChangeTime;

  /** 是否已修改过初始密码：false-未修改（首次登录需强制改密） true-已修改 */
  private Boolean passwordChanged;

  /**
   * JWT Token 版本号（OPTIMIZATION 三.3.1 混合状态管理）
   *
   * <p>每次用户改密 / 被管理员禁用 / 主动注销时递增。 JWT 荷载中携带此版本号，JwtAuthenticationFilter 校验 token
   * 中的版本号与数据库当前版本号是否一致， 不一致则直接拒绝（彻底废止"签发后直至过期始终有效"的缺陷）。
   */
  private Long tokenVersion;

  /**
   * 加密后的 DEK（OPTIMIZATION 三.3.3 DEK/KEK 分层加密）
   *
   * <p>每个用户独立的 Data Encryption Key，用 KEK 加密后存数据库。 手机号、邮箱等敏感字段用此 DEK 加密，密钥轮换时只需重加密 DEK， 历史密文无需改动。KEK
   * 来自外部 KMS（dev 环境变量 / prod Vault）。
   */
  private String encryptedDek;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;

  /** 逻辑删除标识：0-未删除 1-已删除（规范 §2.7.1 审计字段） */
  private Integer isDeleted;

  /** 创建人ID（规范 §2.7.1 审计字段，由 AuditFieldInterceptor 自动填充） */
  private Long createBy;

  /** 更新人ID（规范 §2.7.1 审计字段，由 AuditFieldInterceptor 自动填充） */
  private Long updateBy;

  /** 角色ID列表（非数据库字段，用于前端显示） */
  private transient List<Long> roleIds;

  /** 角色名称列表（非数据库字段，用于前端显示） */
  private transient List<String> roleNames;

  // Getter & Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getEmployeeId() {
    return employeeId;
  }

  public void setEmployeeId(String employeeId) {
    this.employeeId = employeeId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getRealName() {
    return realName;
  }

  public void setRealName(String realName) {
    this.realName = realName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }

  public Long getPositionId() {
    return positionId;
  }

  public void setPositionId(Long positionId) {
    this.positionId = positionId;
  }

  public Long getDepartmentId() {
    return departmentId;
  }

  public void setDepartmentId(Long departmentId) {
    this.departmentId = departmentId;
  }

  public Long getSuperiorId() {
    return superiorId;
  }

  public void setSuperiorId(Long superiorId) {
    this.superiorId = superiorId;
  }

  public Integer getOrgLevel() {
    return orgLevel;
  }

  public void setOrgLevel(Integer orgLevel) {
    this.orgLevel = orgLevel;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer status) {
    this.status = status;
  }

  public String getHireDate() {
    return hireDate;
  }

  public void setHireDate(String hireDate) {
    this.hireDate = hireDate;
  }

  public String getAvatar() {
    return avatar;
  }

  public void setAvatar(String avatar) {
    this.avatar = avatar;
  }

  public Date getPasswordChangeTime() {
    return passwordChangeTime;
  }

  public void setPasswordChangeTime(Date passwordChangeTime) {
    this.passwordChangeTime = passwordChangeTime;
  }

  public Boolean getPasswordChanged() {
    return passwordChanged;
  }

  public void setPasswordChanged(Boolean passwordChanged) {
    this.passwordChanged = passwordChanged;
  }

  public Long getTokenVersion() {
    return tokenVersion;
  }

  public void setTokenVersion(Long tokenVersion) {
    this.tokenVersion = tokenVersion;
  }

  public String getEncryptedDek() {
    return encryptedDek;
  }

  public void setEncryptedDek(String encryptedDek) {
    this.encryptedDek = encryptedDek;
  }

  public LocalDateTime getCreateTime() {
    return createTime;
  }

  public void setCreateTime(LocalDateTime createTime) {
    this.createTime = createTime;
  }

  public LocalDateTime getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(LocalDateTime updateTime) {
    this.updateTime = updateTime;
  }

  public Integer getIsDeleted() {
    return isDeleted;
  }

  public void setIsDeleted(Integer isDeleted) {
    this.isDeleted = isDeleted;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long createBy) {
    this.createBy = createBy;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long updateBy) {
    this.updateBy = updateBy;
  }

  public List<Long> getRoleIds() {
    return roleIds;
  }

  public void setRoleIds(List<Long> roleIds) {
    this.roleIds = roleIds;
  }

  public List<String> getRoleNames() {
    return roleNames;
  }

  public void setRoleNames(List<String> roleNames) {
    this.roleNames = roleNames;
  }

  /** 角色信息（用于MyBatis结果映射） */
  public interface RoleInfo {
    /** 获取角色ID */
    Long getId();

    /** 获取角色名称 */
    String getRoleName();

    /** 获取角色编码 */
    String getRoleCode();
  }
}
