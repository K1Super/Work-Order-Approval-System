package com.workorder.config;


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workorder.common.result.Result;
import com.workorder.util.XssAttackPatterns;
import com.workorder.util.XssCleanUtil;

/**
 * XSS 净化过滤器
 *
 * <p>阶段 3 修复 §3 — XSS 防御
 *
 * <p>职责： 1. 对 application/json 与 application/x-www-form-urlencoded 请求体做 Jsoup 净化 2. 对所有 query/form
 * 参数做净化 3. 文件上传（multipart/form-data）与二进制流跳过 4. /api/auth/login、/api/auth/register、/api/file/**
 * 跳过（避免破坏密码字符与二进制）
 *
 * @author KLord
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class XssFilter implements Filter {

  private static final Logger logger = LoggerFactory.getLogger(XssFilter.class);
  private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_VIOLATION_LOGGER");

  /**
   * 跳过净化的路径前缀（密码含特殊字符、文件为二进制）。
   *
   * <p>W-08：路径统一基于 getServletPath()（不含 context-path，不依赖硬编码 /api/v1 前缀），
   * 前缀与实际 Controller 映射一致：/auth/sessions=登录、/auth/users=注册、/files/**=文件上传。
   */
  private static final String[] EXCLUDED_PATH_PREFIXES = {
      "/auth/sessions", "/auth/users", "/files/"
  };

  /** W-08：Spring context-path（如 /api/v1），用于 servletPath 为空时的路径兜底解析 */
  @Value("${server.servlet.context-path:}")
  private String contextPath;

  @Override
  public void init(FilterConfig filterConfig) {
    logger.info("XSS Filter initialized");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    String path = resolvePath(httpRequest);

    // 跳过排除路径（登录、注册、文件上传等）
    if (isExcluded(path)) {
      // 排除路径若为 JSON 请求（登录/注册），仍做「只缓存不净化」包装：
      // 下游 SqlInjectionInterceptor（W-22）会读取 JSON body 做注入扫描，
      // 必须保证请求流可重复读取，否则 @RequestBody 反序列化将拿到空流
      String excludedCt = httpRequest.getContentType();
      if (excludedCt != null && excludedCt.toLowerCase().contains("application/json")) {
        chain.doFilter(new XssRequestWrapper(httpRequest, false), response);
        return;
      }
      chain.doFilter(request, response);
      return;
    }

    // 非 http 请求直接放行
    if (!(request instanceof HttpServletRequest)) {
      chain.doFilter(request, response);
      return;
    }

    // multipart（文件上传）放行，由 FileUploadValidator 单独处理
    String contentType = httpRequest.getContentType();
    if (contentType != null && contentType.toLowerCase().contains("multipart/")) {
      chain.doFilter(request, response);
      return;
    }

    XssRequestWrapper wrapper = new XssRequestWrapper(httpRequest);

    // 审计修复 P3-6：JSON body 拦截型检测（纵深防御补层）。
    // 净化前检测原始缓存的 JSON body，与 EnterpriseSecurityFilter 参数级检测共用 XssAttackPatterns 同一口径；
    // 命中即 400 拦截，弥补此前 `checkXss` 只遍历 parameterMap、POST JSON body 的 <script> 无人拦截的缺口。
    if (containsXssAttack(wrapper.getRawBodyAsString(), httpRequest)) {
      securityLogger.warn(
          "[XSS拦截-JSONBody] IP: {}, URI: {}, 请求体长度: {}",
          httpRequest.getRemoteAddr(),
          httpRequest.getRequestURI(),
          wrapper.getRawBodyAsString().length());
      HttpServletResponse httpResponse = (HttpServletResponse) response;
      sendErrorResponse(httpResponse, HttpServletResponse.SC_BAD_REQUEST, "检测到非法脚本内容，请检查您的输入");
      return;
    }

    chain.doFilter(wrapper, response);
  }

  /** 检测 JSON 请求体是否命中 XSS 攻击模式（与参数级检测共用同一口径） */
  private boolean containsXssAttack(String body, HttpServletRequest request) {
    if (body == null || body.isEmpty()) {
      return false;
    }
    String contentType = request.getContentType();
    if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
      return false;
    }
    for (Pattern pattern : XssAttackPatterns.PATTERNS) {
      if (pattern.matcher(body).find()) {
        return true;
      }
    }
    return false;
  }

  /** 发送 JSON 错误响应（拦截场景） */
  private void sendErrorResponse(HttpServletResponse response, int statusCode, String message)
      throws IOException {
    response.setStatus(statusCode);
    response.setContentType("application/json;charset=UTF-8");
    ObjectMapper mapper = new ObjectMapper();
    Result<?> errorResult = Result.error(statusCode, message);
    response.getWriter().write(mapper.writeValueAsString(errorResult));
  }

  private boolean isExcluded(String path) {
    if (path == null) return false;
    for (String prefix : EXCLUDED_PATH_PREFIXES) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * W-08：统一路径解析。优先取 getServletPath()（不含 context-path，随部署自动适配）；
   * 若容器未提供 servletPath，则从 requestURI 剥离注入的 server.servlet.context-path 兜底。
   */
  private String resolvePath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath != null && !servletPath.isEmpty()) {
      return servletPath;
    }
    String uri = request.getRequestURI();
    if (uri == null) {
      return "";
    }
    if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  @Override
  public void destroy() {
    // 无资源释放
  }

  /**
   * XSS 净化请求包装器 - 重写 getParameter/getParameterValues/getParameterMap：净化表单与查询参数 - 重写
   * getInputStream：净化 JSON body（支持重复读取）
   */
  public static class XssRequestWrapper extends HttpServletRequestWrapper {

    /** 用于按字段净化 JSON body（ObjectMapper 线程安全，可复用） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private byte[] cachedBody;

    /** 原始请求体文本（净化前），审计修复 P3-6 供拦截型 XSS 检测使用 */
    private String rawBody;

    /** 是否对缓存 body 做 Jsoup 净化（排除路径包装时为 false，仅缓存保证可重复读取） */
    private final boolean cleanBody;

    /** 构造函数，缓存并净化请求体 */
    public XssRequestWrapper(HttpServletRequest request) {
      this(request, true);
    }

    /**
     * 构造函数。
     *
     * @param cleanBody true=缓存并净化 JSON body；false=仅缓存不净化（用于排除路径，保证下游可重复读取）
     */
    public XssRequestWrapper(HttpServletRequest request, boolean cleanBody) {
      super(request);
      this.cleanBody = cleanBody;
      // 缓存请求体（用于 JSON 净化与重复读取）
      cacheBody(request);
    }

    private void cacheBody(HttpServletRequest request) {
      try {
        String contentType = request.getContentType();
        if (contentType != null
            && (contentType.toLowerCase().contains("application/json")
                || contentType.toLowerCase().contains("application/x-www-form-urlencoded"))) {
          StringBuilder sb = new StringBuilder();
          try (BufferedReader reader = request.getReader()) {
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
              sb.append(buf, 0, len);
            }
          }
          // W-08：按字段净化 JSON body（仅清洗非密码字段的字符串值，保留 JSON 结构；
          // 密码字段如 password/oldPassword/newPassword/confirmPassword 不做 Jsoup 清洗，避免改写密码字符）
          // cleanBody=false 时（排除路径）仅缓存不净化
          rawBody = sb.toString();
          cachedBody =
              (cleanBody ? cleanJsonBody(rawBody) : rawBody)
                  .getBytes(StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        // 缓存失败时不阻塞请求，原样放行
      }
    }

    /** 返回原始请求体文本（净化前，供拦截型 XSS 检测使用，审计修复 P3-6） */
    public String getRawBodyAsString() {
      return rawBody;
    }

    /**
     * 净化 JSON body：解析为 JSON 树，仅对非密码字段的字符串值做 Jsoup 清洗，
     * 密码字段原样保留；解析失败时原样放行（不阻塞请求）。
     */
    private String cleanJsonBody(String body) {
      if (body == null || body.isEmpty()) {
        return body;
      }
      try {
        JsonNode root = OBJECT_MAPPER.readTree(body);
        if (root == null) {
          return body;
        }
        cleanNode(root);
        return OBJECT_MAPPER.writeValueAsString(root);
      } catch (Exception e) {
        return body;
      }
    }

    /** 递归清洗 JSON 节点中的字符串值（密码字段跳过） */
    private void cleanNode(JsonNode node) {
      if (node == null || !node.isContainerNode()) {
        return;
      }
      if (node.isObject()) {
        ObjectNode obj = (ObjectNode) node;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          JsonNode value = field.getValue();
          if (value != null && value.isTextual()) {
            if (!isPasswordField(field.getKey())) {
              obj.put(field.getKey(), XssCleanUtil.cleanRelaxed(value.asText()));
            }
          } else {
            cleanNode(value);
          }
        }
      } else if (node.isArray()) {
        for (JsonNode item : (ArrayNode) node) {
          cleanNode(item);
        }
      }
    }

    /** 判断字段名是否带密码语义（password/passwd，忽略大小写与 -/_ 分隔符） */
    private static boolean isPasswordField(String name) {
      if (name == null) {
        return false;
      }
      String normalized = name.toLowerCase().replace("-", "").replace("_", "");
      return normalized.contains("password") || normalized.contains("passwd");
    }

    @Override
    public String getParameter(String name) {
      String value = super.getParameter(name);
      // W-08：密码字段不做 Jsoup 清洗，避免改写密码字符
      if (isPasswordField(name)) {
        return value;
      }
      return XssCleanUtil.safeClean(value);
    }

    @Override
    public String[] getParameterValues(String name) {
      String[] values = super.getParameterValues(name);
      if (values == null) return null;
      // W-08：密码字段不做 Jsoup 清洗
      if (isPasswordField(name)) {
        return values;
      }
      String[] cleaned = new String[values.length];
      for (int i = 0; i < values.length; i++) {
        cleaned[i] = XssCleanUtil.safeClean(values[i]);
      }
      return cleaned;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      Map<String, String[]> original = super.getParameterMap();
      Map<String, String[]> result = new LinkedHashMap<>(original.size());
      for (Map.Entry<String, String[]> entry : original.entrySet()) {
        // W-08：密码字段不做 Jsoup 清洗
        if (isPasswordField(entry.getKey())) {
          result.put(entry.getKey(), entry.getValue());
          continue;
        }
        String[] values = entry.getValue();
        String[] cleaned = new String[values.length];
        for (int i = 0; i < values.length; i++) {
          cleaned[i] = XssCleanUtil.safeClean(values[i]);
        }
        result.put(entry.getKey(), cleaned);
      }
      return result;
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      // 请求头不做净化（保留 Authorization、Content-Type 等原值）
      return super.getHeaders(name);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (cachedBody == null) {
        return super.getInputStream();
      }
      final ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return bais.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          /* no-op */
        }

        @Override
        public int read() {
          return bais.read();
        }
      };
    }

    @Override
    public BufferedReader getReader() throws IOException {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
