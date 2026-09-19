package com.workorder.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.Cookie;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 审批流集成测试基类。
 *
 * <p>运行载体：maven-failsafe（it profile，`mvn verify -Pit`），数据库为真实 PostgreSQL
 * 测试库 work_order_it（src/test/resources/it/setup-db.sql 于 pre-integration-test 阶段重建，
 * Flyway V1~V12 + Flowable ACT_* 自动初始化），Redis 使用本机/CI 实例的 database 1。
 *
 * <p>封装「登录（HttpOnly WOS_TOKEN Cookie）→ 领取 CSRF（XSRF-TOKEN Cookie）→ 带头请求」的
 * 完整认证链路，与生产前端的真实交互方式一致（JWT 走 Cookie 回退链路、CSRF 双提交 Cookie、
 * 防重放 X-Request-Nonce/X-Request-Timestamp）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
public abstract class AbstractIT {

  protected static final String AUTH_COOKIE = "WOS_TOKEN";
  protected static final String XSRF_COOKIE = "XSRF-TOKEN";
  protected static final String XSRF_HEADER = "X-XSRF-TOKEN";
  protected static final String NONCE_HEADER = "X-Request-Nonce";
  protected static final String TIMESTAMP_HEADER = "X-Request-Timestamp";
  /** 路径前缀：MockMvc 不经容器上下文路径（context-path 由真实容器处理），故此处为空串 */
  protected static final String BASE = "";

  @Autowired protected MockMvc mockMvc;

  @Autowired protected ObjectMapper objectMapper;

  /** 单个登录会话：JWT（HttpOnly Cookie 值）+ CSRF Token */
  protected static final class TestSession {
    public final String token;
    public final String csrf;

    private TestSession(String token, String csrf) {
      this.token = token;
      this.csrf = csrf;
    }
  }

  /** 登录并领取 CSRF，返回可直接用于请求的会话（用户名/密码由种子数据提供） */
  protected TestSession session(String username, String password) throws Exception {
    String token = login(username, password);
    String csrf = fetchCsrf(token);
    return new TestSession(token, csrf);
  }

  /** 登录：POST /auth/sessions（CSRF 豁免路径），成功后在 Set-Cookie 中下发 HttpOnly JWT */
  protected String login(String username, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(BASE + "/auth/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    if (!isBizSuccess(body)) {
      throw new IllegalStateException(
          "登录失败: "
              + body.path("msg").asText()
              + " bizCode="
              + body.path("bizCode").asText()
              + " (username="
              + username
              + ")");
    }
    Cookie tokenCookie = result.getResponse().getCookie(AUTH_COOKIE);
    if (tokenCookie == null || tokenCookie.getValue().isEmpty()) {
      throw new IllegalStateException(
          "登录响应未下发 " + AUTH_COOKIE + " HttpOnly Cookie（W-47 契约被破坏）");
    }
    return tokenCookie.getValue();
  }

  /**
   * 领取 CSRF Token：任意经过安全链的 GET 都会由 CsrfFilter 通过 Set-Cookie 下发
   * XSRF-TOKEN（SessionLessCookieCsrfTokenRepository，无会话双提交方案）。
   */
  protected String fetchCsrf(String token) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get(BASE + "/auth/me").cookie(new Cookie(AUTH_COOKIE, token)))
            .andExpect(status().isOk())
            .andReturn();
    Cookie csrfCookie = result.getResponse().getCookie(XSRF_COOKIE);
    if (csrfCookie == null || csrfCookie.getValue().isEmpty()) {
      throw new IllegalStateException("GET 响应未下发 " + XSRF_COOKIE + " Cookie（CSRF 契约被破坏）");
    }
    return csrfCookie.getValue();
  }

  /** 带完整认证头的请求构建：JWT Cookie + CSRF 头 + 防重放头（按需） */
  protected MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder builder,
      TestSession session,
      boolean antiReplay) {
    builder
        .cookie(new Cookie(AUTH_COOKIE, session.token))
        .cookie(new Cookie(XSRF_COOKIE, session.csrf))
        .header(XSRF_HEADER, session.csrf)
        .contentType(MediaType.APPLICATION_JSON)
        .characterEncoding(StandardCharsets.UTF_8.name());
    if (antiReplay) {
      builder
          .header(NONCE_HEADER, newNonce())
          .header(TIMESTAMP_HEADER, String.valueOf(Instant.now().getEpochSecond()));
    }
    return builder;
  }

  /** 认证 + 防重放的写请求构建（审批/快捷审批等 @AntiReplay 端点） */
  protected MockHttpServletRequestBuilder authedWrite(
      MockHttpServletRequestBuilder builder, TestSession session) {
    return authed(builder, session, true);
  }

  /** 认证但无需防重放的读写请求构建（查询类） */
  protected MockHttpServletRequestBuilder authedRead(
      MockHttpServletRequestBuilder builder, TestSession session) {
    return authed(builder, session, false);
  }

  /** 生成一次性防重放 nonce */
  protected static String newNonce() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** 解析响应体中的 Result JSON */
  protected JsonNode resultJson(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * 判定 Result 业务成功（双码制：优先 bizCode == SUCCESS 的三段式码，
   * 无 bizCode 时回退 code == 20000，与 Result.isSuccess() 语义一致）。
   */
  protected static boolean isBizSuccess(JsonNode body) {
    String bizCode = body.path("bizCode").asText("");
    if (!bizCode.isEmpty()) {
      return "01-000-000".equals(bizCode);
    }
    return body.path("code").asInt(-1) == 20000;
  }

  /** 断言业务成功并返回内层 data 节点 */
  protected JsonNode expectSuccess(MvcResult result) throws Exception {
    JsonNode body = resultJson(result);
    if (!isBizSuccess(body)) {
      throw new AssertionError(
          "期望业务成功，实际: "
              + body.path("msg").asText()
              + " (bizCode="
              + body.path("bizCode").asText()
              + ", code="
              + body.path("code").asInt(-1)
              + ")");
    }
    return body.path("data");
  }
}