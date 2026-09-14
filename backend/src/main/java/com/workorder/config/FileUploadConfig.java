package com.workorder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/**
 * 文件上传配置
 *
 * <p>阶段 3 修复 §3 — 文件上传安全
 *
 * <p>限制： - 单文件最大 10MB（application.yml: spring.servlet.multipart.max-file-size） - 单次请求最大 50MB -
 * 实际校验由 {@link com.workorder.util.FileUploadValidator} 完成（后缀白名单 + Magic Number）
 *
 * @author KLord
 */
@Configuration
public class FileUploadConfig {

  @Bean
  public MultipartResolver multipartResolver() {
    StandardServletMultipartResolver resolver = new StandardServletMultipartResolver();
    // strict 模式：拒绝非法 multipart 请求
    resolver.setStrictServletCompliance(true);
    return resolver;
  }
}
