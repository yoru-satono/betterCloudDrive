# BetterCloudDrive 文档

这里是 BetterCloudDrive 的统一文档入口。文档按使用场景组织：先选择阅读目标，再进入架构、开发、参考或运维资料。接口和配置以 `reference` 为准；带日期的历史资料只用于追溯。

## 从这里开始

| 目标 | 推荐入口 |
|------|----------|
| 第一次启动项目 | [入门指南](getting-started/README.md) |
| 快速了解当前功能和技术栈 | [项目 README](../README.md) |
| 理解组件、数据和关键链路 | [架构文档](architecture/README.md) |
| 修改后端、Web、Tauri 或 Android | [开发指南](development/README.md) |
| 查询 HTTP 接口、配置或本地化协议 | [参考资料](reference/README.md) |
| 部署、备份、观察或排障 | [运维手册](operations/README.md) |
| 了解关键选择的原因 | [架构决策记录](decisions/README.md) |
| 查看尚未完成的工作 | [后续计划](ROADMAP.md) |

文档重构的核心阶段已经完成。当前正文按入门、架构、开发、参考、运维、决策和历史分类维护；配置参考继续作为环境变量与运行配置的单一权威文件。

## 文档地图

### 入门

[getting-started](getting-started/README.md) 负责首次运行、开发环境准备和常见启动问题，包括 [Quickstart](getting-started/QUICKSTART.md)、[开发环境准备](getting-started/DEVELOPMENT_SETUP.md)和[本地排障](getting-started/TROUBLESHOOTING.md)。

### 架构

[architecture](architecture/README.md) 负责系统总览、后端模块、数据模型、客户端关系、权限和核心业务链路：

- [系统架构总览](architecture/OVERVIEW.md)
- [后端架构](architecture/BACKEND.md)
- [数据模型](architecture/DATA_MODEL.md)
- [客户端架构](architecture/CLIENTS.md)
- [权限模型](architecture/PERMISSIONS.md)
- [上传下载、异步任务和 OnlyOffice 保存链路](architecture/README.md#关键链路)

### 开发

[development](development/README.md) 负责开发工作流、各端目录、修改路径、调试和最低验证，包含[统一工作流](development/WORKFLOW.md)、后端、Web、Tauri、Android 专项指南和[测试策略](development/TESTING.md)。

### 参考

- [API 总入口](reference/API.md)：通用约定、错误码以及按认证、文件、分享、团队、通知、OnlyOffice 和管理员划分的业务域索引。
- [配置参考](reference/CONFIGURATION.md)：Compose、环境变量、反向代理、Redis、RabbitMQ、安全和配置检查。
- [测试策略](development/TESTING.md)：测试分层、模块命令、最低验证、Testcontainers、专项验收和报告。
- [本地化协议](reference/LOCALIZATION.md)：后端语义 key 与 Web、Tauri、Android 客户端文案约定。

### 运维

- [生产部署](operations/PRODUCTION_DEPLOYMENT.md)：单机 Compose 安全部署、HTTPS 接入和上线验收。
- [升级与回滚](operations/UPGRADE_ROLLBACK.md)：维护窗口、Flyway 边界、发布验收和回滚决策。
- [密钥轮换](operations/SECRET_ROTATION.md)：各类密钥的消费者、失效影响和执行顺序。
- [故障 Runbook](operations/INCIDENT_RUNBOOK.md)：按服务、数据库、存储、队列、Office 和安全事件处置。
- [备份与恢复](operations/BACKUP_RESTORE.md)：PostgreSQL、SeaweedFS、密钥和 OnlyOffice 数据的备份恢复。
- [数据库性能](operations/DATABASE_PERFORMANCE.md)：核心查询、索引、EXPLAIN 和慢查询记录。
- [应用指标与排障](operations/OBSERVABILITY.md)：日志、指标、告警、团队配额、OnlyOffice 和队列。
- [OnlyOffice 部署与排障](operations/ONLYOFFICE_DEPLOYMENT.md)：地址、JWT、保存链路、健康检查和常见错误。

### 决策与历史

- [ADR 索引](decisions/README.md)：全部已接受决策、统一格式和取代规则。
- 后端与数据：[模块化单体](decisions/003-modular-monolith.md)、[元数据与对象内容分离](decisions/004-metadata-object-storage.md)、[RabbitMQ 投递](decisions/002-message-queue-selection.md)。
- 权限与客户端：[团队文件权限](decisions/005-team-file-permissions.md)、[三端边界](decisions/006-client-boundaries.md)、[Android 管理范围](decisions/001-android-admin-scope.md)。
- 协议与保存：[OnlyOffice 异步保存](decisions/007-onlyoffice-async-save.md)、[语义本地化](decisions/008-semantic-localization.md)。
- [历史归档](archive/README.md)：带日期且不代表当前实现的快照；当前包含 2026-07-13 团队模块阶段说明。

## 当前版本基线

以下版本与仓库中的构建文件和 `docker-compose.yml` 保持一致：

| 范围 | 版本 |
|------|------|
| 后端 | Spring Boot 4.1.0、JDK 25、MinIO SDK 9.0.3、JJWT 0.13.0 |
| Web | Node.js 24.18.0、pnpm 11.16.0、Vue 3.5.40、Vue Router 5.2.0、Pinia 4.0.2、Vite 8.1.5、TypeScript 6.0.3 |
| 桌面端 | Tauri 2.11.4、Rust 1.88.0 |
| Android | Android Gradle Plugin 9.3.1、Gradle 9.6.1、Kotlin 2.3.21、compileSdk/targetSdk 37、Compose BOM 2026.06.00 |
| 核心服务 | PostgreSQL 18.4、Redis 8.8.1、RabbitMQ 4.3.4、SeaweedFS 4.40、OnlyOffice 9.3.0.1 |
| 可观测性 | Grafana 13.1.1、Prometheus 3.13.1、Blackbox Exporter 0.28.0、Loki 3.6.13、Alloy 1.18.0、Alertmanager 0.33.1 |

TypeScript 暂时保留在 6.0.3，因为 TypeScript 7 不再提供当前 i18n 检查脚本使用的编译器 API；Zod 暂时保留在 3.25.76，以兼容当前 `@vee-validate/zod` 集成。

## 文档维护

- [文档维护指南](DOCUMENTATION_GUIDE.md)：分类职责、单一事实来源、写作规则、变更同步和迁移流程。
- [文档重构核查记录](DOCUMENTATION_RESTRUCTURE_CHECKLIST.md)：事实基线、各阶段修正和最终完成情况。

新增或移动文档前先确认它所属的唯一分类。新正文完成并核实后再替换旧入口，避免产生两份同时声称权威的说明。
