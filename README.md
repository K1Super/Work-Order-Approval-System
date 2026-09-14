# 工单审批流转系统

## 项目简介

企业级工单审批流转系统，基于 **SpringBoot + Flowable + Vue3** 技术栈开发，实现标准化的审批流程管理、细粒度的权限控制和完整的操作追溯功能。

## 核心特性

- ✅ **工作流引擎**：深度集成Flowable，支持串行/并行审批、动态流程定义；禁用脚本任务防止 RCE
- ✅ **RBAC权限**：基于SpringSecurity的三级权限模型（用户-角色-权限）+ 数据级权限切面（部门/所有权校验）
- ✅ **JWT认证**：无状态Token认证，支持Redis缓存和强制下线
- ✅ **完整追溯**：审批全流程日志记录 + ECharts流程可视化 + 操作审计日志
- ✅ **并行同步**：基于事件监听机制的并行审批状态同步方案；候选人双重校验防任务哄抢
- ✅ **前后端分离**：Vue3 + Element Plus现代化前端界面
- ✅ **安全加固**：SQL注入/XSS/CSRF防御、AES-256敏感字段加密、三级限流、IP+username锁定、HMAC消息签名、DOMPurify前端净化、CSP策略、Prometheus监控告警

## 快速开始

### 环境要求
- JDK 1.8+
- Node.js 16+
- PostgreSQL 12+
- Redis 6.0+
- Maven 3.6+

### 1. 初始化数据库

推荐使用 Flyway 自动迁移（生产环境首选）：
```bash
# Flyway 会在应用启动时自动执行 backend/src/main/resources/db/migration/ 下的迁移脚本
# 无需手动执行 SQL
```

或手动执行幂等初始化脚本（适用于数据库重建场景）：
```bash
psql -U postgres -d work_order_system -f backend/src/main/resources/sql/schema-init.sql
```

> ⚠️ `sql/init.sql` 已废弃，请勿在生产环境使用。

### 2. 启动后端
```bash
cd backend
mvn clean package -DskipTests
java -jar target/work-order-system-2.8.2.jar
```
后端服务地址: `http://localhost:8080/api`

### 3. 启动前端
```bash
cd frontend
npm install
npm run dev
```
前端访问地址: `http://localhost:3000`


## 项目结构

```
Work Order Approval System/
├── backend/                    # SpringBoot后端项目
│   ├── src/main/java/          # Java源代码
│   │   └── com/workorder/      # 主包
│   │       ├── config/         # 配置类（Security/Redis/Flowable）
│   │       ├── controller/     # REST API控制器
│   │       ├── service/        # 业务逻辑层
│   │       ├── dao/            # 数据访问层（MyBatis Mapper）
│   │       ├── entity/         # 实体类
│   │       ├── dto/            # 数据传输对象
│   │       ├── common/         # 公共工具类
│   │       ├── security/       # 安全模块（JWT/UserDetails）
│   │       └── listener/       # Flowable事件监听器
│   └── src/main/resources/     # 配置文件和资源
│       ├── application.yml     # 主配置
│       ├── mapper/             # MyBatis XML映射
│       └── processes/          # BPMN流程定义
├── frontend/                   # Vue3前端项目
│   ├── src/
│   │   ├── api/               # API接口封装
│   │   ├── views/             # 页面组件
│   │   ├── components/        # 公共组件
│   │   ├── store/             # Pinia状态管理
│   │   ├── router/            # 路由配置
│   │   └── utils/             # 工具函数
│   └── package.json           # 前端依赖配置
├── sql/                       # 数据库脚本（已废弃，仅保留历史参考）
│   └── init.sql              # ⚠️ 废弃，改用 schema-init.sql 或 Flyway
└── docs/                      # 项目文档
    ├── TECHNICAL_DOC.md      # 技术架构文档
    ├── API_DOC.md            # 接口文档
    ├── deployment-checklist.md  # 部署安全检查清单
    ├── nginx.conf            # Nginx HTTPS 反向代理配置
    ├── alertmanager-rules.yml  # Prometheus 告警规则
    ├── https-automation.sh   # Let's Encrypt 证书自动化
    ├── db-grants.sql         # 数据库最小权限账号
    └── redis-hardening.conf  # Redis 加固配置
```

## 核心模块说明

### 1. RBAC权限系统
- 用户(User) ↔ 角色(Role) ↔ 权限(Permission) 三级模型
- SpringSecurity + JWT Token 无状态认证
- Redis缓存用户权限，支持接口级和菜单级权限控制

### 2. Flowable工作流引擎
- 支持串行审批和并行审批两种模式
- BPMN流程定义可视化设计
- 全局事件监听机制处理状态同步

### 3. 并行审批解决方案（技术亮点）
**问题**：多分支并行审批时的状态一致性保证  
**方案**：
- 基于Flowable事件驱动机制实时监听节点变化
- Redis分布式锁防止并发修改冲突
- 乐观锁机制处理数据版本冲突
- Redis发布订阅实现状态变更通知

详见：`docs/TECHNICAL_DOC.md` 第五章

### 4. 日志与追溯
- 完整记录审批全流程日志（操作人、时间、意见、状态变更）
- ECharts流程可视化展示审批路径
- 时间线形式展示操作历史

## 主要API接口

