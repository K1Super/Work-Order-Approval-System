package com.workorder.annotation;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Data Permission Isolation Annotation 数据权限隔离注解 — 用于控制器方法级别，强制执行数据访问权限
 *
 * <p>阶段 2 修复 A-10 / H-01：注解从死代码激活，并扩展 dataScope 与 requireDepartmentMatch 字段。
 *
 * <p>Usage: @DataPermission(entityType = "WORK_ORDER", checkOwnership = true, resourceIdParam =
 * "id") @DataPermission(entityType = "EMPLOYEE", dataScope = "DEPARTMENT", requireDepartmentMatch =
 * true)
 *
 * @author KLord
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataPermission {

  /** Isolation type: department, self, all, custom 隔离类型：部门、个人、全部、自定义（兼容旧字段，新代码用 dataScope） */
  String isolationType() default "department";

  /**
   * Data scope — 数据范围（阶段 2 新增） - ALL: 全部数据（仅超管/高管层） - DEPARTMENT: 本部门数据 - DEPARTMENT_AND_SUB:
   * 本部门及子部门数据 - SELF: 仅本人数据 - CUSTOM: 自定义（由 Service 层实现）
   */
  String dataScope() default "DEPARTMENT";

  /** Required permission code for this operation 执行此操作所需的权限编码 */
  String requirePermission() default "";

  /**
   * Whether to check ownership (user can only access their own data) 是否检查所有权（用户只能访问自己的数据）—
   * 防止水平越权（IDOR）
   */
  boolean checkOwnership() default false;

  /** Parameter name that contains the resource ID to check ownership against 包含资源ID的参数名，用于检查所有权 */
  String resourceIdParam() default "id";

  /**
   * Entity type for ownership checking 用于所有权检查的实体类型： - WORK_ORDER: 工单（校验 applicant_id 或当前审批人） -
   * EMPLOYEE: 员工（校验 department_id） - APPROVAL_LOG: 审批日志（校验对应工单的所有权） - USER: 用户（仅本人或超管）
   */
  String entityType() default "";

  /** Whether to require department match (阶段 2 新增) 是否要求部门匹配 — 非 HR/超管用户只能访问本部门数据 */
  boolean requireDepartmentMatch() default false;

  /** Allow super admin to bypass all checks 是否允许超级管理员绕过所有检查 */
  boolean allowSuperAdminBypass() default true;
}
