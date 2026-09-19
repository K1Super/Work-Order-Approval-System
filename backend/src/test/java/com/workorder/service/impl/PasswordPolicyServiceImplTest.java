package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.workorder.dao.UserMapper;
import com.workorder.entity.PasswordHistory;
import com.workorder.entity.User;
import com.workorder.service.IPasswordPolicyService;
import com.workorder.service.ISystemSettingService;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordPolicyService 单元测试：密码强度矩阵、历史复用、过期、账号锁定策略。
 */
@ExtendWith(MockitoExtension.class)
class PasswordPolicyServiceImplTest {

  @Mock private ISystemSettingService systemSettingService;
  @Mock private UserMapper userMapper;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private RedisTemplate<String, Object> redisTemplate;
  @Mock private ValueOperations<String, Object> valueOperations;

  private PasswordPolicyServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new PasswordPolicyServiceImpl();
    inject("systemSettingService", systemSettingService);
    inject("userMapper", userMapper);
    inject("passwordEncoder", passwordEncoder);
    inject("redisTemplate", redisTemplate);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = PasswordPolicyServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject " + fieldName, e);
    }
  }

  private static Map<String, Object> allSettingsWithExpiryDays(int days) {
    Map<String, Object> security = new HashMap<>();
    security.put("passwordExpiryDays", days);
    Map<String, Object> all = new HashMap<>();
    all.put("security", security);
    return all;
  }

  @Nested
  @DisplayName("validatePassword - 密码强度矩阵")
  class ValidatePasswordTests {

    @Test
    @DisplayName("空密码拒绝")
    void null_or_blank_rejected() {
      assertThat(service.validatePassword(null).isValid()).isFalse();
      assertThat(service.validatePassword("  ").isValid()).isFalse();
    }

    @Test
    @DisplayName("长度不足拒绝")
    void too_short() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("Ab1!");
      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("长度不能少于");
    }

    @Test
    @DisplayName("缺少小写字母拒绝")
    void missing_lowercase() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("AB1!EFGH");
      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("小写字母");
    }

    @Test
    @DisplayName("缺少大写字母拒绝")
    void missing_uppercase() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("ab1!efgh");
      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("大写字母");
    }

    @Test
    @DisplayName("缺少数字拒绝")
    void missing_digit() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("Abc!efgh");
      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("数字");
    }

    @Test
    @DisplayName("缺少特殊字符拒绝")
    void missing_special() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("Abc1efgh");
      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("特殊字符");
    }

    @Test
    @DisplayName("弱密码（4 位连续重复）拒绝")
    void weak_repeated_password_rejected() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("Aaaa1!aa");
      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("过于简单");
    }

    @Test
    @DisplayName("满足所有复杂度时通过")
    void strong_password_passes() {
      IPasswordPolicyService.PasswordValidationResult r = service.validatePassword("Qw3#kL9p");
      assertThat(r.isValid()).isTrue();
    }
  }

  @Nested
  @DisplayName("validatePasswordNotReused - 历史复用")
  class HistoryReuseTests {

    @Test
    @DisplayName("历史无匹配时通过")
    void no_history_match() {
      when(userMapper.selectPasswordHistory(1L, 5)).thenReturn(Collections.emptyList());

      IPasswordPolicyService.PasswordValidationResult r =
          service.validatePasswordNotReused(1L, "NewPass1!");

      assertThat(r.isValid()).isTrue();
    }

    @Test
    @DisplayName("命中历史密码拒绝复用")
    void history_match_rejected() {
      PasswordHistory h = new PasswordHistory();
      h.setId(1L);
      h.setPasswordHash("oldhash");
      when(userMapper.selectPasswordHistory(1L, 5)).thenReturn(List.of(h));
      when(passwordEncoder.matches("NewPass1!", "oldhash")).thenReturn(true);

      IPasswordPolicyService.PasswordValidationResult r =
          service.validatePasswordNotReused(1L, "NewPass1!");

      assertThat(r.isValid()).isFalse();
      assertThat(r.getMessage()).contains("最近");
    }
  }

  @Nested
  @DisplayName("isPasswordExpired - 密码过期")
  class PasswordExpiryTests {

    @Test
    @DisplayName("从未改密不判定过期")
    void null_change_time_not_expired() {
      User u = new User();
      u.setId(1L);
      assertThat(service.isPasswordExpired(u)).isFalse();
    }

    @Test
    @DisplayName("超过 90 天判定过期")
    void expired() {
      User u = new User();
      u.setId(1L);
      u.setPasswordChangeTime(new Date(System.currentTimeMillis() - 91L * 24 * 3600 * 1000));
      when(systemSettingService.getAllSettings()).thenReturn(allSettingsWithExpiryDays(90));
      assertThat(service.isPasswordExpired(u)).isTrue();
    }

    @Test
    @DisplayName("90 天内未过期")
    void not_expired() {
      User u = new User();
      u.setId(1L);
      u.setPasswordChangeTime(new Date(System.currentTimeMillis() - 10L * 24 * 3600 * 1000));
      when(systemSettingService.getAllSettings()).thenReturn(allSettingsWithExpiryDays(90));
      assertThat(service.isPasswordExpired(u)).isFalse();
    }
  }

  @Nested
  @DisplayName("账号锁定与失败计数")
  class AccountLockoutTests {

    @Test
    @DisplayName("redis 有锁标记时判定已锁定")
    void is_locked() {
      when(redisTemplate.hasKey(anyString())).thenReturn(true);
      when(redisTemplate.getExpire(anyString(), org.mockito.ArgumentMatchers.any(TimeUnit.class)))
          .thenReturn(10L);
      assertThat(service.isAccountLocked("KLord", "1.2.3.4")).isTrue();
    }

    @Test
    @DisplayName("redis 无锁标记时未锁定")
    void not_locked() {
      when(redisTemplate.hasKey(anyString())).thenReturn(false);
      assertThat(service.isAccountLocked("KLord", "1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("连续失败达到阈值后锁定")
    void locks_after_max_attempts() {
      when(valueOperations.get(anyString())).thenReturn(4); // 第 5 次触发锁定
      PasswordPolicyServiceImpl.LoginAttemptResult r =
          service.recordFailedLoginAttempt("KLord", "1.2.3.4");
      assertThat(r.isLocked()).isTrue();
      assertThat(r.getMessage()).contains("已被锁定");
    }

    @Test
    @DisplayName("未达阈值的失败返回剩余次数")
    void remaining_attempts() {
      when(valueOperations.get(anyString())).thenReturn(2);
      PasswordPolicyServiceImpl.LoginAttemptResult r =
          service.recordFailedLoginAttempt("KLord", "1.2.3.4");
      assertThat(r.isLocked()).isFalse();
      assertThat(r.getRemainingAttemptsOrLockoutMinutes()).isEqualTo(2);
    }
  }
}