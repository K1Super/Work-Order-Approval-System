# 快速开始

> 来源：PROJECT_DOCUMENTATION.md（第 2 章环境要求、第 10 章开发环境启动、第 13 章默认账号与数据库连接），仅重编号。

## 1. 环境要求

### 1.1 运行环境要求

| 环境 | 要求 |
|------|------|
| 操作系统 | Windows 10/11（项目目标平台）、Linux Server |
| JDK | OpenJDK 17+ |
| Node.js | 18 LTS+ |
| 数据库 | PostgreSQL 13+ |
| 缓存 | Redis 6+ |
| 浏览器 | Chrome 90+、Edge 90+、Firefox 88+ |

### 1.2 默认连接信息（开发环境）

| 组件 | 地址/账号 |
|------|-----------|
| 后端服务 | http://localhost:8080/api |
| 前端开发服务器 | http://localhost:5173 |
| 数据库 | work_order_system / postgres / 666666 / 5432 |
| 默认管理员 | KLord / 123456 |
| 管理员密码哈希 | `$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi` |

## 2. 开发环境启动

### 2.1 后端启动

```bash
# 1. 确保 PostgreSQL 和 Redis 已启动
# 2. 创建数据库 work_order_system
# 3. 执行 Maven 构建
cd backend
mvn clean install -DskipTests

# 4. 启动应用
mvn spring-boot:run
# 或使用 java -jar
java -jar target/work-order-system-*.jar
```

### 2.2 前端启动

```bash
cd frontend
npm install
npm run dev
```

## 3. 默认账号与数据库连接

### 3.1 默认账号信息

| 账号 | 密码 | 角色 |
|------|------|------|
| KLord | 123456 | SUPER_ADMIN |

首次登录后建议立即修改默认密码。

### 3.2 数据库连接示例

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5432/work_order_system?stringtype=unspecified
    username: postgres
    password: 666666
```