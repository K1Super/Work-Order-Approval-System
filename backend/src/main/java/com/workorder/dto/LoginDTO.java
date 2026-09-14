package com.workorder.dto;

import java.io.Serializable;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * Login Request DTO
 *
 * @author KLord
 */
public class LoginDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 用户名白名单：仅允许字母、数字、下划线、连字符、点、@，长度1-50。 用于拦截包含单引号/分号/空格/注释符等 SQL 元字符的注入 payload。 兼容用户名（如
   * KLord）与工号（6位数字）及邮箱登录。
   */
  @NotBlank(message = "用户名不能为空")
  @Pattern(regexp = "^[A-Za-z0-9_.@\\-]{1,50}$", message = "用户名格式不正确")
  private String username;

  /** Password */
  @NotBlank(message = "密码不能为空")
  private String password;

  // Getter & Setter
  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
