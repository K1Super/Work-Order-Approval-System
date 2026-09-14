package com.workorder.dao;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.workorder.entity.SecurityAuditLog;

/**
 * Security Audit Log Mapper Interface 安全审计日志Mapper接口
 *
 * @author KLord
 */
@Mapper
public interface SecurityAuditLogMapper {

  /** Insert audit log record 插入审计日志记录 */
  int insert(SecurityAuditLog auditLog);

  /**
   * Query audit logs with pagination and filters 分页查询审计日志（支持多条件过滤）
   *
   * @param params Query parameters: - userId: User ID filter - actionType: Action type filter -
   *     status: Status filter (SUCCESS, FAILURE, WARNING) - startDate: Start date (yyyy-MM-dd
   *     HH:mm:ss) - endDate: End date (yyyy-MM-dd HH:mm:ss) - ipAddress: IP address filter -
   *     pageNum: Page number (1-based) - pageSize: Page size
   * @return Paginated result with total count and data list
   */
  Map<String, Object> queryWithPagination(Map<String, Object> params);

  /** Count total records matching filters 统计符合条件的记录总数 */
  int countByFilters(@Param("params") Map<String, Object> params);
}
