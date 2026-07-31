# BetterCloudDrive 文档

本文档目录按用途分组。接口和配置以参考资料为准；历史归档只用于追溯，不代表当前实现。

## 后续计划

- [后续计划](ROADMAP.md)：当前尚未完成的安全、正确性、扩展性和客户端改进任务。

## 当前版本基线

以下版本与仓库中的构建文件和 `docker-compose.yml` 保持一致：

| 范围 | 版本 |
| --- | --- |
| 后端 | Spring Boot 4.1.0、JDK 25、MinIO SDK 9.0.3、JJWT 0.13.0 |
| Web | Node.js 24.18.0、pnpm 11.16.0、Vue 3.5.40、Vue Router 5.2.0、Pinia 4.0.2、Vite 8.1.5、TypeScript 6.0.3 |
| 桌面端 | Tauri 2.11.5、Rust 1.88.0 |
| Android | Android Gradle Plugin 9.3.1、Gradle 9.6.1、Kotlin 2.3.21、compileSdk/targetSdk 37、Compose BOM 2026.06.00 |
| 核心服务 | PostgreSQL 18.4、Redis 8.8.1、RabbitMQ 4.3.4、SeaweedFS 4.40、OnlyOffice 9.3.0.1 |
| 可观测性 | Grafana 13.1.1、Prometheus 3.13.1、Blackbox Exporter 0.28.0、Loki 3.6.13、Alloy 1.18.0、Alertmanager 0.33.1 |

TypeScript 暂时保留在 6.0.3，因为 TypeScript 7 不再提供当前 i18n 检查脚本使用的编译器 API；Zod 暂时保留在 3.25.76，以兼容当前 `@vee-validate/zod` 集成。

## 参考资料

- [API 文档](reference/API.md)：认证、文件、分享、团队、通知、OnlyOffice 和管理员接口。
- [配置与部署](reference/CONFIGURATION.md)：Docker Compose、环境变量、反向代理、Redis、RabbitMQ、安全和完整验证。
- [测试策略](reference/TESTING.md)：测试分层、模块命令、Testcontainers、安全验收和覆盖率报告。
- [本地化协议](reference/LOCALIZATION.md)：后端语义 key 与 Web、Tauri、Android 客户端文案约定。

## 运维手册

- [备份与恢复](operations/BACKUP_RESTORE.md)：PostgreSQL、SeaweedFS 和 OnlyOffice 数据备份、恢复与演练。
- [数据库性能](operations/DATABASE_PERFORMANCE.md)：核心查询、索引、EXPLAIN 和慢查询记录。
- [应用指标与排障](operations/OBSERVABILITY.md)：团队配额、OnlyOffice 保存和队列积压指标。
- [OnlyOffice 部署与排障](operations/ONLYOFFICE_DEPLOYMENT.md)：地址、JWT、保存链路、健康检查和常见错误。

## 架构决策

- [Android Admin 范围](decisions/ANDROID_ADMIN_SCOPE.md)：Android 管理功能的当前边界和未来最小入口。
- [消息队列选型](decisions/MESSAGE_QUEUE_SELECTION.md)：RabbitMQ 拓扑、可靠性边界和切换策略。

## 历史归档

- [团队模块阶段发布说明](archive/TEAM_RELEASE_NOTES.md)：2026-07-13 的功能与验证快照，仅供历史追溯。
