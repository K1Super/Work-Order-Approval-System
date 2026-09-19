package com.workorder.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.config.MetricsConfig;
import com.workorder.security.DekContext;
import com.workorder.security.DekService;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

/**
 * AES 加密工具（AesEncryptionUtil）单元测试。
 *
 * <p>覆盖审计修复：P1-1 解密全失败 fail-close（prod 返回 null / 非 prod 迁移期容错）、P2-4 DEK 加密失败回退记告警指标。
 */
@ExtendWith(MockitoExtension.class)
class AesEncryptionUtilTest {

  /** 32 字节测试 legacy 密钥（Base64） */
  private static final String LEGACY_KEY_B64 = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";

  /** 32 字节测试 per-user DEK */
  private static final SecretKey USER_DEK =
      new SecretKeySpec("abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8), "AES");

  /**
   * 格式合法（base64(iv):base64(cipher)）但内容无效的密文 ——
   * 任何密钥做 GCM 解密都会 AEADBadTag 失败，用于构造「全密钥解密失败」场景。
   */
  private static final String UNDECRYPTABLE_CIPHER = "AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBB";

  @Mock private Environment environment;
  @Mock private MetricsConfig metricsConfig;
  @Mock private DekService dekService;

  private AesEncryptionUtil util;

  @BeforeEach
  void setUp() {
    util = new AesEncryptionUtil();
    inject("encryptionKeyConfig", LEGACY_KEY_B64);
    inject("environment", environment);
    inject("metricsConfig", metricsConfig);
    inject("dekService", dekService);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = AesEncryptionUtil.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(util, value);
    } catch (Exception e) {
      throw new RuntimeException("注入 " + fieldName + " 失败", e);
    }
  }

  private void stubProfile(String profile) {
    when(environment.getActiveProfiles()).thenReturn(new String[] {profile});
  }

  @AfterEach
  void tearDown() {
    DekContext.clear();
  }

  @Nested
  @DisplayName("P1-1：解密全失败 fail-close")
  class DecryptFailCloseTests {

    @Test
    @DisplayName("prod：所有密钥均无法解密时返回 null，密文不交还调用方，且记告警指标")
    void prod_returns_null_and_records_metric() {
      stubProfile("prod");
      util.init();

      String result = util.decrypt(UNDECRYPTABLE_CIPHER);

      assertThat(result).isNull();
      verify(metricsConfig).recordAesDecryptFailure();
    }

    @Test
    @DisplayName("非 prod：解密全失败保留迁移期容错（原样返回）并记告警指标")
    void non_prod_returns_original_value() {
      stubProfile("dev");
      util.init();

      String result = util.decrypt(UNDECRYPTABLE_CIPHER);

      assertThat(result).isEqualTo(UNDECRYPTABLE_CIPHER);
      verify(metricsConfig).recordAesDecryptFailure();
    }

    @Test
    @DisplayName("非加密格式的旧明文数据原样返回，不触发告警")
    void plaintext_value_returned_as_is() {
      stubProfile("prod");
      util.init();

      String result = util.decrypt("13800138000");

      assertThat(result).isEqualTo("13800138000");
      verify(metricsConfig, never()).recordAesDecryptFailure();
    }

    @Test
    @DisplayName("per-user DEK 可正确解密时不触碰 legacy 回退")
    void dek_decrypt_success() {
      stubProfile("prod");
      util.init();
      when(dekService.getUserDek(9L)).thenReturn(USER_DEK);
      DekContext.setUserId(9L);
      String cipher = util.encrypt("13800138000");

      assertThat(util.decrypt(cipher)).isEqualTo("13800138000");
      verify(metricsConfig, never()).recordAesDecryptFailure();
    }
  }

  @Nested
  @DisplayName("P2-4：DEK 加密失败回退与指标")
  class DekFallbackTests {

    @Test
    @DisplayName("DEK 服务异常时回退 legacy 密钥并记告警指标")
    void dek_failure_falls_back_and_records_metric() {
      stubProfile("dev");
      util.init();
      when(dekService.getUserDek(9L)).thenThrow(new RuntimeException("dek service down"));
      DekContext.setUserId(9L);

      String cipher = util.encrypt("some-data");

      verify(metricsConfig).recordDekFallback();
      assertThat(util.isEncryptedFormat(cipher)).isTrue();
      assertThat(util.decrypt(cipher)).isEqualTo("some-data");
    }

    @Test
    @DisplayName("DEK 加密成功时不记降级指标")
    void dek_success_records_no_fallback_metric() {
      stubProfile("dev");
      util.init();
      when(dekService.getUserDek(9L)).thenReturn(USER_DEK);
      DekContext.setUserId(9L);

      String cipher = util.encrypt("some-data");

      verify(metricsConfig, never()).recordDekFallback();
      assertThat(util.isEncryptedFormat(cipher)).isTrue();
      assertThat(util.decryptWithKey(cipher, USER_DEK)).isEqualTo("some-data");
    }
  }
}