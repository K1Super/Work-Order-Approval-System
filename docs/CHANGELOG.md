# 变更记录

按版本倒序记录工单审批流转系统的所有显著变更（Keep a Changelog 风格）。基线版本为工程化代码审计报告 v2.8.2。

## 2026-09-19 — 安全缺陷修复（P1/P2/P3 审计整改）

- **P1-1 AES 解密 fail-close**：`AesEncryptionUtil.decrypt` 全密钥解密失败时不再原样返回密文。prod 返回 null + error 日志 + `wos_aes_decrypt_failures_total` 告警指标（密文绝不交还调用方，杜绝被篡改数据当明文展示/写回）；非 prod 保留迁移期容错（原样返回 + warn）。
- **P1-2 安全响应头单一来源**：`EnterpriseSecurityFilter` 移除无条件 `Strict-Transport-Security` 与已废弃的 `X-XSS-Protection`，HSTS 由 `SecurityConfig` 单一来源控制（仅 prod 下发），修复 filter 先执行覆盖 SecurityConfig prod 判断的问题。
- **P2-3 限流边界文档化**：README 部署章节与 deployment-guide §2.1 明确「应用层 Caffeine 三级限流仅单实例有效，多实例部署必须由 Nginx `limit_req` 承担分布式限流」，并附登录 5r/m/IP、审批 30r/m/用户（按 JWT 键近似用户维度）的完整 nginx 配置示例。
- **P2-4 DEK 降级可观测**：`AesEncryptionUtil.encrypt` DEK 加密失败回退 legacy 共享密钥时记 `wos_dek_fallback_total` 告警指标（Prometheus 采集），补齐 per-user 隔离属性被静默降级的监控缺口。
- **P2-5 审计失败告警**：`WorkOrderEventListener.logAuditEvent` 异步落库失败时除 error 日志外记 `wos_audit_persistence_failures_total` 告警指标（按事件类型 tag），与 P4 对账任务形成「指标告警 + 最终一致兜底」。
- **P3-6 JSON body XSS 拦截**：新增共享攻击模式 `XssAttackPatterns`（`EnterpriseSecurityFilter` 参数级与 `XssFilter` 共用同一口径）；`XssFilter` 在净化前检测原始 JSON body，含 `<script>` 等攻击特征直接 400 拦截，纵深防御补齐 body 层。
- **P3-7 核实为非缺陷**：`FileUploadValidator.generateSafeName`（32 位无横线 hex + 扩展名）与 `FileController.isValidFileId` 正则 `^[a-fA-F0-9]{32}\.[a-zA-Z0-9]{1,10}$` 完全对齐，上传后可正常下载，无需修改。
- **P3-8 通知占位记录**：`notifyApplicant/notifyCurrentApprover` 仅日志占位，无真实邮件/IM 发送；README 已列扩展方向，待通知服务接入。
- **验证结果**：新增 AES fail-close/DEK 降级（6 例）、JSON body XSS 拦截（5 例）、安全响应头回归（2 例）单测；`mvn verify -Pit` 全量通过（checkstyle 硬门禁含测试源码）。

## 2026-09-19 — 员工服务组件化拆分（上帝类治理）

