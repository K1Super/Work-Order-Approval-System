package com.workorder.controller;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.result.Result;
import com.workorder.util.FileUploadValidator;

/**
 * 文件上传/下载控制器
 *
 * <p>阶段 3 修复 §3 — 文件上传安全
 *
 * <p>安全策略： 1. 上传：@PreAuthorize("isAuthenticated()") + FileUploadValidator（后缀白名单 + Magic Number） 2.
 * 重命名存储：UUID + 原后缀，防路径穿越与覆盖 3. 不直接暴露磁盘路径，下载通过 fileId 流式输出 4. 下载校验：仅认证用户可下载（生产可加所有权校验）
 *
 * @author KLord
 */
@RestController
@RequestMapping("/files")
public class FileController {

  private static final Logger logger = LoggerFactory.getLogger(FileController.class);

  @Value("${file.storage-path:./uploads}")
  private String storagePath;

  /**
   * 文件上传
   *
   * @param file 上传的文件
   * @return fileId, originalName, size
   */
  @PostMapping("/upload")
  @PreAuthorize("isAuthenticated()")
  public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
    // 1. 安全校验（后缀 + Magic Number + 大小）
    FileUploadValidator.validate(file);

    // 2. 重命名存储
    String originalName = file.getOriginalFilename();
    String safeName = FileUploadValidator.generateSafeName(originalName);

    // 3. 确保存储目录存在
    Path storageDir = Paths.get(storagePath).toAbsolutePath().normalize();
    try {
      Files.createDirectories(storageDir);
    } catch (IOException e) {
      logger.error("创建存储目录失败: {}", storageDir, e);
      throw new BusinessException("文件存储服务不可用");
    }

    // 4. 写入文件（避免路径穿越：仅使用 UUID 文件名）
    Path targetPath = storageDir.resolve(safeName).normalize();
    if (!targetPath.startsWith(storageDir)) {
      // 二次防御：确保目标路径仍在存储目录内
      throw new BusinessException("非法文件路径");
    }

    try {
      file.transferTo(targetPath.toFile());
      logger.info(
          "[文件上传成功] originalName={}, storedAs={}, size={}", originalName, safeName, file.getSize());
    } catch (IOException e) {
      logger.error("文件写入失败: {}", targetPath, e);
      throw new BusinessException("文件上传失败");
    }

    // 5. 返回结果（不暴露磁盘路径）
    Map<String, Object> data = new HashMap<>();
    data.put("fileId", safeName);
    data.put("originalName", originalName);
    data.put("size", file.getSize());
    data.put("url", "/api/files/download/" + safeName);

    return Result.success("文件上传成功", data);
  }

  /**
   * 文件下载
   *
   * @param fileId 文件 ID（UUID 文件名）
   * @return 文件流
   */
  @GetMapping("/download/{fileId}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Resource> download(@PathVariable String fileId) {
    // 防 path traversal：fileId 必须为 UUID 格式
    if (!isValidFileId(fileId)) {
      throw new BusinessException("非法文件 ID");
    }

    Path storageDir = Paths.get(storagePath).toAbsolutePath().normalize();
    Path filePath = storageDir.resolve(fileId).normalize();
    if (!filePath.startsWith(storageDir)) {
      throw new BusinessException("非法文件路径");
    }

    File file = filePath.toFile();
    if (!file.exists() || !file.isFile()) {
      throw new BusinessException("文件不存在");
    }

    Resource resource = new FileSystemResource(file);
    String contentType;
    try {
      contentType = Files.probeContentType(filePath);
    } catch (IOException e) {
      contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
    if (contentType == null) {
      contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    // 强制下载（不内联展示，防 XSS）
    String downloadName = UUID.randomUUID() + "_" + fileId;
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(resource);
  }

  /** 校验 fileId 是否为安全格式（UUID + 扩展名，无路径分隔符） */
  private boolean isValidFileId(String fileId) {
    if (fileId == null || fileId.isEmpty()) {
      return false;
    }
    // 仅允许字母数字 + 一个点 + 扩展名
    return fileId.matches("^[a-fA-F0-9]{32}\\.[a-zA-Z0-9]{1,10}$");
  }
}
