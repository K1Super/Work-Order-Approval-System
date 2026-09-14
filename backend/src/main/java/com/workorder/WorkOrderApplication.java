package com.workorder;


import org.flowable.engine.RepositoryService;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.StopWatch;

/**
 * 工单审批流转系统 — 启动入口（纯粹的容器装配工与生命周期守门员）
 *
 * <p>严格遵循 improve.md《入口文件终极设计规范与治理标准》第一部分： 1. 精准打击的包扫描边界 —— scanBasePackages 显式限定，禁止通配符 .* 扫描 2.
 * 零逻辑的纯粹触发者 —— 仅 main + 元注解，所有 @Bean 下沉至 config 包 3. 启动期"硬断"与"脱敏"双校验 —— EnvironmentAware
 * 最早期校验，密钥脱敏掩码 4. 强制显式初始化排序 —— FlowableConfig @AutoConfigureBefore + @DependsOn 5. 安全过滤器链前置抢占 ——
 * SecurityConfig @Order(HIGHEST_PRECEDENCE) 6. 全生命周期资源"闭环销毁" —— FlowableConfig @PreDestroy 关闭
 * AsyncExecutor 7. 环境差异注入，入口零硬编码 —— 仅读 spring.profiles.active 用于启动提示 8. 启动耗时基线埋点 —— StopWatch
 * 打印总耗时与 JVM 内存占用率
 *
 * @author KLord
 * @since 2026-06-07
 */
@SpringBootApplication(
    scanBasePackages = "com.workorder",
    // 显式排除 DevTools 自动配置，防止本地热部署 RestartScope 导致生产内存泄漏
    // 用 excludeName（字符串）而非 exclude（Class），避免无 devtools 依赖时编译报错
    excludeName = {"org.springframework.boot.devtools.autoconfigure.DevToolsAutoConfiguration"})
@MapperScan(basePackages = "com.workorder.dao")
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
public class WorkOrderApplication {

  private static final Logger logger = LoggerFactory.getLogger(WorkOrderApplication.class);

  /**
   * 纯粹触发者：仅 SpringApplication.run + StopWatch 启动耗时基线埋点。 严禁 if/else 判断、工具方法或配置 Bean（规范第一部分 §1 第 2
   * 条）。
   */
  public static void main(String[] args) {
    StopWatch stopWatch = new StopWatch("WorkOrder-Startup");
    stopWatch.start();
    SpringApplication.run(WorkOrderApplication.class, args);
    stopWatch.stop();
    logStartupBaseline(stopWatch.getTotalTimeMillis());
  }

  /** 启动基线埋点：总耗时 + JVM 内存占用率（规范 §1 第 8 条） */
  private static void logStartupBaseline(long totalMillis) {
    Runtime rt = Runtime.getRuntime();
    long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
    long maxMB = rt.maxMemory() / 1024 / 1024;
    double usage = maxMB > 0 ? (double) usedMB / maxMB * 100 : 0;
    logger.info("========================================================");
    logger.info("[启动基线] 工单审批流转系统启动完成 | 总耗时: {} ms", totalMillis);
    logger.info(
        "[启动基线] JVM 内存: {}MB / {}MB (使用率: {}%)", usedMB, maxMB, String.format("%.1f", usage));
    logger.info("========================================================");
  }

  /**
   * 启动期"硬断"与"脱敏"双校验 + BPMN 部署校验（规范 §1 第 3 条 + 红线"内部必要方法"）
   *
   * <p>- EnvironmentAware：容器初始化最早期（@PostConstruct 阶段前）校验必备环境变量， 校验失败抛 IllegalStateException
   * 终止启动；密钥日志做脱敏掩码（仅显后 4 位）。 - ApplicationRunner：容器启动后校验核心 BPMN 文件是否已部署，仅告警不阻断。
   */
  @org.springframework.stereotype.Component
  static class StartupCheckRunner implements ApplicationRunner, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(StartupCheckRunner.class);