- **EmployeeServiceImpl 拆分为薄门面 + 5 组件**：`EmployeeServiceImpl`（原约 1760 行 → 约 150 行）仅实现 `IEmployeeService` 契约并纯委托；查询下沉至 `EmployeeQueryService`（分页/详情/导出/字典/上级），写操作下沉至 `EmployeeLifecycleOrchestrator`（创建/更新/删除/启用禁用/批量导入），密码下沉至 `EmployeePasswordService`（企业级重置安全规则 + 强密码生成），角色下沉至 `EmployeeRoleService`（全量替换分配 + orgLevel 自动计算）；权限判定收敛为 `EmployeeAccessGuard`（部门管理员/超管/最高角色/跨部门拦截）。
- **事务边界保持不变**：create/update/delete/toggle/batchImport/resetPassword/assignRoles 的 `@Transactional(rollbackFor = Exception.class)` 随方法迁移至组件；门面跨 bean 调用 join 组件事务，与拆分前代理语义一致。
- **逻辑原样保留**：唯一工号生成（W-35 唯一性）、DEK per-user 三段式分层加密（收敛为 `insertUserWithDek`，创建与批量共用）、禁用时 tokenVersion 递增 + 缓存失效联动（OPTIMIZATION 三.3.1）、逻辑删除与超管删除保护；跨部门拦截改用 `Objects.equals` 空值安全比较（原实现目标部门为 null 时会 NPE）。
- **测试重组与补强**：原门面单测按新边界迁移至 `EmployeeLifecycleOrchestratorTest`（11 例）与 `EmployeeQueryServiceTest`（10 例）；新增访问控制（16 例）、密码安全规则（9 例）、角色分配（4 例）组件单测；门面重写为纯委托校验（12 例）。
- **验证结果**：单测 343 + 集成测试 10（合计 353，原 301）全部通过；`mvn verify -Pit` BUILD SUCCESS（含 checkstyle 硬门禁，测试源码纳入检查）。

## 2026-09-19 — 工单服务组件化拆分（上帝类治理）

- **WorkOrderServiceImpl 拆分为薄门面 + 6 组件**：`WorkOrderServiceImpl`（原约 1700 行 → 约 200 行）仅保留 `IWorkOrderService` 契约实现与草稿创建、查询、更新、删除、净化/校验辅助逻辑；流程职责下沉至 `WorkOrderLifecycleOrchestrator`（提交/重提/撤回/终止/归档）、`WorkOrderApprovalOrchestrator`（审批编排）；公共能力收敛为 `WorkOrderStateMachine`（终态判定 + 乐观锁原语）、`WorkOrderAccessService`（跨部门/跨级权限、IDOR、候选人校验）、`WorkOrderDisplayAssembler`（非持久化显示字段）、`WorkOrderAuditService`（审计直写 + 领域事件发布），新增审批链配置 `ApprovalChainConfig`。
- **事务边界保持不变**：提交/重提/撤回/终止/审批仍由编排器 `@Transactional(rollbackFor = Exception.class)` 声明；`submitNewWorkOrder` 自调用 `submitWorkOrder` 保持同事务语义；审批的咨询锁（`pg_advisory_xact_lock`）+ 乐观锁（`version`）并发正确性边界不变。
- **测试重组与补强**：门面单测收敛为 updateWorkOrder 白名单/Mass Assignment/乐观锁防护（6 例）；新增状态机（9 例）、访问控制（17 例）、展示组装（7 例）组件单测，覆盖终态判定、跨部门/跨级审批拦截、IDOR 可见性、下一节点信息组装等。
- **验证结果**：单测 291 + 集成测试 10（合计 301，原 281）全部通过；`mvn verify -Pit` BUILD SUCCESS（含 checkstyle 硬门禁，测试源码纳入检查）。

## 2026-09-19 — 评审修复（第二轮）

- **P1-1 Swagger 收权与 prod 关闭**：`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` 从 `permitAll` 收权至 `hasRole("SUPER_ADMIN")`（仅超管可见）；`application-prod.yml` 中 `springdoc.api-docs.enabled` 与 `springdoc.swagger-ui.enabled` 均改为 `false`（生产默认不生成文档）。
- **P1-2 filter 路径匹配修复**：`JwtAuthenticationFilter.shouldNotFilter` 改用 `AntPathMatcher` 同时匹配无前缀与 `/api/v1` 前缀两种形式；排除清单收敛为 `/auth/sessions`、`/public/**`、`/static/**` 与静态资源后缀（`.html/.css/.js/.png/.ico`）；`/auth/users` 移出排除清单（保留其 `@PreAuthorize("hasRole('SUPER_ADMIN')")` 生效）。
- **P2-6 tokenVersion Redis 60s 缓存 + 4 处失效联动**：新增 `TokenVersionCache`（key `token:version:{userId}`、TTL 60 秒、Redis 异常降级直查 DB）；注销、改密、密码重置、禁用用户 4 处 token_version 递增成功后调用 `evict`；`JwtAuthenticationFilter` 改走缓存。
- **P2-7 IT 密码去硬编码**：it profile 默认密码置空（`<it.db.password></it.db.password>`），`application-it.yml` 的 `${IT_DB_PASSWORD:}` 不提供默认值；运行需 `-Dit.db.password=...` 或环境变量 `IT_DB_PASSWORD`，未配置时快速失败。
- **P3-8 HSTS 仅 prod、移除 X-XSS-Protection**：`Strict-Transport-Security` 仅 prod profile 下发（dev/it 不下发，避免污染本地 HTTP 调试）；删除已废弃的 `X-XSS-Protection` 响应头。
- **P3-9 说明（待办）**：actuator 仍仅 `SUPER_ADMIN` 可访问，建议部署层叠加内网白名单或独立端口。
- **P3-10 说明（待办）**：两个 BPMN 无并行网关/会签节点（并行能力为事件监听器候选人缓存，已有 `ParallelCandidateCacheIT` 覆盖）；"并行一票否决"用例需先引入并行网关后补。

