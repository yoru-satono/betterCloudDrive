# BetterCloudDrive 文档

本文档目录按用途分组。接口和配置以参考资料为准；历史归档只用于追溯，不代表当前实现。

## 后续计划

- [后续计划](ROADMAP.md)：当前尚未完成的安全、正确性、扩展性和客户端改进任务。

## 参考资料

- [API 文档](reference/API.md)：认证、文件、分享、团队、通知、OnlyOffice 和管理员接口。
- [配置与部署](reference/CONFIGURATION.md)：Docker Compose、环境变量、反向代理、Redis、RabbitMQ、安全和完整验证。
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
