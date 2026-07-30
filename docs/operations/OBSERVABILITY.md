# 可观测性运行与排障

默认数据流：

```text
runtime/audit.log -> Alloy -> Loki -------+
                                           +-> Grafana
Spring OpenTelemetry -> Collector -> Tempo-+
                                           |
Actuator/exporters -> Prometheus ----------+
                         |
                         +-> Alertmanager -> Mailpit（开发环境）
Blackbox Exporter -> 关键 HTTP 端点 -------+
```

Grafana 的 `BetterCloudDrive` 目录预置以下 Dashboard：

- `Service Overview`：服务状态、当前告警、HTTP/队列/OnlyOffice SLI、吞吐、错误率和延迟。
- `Storage & Transfer`：对象存储调用成功/失败、耗时、传输量及 SeaweedFS 三个 HTTP 探测。
- `Async & Collaboration`：队列积压与处理、观测数据新鲜度、OnlyOffice，以及团队配额总量、区间、告警和通知失败。
- `Infrastructure`：Blackbox、可观测性组件、JVM、连接池、PostgreSQL、Redis 和 RabbitMQ。

四个 Dashboard 使用固定 UID，所有 Prometheus 面板固定引用 `bcd-prometheus`，不会依赖 Grafana 自动生成的数据源 ID。顶部统一显示服务状态和当前 firing 告警，并提供 Loki 日志、Tempo Trace 与本文档的跳转链接。聚合 Stat 面板对无流量使用明确的 `0` 或健康基线，时序与表格面板设置 `noValue` 文案，避免把 `NaN` 当成真实故障。

Loki 数据源会把 JSON 日志中的 `trace_id` 链接到 Tempo；Tempo 也能按 trace ID 回查 Loki。`trace_id`、`request_id` 不作为 Loki 标签，避免高基数索引。

## Prometheus 和告警

Prometheus 默认保留 30 天数据，配置位于 `observability/prometheus.yml`，规则位于 `observability/prometheus-rules.yml`。首批规则覆盖：后端不可用、5xx 比例、P95 延迟、JVM 堆压力、连接池饱和、队列积压、DLQ、OnlyOffice 保存失败及观测/基础设施指标源不可用。

同时提供四个低基数 SLI 记录规则：HTTP 可用率、HTTP P95 延迟、队列处理成功率和 OnlyOffice 保存成功率。当前初始目标为 HTTP/队列成功率 99%；这些数值是项目默认基线，生产上线前应根据实际流量、依赖 SLA 和错误预算重新评审。

开发环境继续使用 `observability/alertmanager.yml`，把告警发给 Mailpit 中的 `admin@betterclouddrive.local`。生产环境必须复制并评审 `observability/alertmanager-production.yml.example`，把 SMTP、收件人、Webhook 和密钥替换为真实值，然后通过独立 overlay 启动：

```powershell
$env:ALERTMANAGER_PRODUCTION_CONFIG_FILE = 'C:\restricted\alertmanager.yml'
$env:PROMETHEUS_ENVIRONMENT = 'production'
docker compose --env-file .env.production -f docker-compose.yml -f docker-compose.production.yml -f docker-compose.production-observability.yml up -d
```

生产配置按 `environment/service/alertname` 分组，warning 只发邮件，critical 同时发邮件和 Webhook；两条路径都发送 resolved。相同环境、服务和告警名的 critical 会抑制 warning，其他服务或告警不受影响。每条新增生产告警必须提供 `runbook_url`。不要把包含凭据的生产配置提交到仓库。

诊断端口默认不发布；先使用 `docker-compose.diagnostics.yml` overlay 启动，再检查采集目标和告警规则：

```powershell
Invoke-RestMethod http://127.0.0.1:9090/api/v1/targets
Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/rules?type=alert'
Invoke-RestMethod http://127.0.0.1:9093/-/ready
Invoke-RestMethod 'http://127.0.0.1:9115/probe?module=http_2xx&target=http://frontend/healthz'
```

可观测性服务在主 Compose 中只保留容器网络访问。诊断 overlay 也仅绑定 `127.0.0.1`，但生产环境默认不要叠加它，也不要在生产公网入口代理这些端口。

生产 profile 在内部管理端口 `8081` 暴露 Actuator，业务 API 端口不提供 metrics。诊断 overlay 可从宿主机检查：

