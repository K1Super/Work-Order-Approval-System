package com.workorder.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 企业安全过滤器（EnterpriseSecurityFilter）安全响应头单元测试。
 *
 * <p>覆盖审计修复 P1-2：本 filter 不再下发放 HSTS 与已废弃的 X-XSS-Protection（单一来源 SecurityConfig），
 * 其余安全响应头保持。
 */
class EnterpriseSecurityFilterTest {

  private EnterpriseSecurityFilter filter;

  @BeforeEach
  void setUp() {
    filter = new EnterpriseSecurityFilter();
    filter.init(null);
  }

  @Test
  @DisplayName("P1-2：不无条件下发 HSTS 与 X-XSS-Protection")
  void hsts_and_xss_protection_not_set_by_filter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/sessions");
    request.setServletPath("/auth/sessions");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {
      // no-op
    });

    assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    assertThat(response.getHeader("X-XSS-Protection")).isNull();
  }

  @Test
  @DisplayName("其余安全响应头保持下发")
  void other_security_headers_preserved() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/sessions");
    request.setServletPath("/auth/sessions");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {
      // no-op
    });

    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    assertThat(response.getHeader("Content-Security-Policy")).contains("script-src 'self'");
    assertThat(response.getHeader("Cache-Control")).isEqualTo(
        "no-store, no-cache, must-revalidate, proxy-revalidate");
  }
}