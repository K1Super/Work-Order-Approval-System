# 常见问题排查

> 来源：PROJECT_DOCUMENTATION.md 第 12 章「典型问题与根因分析」，仅重编号。

## 1. 典型问题与根因分析

### 1.1 AES TypeHandler 全局注册导致登录失败

**现象**：登录时返回 "Bad credentials"，数据库中用户存在。

**根因**：`AesEncryptedStringTypeHandler` 同时标注 `@Component` 和 `@MappedTypes(String.class)`，被 MyBatis 全局注册到所有 String 参数。导致 `WHERE username = #{username}` 的查询参数被 AES 加密，而数据库存的是明文 "KLord"，无法匹配。

**修复**：移除 `@Component` 和 `@MappedTypes`，通过 `AesTypeHandlerInitializer` 静态注入 `AesEncryptionUtil`；仅在 Mapper XML 中显式指定 `typeHandler` 的字段生效。

### 1.2 AuthenticationManager 使用默认 DelegatingPasswordEncoder

**现象**：正确密码登录失败，提示 "Bad credentials"。

**根因**：`authConfig.getAuthenticationManager()` 返回的全局 AuthenticationManager 可能使用 Spring Security 默认的 `DelegatingPasswordEncoder`，要求哈希带 `{bcrypt}` 前缀，而数据库中哈希为 `$2a$10$...` 无前缀格式。

**修复**：显式用 `ProviderManager` + `DaoAuthenticationProvider` 构建全局 `AuthenticationManager` bean，强制使用 `BCryptPasswordEncoder`。

### 1.3 AES 加密配置不匹配导致手机号显示密文

**现象**：手机号显示为 `kSrpmoO1NbDEkwcm:MvI9KJ1mC2jsEwKy9cCGjg==`。

**根因**：`AesEncryptionUtil.java` 读取顶层属性 `${aes.encryption-key}`，而配置文件使用嵌套属性 `app.security.aes-encryption-key`，导致密钥为空，每次重启生成随机密钥。

**修复**：修正 `@Value` 注解读取嵌套属性，并在 dev 环境设置固定默认密钥。

### 1.4 硬编码成功码导致操作按钮失效

**现象**：编辑员工后无成功提示、对话框不关闭、列表不刷新。

**根因**：`handleSubmit` 中硬编码 `response = { code: 200 }`，而实际 `RESULT_CODE.SUCCESS = 20000`。

**修复**：重写 `handleSubmit` 使用真实后端响应。

### 1.5 员工删除后仍显示且可重复删除

**现象**：删除员工后列表仍显示，再次删除仍成功。

**根因**：`EmployeeServiceImpl.deleteEmployee` 错误使用 `updateById` 仅设置 `status=0`，而非调用 `deleteById` 设置 `is_deleted=1`。

**修复**：改为 `deleteById` 执行逻辑删除，使 `is_deleted=1` 生效。

### 1.6 FlowableConfig 循环依赖导致启动失败

**现象**：Spring Boot 启动报错循环依赖。

**根因**：`FlowableConfig` 作为 `EngineConfigurationConfigurer` 被引擎创建时引用，内部直接 `@Autowired ProcessEngine` 形成 `FlowableConfig → ProcessEngine → FlowableConfig` 循环。

**修复**：改用 `ObjectProvider<ProcessEngine>` 惰性注入，仅在 `@PreDestroy` 关闭时按需获取。

### 1.7 phone/email 字段长度不足导致新增/编辑失败

**现象**：新增或编辑员工时返回 500，提示 "值太长了"。

**根因**：AES-GCM 密文格式为 `base64(iv):base64(cipher+tag)`，11 位手机号密文约 53 字符，原 `phone VARCHAR(20)` 不足。

**修复**：通过 Flyway V5 将 `phone`、`email` 扩展至 `VARCHAR(255)`。