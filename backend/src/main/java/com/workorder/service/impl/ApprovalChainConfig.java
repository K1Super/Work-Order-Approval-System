package com.workorder.service.impl;


/**
 * 审批链配置。
 *
 * <p>描述一次工单提交经规则推导出的审批层级需求：是否需专项审核、是否需要高管层（总监）审批、 是否需要决策层（总经理/董事长）终审，以及用于日志/展示的审批链描述文本。
 *
 * @author KLord
 */
class ApprovalChainConfig {

  private final boolean needSpecialistReview;
  private final boolean needDirectorApproval;
  private final boolean needExecutiveApproval;
  private final String description;

  public ApprovalChainConfig(
      boolean needSpecialistReview,
      boolean needDirectorApproval,
      boolean needExecutiveApproval,
      String description) {
    this.needSpecialistReview = needSpecialistReview;
    this.needDirectorApproval = needDirectorApproval;
    this.needExecutiveApproval = needExecutiveApproval;
    this.description = description;
  }

  public boolean isNeedSpecialistReview() {
    return needSpecialistReview;
  }

  public boolean isNeedDirectorApproval() {
    return needDirectorApproval;
  }

  public boolean isNeedExecutiveApproval() {
    return needExecutiveApproval;
  }

  public String getDescription() {
    return description;
  }
}