| 模块 | 接口 | 说明 |
|------|------|------|
| 认证 | POST /auth/login | 用户登录 |
| 认证 | GET /auth/current-user | 获取当前用户 |
| 工单 | POST /workorder | 提交工单 |
| 工单 | GET /workorder/my | 我的工单列表 |
| 工单 | GET /workorder/pending | 待我审批列表 |
| 审批 | POST /approval | 审批操作（通过/驳回） |
| 审批 | GET /approval/log/{id} | 审批日志查询 |

完整接口文档见：`docs/API_DOC.md`

## 技术栈详情

**后端技术**:
- SpringBoot 2.7.18 (Web, Security, Data Redis)
- MyBatis 2.3.1 + PostgreSQL 42.6.0
- Flowable 6.8.0 (工作流引擎，禁用脚本任务)
- JWT (jjwt 0.11.5)
- Redis (Lettuce客户端)
- Jsoup 1.17.2 (XSS 净化)
- Caffeine 3.1.8 (三级令牌桶限流)
- Micrometer Prometheus (监控指标)

**前端技术**:
- Vue 3.3.4 (Composition API)
- Vue Router 4.2.4
- Pinia 2.1.7 (状态管理)
- Element Plus 2.3.14 (UI组件库)
- Axios 1.5.0 (HTTP客户端)
- Vite 4.4.9 (构建工具)
- ECharts 5.4.3 (图表库)
- DOMPurify 3.0.6 (HTML 净化，v-safe-html 指令)

## 部署建议

### 生产环境部署
1. **数据库**: PostgreSQL主从复制，定期备份；最小权限账号（见 `docs/db-grants.sql`）
2. **缓存**: Redis Cluster集群模式；启用密码与ACL（见 `docs/redis-hardening.conf`）
3. **应用**: 多实例部署 + Nginx负载均衡（HTTPS 反向代理见 `docs/nginx.conf`）
4. **监控**: Prometheus + Grafana + AlertManager（告警规则见 `docs/alertmanager-rules.yml`）
5. **日志**: ELK Stack日志收集分析（结构化日志见 `backend/src/main/resources/logback-spring.xml`）
6. **证书**: Let's Encrypt 自动化签发与续期（见 `docs/https-automation.sh`）

### 必备环境变量（生产环境）
```bash
DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD
REDIS_HOST / REDIS_PORT / REDIS_PASSWORD
JWT_SECRET
AES_ENCRYPTION_KEY          # 敏感字段加密密钥
MESSAGE_SIGNING_KEY         # Redis Pub/Sub 消息签名密钥
CORS_ORIGINS                # 允许的前端域名
SERVER_PORT
SPRING_PROFILES_ACTIVE=prod
```

完整部署安全检查清单见：`docs/deployment-checklist.md`

### Docker部署（可选）
```bash
# 构建镜像
docker build -t work-order-system:latest .

# 运行容器
docker run -d \
  --name work-order-api \
  -p 8080:8080 \
  -e DB_HOST=postgres \
  -e DB_PASSWORD=... \
  -e REDIS_HOST=redis \
  -e REDIS_PASSWORD=... \
  -e JWT_SECRET=... \
  -e AES_ENCRYPTION_KEY=... \
  -e MESSAGE_SIGNING_KEY=... \
  -e SPRING_PROFILES_ACTIVE=prod \
  work-order-system:latest
```

## 扩展方向

- [ ] 集成消息通知（邮件/钉钉/企微）
- [ ] 移动端适配（小程序/App）
- [ ] 流程在线设计器（Flowable Modeler）
- [ ] 数据报表与统计分析
- [ ] 多租户支持
- [ ] 国际化(i18n)

## 文档索引

- 📖 [技术架构文档](./docs/TECHNICAL_DOC.md) - 系统设计、数据库、核心功能实现
- 📚 [API接口文档](./docs/API_DOC.md) - 完整的RESTful API说明
- 💾 [数据库初始化脚本](./backend/src/main/resources/sql/schema-init.sql) - 幂等建表+初始化数据（PostgreSQL）
- 🔄 [Flyway迁移脚本](./backend/src/main/resources/db/migration/) - V1/V2/V3 增量迁移
- 🔒 [部署安全检查清单](./docs/deployment-checklist.md) - 生产环境 8 大节安全检查
- 🌐 [Nginx HTTPS配置](./docs/nginx.conf) - TLS 1.2/1.3 + 安全头 + 限流
- 🔑 [HTTPS证书自动化](./docs/https-automation.sh) - Let's Encrypt 签发与续期
- 🚨 [Prometheus告警规则](./docs/alertmanager-rules.yml) - 安全+系统两组告警
- 🛡️ [数据库权限配置](./docs/db-grants.sql) - 最小权限账号
- ⚡ [Redis加固配置](./docs/redis-hardening.conf) - 密码+ACL+危险命令禁用
- 🔍 [CI/CD安全扫描](./.github/workflows/security-scan.yml) - 依赖扫描+CodeQL+密钥扫描

## 开发团队

**开发者**: 资深全栈开发工程师  
**开发周期**: 2026-06-07  
**项目版本**: v2.8.2

## 许可证

本项目仅供学习和参考使用。

---

> **提示**: 如有问题请查看 `docs/TECHNICAL_DOC.md` 获取详细的技术实现说明。