```powershell
Invoke-WebRequest http://127.0.0.1:8081/actuator/prometheus
```

## 对象存储与 HTTP 探测

后端直接记录 S3 兼容对象存储调用，不依赖上层 HTTP 状态推断存储是否正常：

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.storage.operations` | Counter | `operation,outcome` | `put/get/delete/head/copy` 操作的成功与失败次数 |
| `bcd.storage.operation.duration` | Timer | `operation,outcome` | S3 调用完成或返回响应流前的耗时 |
| `bcd.storage.bytes` | Counter | `direction` | 实际上传或被调用方读取的 payload 字节数 |

标签均来自固定枚举，不包含 bucket、对象 Key、文件名、团队或用户标识。下载字节在响应流关闭时累计；若调用方没有读取完整内容，指标只记录实际读取量。Prometheus 中对应名称为 `bcd_storage_operations_total`、`bcd_storage_operation_duration_seconds` 和 `bcd_storage_bytes_total`。

Blackbox Exporter 从 `bcd-network` 内执行真实 HTTP GET，覆盖前端、后端管理健康端点、OnlyOffice、SeaweedFS Master/Filer/S3，以及 Grafana、Loki、Tempo 的只读健康端点。查询整体状态：

```promql
probe_success{job="blackbox-http"}
```

`BetterCloudDriveHttpProbeFailed` 在任一端点连续失败两分钟后触发，并在探测恢复后自动解除。先用告警的 `service` 和 `instance` 检查对应健康端点、容器日志及 `bcd-network` 连通性。Blackbox Exporter 无持久状态，主 Compose 不发布端口；诊断 overlay 仅把 `9115` 绑定到 `127.0.0.1`。

当前 SeaweedFS `4.40` 的 Master 与 Filer `/metrics` 均未提供稳定 Prometheus 输出，因此本阶段只接入真实 HTTP 探测，不生成不可靠的容量、节点或 Volume 指标。后续升级 SeaweedFS 并确认其指标契约后再单独增强。

## 团队配额

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.team.quota.used.bytes` | Gauge | 无 | 所有有效团队当前使用字节数 |
| `bcd.team.quota.limit.bytes` | Gauge | 无 | 所有有效团队配额总字节数 |
| `bcd.team.quota.teams` | Gauge | `usage` | 按 `<80/80-90/90-100/>=100%` 分桶的团队数 |
| `bcd.team.quota.alerts` | Counter | `threshold` | 去重后实际触发的告警次数 |
| `bcd.team.quota.notification.failures` | Counter | `channel` | `queue/direct/email` 通知失败次数 |

告警触发和通知失败同时写入结构化运行日志。单团队明细保留在业务数据库和管理后台；Prometheus 仅存总量与固定分桶，不使用团队或用户 ID 标签，避免无界基数。

## 观测数据新鲜度

队列和团队配额 Gauge 可能在采集失败后保留旧值，因此不能只根据业务 Gauge 判断采集是否正常。后端为三个固定采集源 `team-quota`、`queue-rabbitmq`、`queue-database` 暴露：

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `bcd.observation.last.success.timestamp` | Gauge | `source` | 最近一次成功刷新的 Unix 时间戳；进程启动后尚未成功时为 `0` |
| `bcd.observation.refresh.failures` | Counter | `source` | 刷新失败次数 |
| `bcd.observation.refresh.duration` | Timer | `source,outcome` | 成功或失败刷新耗时 |

Prometheus 名称分别规范化为 `_seconds`、`_total` 和 `_seconds` 后缀。`BetterCloudDriveObservationDataStale` 在最近成功时间超过五分钟时进入 pending；对于从未成功的采集源，会等待应用启动超过五分钟后再判断，避免启动期误报。告警恢复表示采集重新成功，不代表之前缓存的业务值本身一定发生变化。

## Trace 关联

Spring 的 OpenTelemetry/Micrometer Observation 是 trace context 的唯一事实来源。HTTP 审计从当前真实 Span 读取 `trace_id/span_id`；消息发布显式写入 W3C `traceparent`/`tracestate`，消息消费端提取它并创建 Consumer Span。监听容器保留框架级 Observation；RabbitTemplate 的发送 Observation 则关闭，避免它在发送前用调度器上下文覆盖应用已恢复的 Outbox context。

