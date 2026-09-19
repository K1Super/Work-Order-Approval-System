package com.workorder.util;


import java.util.regex.Pattern;

/**
 * XSS 攻击模式常量（拦截口径统一来源）。
 *
 * <p>审计修复 P3-6：拦截型 XSS 检测由 EnterpriseSecurityFilter（参数级）与 XssFilter（JSON body 级）共同承担，
 * 两组检测必须共用同一组模式，避免定义漂移导致拦截口径不一致。
 *
 * <p>仅检测真实 XSS 特征，不误拦合法 HTML（净化职责仍由 {@link XssCleanUtil} 承担）。
 *
 * @author KLord
 */
public final class XssAttackPatterns {

  private XssAttackPatterns() {
    // 工具类禁止实例化
  }

  /** XSS 攻击模式数组 */
  public static final Pattern[] PATTERNS = {
    Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
    Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
    Pattern.compile(
        "on(load|error|click|mouseover|focus|blur|submit)\\s*=", Pattern.CASE_INSENSITIVE),
    Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
    Pattern.compile("<object[^>]*>", Pattern.CASE_INSENSITIVE),
    Pattern.compile("<embed[^>]*>", Pattern.CASE_INSENSITIVE),
    Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
    Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE)
  };
}