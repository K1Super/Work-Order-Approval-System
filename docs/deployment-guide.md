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

### 2.1 多实例与分布式限流（重要）

应用层 Caffeine 三级限流（登录 5/min/IP、审批 30/min/用户、其他 100/min）为 **JVM 本地令牌桶，仅在单实例部署下阈值准确**。
多实例 + Nginx 负载均衡时实际阈值 = 单实例阈值 × 实例数，登录防暴力破解随实例数线性稀释。
因此多实例部署必须由 Nginx `limit_req` 承担分布式限流；应用层本地桶继续保留，作为 Nginx 被绕过时的纵深防御。

```nginx
http {
    # 登录接口限流：5 r/min per IP（与应用层阈值一致）
    limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;

    # 审批接口限流：按 Authorization 头中的 token 键（近似用户维度，rate=30r/min）
    map $http_authorization $approval_limiter_key {
        default "anonymous";
        "~^Bearer\s+(.+)$" $1;
    }
    limit_req_zone $approval_limiter_key zone=approval:10m rate=30r/m;

    upstream workorder_backend {
        server 10.0.1.11:8080;
        server 10.0.1.12:8080;
        keepalive 32;
    }

    server {
        listen 443 ssl http2;
        server_name workorder.company.com;

        # 登录接口（context-path 为 /api/v1）
        location = /api/v1/auth/sessions {
            limit_req zone=login burst=5 nodelay;
            proxy_pass http://workorder_backend/api/v1/;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }

        # 审批接口
        location /api/v1/approvals/ {
            limit_req zone=approval burst=10 nodelay;
            proxy_pass http://workorder_backend/api/v1/;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }

        location /api/v1/ {
            proxy_pass http://workorder_backend/api/v1/;
        }
    }
}
```

**说明**：

- `limit_req_zone` 键必须是稳定且无空格的值；JWT 仅含 `[A-Za-z0-9._-]` 字符无空格，可直接作为审批维度键。
- token 过期续签后键会变化（近似用户维度）。若需精确用户维度限流，可将应用层限流迁移为 Redis 集中限流（`EnterpriseSecurityFilter` 已预留可替换位置，尚未交付）。
- 应用层 `work-order-system.rate-limit.*` 阈值与 Nginx `rate=` 需保持同值，避免两侧口径漂移；Nginx 侧建议略微宽松（`burst` 兜底内网正常波峰）。

## 3. 监控与日志

### 3.1 Actuator 端点

| 端点 | 用途 |
|------|------|
| `/api/actuator/health` | 健康检查 |
| `/api/actuator/info` | 应用信息 |
| `/api/actuator/metrics` | 指标数据 |
| `/api/actuator/prometheus` | Prometheus 格式指标 |

### 3.2 安全告警指标（建议接入 AlertManager 规则）

以下计数器在安全降级/失败时递增，建议配置告警（短时间内非零即告警）：

| 指标 | 含义 | 触发场景 |
|------|------|----------|
| `wos_dek_fallback_total` | DEK 加密失败回退 legacy 共享密钥 | 打破 per-user 密钥隔离假设（commission） |
| `wos_aes_decrypt_failures_total` | 密文无法被任何密钥解密 | 密钥错配或数据被篡改/损坏 |
| `wos_audit_persistence_failures_total` | 审计日志异步落库失败 | 审计轨迹丢失，需对账排查 |
| `wos_login_attempts_total{result="failure"}` | 登录失败 | 疑似暴力破解 |
| `wos_authorization_denials_total` | 授权拒绝 | 疑似越权探测 |

### 3.3 日志配置

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