# ADR-002：RabbitMQ 作为运行时投递通道

- 状态：已接受
- 日期：2026-07-07
- 适用范围：通知、团队任务、Outbox、死信和后台 Worker

## 背景与约束

通知、缩略图、审计导出、文件夹 ZIP 和 OnlyOffice 保存需要异步执行、重试和可观测失败。用户可见状态必须在进程或 broker 重启后仍可查询，也不能因消息重复投递而重复生成业务结果。

Redis 还承担验证码、上传状态、下载 ticket、WebDAV 锁、限流和缓存；把消息可靠性、重试和业务事实同时放入 Redis Streams 会扩大单一组件职责。仅靠定时轮询 PostgreSQL 虽然可恢复，但即时性和负载边界较差。

## 决策

使用 RabbitMQ 作为通知和后台任务的运行时投递与及时唤醒通道。PostgreSQL 的 `team_event_outbox`、`team_tasks` 和 `dead_letter_records` 保持事实来源、幂等和审计边界；消息正文只携带定位持久记录所需的信息，消费者重新读取当前状态。

拓扑包括持久化 topic exchange `bcd.events`、通知队列 `bcd.notifications`、任务队列 `bcd.team-tasks`、direct exchange `bcd.dead-letter` 和统一死信队列。生产者使用 Publisher Confirm；消费者手动 ACK，数据库原子 claim 与唯一约束承担重复投递幂等。

Redis 不再承载消息队列，但继续承担短期、可重建状态。

## 考虑过的替代方案

- Redis Streams：部署组件更少，但需要自行承担消费者组恢复、死信、重投和业务状态对账，且与缓存故障域耦合。
- 只轮询 PostgreSQL：事实一致性简单，但低延迟需要高频扫描，会增加数据库负载。
- 让 RabbitMQ 成为任务状态源：降低数据库表数量，但不适合用户查询、审计、幂等 claim 和恢复对账。
- Kafka：吞吐和重放能力强，但当前任务规模不需要其运维复杂度。

## 后果

- 正面：broker 提供可靠路由、ACK、Publisher Confirm 和死信能力，数据库提供可查询状态与恢复扫描。
- 正面：RabbitMQ 中断时已提交业务事实仍保留，恢复后可以继续发布或扫描。
- 负面：必须处理数据库提交成功但发布失败、重复投递和数据库/broker 指标对账。
- 负面：生产环境增加 RabbitMQ 凭据、容量、告警和升级责任。

迁移不双读 Redis Streams，也不搬迁旧 Stream 消息；遗留 Outbox 和任务由 PostgreSQL 状态重新发布或扫描。

## 实现与验证

- 执行链路：[异步任务流](../architecture/flows/ASYNC_TASKS.md)
- 模块与事务边界：[后端架构](../architecture/BACKEND.md)
- 运维与故障处置：[可观测性](../operations/OBSERVABILITY.md)、[故障 Runbook](../operations/INCIDENT_RUNBOOK.md)
- 当前实现：`betterclouddrive-service` 队列组件、`betterclouddrive-scheduler` Outbox/Worker、RabbitMQ Compose 服务
