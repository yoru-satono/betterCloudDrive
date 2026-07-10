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
| `bcd.queue.stream.length` | Gauge | `stream` | Redis Stream 当前长度；覆盖 team events、notifications、team tasks 和 dead letter |
| `bcd.queue.consumer.pending` | Gauge | `stream`, `group` | 已投递但尚未 ACK 的 consumer group 消息数 |
| `bcd.queue.records.pending` | Gauge | `source` | PostgreSQL outbox/team task 事实表中的 `PENDING` 数量 |
| `bcd.queue.records.retry` | Gauge | `source` | `PENDING` 且 `retry_count > 0` 的记录数 |
| `bcd.queue.deadlettered` | Counter | `stream` | 运行期间进入 dead letter 的消息数 |
| `bcd.queue.processing.results` | Counter | `operation`, `outcome` | outbox 发布和通知消费批次的成功/失败次数 |
| `bcd.queue.processing.duration` | Timer | `operation`, `outcome` | outbox 发布和通知消费批次耗时 |
| `bcd.queue.observation.available` | Gauge | `source` | 最近一次刷新时 Redis/数据库指标源是否可用，`1` 可用、`0` 不可用 |

刷新任务默认每 15 秒读取 Redis Streams 和 PostgreSQL 事实表。pending/retry 达到 `QUEUE_REDIS_BACKLOG_WARN_THRESHOLD`，或 dead-letter 长度达到 `QUEUE_REDIS_DEAD_LETTER_WARN_THRESHOLD` 时，仅在跨越阈值时写入结构化告警；恢复到阈值以下会记录恢复日志。指标源不可用和恢复也采用相同的状态转换日志，避免每次刷新重复刷屏。

排查时不要把 `stream.length` 直接等同于未处理消息：ACK 不会自动删除 Stream 记录，长度还受 `QUEUE_REDIS_TRIM_MAX_LENGTH` 控制。实际消费异常优先查看 `consumer.pending`、数据库 `records.pending/retry`、dead-letter 和 processing failure/duration。
