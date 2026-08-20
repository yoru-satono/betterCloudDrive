# 运维手册

本目录面向部署和维护 BetterCloudDrive 的人员，覆盖数据保护、性能、可观测性、专项服务和故障处置。

## 当前手册

- [生产部署](PRODUCTION_DEPLOYMENT.md)：单机 Compose 上线边界、配置渲染、HTTPS、启动和验收。
- [升级与回滚](UPGRADE_ROLLBACK.md)：发布记录、迁移判断、维护窗口、应用与数据回滚。
- [密钥与凭据轮换](SECRET_ROTATION.md)：JWT、分享密码、OnlyOffice、Grafana 和基础设施凭据。
- [生产故障 Runbook](INCIDENT_RUNBOOK.md)：服务、数据库、存储、队列、Office、观测和安全事件处置。
- [备份与恢复](BACKUP_RESTORE.md)：PostgreSQL、SeaweedFS、密钥和 OnlyOffice 数据的备份恢复。
- [数据库性能](DATABASE_PERFORMANCE.md)：团队核心查询、索引和慢查询检查。
- [可观测性运行与排障](OBSERVABILITY.md)：Prometheus、Loki、Grafana、告警、日志和业务指标。
- [OnlyOffice 部署与排障](ONLYOFFICE_DEPLOYMENT.md)：地址、JWT、健康检查、保存链路和常见错误。

## 运维边界

- 当前生产 Compose 是单主机、单应用实例，升级与重建会产生维护窗口。
- 仓库没有自动备份、自动恢复、镜像签名、滚动发布或密钥双版本能力；文档会明确人工或外部系统的责任边界。
- `R__development_data.sql` 仍包含开发账号种子数据，在正式生产构建处理该风险前不得开放公网。
- 精确环境变量以[配置参考](../reference/CONFIGURATION.md)为准，运维手册只说明特定场景需要使用哪些配置。

任何恢复、删除卷、清空死信或批量重试操作都需要先确认精确目标和可恢复点。
