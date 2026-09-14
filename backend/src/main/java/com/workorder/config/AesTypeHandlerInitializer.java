package com.workorder.config;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.workorder.security.DekService;
import com.workorder.util.AesEncryptionUtil;

/**
 * AesEncryptedStringTypeHandler 静态字段初始化器
 *
 * <p>设计目的： AesEncryptedStringTypeHandler 不能是 Spring Bean（@Component）， 否则
 * mybatis-spring-boot-starter 会把它全局注册到 SqlSessionFactory， 导致所有 String 类型参数被自动加密，引发登录失败等严重 Bug。
 *
 * <p>因此通过这个独立的 @Component 在容器启动时调用静态 setter 方法， 把 AesEncryptionUtil 和 DekService 注入到 TypeHandler
 * 的静态字段中。
 *
 * <h3>关键修复（手机号密文显示 Bug 根因）：</h3>
 *
 * 之前只注入了 AesEncryptionUtil，遗漏了 DekService， 导致 TypeHandler 的 decryptWithRowDek() 方法中
 * dekServiceInstance 始终为 null， 行级 DEK 解密逻辑永远不会执行，所有解密走 fallback（依赖 ThreadLocal DekContext + legacy
 * 密钥）， 批量查询多个用户时无法为每行切换 DekContext → 非 DEK 对应用户的 phone/email 无法解密 → 显示密文。
 *
 * @author KLord
 */
@Component
public class AesTypeHandlerInitializer {

  private static final Logger logger = LoggerFactory.getLogger(AesTypeHandlerInitializer.class);

  @Autowired private AesEncryptionUtil aesEncryptionUtil;

  @Autowired private DekService dekService;

  @PostConstruct
  public void init() {
    AesEncryptedStringTypeHandler.setAesUtil(aesEncryptionUtil);
    AesEncryptedStringTypeHandler.setDekService(dekService);
    logger.info("✅ AesTypeHandlerInitializer 完成：AesEncryptionUtil + DekService 均已注入 TypeHandler");
  }
}
