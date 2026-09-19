package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.result.Result;
import com.workorder.config.MetricsConfig;
import com.workorder.dao.UserMapper;
import com.workorder.dto.LoginDTO;
import com.workorder.dto.RoleInfoDTO;
import com.workorder.entity.User;
import com.workorder.security.CustomUserDetails;
import com.workorder.security.EncryptionHelper;
import com.workorder.security.JwtUtil;
import com.workorder.security.TokenVersionCache;
import com.workorder.service.IPasswordPolicyService;
import com.workorder.service.ISecurityAuditService;
import com.workorder.service.ISystemSettingService;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * AuthService 单元测试：登录成功/密码错误/禁用/锁定、logout tokenVersion 递增、refresh/register 主分支。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

  @Mock private AuthenticationManager authenticationManager;
  @Mock private UserMapper userMapper;
  @Mock private JwtUtil jwtUtil;
  @Mock private RedisTemplate<String, Object> redisTemplate;
  @Mock private ValueOperations<String, Object> valueOperations;
  @Mock private IPasswordPolicyService passwordPolicyService;
  @Mock private ISecurityAuditService securityAuditService;
  @Mock private ISystemSettingService systemSettingService;
  @Mock private BCryptPasswordEncoder passwordEncoder;
  @Mock private MetricsConfig metricsConfig;
  @Mock private TokenVersionCache tokenVersionCache;

  private AuthServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AuthServiceImpl();
    inject("authenticationManager", authenticationManager);
    inject("userMapper", userMapper);
    inject("jwtUtil", jwtUtil);
    inject("redisTemplate", redisTemplate);
    inject("passwordPolicyService", passwordPolicyService);
    inject("securityAuditService", securityAuditService);
    inject("systemSettingService", systemSettingService);
    inject("passwordEncoder", passwordEncoder);
    inject("metricsConfig", metricsConfig);
    inject("tokenVersionCache", tokenVersionCache);
    inject("encryptionHelper", new EncryptionHelper());
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  private void inject(String fieldName, Object value) {
    try {
      var field = AuthServiceImpl.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to inject " + fieldName, e);
    }
  }

  private static LoginDTO makeLogin(String username, String password) {
    LoginDTO dto = new LoginDTO();
    dto.setUsername(username);
    dto.setPassword(password);
    return dto;
  }

  private static User makeUser(Long id, String username, String realName) {
    User u = new User();
    u.setId(id);
    u.setUsername(username);
    u.setRealName(realName);
    u.setStatus(1);
    u.setTokenVersion(3L);
    u.setPasswordChanged(true);
    return u;
  }

  private static CustomUserDetails makeUserDetails(Long id, String username, String realName) {
    return new CustomUserDetails(id, username, "encoded", realName, 1, Collections.emptyList());
  }

  @Nested
  @DisplayName("login - 登录主流程与失败分支")
  class LoginTests {

    @Test
    @DisplayName("登录成功返回 token 与用户信息")
    void login_success() {
      CustomUserDetails userDetails = makeUserDetails(1L, "KLord", "张三");
      Authentication authentication = mock(Authentication.class);
      when(authentication.getPrincipal()).thenReturn(userDetails);
      when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
          .thenReturn(authentication);

      User user = makeUser(1L, "KLord", "张三");
      when(userMapper.selectById(1L)).thenReturn(user);
      when(passwordPolicyService.isPasswordExpired(user)).thenReturn(false);
      when(systemSettingService.getSettingValue("tokenExpiryHours", "24")).thenReturn("24");
      when(jwtUtil.generateToken(anyString(), anyLong(), anyLong())).thenReturn("jwt-token");
      when(jwtUtil.getExpirationDateFromToken("jwt-token"))
          .thenReturn(new Date(System.currentTimeMillis() + 3600000L));
      when(userMapper.selectRolesByUserId(1L))
          .thenReturn(List.of(new RoleInfoDTO(1L, "普通员工", "STAFF")));
      when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of("workorder:view"));

      Result<Map<String, Object>> r = service.login(makeLogin("KLord", "pwd"));

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).containsEntry("token", "jwt-token");
      assertThat(r.getData()).containsEntry("username", "KLord");
      assertThat(r.getData()).containsEntry("realName", "张三");
      verify(metricsConfig).recordLoginAttempt(true);
    }

    @Test
    @DisplayName("密码错误映射为友好提示并抛业务异常")
    void bad_credentials() {
      when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
          .thenThrow(new BadCredentialsException("Bad credentials"));
      when(passwordPolicyService.recordFailedLoginAttempt(anyString(), anyString()))
          .thenReturn(null);

      assertThatThrownBy(() -> service.login(makeLogin("KLord", "wrong")))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("用户名或密码错误");
      verify(metricsConfig).recordLoginAttempt(false);
    }

    @Test
    @DisplayName("禁用用户登录被拒绝")
    void disabled_account_rejected() {
      when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
          .thenThrow(new DisabledException("User is disabled"));
      when(passwordPolicyService.recordFailedLoginAttempt(anyString(), anyString()))
          .thenReturn(null);

      assertThatThrownBy(() -> service.login(makeLogin("KLord", "pwd")))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("账号已被禁用，请联系管理员");
      verify(metricsConfig).recordLoginAttempt(false);
    }

    @Test
    @DisplayName("连续失败触发锁定（限流）时按策略消息拒绝")
    void account_locked_rejects() {
      when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
          .thenThrow(new BadCredentialsException("Bad credentials"));
      PasswordPolicyServiceImpl.LoginAttemptResult locked =
          new PasswordPolicyServiceImpl.LoginAttemptResult(
              true, 30, "账号因连续 5 次登录失败已被锁定 30 分钟，请稍后重试或联系管理员");
      when(passwordPolicyService.recordFailedLoginAttempt(anyString(), anyString()))
          .thenReturn(locked);
      when(systemSettingService.getSettingValue("maxLoginAttempts", "5")).thenReturn("5");
      when(systemSettingService.getSettingValue("lockoutDurationMinutes", "30")).thenReturn("30");

      assertThatThrownBy(() -> service.login(makeLogin("KLord", "pwd")))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("已被锁定");
      verify(metricsConfig).recordLoginAttempt(false);
    }
  }

  @Nested
  @DisplayName("logout / refreshToken / register")
  class OtherAuthTests {

    @Test
    @DisplayName("logout 递增 tokenVersion 并使会话失效")
    void logout_bumps_token_version() {
      when(jwtUtil.getUsernameFromToken("token")).thenReturn("KLord");
      when(jwtUtil.getUserIdFromToken("token")).thenReturn(1L);
      when(userMapper.incrementTokenVersion(1L)).thenReturn(1);
      when(redisTemplate.delete("auth:token:1")).thenReturn(true);
      when(redisTemplate.delete("auth:permissions:1")).thenReturn(true);

      Result<?> r = service.logout("token");

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).isEqualTo("注销成功");
      verify(userMapper).incrementTokenVersion(1L);
      verify(tokenVersionCache).evict(1L);
    }

    @Test
    @DisplayName("refreshToken 成功生成新 token")
    void refresh_token_success() {
      when(jwtUtil.getUsernameFromToken("token")).thenReturn("KLord");
      when(jwtUtil.getUserIdFromToken("token")).thenReturn(1L);
      User current = makeUser(1L, "KLord", "张三");
      when(userMapper.selectById(1L)).thenReturn(current);
      when(jwtUtil.getTokenVersionFromToken("token")).thenReturn(3L);
      when(jwtUtil.generateToken(anyString(), anyLong(), anyLong())).thenReturn("new-token");
      when(systemSettingService.getSettingValue("tokenExpiryHours", "24")).thenReturn("24");
      when(jwtUtil.getExpirationDateFromToken("new-token"))
          .thenReturn(new Date(System.currentTimeMillis() + 3600000L));
      when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of());

      Result<Map<String, Object>> r = service.refreshToken("token");

      assertThat(r.isSuccess()).isTrue();
      assertThat(r.getData()).containsEntry("token", "new-token");
    }

    @Test
    @DisplayName("refreshToken 版本号不一致时拒绝刷新")
    void refresh_token_version_mismatch() {
      when(jwtUtil.getUsernameFromToken("token")).thenReturn("KLord");
      when(jwtUtil.getUserIdFromToken("token")).thenReturn(1L);
      User current = makeUser(1L, "KLord", "张三");
      when(userMapper.selectById(1L)).thenReturn(current);
      when(jwtUtil.getTokenVersionFromToken("token")).thenReturn(2L);

      Result<Map<String, Object>> r = service.refreshToken("token");

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).isEqualTo("Token 已失效，请重新登录");
    }

    @Test
    @DisplayName("无效 token 无法刷新")
    void refresh_token_invalid() {
      when(jwtUtil.getUsernameFromToken("token")).thenReturn(null);
      when(jwtUtil.getUserIdFromToken("token")).thenReturn(null);

      Result<Map<String, Object>> r = service.refreshToken("token");

      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("无效的Token");
    }

    @Test
    @DisplayName("自助注册未开放")
    void register_disabled() {
      Result<?> r = service.register(Map.of("username", "KLord"));
      assertThat(r.isSuccess()).isFalse();
      assertThat(r.getMsg()).contains("注册功能暂未开放");
    }
  }
}