事务 Outbox 和数据库后台任务不会把请求 Span 长时间保持为 parent。写入事实表时只保存 W3C context，后台执行时创建新的 root Span，并用 span link 指回原请求。这样即使消息延迟或多次重试，也不会形成跨分钟/小时的父子链或无限增长的 retry parent chain。每一次重试都是独立 trace，并链接同一个原始请求。

应用定义的异步 Span 如下：

| Span | Kind/关联方式 | 说明 |
|------|---------------|------|
| `outbox.publish` | Producer；新 root + 原请求 link | 从数据库 Outbox 发布消息，每次尝试独立 |
| `notification.dispatch` | Consumer；消息 header parent | 消费并派发团队通知 |
| `team_task.execute` | Consumer；消息 header parent | 消费团队任务唤醒消息 |
| `onlyoffice.save` | Internal；新 root + 任务来源 link | 执行延迟的 OnlyOffice 保存 |
| `storage.put` / `storage.get` | 当前执行上下文的子 Span | S3 兼容存储写入和流式读取 |

自定义属性严格限制为固定枚举：`messaging.system`、`messaging.destination.name`、`bcd.task.type`、`bcd.operation.outcome`。文件名、对象 Key、payload、团队/用户/任务 ID 和异常消息均不写入 Span。未知 destination/task type 统一归为 `unknown`/`UNKNOWN`。`retry` 和 `dead_letter` 同时记录同名事件、`ERROR` 状态及对应 outcome，正常完成记录 `success`。

在 Tempo 排障时，先按 `outbox.publish`、`team_task.execute` 等 Span 名称搜索，再查看 `Links` 中的原请求 trace/span；不要假定延迟任务与 HTTP 请求共享同一个 trace ID。Consumer Span 则应与对应 `outbox.publish` 处在同一 trace，并以发布 Span 为 parent。

当前在应用入口使用 head sampling：dev profile 默认采样率为 `1.0`，prod profile 默认采样率为 `0.1`，均可用 `TRACING_SAMPLING_PROBABILITY` 覆盖。完整验收脚本会显式使用 `1.0`，避免 Tempo 关联测试受到概率采样影响。生产环境应根据流量和存储预算把该值维持在 `0.01-0.2` 范围；head sampling 在请求开始时决策，成本低，但不能保证保留所有错误或慢请求。若未来需要错误和慢请求优先保留，应在评估 Collector 容量后引入 tail sampling，不能同时假设上游已丢弃的 Trace 可被恢复。

Collector 的 trace pipeline 先经过 `memory_limiter`，再进入有界 `batch`。默认软内存上限为 `384` MiB、尖峰预留为 `96` MiB；可分别通过 `OTEL_COLLECTOR_MEMORY_LIMIT_MIB` 和 `OTEL_COLLECTOR_MEMORY_SPIKE_LIMIT_MIB` 调整。到 Tempo 的导出启用指数退避重试和有界内存队列，默认 4 个消费者、4096 批，可通过 `OTEL_COLLECTOR_QUEUE_CONSUMERS` 和 `OTEL_COLLECTOR_QUEUE_SIZE` 调整。队列不是持久队列；Tempo 长时间不可用时，数据会在重试上限或队列满后丢弃。Collector 容器限制为 640 MiB，必须让软内存上限低于容器上限并保留进程余量。

## 宿主机、容器与容量告警

Node Exporter 采集 Docker 宿主机 CPU、内存、文件系统空间和 inode；cAdvisor 采集容器启动时间、OOM、内存使用和限制。两者只加入内部 `bcd-network`，不发布宿主机端口。Prometheus 中应看到 `node-exporter` 和 `cadvisor` target 为 `up`：

```promql
up{job=~"node-exporter|cadvisor"}
```

默认告警提前覆盖：

- 数据盘可用空间低于 15% 持续 15 分钟发 warning，低于 5% 持续 5 分钟发 critical；inode 低于 10% 持续 15 分钟发 warning。
- 容器 15 分钟内启动时间变化超过 2 次视为频繁重启；出现 OOM 事件立即告警；受限容器内存使用超过限制的 90% 持续 10 分钟告警。
- Prometheus 块写入、Loki 日志接收或 Tempo span 接收超过 5 MiB/s 持续 30 分钟，提示检查异常数据增长与保留预算。

