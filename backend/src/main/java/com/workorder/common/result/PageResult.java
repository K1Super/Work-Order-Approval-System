package com.workorder.common.result;


import java.io.Serializable;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 分页响应结果（规范 §1 API 设计规范 — 分页响应统一格式）
 *
 * <p>字段命名兼容策略（双码制过渡）：
 *
 * <ul>
 *   <li>序列化（Java→JSON）：使用规范字段名 page/page_size
 *   <li>反序列化（JSON→Java）：同时接受 page/pageNum 和 page_size/pageSize
 * </ul>
 *
 * <p>规范分页格式：
 *
 * <pre>
 * {
 *   "list": [],
 *   "page": 1,
 *   "page_size": 20,
 *   "total": 100,
 *   "total_pages": 5
 * }
 * </pre>
 *
 * @param <T> 数据类型
 * @author KLord
 */
public class PageResult<T> implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 数据列表 */
  private List<T> list;

  /** 总记录数 */
  private long total;

  /** 当前页码（序列化为 page，反序列化同时接受 pageNum） */
  @JsonProperty("page")
  @JsonAlias("pageNum")
  private int pageNum;

  /** 每页大小（序列化为 page_size，反序列化同时接受 pageSize） */
  @JsonProperty("page_size")
  @JsonAlias("pageSize")
  private int pageSize;

  /** 总页数 */
  @JsonProperty("total_pages")
  private int totalPages;

  /** 构造函数，初始化空分页结果 */
  public PageResult() {}

  /** 构造函数，指定数据列表、总数、页码和每页大小 */
  public PageResult(List<T> list, long total, int pageNum, int pageSize) {
    this.list = list;
    this.total = total;
    this.pageNum = pageNum;
    this.pageSize = pageSize;
    this.totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
  }

  // Getter & Setter
  public List<T> getList() {
    return list;
  }

  public void setList(List<T> list) {
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public int getPageNum() {
    return pageNum;
  }

  public void setPageNum(int pageNum) {
    this.pageNum = pageNum;
  }

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
  }

  public int getTotalPages() {
    return totalPages;
  }

  public void setTotalPages(int totalPages) {
    this.totalPages = totalPages;
  }
}
