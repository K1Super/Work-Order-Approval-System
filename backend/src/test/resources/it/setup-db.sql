-- ============================================================
-- 集成测试数据库重建脚本（sql-maven-plugin 在 pre-integration-test 阶段执行）
-- 幂等：断开存量连接 → DROP IF EXISTS → 重建空库。
-- 后续由 Flyway V1~V12 迁移建表，Flowable 自动建 ACT_* 表。
-- 注意：脚本以 autocommit 逐条执行，CREATE DATABASE 不能位于事务块内。
-- ============================================================
SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
 WHERE datname = 'work_order_it'
   AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS work_order_it;

CREATE DATABASE work_order_it ENCODING 'UTF8' TEMPLATE template0;