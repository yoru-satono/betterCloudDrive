# 架构文档

本目录用于解释 BetterCloudDrive 的系统边界、组件关系、领域模型、权限规则和关键业务链路。架构文档回答“系统怎样协作以及为什么这样划分”，不替代 API 和配置参考。

## 推荐阅读顺序

1. [系统架构总览](OVERVIEW.md)：运行组件、数据归属、同步/异步边界和部署形态。
2. [后端架构](BACKEND.md)：七个 Maven 模块、依赖方向、事务和安全边界。
3. [数据模型](DATA_MODEL.md)：核心实体关系、个人/团队作用域和生命周期。
4. [客户端架构](CLIENTS.md)：Web、Qt、KMP 移动端的共同协议与平台差异。
5. [权限模型](PERMISSIONS.md)：全局角色、团队角色、文件覆盖与继承顺序。

## 关键链路

- [上传与下载](flows/UPLOAD_DOWNLOAD.md)：分片上传、幂等完成、流式上传和下载授权。
- [异步任务](flows/ASYNC_TASKS.md)：Outbox、RabbitMQ、团队任务、重试与死信。
- [OnlyOffice 保存](flows/ONLYOFFICE_SAVE.md)：回调预留、后台保存、版本和失败恢复。

## 相关决策与操作手册

- [ADR 索引](../decisions/README.md)：模块化单体、数据存储、权限、客户端、队列、OnlyOffice 和本地化等关键选择。
- [RabbitMQ 投递决策](../decisions/002-message-queue-selection.md)：PostgreSQL Outbox、RabbitMQ 和死信边界的选择原因。
- [备份与恢复](../operations/BACKUP_RESTORE.md)：PostgreSQL 与对象存储的一致恢复要求。
- [观测与排障](../operations/OBSERVABILITY.md)：日志、指标、队列和任务的运行检查。
- [OnlyOffice 部署与排障](../operations/ONLYOFFICE_DEPLOYMENT.md)：地址、JWT、健康检查和常见错误。

架构文档描述稳定边界；精确路径、字段、变量和操作命令仍分别以 `reference` 和 `operations` 为准。
