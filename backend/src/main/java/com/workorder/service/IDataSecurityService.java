package com.workorder.service;

import java.util.List;
import java.util.Map;

import com.workorder.entity.User;

/**
 * Data Security Isolation Service for Four-Tier Architecture
 *
 * <p>Security Levels: - Level 0 (Super Admin): Full access to all data - Level 1 (Decision):
 * Company-wide data with sensitive info - Level 2 (Management): Department + cross-dept overview
 * (desensitized) - Level 3 (Functional): Type-specific data across company - Level 4 (Employee):
 * Own data only - Level 5 (Dept Admin): Department data management
 */
public interface IDataSecurityService {

  /** Apply data isolation filter based on user's organizational level */
  Map<String, Object> applyDataFilter(Map<String, Object> params, User currentUser);

  /** Desensitize sensitive data based on user's permission level */
  <T> List<T> desensitizeData(List<T> data, User currentUser, String dataType);

  /** Check if user can access specific data record */
  boolean canAccessData(Long dataId, String dataType, User currentUser);

  /** Check if user can view sensitive fields (salary, ID number, etc.) */
  boolean canViewSensitiveData(User currentUser);

  /** Log data access for audit trail */
  void logDataAccess(
      User currentUser, String action, String dataType, Long dataId, boolean success);

  /** Get user's maximum accessible scope */
  String getAccessibleScope(User currentUser);
}
