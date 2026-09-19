# 部署指南

> 来源：PROJECT_DOCUMENTATION.md 第 10 章（10.2–10.5 生产部署部分），仅重编号。

## 1. 生产环境部署

### 1.1 环境变量

| 变量 | 说明 | 必填 |
|------|------|------|
| `SPRING_PROFILES_ACTIVE` | 激活 profile，生产环境必须为 `prod` | 是 |
| `DB_HOST` | 数据库主机 | 是 |
| `DB_PORT` | 数据库端口 | 否（默认 5432） |
| `DB_NAME` | 数据库名 | 否（默认 work_order_system） |
| `DB_USERNAME` | 数据库用户名 | 是 |
| `DB_PASSWORD` | 数据库密码 | 是 |
| `REDIS_HOST` | Redis 主机 | 是 |
| `REDIS_PORT` | Redis 端口 | 否（默认 6379） |
| `REDIS_PASSWORD` | Redis 密码 | 否 |
| `JWT_SECRET` | JWT 签名密钥（≥32 位） | 是 |
| `AES_ENCRYPTION_KEY` | AES 加密密钥（32 字节 Base64） | 是 |
| `MESSAGE_SIGNING_KEY` | HMAC 签名密钥（≥32 位） | 是 |
| `SERVER_PORT` | 服务端口 | 否（默认 8080） |
| `CORS_ORIGINS` | CORS 允许来源 | 是 |

### 1.2 生产配置要点

- `application-prod.yml` 中密钥**无默认值**，必须全部通过环境变量注入。
- 生产环境禁用 DevTools。
- Actuator 端点仅暴露 `health`、`info`、`metrics`、`prometheus`，并叠加 Nginx IP 白名单。
- 启用 HTTPS，配置 HSTS。
- 数据库使用独立账号，最小权限原则。

## 2. Nginx 反向代理示例

```nginx
server {
    listen 443 ssl http2;
    server_name workorder.company.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    # HSTS
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # 安全响应头
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;

    location / {
        root /var/www/work-order-frontend;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://localhost:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Actuator 仅允许内网 IP
    location /api/actuator/ {
        allow 10.0.0.0/8;
        deny all;
        proxy_pass http://localhost:8080/api/actuator/;
    }
}
```

## 3. 监控与日志

### 3.1 Actuator 端点

| 端点 | 用途 |
|------|------|
| `/api/actuator/health` | 健康检查 |
| `/api/actuator/info` | 应用信息 |
| `/api/actuator/metrics` | 指标数据 |
| `/api/actuator/prometheus` | Prometheus 格式指标 |

### 3.2 日志配置

- 开发环境：`logs/dev.log`，DEBUG 级别。
- 生产环境：结构化日志输出，支持按日期切割。
- 安全审计日志单独输出到 `logback-audit.xml` 配置的 appender。

## 4. 数据库备份

```bash
# 每日全量备份
pg_dump -h localhost -U postgres -d work_order_system > backup_$(date +%Y%m%d).sql

# 保留最近 30 天备份
find /backups -name "backup_*.sql" -mtime +30 -delete
```

## 5. 容器化部署（待办）

Dockerfile 与 docker-compose 尚未交付，历史文档及部署说明中出现的 docker 命令当前不可用；待交付后本节启用。