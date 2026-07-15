# 可观测性运行与排障

默认数据流：

```text
runtime/audit.log -> Promtail -> Loki ----+
                                           +-> Grafana
Spring OpenTelemetry -> Collector -> Tempo-+
                                           |
Actuator/exporters -> Prometheus ----------+
                         |
                         +-> Alertmanager -> Mailpit（开发环境）
```

Grafana 的 `BetterCloudDrive` 目录预置以下 Dashboard：

- `Application Overview`：可用性、吞吐、错误率、HTTP 延迟、JVM、连接池及观测组件健康度。
- `Business Operations`：RabbitMQ/事实表积压、DLQ、队列消费和 OnlyOffice 保存。

Loki 数据源会把 JSON 日志中的 `trace_id` 链接到 Tempo；Tempo 也能按 trace ID 回查 Loki。`trace_id`、`request_id` 不作为 Loki 标签，避免高基数索引。

## Prometheus 和告警

Prometheus 默认保留 30 天数据，配置位于 `observability/prometheus.yml`，规则位于 `observability/prometheus-rules.yml`。首批规则覆盖：后端不可用、5xx 比例、P95 延迟、JVM 堆压力、连接池饱和、队列积压、DLQ、OnlyOffice 保存失败及观测/基础设施指标源不可用。

同时提供四个低基数 SLI 记录规则：HTTP 可用率、HTTP P95 延迟、队列处理成功率和 OnlyOffice 保存成功率。当前初始目标为 HTTP/队列成功率 99%；这些数值是项目默认基线，生产上线前应根据实际流量、依赖 SLA 和错误预算重新评审。

开发环境 Alertmanager 把告警发给 Mailpit 中的 `admin@betterclouddrive.local`。生产部署前必须修改 `observability/alertmanager.yml` 的 SMTP 和接收人，或改为受控的 Webhook/企业通知渠道。告警均使用持续时间窗口，避免瞬时尖峰造成告警风暴。

检查采集目标和告警规则：

```powershell
Invoke-RestMethod http://127.0.0.1:9090/api/v1/targets
Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/rules?type=alert'
Invoke-RestMethod http://127.0.0.1:9093/-/ready
```

生产环境如果不需要从宿主机诊断 Prometheus/Alertmanager，应移除 Compose 中相应的 `ports`，仅保留内部网络访问。

后端通过 Spring Boot Actuator 暴露 Prometheus 格式的 Micrometer 指标：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/actuator/prometheus
```

## 团队配额

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.team.quota.used.bytes` | Gauge | 无 | 所有有效团队当前使用字节数 |
| `bcd.team.quota.limit.bytes` | Gauge | 无 | 所有有效团队配额总字节数 |
| `bcd.team.quota.teams` | Gauge | `usage` | 按 `<80/80-90/90-100/>=100%` 分桶的团队数 |
| `bcd.team.quota.alerts` | Counter | `threshold` | 去重后实际触发的告警次数 |
| `bcd.team.quota.notification.failures` | Counter | `channel` | `queue/direct/email` 通知失败次数 |

告警触发和通知失败同时写入结构化运行日志。单团队明细保留在业务数据库和管理后台；Prometheus 仅存总量与固定分桶，不使用团队或用户 ID 标签，避免无界基数。

## Trace 关联

Spring 的 OpenTelemetry/Micrometer Observation 是 trace context 的唯一事实来源。HTTP 审计从当前真实 Span 读取 `trace_id/span_id`，RabbitTemplate 和监听容器启用 Observation，自动通过消息头传播 W3C context。生产环境建议将 `TRACING_SAMPLING_PROBABILITY` 调低到 `0.01-0.2`；开发和验收环境可保持 `1.0`。

按响应头检查 trace：

```powershell
$response = Invoke-WebRequest http://127.0.0.1:8080/actuator/health
$traceId = $response.Headers['X-Trace-ID']
Invoke-RestMethod "http://127.0.0.1:3200/api/traces/$traceId"
```

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
