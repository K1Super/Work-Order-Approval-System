package com.workorder.common.exception;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 错误码注册表 — 企业级错误码集中管理（规范 §2 错误码规范）
 *
 * <p>职责：
 *
 * <ul>
 *   <li>按 bizCode 查找 ErrorCode 枚举值
 *   <li>按旧数值码 legacyCode 查找 ErrorCode 枚举值
 *   <li>渲染带占位符的错误消息
 *   <li>启动时自检：扫描所有枚举值确保无重复 bizCode，重复则拒绝启动
 * </ul>
 *
 * <p>CI 自动化校验：启动时自检失败会抛出 IllegalStateException 阻止应用启动， 确保错误码注册的完整性和唯一性。
 *
 * @author KLord
 */
@Component
public class ErrorCodeRegistry {

  private static final Logger logger = LoggerFactory.getLogger(ErrorCodeRegistry.class);

  /** bizCode → ErrorCode 映射 */
  private static final Map<String, ErrorCode> BIZ_CODE_MAP = new HashMap<>();

  /** legacyCode → ErrorCode 映射（向后兼容） */
  private static final Map<Integer, ErrorCode> LEGACY_CODE_MAP = new HashMap<>();

  /**
   * 启动时自检：扫描所有 ErrorCode 枚举值，确保无重复 bizCode。 重复 bizCode 会导致应用拒绝启动（CI 阶段即可发现）。
   *
   * <p>注意：legacyCode 为向后兼容的粗粒度旧码（HTTP 状态码 × 100 + 1），多个细分错误码共享同一个
   * legacyCode 是设计预期（如所有 404 类错误共享 40401）。因此仅校验 bizCode 唯一性， legacyCode 重复时仅记录
   * WARN 日志不阻断启动；映射表采用 first-wins 策略（首次注册的 ErrorCode 优先）。
   */
  @PostConstruct
  public void init() {
    BIZ_CODE_MAP.clear();
    LEGACY_CODE_MAP.clear();

    Set<String> duplicateBizCodes = new HashSet<>();
    Set<Integer> duplicateLegacyCodes = new HashSet<>();

    for (ErrorCode errorCode : ErrorCode.values()) {
      // 检测 bizCode 重复（必须唯一，重复则拒绝启动）
      String bizCode = errorCode.getBizCode();
      if (BIZ_CODE_MAP.containsKey(bizCode)) {
        duplicateBizCodes.add(bizCode);
      }
      BIZ_CODE_MAP.put(bizCode, errorCode);

      // 收集 legacyCode 重复（仅告警不阻断：粗粒度旧码按设计可被多个细分错误共享）
      Integer legacyCode = errorCode.getLegacyCode();
      if (legacyCode != null) {
        if (LEGACY_CODE_MAP.containsKey(legacyCode)) {
          duplicateLegacyCodes.add(legacyCode);
        } else {
          // first-wins：首个注册的 ErrorCode 优先，保证 lookupByLegacyCode 结果稳定可预测
          LEGACY_CODE_MAP.put(legacyCode, errorCode);
        }
      }
    }

    if (!duplicateBizCodes.isEmpty()) {
      throw new IllegalStateException(
          "ErrorCode 注册自检失败：存在重复的 bizCode: "
              + duplicateBizCodes
              + "。请检查 ErrorCode 枚举定义，确保每个 bizCode 唯一。");
    }

    if (!duplicateLegacyCodes.isEmpty()) {
      logger.warn(
          "[ErrorCodeRegistry] 检测到 {} 个 legacyCode 被多个细分错误码共享（符合粗粒度旧码设计，不阻断启动）: {}",
          duplicateLegacyCodes.size(),
          duplicateLegacyCodes);
    }

    logger.info(
        "[ErrorCodeRegistry] 错误码注册自检通过：共 {} 个错误码，{} 个旧码映射",
        BIZ_CODE_MAP.size(),
        LEGACY_CODE_MAP.size());
  }

  /**
   * 按 bizCode 查找 ErrorCode
   *
   * @param bizCode 三段式字符串错误码（如 "01-000-001"）
   * @return 对应的 ErrorCode 枚举值，未找到返回 null
   */
  public static ErrorCode lookup(String bizCode) {
    return BIZ_CODE_MAP.get(bizCode);
  }

  /**
   * 按旧数值码查找 ErrorCode（向后兼容）
   *
   * @param legacyCode 旧数值码（如 50001）
   * @return 对应的 ErrorCode 枚举值，未找到返回 null
   */
  public static ErrorCode lookupByLegacyCode(int legacyCode) {
    return LEGACY_CODE_MAP.get(legacyCode);
  }

  /**
   * 渲染带占位符的中文错误消息
   *
   * @param bizCode 三段式字符串错误码
   * @param args 占位符参数
   * @return 渲染后的中文消息；bizCode 不存在时返回 "未知错误"
   */
  public static String renderMessage(String bizCode, Object... args) {
    ErrorCode errorCode = lookup(bizCode);
    if (errorCode == null) {
      return "未知错误";
    }
    return errorCode.renderMessage(args);
  }

  /** 获取所有已注册的 bizCode 集合（用于 CI 校验等场景） */
  public static Set<String> getAllBizCodes() {
    return new HashSet<>(BIZ_CODE_MAP.keySet());
  }
}
