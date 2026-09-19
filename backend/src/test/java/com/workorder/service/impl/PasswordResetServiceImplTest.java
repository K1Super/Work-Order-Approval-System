package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.result.Result;
import com.workorder.dao.PasswordResetTokenMapper;
import com.workorder.dao.UserMapper;
import com.workorder.entity.PasswordResetToken;
import com.workorder.entity.User;
import com.workorder.security.TokenVersionCache;
import com.workorder.service.IPasswordPolicyService;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordResetService 单元测试：生成/校验/消费 token、过期、一次性使用。
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

  @Mock private PasswordResetTokenMapper tokenMapper;
  @Mock private UserMapper userMapper;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private IPasswordPolicyService passwordPolicyService;
  @Mock private TokenVersionCache tokenVersionCache;

  private PasswordResetServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new PasswordResetServiceImpl();
    inject("tokenMapper", tokenMapper);
    inject("userMapper", userMapper);
    inject("passwordEncoder", passwordEncoder);
    inject("passwordPolicyService", passwordPolicyService);
    inject("tokenVersionCache", tokenVersionCache);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = PasswordResetServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject " + fieldName, e);
    }
  }

  private static PasswordResetToken makeToken(Long id, Long userId, boolean used, Date expiry) {
    PasswordResetToken t = new PasswordResetToken();
    t.setId(id);
    t.setUserId(userId);
    t.setUsed(used);
    t.setExpiryTime(expiry);
    return t;
  }

  private static User makeUser(Long id, Integer status) {
    User u = new User();
    u.setId(id);
    u.setUsername("user" + id);
    u.setRealName("用户" + id);
    u.setStatus(status);
    return u;
  }

  @Nested
  @DisplayName("generateResetToken - 生成 token")
  class GenerateTests {

    @Test
    @DisplayName("目标用户不存在")
    void nonexistent_user() {
      when(userMapper.selectById(1L)).thenReturn(null);
      Result<Map<String, Object>> r = service.generateResetToken(1L, 9L);
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).isEqualTo("目标用户不存在");
    }

    @Test
    @DisplayName("成功生成 64 字符明文 token 并存哈希")
    void generate_success() {
      when(userMapper.selectById(1L)).thenReturn(makeUser(1L, 1));
      when(tokenMapper.insert(any(PasswordResetToken.class))).thenReturn(1);

      Result<Map<String, Object>> r = service.generateResetToken(1L, 9L);

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData().get("token").toString()).hasSize(64);
      assertThat(r.getData().get("expiryMinutes")).isEqualTo(15L);
      verify(tokenMapper).invalidateByUserId(1L);

      ArgumentCaptor<PasswordResetToken> captor =
          ArgumentCaptor.forClass(PasswordResetToken.class);
      verify(tokenMapper).insert(captor.capture());
      PasswordResetToken saved = captor.getValue();
      assertThat(saved.getUserId()).isEqualTo(1L);
      assertThat(saved.getTokenHash()).hasSize(64);
      assertThat(saved.getUsed()).isFalse();
    }
  }

  @Nested
  @DisplayName("validateResetToken - 校验 token")
  class ValidateTests {

    @Test
    @DisplayName("空 token 拒绝")
    void empty_token() {
      assertThat(service.validateResetToken("  ").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("令牌不存在")
    void token_not_found() {
      when(tokenMapper.selectByTokenHash(anyString())).thenReturn(null);
      Result<Map<String, Object>> r = service.validateResetToken("raw");
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("无效或不存在");
    }

    @Test
    @DisplayName("令牌已使用（一次性）")
    void token_already_used() {
      when(tokenMapper.selectByTokenHash(anyString()))
          .thenReturn(makeToken(10L, 1L, true, new Date(System.currentTimeMillis() + 100000L)));
      Result<Map<String, Object>> r = service.validateResetToken("raw");
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("已使用");
    }

    @Test
    @DisplayName("令牌已过期")
    void token_expired() {
      when(tokenMapper.selectByTokenHash(anyString()))
          .thenReturn(makeToken(10L, 1L, false, new Date(System.currentTimeMillis() - 1000L)));
      Result<Map<String, Object>> r = service.validateResetToken("raw");
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("已过期");
    }

    @Test
    @DisplayName("有效令牌返回用户名与过期时间")
    void token_valid() {
      Date expiry = new Date(System.currentTimeMillis() + 100000L);
      when(tokenMapper.selectByTokenHash(anyString())).thenReturn(makeToken(10L, 1L, false, expiry));
      when(userMapper.selectById(1L)).thenReturn(makeUser(1L, 1));

      Result<Map<String, Object>> r = service.validateResetToken("raw");

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).containsEntry("username", "user1");
      assertThat(r.getData()).containsEntry("expiryTime", expiry);
    }
  }

  @Nested
  @DisplayName("consumeResetToken - 消费 token")
  class ConsumeTests {

    @Test
    @DisplayName("空 token 或空新密码拒绝")
    void empty_params_rejected() {
      assertThat(service.consumeResetToken(null, "p").isSuccess()).isFalse();
      assertThat(service.consumeResetToken("raw", null).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("令牌已使用拒绝再次消费")
    void used_token_rejected() {
      when(tokenMapper.selectByTokenHash(anyString()))
          .thenReturn(makeToken(10L, 1L, true, new Date(System.currentTimeMillis() + 100000L)));
      Result<Void> r = service.consumeResetToken("raw", "NewPass1!");
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("已使用");
    }

    @Test
    @DisplayName("新密码不符合强度策略拒绝")
    void weak_password_rejected() {
      when(tokenMapper.selectByTokenHash(anyString()))
          .thenReturn(makeToken(10L, 1L, false, new Date(System.currentTimeMillis() + 100000L)));
      when(userMapper.selectById(1L)).thenReturn(makeUser(1L, 1));
      when(passwordPolicyService.validatePassword("weak"))
          .thenReturn(new IPasswordPolicyService.PasswordValidationResult(false, "密码必须包含至少一个数字"));

      Result<Void> r = service.consumeResetToken("raw", "weak");

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("密码必须包含至少一个数字");
    }

    @Test
    @DisplayName("成功消费：更新密码、递增 tokenVersion、标记已使用、记录历史")
    void consume_success() {
      when(tokenMapper.selectByTokenHash(anyString()))
          .thenReturn(makeToken(10L, 1L, false, new Date(System.currentTimeMillis() + 100000L)));
      User target = makeUser(1L, 1);
      when(userMapper.selectById(1L)).thenReturn(target);
      when(passwordPolicyService.validatePassword("NewPass1!"))
          .thenReturn(new IPasswordPolicyService.PasswordValidationResult(true, "ok"));
      when(passwordPolicyService.validatePasswordNotReused(1L, "NewPass1!"))
          .thenReturn(new IPasswordPolicyService.PasswordValidationResult(true, "ok"));
      when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded");
      when(userMapper.updatePassword(target)).thenReturn(1);
      when(userMapper.incrementTokenVersion(1L)).thenReturn(1);

      Result<Void> r = service.consumeResetToken("raw", "NewPass1!");

      assertThat(r.isSuccess()).isTrue();
      verify(userMapper).incrementTokenVersion(1L);
      verify(tokenVersionCache).evict(1L);
      verify(tokenMapper).markAsUsed(eq(10L), any(Date.class));
      verify(passwordPolicyService).recordPasswordChange(1L, "encoded");
    }
  }
}