## 2026-09-19 — 自动化测试与 CI/CD 补齐

### P0 自动化测试

- **Service 层单测**：新增 Service 层单元测试清单说明，覆盖核心业务逻辑。
- **审批流关键路径集成测试**：覆盖通过、驳回、重提交、并行、越权等核心场景，基于真实 PostgreSQL + Redis，运行命令 `mvn verify -Pit`。

### P1 CI/CD

- 新增 `.github/workflows/ci.yml`：后端 build → test（单测 + 集成）→ lint（checkstyle 硬门禁）→ package；前端 `npm ci` + `npm run build`。
- 安全扫描沿用独立工作流 `security-scan.yml`（OWASP + CodeQL + gitleaks + npm audit），与 CI 并行执行。

### 质量门禁变化

- checkstyle 设为硬门禁（`mvn validate` 阶段自动执行，`failsOnError=true`，含测试源码），并清零存量违规。

### 待办

- **Docker 交付物暂缓**：Dockerfile 与 docker-compose 尚未交付；历史文档 / 部署说明中出现的 docker 命令当前不可用。待交付后启用 `deployment-guide.md` 容器化章节。

## 2026-09-19 — 文档结构规范化

- 将 `docs/` 目录重构为 11 文件标准结构：README、srs、architecture、database-design、api-spec、getting-started、coding-standards、testing、deployment-guide、troubleshooting、CHANGELOG。
- 历史文档归档至 `legacy/`：工程化代码审计报告（`security-audit-report.md`）、生产级修复实施与验收规范（`fix-spec.md`）、优化提案（`OPTIMIZATION.md`）、原始总文档（`PROJECT_DOCUMENTATION.md`）与企业标准规范等。
- 图片与架构图资源统一归入 `assets/`。

## 生产级修复（W-01 ~ W-48）

基于 v2.8.2 审计发现，按里程碑推进并完成全部 48 项修复：

| 里程碑 | 范围 | 内容 |
|---|---|---|
| M1 P0 止血 | W-01 ~ W-07 | 审批端到端、离职、审计、重提交、越权、密钥扫描 |
| M2 P1 加固 | W-08 ~ W-19 | 限流、XSS、加密、权限、持久化、配置、CI |
| M3 P2 治理 | W-20 ~ W-48 | 安全、数据一致、前端体验分批上线 |

### P0 阻断级（W-01 ~ W-07）

