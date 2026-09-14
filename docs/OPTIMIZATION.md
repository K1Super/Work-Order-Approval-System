数据一致性、安全深度、可扩展性和可观测性方面仍有提升空间
**一、架构层面的核心缺陷与解耦方案**

当前架构最突出的问题是工单主表与流程引擎强耦合。`work_order` 表直接存放 `process_instance_id`、`current_node`、`current_assignee` 等运行时状态，这导致：
- 业务表与 Flowable 生命周期绑定，后续若替换引擎或升级版本，业务表不得不随之调整。
- 工单状态更新依赖流程引擎反向写入，容易出现数据不一致。
- 查询工单详情时，冗余的当前节点信息可能滞后，需要额外同步机制。

**优化方案**：
新建一张轻量级的关联表 `order_process_link`，仅包含 `work_order_id` 和 `process_instance_id` 两个关键字段。工单表只保留业务状态（如 DRAFT、PENDING、APPROVED），不再承载任何流程运行时数据。流程引擎推送状态变更时，通过领域事件通知工单上下文更新业务状态，而不是直接修改工单表的冗余列。前端需要展示当前审批人、节点名称时，通过流程实例 ID 实时调用 Flowable API 或从缓存获取。这样工单模块与流程引擎彻底解耦，两边可独立演进。

**二、数据一致性的深层保障**

尽管规范禁止数据库外键且要求应用层校验，但在并发、异常或人工订正脚本场景下，仍极易产生悬空引用。例如：用户被逻辑删除后，`work_order.applicant_id` 指向不存在的记录；或者关系表残留孤行。

**多层防护策略**：
- 在 Service 层的写操作中使用 Postgres 的 `pg_advisory_xact_lock` 或乐观锁 `version` 字段，防止并发误删。
- 建立定时对账任务（例如每 30 分钟一次），扫描 `work_order.applicant_id`、`approval_log.operator_id` 等引用字段，与 `sys_user` 比对，发现指向已逻辑删除用户的记录时，自动记录异常并尝试修复（如标记“申请人已离职”）。
- 利用 CDC 工具（如 Debezium）捕获 `sys_user` 的逻辑删除事件，触发关联数据处理，做到准实时补偿，而不仅依赖定时任务。

**三、安全体系的全面升级**

**JWT 无状态 → 混合状态管理**
当前 JWT 签发后直至过期始终有效，用户被禁用或密码重置后，旧 Token 仍可访问。改进方法：在 JWT 荷载中增加 `tokenVersion` 字段，与数据库用户表中的版本号对应。每次用户改密或被管理员禁用时，版本号递增。认证过滤器校验版本号，不匹配则直接拒绝。再配合将 Token 存储从 `localStorage` 迁移至 `HttpOnly; Secure; SameSite=Strict` 的 Cookie，彻底杜绝 XSS 窃取。

**前端 HMAC 签名 → 服务端 CSRF Token 或非对称挑战**
浏览器端使用共享对称密钥计算 HMAC，密钥很容易通过开发工具泄露，签名机制形同虚设。应立即废弃。采用 Spring Security 内置的 CSRF 防护，配合 Cookie 的 SameSite 属性即可有效防止跨站请求伪造。如果确有需求验证请求体完整性，可引入 WebCrypto API 生成不可导出的 ECDSA 密钥对：登录时前端生成密钥对，将公钥提交服务端注册；后续敏感请求用私钥签名，服务端用公钥验签。私钥永不出浏览器。

**AES 单密钥 → 分层加密与密钥轮换**
当前所有敏感字段共用同一 AES 密钥，一旦泄露所有历史数据都可解密，且无法实现密钥轮换。企业级做法是引入数据加密密钥（DEK）和密钥加密密钥（KEK）：
- 为每条敏感记录（或每个用户）生成独立的 DEK，用 DEK 加密手机号等字段。
- DEK 本身用 KEK 加密后存于数据库（如 `sys_user.encrypted_dek` 列）。
- KEK 来自外部密钥管理服务（Vault 或云 KMS），应用启动时获取并缓存在内存。
- 密钥轮换时只需重新加密 DEK，历史密文无需改动。
开发环境必须使用隔离密钥，生产环境硬断校验 KEK 来源。

**四、数据库性能与设计优化**

**日志表分区并移除逻辑删除**
`approval_log`、`sys_security_audit_log`、`work_order_comment` 等追加写日志表不应有 `is_deleted` 列，删除审计轨迹是严重合规风险。应移除该列，改为纯追加写。同时启用 Postgres 声明式分区（按月），使用 `pg_partman` 自动管理。历史数据清理直接 `DROP PARTITION`，避免逐行 DELETE 导致的膨胀和锁竞争。查询时务必带上 `create_time` 条件以利用分区裁剪。

**枚举字段字典化**
`work_order.status`、`approval_log.action` 等使用 VARCHAR 存储少量枚举值，既浪费空间又降低索引效率。全部改为 `SMALLINT`，配合 CHECK 约束和代码层枚举类。例如状态码：1-草稿，2-审批中，3-已通过，4-已驳回，5-已归档，6-已终止。这样索引体积更小，比较更快，且杜绝拼写错误。

**索引清理与补充**
删除被唯一约束覆盖的冗余索引，如 `sys_user` 上的 `idx_sys_user_username` 普通索引，以及被复合索引覆盖的 `idx_password_history_user_id`。为待办列表等高频查询创建条件索引：`CREATE INDEX idx_work_order_pending ON work_order(status, current_assignee) WHERE status = 2;`（假设 2 代表审批中）。

**统一时间类型**
将所有 `TIMESTAMP WITHOUT TIME ZONE` 改为 `TIMESTAMPTZ`（`TIMESTAMP WITH TIME ZONE`）。设置数据库 `timezone = 'UTC'`，后端实体使用 `Instant` 或 `OffsetDateTime`，序列化时输出 ISO-8601 格式带时区偏移。这样无论部署在哪个时区，时间都表示同一时刻，彻底规避歧义。