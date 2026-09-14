package com.workorder.util;


import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 日志脱敏转换器 — 规范条款 2（零明文敏感数据）+ 规范条款 6（敏感数据脱敏）
 *
 * <p>自动屏蔽日志中的敏感信息，支持两种格式：
 *
 * <ol>
 *   <li>{@code key=value} / {@code key: value} 格式 — 保留前缀，替换值为 ***
 *   <li>JSON 格式 {@code "key":"value"} — 整对替换为 {@code "key":"******"}
 * </ol>
 *
 * <p>覆盖字段：password、token、Authorization Bearer、secret、newPassword、oldPassword、
 * apiKey、accessKey、phone、idcard、bankcard、email、cookie、sessionId、privateKey、signingKey
 *
 * <p>用法：logback-spring.xml 中
 *
 * <pre>
 * &lt;conversionRule conversionWord="msg"
 *     converterClass="com.workorder.util.SensitiveDataConverter"/&gt;
 * </pre>
 *
 * @author KLord
 */
public class SensitiveDataConverter extends ClassicConverter {

  // ==================== key=value / key: value 格式 ====================
  // 保留前缀（$1），替换值为 ***

  private static final Pattern[] KV_PATTERNS = {
    // 原有 8 种
    Pattern.compile("(?i)(password\\s*[=:]\\s*)\\S+"),
    Pattern.compile("(?i)(token\\s*[=:]\\s*)\\S+"),
    Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)\\S+"),
    Pattern.compile("(?i)(secret\\s*[=:]\\s*)\\S+"),
    Pattern.compile("(?i)(newPassword\\s*[=:]\\s*)\\S+"),
    Pattern.compile("(?i)(oldPassword\\s*[=:]\\s*)\\S+"),
    Pattern.compile("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+"),
    Pattern.compile("(?i)(access[_-]?key\\s*[=:]\\s*)\\S+"),
    // 新增 8 种（规范条款 6 完整覆盖）
    Pattern.compile("(?i)(phone\\s*[=:]\\s*)\\d{7,}"), // 手机号（7位以上数字）
    Pattern.compile("(?i)(idcard\\s*[=:]\\s*)\\d{15,18}"), // 身份证号
    Pattern.compile("(?i)(bankcard\\s*[=:]\\s*)\\d{13,19}"), // 银行卡号
    Pattern.compile("(?i)(email\\s*[=:]\\s*)[^\\s,;}]+"), // 邮箱
    Pattern.compile("(?i)(cookie\\s*[=:]\\s*)\\S+"), // Cookie
    Pattern.compile("(?i)(sessionId\\s*[=:]\\s*)\\S+"), // SessionId
    Pattern.compile("(?i)(private[_-]?[Kk]ey\\s*[=:]\\s*)\\S+"), // 私钥
    Pattern.compile("(?i)(signing[_-]?[Kk]ey\\s*[=:]\\s*)\\S+"), // 签名密钥
  };

  private static final String KV_REPLACEMENT = "$1***";

  // ==================== JSON 格式 "key":"value" ====================
  // 整对替换为 "key":"******"

  private static final Pattern[] JSON_PATTERNS = {
    Pattern.compile("(?i)\"password\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"token\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"secret\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"phone\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"email\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"idcard\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"bankcard\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"sessionId\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"cookie\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"privateKey\"\\s*:\\s*\"[^\"]+\""),
    Pattern.compile("(?i)\"signingKey\"\\s*:\\s*\"[^\"]+\""),
  };

  /** JSON 格式的键名列表（用于构造替换字符串） */
  private static final String[] JSON_KEYS = {
      "password",
      "token",
      "secret",
      "phone",
      "email",
      "idcard",
      "bankcard",
      "sessionId",
      "cookie",
      "privateKey",
      "signingKey"
  };

  @Override
  public String convert(ILoggingEvent event) {
    String msg = event.getFormattedMessage();
    if (msg == null || msg.isEmpty()) {
      return msg;
    }

    // 第一轮：处理 key=value / key: value 格式（保留前缀 $1***）
    for (Pattern pattern : KV_PATTERNS) {
      msg = pattern.matcher(msg).replaceAll(KV_REPLACEMENT);
    }

    // 第二轮：处理 JSON 格式 "key":"value" → "key":"******"
    for (int i = 0; i < JSON_PATTERNS.length; i++) {
      String jsonReplacement = "\"" + JSON_KEYS[i] + "\":\"******\"";
      msg = JSON_PATTERNS[i].matcher(msg).replaceAll(jsonReplacement);
    }

    return msg;
  }
}
