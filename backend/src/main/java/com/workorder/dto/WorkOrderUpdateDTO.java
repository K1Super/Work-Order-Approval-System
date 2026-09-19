package com.workorder.dto;

import java.io.Serializable;

/**
 * 工单编辑白名单 DTO（W-04 修复，fix-spec §3）
 *
 * <p>防止 Mass Assignment：仅暴露业务可编辑项，禁止携带 status/complete_time/department_id/priority
 * 等受控字段，杜绝草稿直接改「已通过」的越权路径。状态迁移只走 updateStatusWithVersion。
 *
 * @author KLord
 */
public class WorkOrderUpdateDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 工单标题 */
  private String title;

  /** 工单内容 */
  private String content;

  /** 备注 */
  private String remark;

  /** 附件URL */
  private String attachmentUrl;

  /** 申请部门（显示名，只读场景由申请人维度派生） */
  private String department;

  // Getter & Setter
  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getAttachmentUrl() {
    return attachmentUrl;
  }

  public void setAttachmentUrl(String attachmentUrl) {
    this.attachmentUrl = attachmentUrl;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }
}
