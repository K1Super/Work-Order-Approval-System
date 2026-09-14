package com.workorder.config;


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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

  /** 跳过净化的路径前缀（密码含特殊字符、文件为二进制） */
  private static final String[] EXCLUDED_PATH_PREFIXES = {
      "/api/auth/sessions", "/api/auth/users", "/api/files/"
  };

  @Override
  public void init(FilterConfig filterConfig) {
    logger.info("XSS Filter initialized");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    String uri = httpRequest.getRequestURI();

    // 跳过排除路径
    if (isExcluded(uri)) {
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
    chain.doFilter(wrapper, response);
  }

  private boolean isExcluded(String uri) {
    if (uri == null) return false;
    for (String prefix : EXCLUDED_PATH_PREFIXES) {
      if (uri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
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

    private byte[] cachedBody;

    /** 构造函数，缓存并净化请求体 */
    public XssRequestWrapper(HttpServletRequest request) {
      super(request);
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
          // 净化 JSON body（仅净化字符串值，保留 JSON 结构）
          String cleaned = XssCleanUtil.safeClean(sb.toString());
          // safeClean 用 Safelist.none() 会移除所有标签但保留文本，
          // 对 JSON 字符串可能误伤（如 "key":"<value>"）。改用宽松策略保留字符：
          cleaned = cleanJsonBody(sb.toString());
          cachedBody = cleaned.getBytes(StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        // 缓存失败时不阻塞请求，原样放行
      }
    }

    /** 净化 JSON body：仅对字符串值做 HTML 标签剥离，保留 JSON 结构 简化策略：剥离 < > 标签括号内容（XSS payload 主要通过标签注入） */
    private String cleanJsonBody(String body) {
      if (body == null || body.isEmpty()) {
        return body;
      }
      try {
        // 简单策略：用 Jsoup.relaxed 净化整个字符串，
        // Jsoup 会保留文本内容，剥离 <script> 等危险标签
        return XssCleanUtil.cleanRelaxed(body);
      } catch (Exception e) {
        return body;
      }
    }

    @Override
    public String getParameter(String name) {
      String value = super.getParameter(name);
      return XssCleanUtil.safeClean(value);
    }

    @Override
    public String[] getParameterValues(String name) {
      String[] values = super.getParameterValues(name);
      if (values == null) return null;
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