- **W-01**：修复审批「驳回」不断流——BPMN 增加排他网关按 `approved` 路由到 `end_rejected`，驳回终态为 `REJECTED`。
- **W-02**：修复中间节点通过即置 `APPROVED`——引入 `DRAFT → PENDING → APPROVED/REJECTED/TERMINATED/ARCHIVED` 状态机，仅流程结束且 `approved=true` 才置 `APPROVED`。
- **W-03**：修复重新提交 `order_process_link` 唯一键冲突——改为部分唯一索引（`WHERE is_deleted=0`），`createLink` 失败抛业务异常。
- **W-04**：修复更新工单 Mass Assignment——新增白名单 `WorkOrderUpdateDTO`，状态迁移仅走 `updateStatusWithVersion`。
- **W-05**：修复审计表 `is_deleted` 失联导致审计丢失——以数据库 Schema 为事实源，审计 append-only，禁止吞异常。
- **W-06**：修复 V9 `CHECK` 与离职 `status=-2` 冲突——统一状态字典并将 `CHECK` 扩展至 `(-2,-1,0,1)`。
- **W-07**：清理明文凭证与弱口令工具，补充 `.gitleaks.toml` 并使 CI 密钥扫描真实阻断。

### P1 加固（W-08 ~ W-19）

- **安全**：W-08 统一 context-path 解析（修复登录限流/XSS 排除失效）、W-11 维护接口收权至 `SUPER_ADMIN` + dev 限定、W-16 限流/锁定键改用 `getRemoteAddr()`。
- **数据**：W-09 离职表手机号/邮箱 AES TypeHandler 落盘、W-10 删除加密列 LIKE 搜索、W-15 种子表补 `setval`。
- **工作流**：W-12 职能节点按 assignee/candidate 判定、W-13 link 在主事务内同步持久化、W-14 显式注册事件监听器并删除双实例。
- **工程**：W-17 `application-prod.yml` 入库、W-18 日志格式收敛由 logback 统一管理、W-19 CI 依赖扫描统一 `dependency-check-maven:9.0.9`。

### P2 治理（W-20 ~ W-48）

- **安全与错误处理**：统一异常脱敏（W-20）、上传缓冲限制（W-21）、SQL 注入拦截覆盖 JSON（W-22）、CSRF 策略（W-24）、数据权限校验（W-25）、防重放（W-30）、Redis Pub/Sub 死代码（W-31）、CSP 收敛（W-46）、登录 tempToken 走 HttpOnly Cookie（W-47）、前端 logger 统一与 CSRF（W-48）。
- **工作流与数据一致性**：AES/KEK 统一 prod 判断（W-23）、link 不吞异常（W-26）、节点跳过/分配失败告警补偿（W-27）、删除死代码（W-28）、fail-close（W-29）、归档委托（W-32）、离职 countTotal 过滤（W-33）、审计 Mapper 错位（W-34）、`employee_id` 唯一约束（W-35）、对账加锁（W-36）、schema-init 对齐（W-37）、时区统一（W-38）、分区预建（W-39）、关闭 `db.auto-create`（W-40）。
- **前端与部署**：`serve.cjs` 路径穿越（W-41）、创建工单附件真实上传（W-42）、401 跳登录（W-43）、`errorReporter.js` 注册（W-44）、登出回收动态路由（W-45）。

### 验证结果

- 后端 271 单测 + 10 集成测试（合计 281）全部通过。
- 前端构建成功。
- 新增 V10 / V11 / V12 三个 Flyway 迁移。
- git 历史已重写（清理凭证残留）。
- 待办：凭证轮换（数据库、Redis、JWT、KEK、第三方密钥、默认管理员密码）及相关安全问题收尾。

## v2.8.2 — 工程化审计基线

审计报告 v2.8.2 对系统做全量只读审查（工作流核心 / 安全 / 数据层 / 前端 / 构建部署 5 层），共发现：

- **P0 7 项**（阻断/高危）：审批状态机断链、越权改状态、审计表失联、离职 CHECK 冲突、启动器破坏性写库、序列未重置、明文凭证残留。
- **P1 12 项**：登录限流/XSS 排除失效、跨部门审批卡死、IDOR、CI 闸虚置、加密明文落盘、日志配置打架等。
- **P2 20+ 项**（另有 P3 10+ 项低危）：N+1、死代码、配置键名失效、分区停更、竞态、`serve.cjs` 路径穿越等。

核心症结在于文档宣称的架构能力（并行审批 + Redis 分布式锁 + 事件监听状态同步）远超代码实际落地（纯串行流程 + PG 咨询锁 + 从未注册的监听器）。详细证据见 `legacy/security-audit-report.md`。