Docker Desktop 的 exporter 看到的是其 Linux VM，而不是 Windows/macOS 宿主机本身；生产 Linux 部署应确认 `/:/host`、`/sys`、`/var/lib/docker` 等只读挂载可见。主 Compose 没有要求 `rslave` 挂载传播，以兼容 Docker Desktop；若 Linux 主机使用动态挂载的数据盘，应在受控的生产 overlay 中为 `/host` 增加 `rslave`，并验证新挂载点能被采集。磁盘告警触发后，先用 `instance/device/mountpoint` 定位文件系统，再检查 Prometheus、Loki、Tempo 的保留期和对应命名卷增长；不要直接删除卷。

可观测性容器设有 CPU、内存和 PID 上限：Exporter/Blackbox 为 192 MiB，Alertmanager/Alloy/cAdvisor 为 320 MiB，Loki/Tempo/Collector/Grafana 为 640 MiB，Prometheus 为 896 MiB。Prometheus、Loki、Tempo、Grafana 和 Alertmanager 使用命名卷；Exporter、Blackbox、Alloy 和 Collector 为无状态组件。生产调优必须同时观察 OOM、近上限告警、采集间隔和磁盘增长，不能只提高单项上限。

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

## 死信处置

RabbitMQ 仍是死信路由入口，但 `bcd.dead-letter` 由单并发的入库消费者处理。消费者只在 `dead_letter_records` 持久化成功后 ACK；数据库暂时不可用时 NACK 并重新入队。管理列表因此不会通过 `basic.get` 等方式破坏性地遍历 RabbitMQ。

持久记录包含原始队列、固定任务类型、有限失败分类、投递次数、首次/最后失败时间以及 request/trace ID。原始 payload 仅用于服务端重放，不会由列表或详情 API 返回。指标使用固定标签：

| 指标 | 标签 | 说明 |
|------|------|------|
| `bcd_queue_dead_letters` | `queue,category` | 当前 OPEN 死信数 |
| `bcd_queue_dead_letter_actions_total` | `action,outcome` | 重放和丢弃操作结果 |

`queue` 仅允许 `notifications/team-tasks/unknown`；`category` 仅允许 `invalid-payload/missing-record/delivery-exhausted/unknown`。`BetterCloudDriveDeadLettersPresent` 持续两分钟后按来源和分类触发，并提供管理入口 `/admin/dead-letters`。

管理员处置顺序：

1. 按来源和失败分类筛选，使用 request/trace ID 关联日志和 Tempo。
2. 确认依赖已恢复、消息类型有效且对应数据库事实仍存在。
3. 单条或最多 20 条批量重放。服务使用行锁和状态转换保证同一死信记录只接受一次重放；业务消费者继续依靠 Outbox/任务 claim 保证副作用幂等。
4. 无法或不应重放时显式丢弃，并填写 3–500 字符原因。
5. 在管理员操作日志中核对 `DEAD_LETTER_REPLAY` 或 `DEAD_LETTER_DISCARD`。

不要通过 RabbitMQ 管理页清空整个 DLQ，也不要直接删除生产记录。自动化测试消息必须携带 `bcd-e2e:` 前缀的 `testMarker`；清理脚本只删除这种显式标记记录，不会按时间、队列或状态批量清除其他死信。

## 安全与保留策略

- Loki、Tempo、Prometheus、Alertmanager、Blackbox Exporter、OTLP 和 Alloy 默认不发布宿主机端口。
- 审计事件默认写入数据库和 `audit.log`，但请求正文默认不采集；设置 `AUDIT_CAPTURE_BODY=true` 后也仅采集限长、可解析并脱敏的 JSON。
- 只有来自 `OBSERVABILITY_TRUSTED_PROXY_CIDRS` 的请求才会采用 `X-Forwarded-For` / `X-Real-IP`。
- 默认保留期为 Loki 30 天、Tempo 7 天、Prometheus 30 天。指标、日志和 trace 可按保留期淘汰，持久审计事实以 PostgreSQL 为准。
- 生产告警配置从 `alertmanager-production.yml.example` 复制，并通过 `docker-compose.production-observability.yml` 和 `ALERTMANAGER_PRODUCTION_CONFIG_FILE` 挂载；开发环境仍由 Mailpit 接收。
