# 消息队列选型

## 结论

BetterCloudDrive 使用 **RabbitMQ** 作为团队通知和后台任务的运行时投递通道。PostgreSQL `team_event_outbox` 与 `team_tasks` 是事实来源和幂等边界；RabbitMQ 只负责可靠分发和及时唤醒 worker。

Redis 仍用于验证码、上传状态、下载 ticket、WebDAV 锁、限流和缓存，但不再承载消息队列。

## 拓扑

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Topic Exchange | `bcd.events` | 应用事件入口 |
| Queue | `bcd.notifications` | 异步生成站内通知、邮件和实时刷新事件 |
| Queue | `bcd.team-tasks` | 审计导出、缩略图、OnlyOffice 保存和文件夹 ZIP 唤醒 |
| Direct Exchange | `bcd.dead-letter` | 无法处理消息的死信路由 |
| Queue | `bcd.dead-letter` | 统一保存通知和任务死信 |

通知使用 routing key `notification.dispatch`，团队任务使用 `team-task.wakeup`。Exchange、队列和消息均持久化；Listener 使用手动 ACK，拒绝且不重新入队的消息由 broker 投递到死信队列。

## 可靠性边界

1. 业务事务先写 PostgreSQL Outbox 或任务表，Outbox 调度器仅在 Publisher Confirm 成功且消息可路由后更新发布状态。
2. 通知消费失败时回写 Outbox 为 `PENDING` 并 ACK，由数据库控制退避和最大重试次数；最终失败进入 DLQ。
3. 团队任务消费前通过数据库原子 claim 保证幂等；重复唤醒安全 ACK，执行异常进入 DLQ，同时由低频数据库扫描兜底。
4. 普通团队领域事件当前没有消费者，只保留在 PostgreSQL Outbox，不创建永久积压的无人消费队列。
5. 队列不是用户可见状态源，任务中心状态始终来自 `team_tasks`。

## 切换策略

迁移采用直接切换，不双读 Redis Streams，也不搬迁 Stream 消息。遗留通知的超时 `PROCESSING` Outbox 会自动重置后发布到 RabbitMQ；团队任务由数据库扫描恢复。
