package com.workorder.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * XSS 过滤器（XssFilter）单元测试。
 *
 * <p>覆盖审计修复 P3-6：JSON body 拦截型检测（含 <script> 的 POST JSON body 被 400 拦截；
 * 洁净 body 放行；登录/注册排除路径与 form 请求行为不变）。
 */
class XssFilterTest {

  private XssFilter filter;

  @BeforeEach
  void setUp() {
    filter = new XssFilter();
  }

  private MockHttpServletRequest jsonRequest(String path, String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setContentType("application/json");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    request.setServletPath(path);
    return request;
  }

  private static AtomicBoolean chainFlag() {
    return new AtomicBoolean(false);
  }

  @Nested
  @DisplayName("P3-6：JSON body XSS 拦截")
  class JsonBodyXssDetectionTests {

    @Test
    @DisplayName("JSON body 含 <script> 时 400 拦截且不进入后续链")
    void json_body_with_script_blocked() throws Exception {
      MockHttpServletRequest request =
          jsonRequest("/workorders", "{\"title\":\"<script>alert(1)</script>\"}");
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicBoolean called = chainFlag();

      filter.doFilter(request, response, (req, res) -> called.set(true));

      assertThat(called).isFalse();
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString()).contains("非法脚本");
    }

    @Test
    @DisplayName("JSON body 含 javascript: 协议时 400 拦截")
    void json_body_with_javascript_scheme_blocked() throws Exception {
      MockHttpServletRequest request =
          jsonRequest("/workorders", "{\"title\":\"javascript:alert(1)\"}");
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicBoolean called = chainFlag();

      filter.doFilter(request, response, (req, res) -> called.set(true));

      assertThat(called).isFalse();
      assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("洁净 JSON body 放行进入后续链")
    void clean_json_body_passthrough() throws Exception {
      MockHttpServletRequest request =
          jsonRequest("/workorders", "{\"title\":\"正常工单标题\"}");
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicBoolean called = chainFlag();

      filter.doFilter(request, response, (req, res) -> called.set(true));

      assertThat(called).isTrue();
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("行为不变性：排除路径与 form 请求")
  class BehaviorPreservationTests {

    @Test
    @DisplayName("登录路径（排除路径）不触发 body 拦截（密码等字段不受影响）")
    void excluded_login_path_not_intercepted() throws Exception {
      MockHttpServletRequest request =
          jsonRequest("/auth/sessions", "{\"username\":\"<script>u</script>\"}");
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicBoolean called = chainFlag();

      filter.doFilter(request, response, (req, res) -> called.set(true));

      assertThat(called).isTrue();
      assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("form-urlencoded 请求不受 body 拦截影响")
    void form_request_passthrough() throws Exception {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/workorders");
      request.setContentType("application/x-www-form-urlencoded");
      request.setContent("title=normal".getBytes(StandardCharsets.UTF_8));
      request.setServletPath("/workorders");
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicBoolean called = chainFlag();

      filter.doFilter(request, response, (req, res) -> called.set(true));

      assertThat(called).isTrue();
    }
  }
}