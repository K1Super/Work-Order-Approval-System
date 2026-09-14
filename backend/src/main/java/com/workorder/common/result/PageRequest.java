package com.workorder.common.result;


import java.io.Serializable;

import org.apache.ibatis.annotations.Param;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 分页请求参数（规范 §1 API 设计规范 — 过滤/排序/搜索标准化）
 *
 * <p>字段命名兼容策略（双码制过渡）：
 *
 * <ul>
 *   <li>序列化（Java→JSON）：使用规范字段名 page/page_size
 *   <li>反序列化（JSON→Java）：同时接受 page/pageNum 和 page_size/pageSize
 * </ul>
 *
 * <p>排序支持规范格式：?sort=-created_at,priority（-表示降序）
 *
 * @author KLord
 */
public class PageRequest implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 当前页码（从1开始，序列化为 page） */
  @JsonProperty("page")
  @JsonAlias("pageNum")
  private Integer pageNum = 1;

  /** 每页大小（序列化为 page_size） */
  @JsonProperty("page_size")
  @JsonAlias("pageSize")
  private Integer pageSize = 10;

  /** 排序字段（旧格式，向后兼容） */
  private String sortField;

  /** 排序方式：asc/desc（旧格式，向后兼容） */
  private String sortOrder = "desc";

  /** 规范排序参数（格式：-created_at,priority） - 前缀表示降序，无前缀表示升序 - 优先使用 sort 参数，若无则回退 sortField + sortOrder */
  private String sort;

  /** 搜索关键词（规范 §1：?q=关键词） */
  private String q;

  /** 构造函数，使用默认分页参数 */
  public PageRequest() {}

  /** 构造函数，指定页码和每页大小 */
  public PageRequest(Integer pageNum, Integer pageSize) {
    this.pageNum = pageNum;
    this.pageSize = pageSize;
  }

  /** 获取PostgreSQL的LIMIT偏移量 */
  public int getOffset() {
    return (pageNum - 1) * pageSize;
  }

  /**
   * 解析排序参数为 SQL ORDER BY 子句（安全白名单校验）
   *
   * @param allowedFields 允许排序的字段白名单
   * @return SQL ORDER BY 子句（不含 ORDER BY 关键字），无排序返回 null
   */
  public String buildOrderBy(java.util.Set<String> allowedFields) {
    if (allowedFields == null || allowedFields.isEmpty()) {
      return null;
    }

    // 优先使用规范 sort 参数
    if (sort != null && !sort.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      String[] parts = sort.split(",");
      for (String part : parts) {
        part = part.trim();
        if (part.isEmpty()) continue;

        boolean desc = part.startsWith("-");
        String field = desc ? part.substring(1) : part;

        // 白名单校验（防 SQL 注入，规范 §3：动态排序字段需白名单验证）
        if (!allowedFields.contains(field)) {
          continue;
        }

        if (sb.length() > 0) sb.append(", ");
        sb.append(field).append(desc ? " DESC" : " ASC");
      }
      return sb.length() > 0 ? sb.toString() : null;
    }

    // 回退旧格式 sortField + sortOrder
    if (sortField != null && !sortField.isEmpty() && allowedFields.contains(sortField)) {
      String direction = "desc".equalsIgnoreCase(sortOrder) ? " DESC" : " ASC";
      return sortField + direction;
    }

    return null;
  }

  // Getter & Setter
  public Integer getPageNum() {
    return pageNum;
  }

  public void setPageNum(Integer pageNum) {
    this.pageNum = pageNum;
  }

  public Integer getPageSize() {
    return pageSize;
  }

  public void setPageSize(Integer pageSize) {
    this.pageSize = pageSize;
  }

  public String getSortField() {
    return sortField;
  }

  public void setSortField(String sortField) {
    this.sortField = sortField;
  }

  public String getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(String sortOrder) {
    this.sortOrder = sortOrder;
  }

  public String getSort() {
    return sort;
  }

  public void setSort(String sort) {
    this.sort = sort;
  }

  public String getQ() {
    return q;
  }

  public void setQ(String q) {
    this.q = q;
  }
}
