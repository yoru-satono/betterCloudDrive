# 应用指标与排障

后端通过 Spring Boot Actuator 暴露 Micrometer 指标。查看指标名和单项明细：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/metrics
Invoke-RestMethod http://127.0.0.1:8080/actuator/metrics/bcd.team.quota.usage.ratio
```

## 团队配额

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.team.quota.used.bytes` | Gauge | `team.id` | 当前使用字节数 |
| `bcd.team.quota.limit.bytes` | Gauge | `team.id` | 配额字节数 |
| `bcd.team.quota.usage.ratio` | Gauge | `team.id` | 使用率，`0-1` |
| `bcd.team.quota.threshold.percent` | Gauge | `team.id` | 当前达到的 `0/80/90/100` 阈值 |
| `bcd.team.quota.alerts` | Counter | `threshold` | 去重后实际触发的告警次数 |
| `bcd.team.quota.notification.failures` | Counter | `channel` | `queue/direct/email` 通知失败次数 |

告警触发和通知失败同时写入结构化运行日志。Loki 中可搜索 `Team quota alert triggered` 或 `Failed to dispatch team quota alert`，日志包含 `teamId`、`userId`、阈值和通知通道；指标不使用用户 ID 标签，避免无界基数。

## OnlyOffice 保存

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.onlyoffice.callbacks` | Counter | `status` | 收到的 callback 数；未知状态归为 `other` |
| `bcd.onlyoffice.callback.outcomes` | Counter | `outcome` | `queued/duplicate/ignored` callback 处理结果 |
| `bcd.onlyoffice.save.results` | Counter | `outcome` | 后台保存 `success/failure` 次数 |
| `bcd.onlyoffice.save.duration` | Timer | `outcome` | 下载编辑结果并创建版本的耗时 |
| `bcd.onlyoffice.save.retries` | Counter | 无 | 用户从任务中心重试保存的次数 |

保存失败日志包含 `callbackId`、`teamId`、`fileId` 和异常；成功日志包含生成的 `versionId`。排查时先比较 callback `queued` 数与 save result 数，再查看失败耗时、重试次数和 `ONLYOFFICE_SAVE` 任务状态。

## 队列积压

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.queue.messages.ready` | Gauge | `queue` | RabbitMQ 中等待消费的消息数 |
| `bcd.queue.consumer.inflight` | Gauge | `queue` | 当前应用实例正在处理的消息数 |
| `bcd.queue.records.pending` | Gauge | `source` | PostgreSQL outbox/team task 事实表中的 `PENDING` 数量 |
| `bcd.queue.records.retry` | Gauge | `source` | `PENDING` 且 `retry_count > 0` 的记录数 |
| `bcd.queue.deadlettered` | Counter | `queue` | 运行期间进入 dead letter 的消息数 |
| `bcd.queue.processing.results` | Counter | `operation`, `outcome` | outbox 发布、通知消费和团队任务唤醒批次的成功/失败次数 |
| `bcd.queue.processing.duration` | Timer | `operation`, `outcome` | outbox 发布、通知消费和团队任务唤醒批次耗时 |
| `bcd.queue.team_tasks.claimed` | Counter | `type` | 各类型团队任务被数据库原子 claim 的数量 |
| `bcd.queue.team_tasks.duplicate_wakeups` | Counter | `type` | 未 claim 到任务的重复或延迟唤醒数量 |
| `bcd.queue.team_tasks.timeout_recovered` | Counter | 无 | 超时 RUNNING 任务恢复为 PENDING 的数量 |
| `bcd.queue.observation.available` | Gauge | `source` | 最近一次刷新时 RabbitMQ/数据库指标源是否可用，`1` 可用、`0` 不可用 |

刷新任务默认每 15 秒读取 RabbitMQ 队列和 PostgreSQL 事实表。ready、pending 或 retry 达到 `QUEUE_RABBITMQ_BACKLOG_WARN_THRESHOLD`，或 DLQ 达到 `QUEUE_RABBITMQ_DEAD_LETTER_WARN_THRESHOLD` 时，仅在跨越阈值时写入结构化告警；恢复到阈值以下会记录恢复日志。

实际消费异常优先查看 `messages.ready`、`consumer.inflight`、数据库 `records.pending/retry`、DLQ 和 processing failure/duration。
