package com.workorder.util;


import org.apache.ibatis.annotations.Param;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * XSS 净化工具类
 *
 * <p>阶段 3 修复 §3 — XSS 防御
 *
 * <p>三档净化策略： - clean：完全移除所有 HTML 标签（用于标题、备注等纯文本字段） - cleanHtml：保留简单文本标签
 * b/i/em/strong/br（用于审批意见等有限富文本） - cleanRelaxed：保留格式化标签 a/p/img/table 等（用于工单内容等富文本字段）
 *
 * @author KLord
 */
public final class XssCleanUtil {

  /** 完全无标签白名单（纯文本） */
  private static final Safelist NONE = Safelist.none();

  /** 简单文本白名单（b/i/em/strong/br） */
  private static final Safelist SIMPLE = Safelist.simpleText();

  /** 宽松白名单（a/b/i/em/strong/div/p/img/table/ul/ol/li 等） */
  private static final Safelist RELAXED = Safelist.relaxed();

  private XssCleanUtil() {
    // 工具类禁止实例化
  }

  /**
   * 完全净化：移除所有 HTML 标签（用于标题、备注、原因等纯文本字段）
   *
   * @param input 原始输入
   * @return 净化后的纯文本（HTML 实体编码后）
   */
  public static String clean(String input) {
    if (input == null) {
      return null;
    }
    return Jsoup.clean(input, NONE);
  }

  /**
   * 简单文本净化：仅保留 b/i/em/strong/br 标签（用于审批意见）
   *
   * @param input 原始输入
   * @return 净化后的简单 HTML
   */
  public static String cleanHtml(String input) {
    if (input == null) {
      return null;
    }
    return Jsoup.clean(input, SIMPLE);
  }

  /**
   * 宽松净化：保留格式化标签（用于工单内容等富文本字段）
   *
   * @param input 原始输入
   * @return 净化后的富文本 HTML
   */
  public static String cleanRelaxed(String input) {
    if (input == null) {
      return null;
    }
    return Jsoup.clean(input, RELAXED);
  }

  /**
   * 安全净化（null 安全 + 异常容错） 任何异常返回空字符串（fail-closed）
   *
   * @param input 原始输入
   * @return 净化后的字符串
   */
  public static String safeClean(String input) {
    if (input == null) {
      return null;
    }
    try {
      return Jsoup.clean(input, NONE);
    } catch (Exception e) {
      // fail-closed：异常时返回空字符串（不返回原值，避免注入）
      return "";
    }
  }
}