    @Autowired(required = false)
    private RepositoryService repositoryService;

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
      this.environment = environment;
      validateCriticalConfigs();
    }

    /** 硬断校验：JWT 密钥 / AES 密钥 / 消息签名密钥 / 数据源连接，失败即终止启动 */
    private void validateCriticalConfigs() {
      String profile = environment.getProperty("spring.profiles.active", "dev");
      log.info("========================================================");
      log.info("[启动校验] 当前运行环境: {}", profile);
      log.info("========================================================");

      // 1. JWT 密钥（硬断：必须存在且 ≥32 位）
      String jwtSecret = environment.getProperty("jwt.secret", "");
      if (jwtSecret.isBlank() || jwtSecret.length() < 32) {
        throw new IllegalStateException(
            "[启动校验-硬断] jwt.secret 缺失或长度不足 32 位（当前 "
                + jwtSecret.length()
                + "），请设置环境变量 JWT_SECRET。系统拒绝启动。");
      }
      log.info("[启动校验] JWT_SECRET: ****{}", maskSuffix(jwtSecret));

      // 2. AES 加密密钥（生产硬断；dev 可空，将生成临时密钥）
      String aesKey = environment.getProperty("work-order-system.security.aes-encryption-key", "");
      if ("prod".equalsIgnoreCase(profile) && aesKey.isBlank()) {
        throw new IllegalStateException(
            "[启动校验-硬断] 生产环境必须配置 work-order-system.security.aes-encryption-key"
                + "（环境变量 AES_ENCRYPTION_KEY，32 字节 Base64）。系统拒绝启动。");
      }
      if (!aesKey.isBlank()) {
        log.info("[启动校验] AES_KEY: ****{}", maskSuffix(aesKey));
      } else {
        log.warn("[启动校验] AES_KEY 未配置（dev 环境将生成临时密钥，重启后旧密文无法解密）");
      }

      // 3. 数据源连接（硬断：URL 与用户名必须存在）
      // 注：HMAC 签名机制已废弃，改用 Spring Security CSRF Token，故移除 message-signing-key 校验
      String dbUrl = environment.getProperty("spring.datasource.url", "");
      String dbUser = environment.getProperty("spring.datasource.username", "");
      if (dbUrl.isBlank()) {
        throw new IllegalStateException("[启动校验-硬断] spring.datasource.url 未配置。系统拒绝启动。");
      }
      if (dbUser.isBlank()) {
        throw new IllegalStateException("[启动校验-硬断] spring.datasource.username 未配置。系统拒绝启动。");
      }
      log.info("[启动校验] DB_URL: {}", maskDbUrl(dbUrl));
      log.info("[启动校验] DB_USER: {} | DB_PASSWORD: ********", dbUser);

      log.info("[启动校验] 全部硬断校验通过 ✓");
    }

    /** ApplicationRunner：校验核心 BPMN 流程定义是否已部署，仅告警不阻断 */
    @Override
    public void run(ApplicationArguments args) {
      try {
        if (repositoryService == null) {
          log.warn("[BPMN 校验] Flowable RepositoryService 未就绪，跳过部署校验");
          return;
        }
        long count = repositoryService.createProcessDefinitionQuery().count();
        log.info("[BPMN 校验] 已部署流程定义数量: {}", count);
        if (count == 0) {
          log.warn("[BPMN 校验] ⚠️ 未发现已部署的流程定义！请部署 BPMN 流程文件，否则审批流转功能不可用。");
        }
      } catch (Exception e) {
        log.warn("[BPMN 校验] 校验异常（仅告警不阻断）: {}", e.getMessage());
      }
    }

    /** 脱敏掩码：仅显示后 4 位 */
    private String maskSuffix(String value) {
      return (value == null || value.length() <= 4) ? "****" : value.substring(value.length() - 4);
    }

    /** 数据库 URL 脱敏：隐藏 password 参数 */
    private String maskDbUrl(String url) {
      return url.replaceAll("password=[^&\\s]*", "password=****");
    }
  }
}
