# 项目文档

工单审批流转系统（后端 SpringBoot + Spring Security + Flowable + MyBatis + PostgreSQL + Redis，前端 Vue3 + Pinia + Element Plus + Axios + Vite）的文档索引与维护入口。

## 文档目录

| 文件 | 说明 |
|---|---|
| README.md | 本文档，文档索引与维护约定 |
| srs.md | 需求规格说明 |
| architecture.md | 总体设计 |
| database-design.md | 数据库设计 |
| api-spec.md | 接口规范 |
| getting-started.md | 快速开始 |
| coding-standards.md | 编码规范 |
| testing.md | 测试说明 |
| deployment-guide.md | 部署指南 |
| troubleshooting.md | 常见问题排查 |
| CHANGELOG.md | 变更记录 |

## 目录结构

- `assets/`：图片与架构图等资源文件。
- `legacy/`：历史文档归档，包括工程化代码审计报告（`security-audit-report.md`）、生产级修复实施与验收规范（`fix-spec.md`）、优化提案（`OPTIMIZATION.md`）、原始总文档（`PROJECT_DOCUMENTATION.md`）与企业标准规范（`Enterprise General SE Standards.md`、`Enterprise R&D Standard Specifications.md`、`Entry File Architecture Specification.md`）等。

## 维护约定

1. 架构、数据库结构或接口发生变更时，须同步更新 `architecture.md`、`database-design.md`、`api-spec.md` 等对应文档。
2. 所有显著变更须在 `CHANGELOG.md` 中记录（按版本倒序，Keep a Changelog 风格）。
3. 图片与架构图统一放入 `assets/`；历史/过期文档归档至 `legacy/`，不在根目录新增与标准结构重复的阐述性文档。