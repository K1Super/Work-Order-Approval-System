package com.workorder.util;


import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import com.workorder.common.exception.BusinessException;
import com.workorder.common.exception.ErrorCode;

/**
 * 文件上传安全校验器
 *
 * <p>阶段 3 修复 §3 — 文件上传安全
 *
 * <p>校验项： 1. 文件非空 + 大小限制（≤ 10MB） 2. 后缀白名单：pdf, doc, docx, xls, xlsx, png, jpg, jpeg, zip 3. Magic
 * Number 校验（前 8 字节比对真实文件类型，防伪造后缀） 4. 文件名安全重命名：UUID + 原后缀（避免路径穿越与覆盖）
 *
 * @author KLord
 */
public final class FileUploadValidator {

  private static final Logger logger = LoggerFactory.getLogger(FileUploadValidator.class);

  /** 字节掩码（用于 byte→int 无符号转换） */
  private static final int BYTE_MASK = 0xff;

  /** 文件大小上限：10MB */
  public static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

  /** 允许的扩展名白名单 */
  private static final Set<String> ALLOWED_EXTENSIONS =
      new HashSet<>(
          Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "png", "jpg", "jpeg", "zip"));

  /** Magic Number 签名表（前 8 字节十六进制小写前缀） key = 扩展名, value = 允许的 Magic Number 前缀列表 */
  private static final Map<String, String[]> MAGIC_NUMBERS = new HashMap<>();

  static {
    MAGIC_NUMBERS.put("pdf", new String[] {"25504446"}); // %PDF
    MAGIC_NUMBERS.put("doc", new String[] {"d0cf11e0"}); // OLE2 复合文档
    MAGIC_NUMBERS.put("docx", new String[] {"504b0304"}); // PK\x03\x04 (zip)
    MAGIC_NUMBERS.put("xls", new String[] {"d0cf11e0"});
    MAGIC_NUMBERS.put("xlsx", new String[] {"504b0304"});
    MAGIC_NUMBERS.put("png", new String[] {"89504e47"}); // \x89PNG
    MAGIC_NUMBERS.put("jpg", new String[] {"ffd8ff"});
    MAGIC_NUMBERS.put("jpeg", new String[] {"ffd8ff"});
    MAGIC_NUMBERS.put("zip", new String[] {"504b0304"});
  }

  private FileUploadValidator() {
    // 工具类禁止实例化
  }

  /**
   * 校验上传文件
   *
   * @param file 上传文件
   * @throws BusinessException 校验失败时抛出（含可读错误信息）
   */
  public static void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "上传文件不能为空");
    }

    // 1. 大小校验
    if (file.getSize() > MAX_FILE_SIZE) {
      throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
    }

    // 2. 后缀白名单校验
    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.isEmpty()) {
      throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "文件名不能为空");
    }

    // 防路径穿越：仅取文件名部分，剥离路径
    String safeName = originalName;
    int lastSlash = Math.max(safeName.lastIndexOf('/'), safeName.lastIndexOf('\\'));
    if (lastSlash >= 0) {
      safeName = safeName.substring(lastSlash + 1);
    }
    if (safeName.isEmpty() || safeName.startsWith(".")) {
      throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "非法文件名");
    }

    String ext = getExtension(safeName).toLowerCase();
    if (!ALLOWED_EXTENSIONS.contains(ext)) {
      logger.warn("[文件上传拦截] 非法后缀: {}, 原始名: {}", ext, originalName);
      throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    // 3. Magic Number 校验
    try (InputStream is = file.getInputStream()) {
      byte[] header = new byte[8];
      int read = is.read(header);
      if (read < 3) {
        throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "文件内容不完整");
      }

      String hexHeader = bytesToHex(header, read);

      String[] allowedMagics = MAGIC_NUMBERS.get(ext);
      if (allowedMagics == null || !matchesAnyMagic(hexHeader, allowedMagics)) {
        logger.warn(
            "[文件上传拦截] Magic Number 不匹配: ext={}, header={}, 原始名: {}", ext, hexHeader, originalName);
        throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
      }
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, e.getMessage());
    }
  }

  /**
   * 生成安全文件名：UUID + 原后缀
   *
   * @param originalName 原始文件名
   * @return UUID + 后缀
   */
  public static String generateSafeName(String originalName) {
    String ext = getExtension(originalName).toLowerCase();
    return UUID.randomUUID().toString().replace("-", "") + "." + ext;
  }

  /** 获取文件扩展名（不含点，小写） */
  private static String getExtension(String name) {
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return "";
    }
    return name.substring(dot + 1).toLowerCase();
  }

  /** 字节数组转十六进制字符串 */
  private static String bytesToHex(byte[] bytes, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      sb.append(String.format("%02x", bytes[i] & BYTE_MASK));
    }
    return sb.toString();
  }

  /** 检查 hex header 是否以任一允许的 Magic Number 开头 */
  private static boolean matchesAnyMagic(String hexHeader, String[] allowedMagics) {
    for (String magic : allowedMagics) {
      if (hexHeader.startsWith(magic)) {
        return true;
      }
    }
    return false;
  }
}
