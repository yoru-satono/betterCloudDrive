# 可观测性运行与排障

BetterCloudDrive 默认采用适合 4 核 8GB 单机部署的精简观测栈：

```text
Actuator/RabbitMQ/Blackbox -> Prometheus -> Alertmanager
                                  |
runtime/audit.log -> Alloy -> Loki +-> Grafana
```

应用仍创建进程内 Micrometer/OpenTelemetry Span，以便在 HTTP、Outbox、RabbitMQ 和后台任务之间传递关联上下文，但不通过 OTLP 导出完整 Trace。`X-Request-ID` 和 `X-Trace-ID` 用于关联响应、运行日志、审计日志和 `operation_logs`。

## 组件和容量

| 组件 | 用途 | 内存上限 |
| --- | --- | --- |
| Prometheus | 指标、记录规则和告警计算 | 512 MiB |
| Grafana | Dashboard 与 Loki Explore | 384 MiB |
| Loki | 单节点日志存储和查询 | 384 MiB |
| Alloy | 采集后端日志文件 | 192 MiB |
| Alertmanager | 邮件/Webhook 告警路由 | 192 MiB |
| Blackbox Exporter | 关键 HTTP 探活 | 128 MiB |

六个组件合计限制约为 1.7 CPU、1.75 GiB 内存。Prometheus 默认保留 15 天，Loki 14 天，Alertmanager 状态保留 72 小时。宿主机容量、容器 OOM 和重启监控由 Docker 或主机平台负责，不在应用 Compose 内运行特权 cAdvisor 或 Node Exporter。

Grafana 的 `BetterCloudDrive` 目录预置三个 Dashboard：

- `Service Overview`：服务状态、HTTP、JVM、Hikari、RabbitMQ、关键探活和当前告警。
- `Storage & Transfer`：对象存储操作、失败率、耗时、传输量和 SeaweedFS S3 探活。
- `Async & Collaboration`：队列、死信、OnlyOffice、团队配额和观测数据新鲜度。

数据源 UID 固定为 `bcd-prometheus`、`bcd-loki` 和 `bcd-alertmanager`。日志中的 `trace_id`、`request_id` 不作为 Loki 标签，避免高基数索引；管理员可以在日志中心筛选后跳转 Grafana。

## Prometheus 和告警

Prometheus 每 15 秒采集：

- 后端 `/actuator/prometheus`
- RabbitMQ 内置指标
- Prometheus、Loki、Alloy、Alertmanager 和 Blackbox Exporter
- Blackbox 的前端、后端管理健康端点、SeaweedFS S3、OnlyOffice 四个 HTTP 目标

核心告警覆盖端点或后端不可用、持续 5xx、P95 延迟、JVM 堆压力、数据库连接池饱和、对象存储连续失败、队列积压、死信、观测数据异常、OnlyOffice 保存失败、RabbitMQ 资源告警及 Prometheus/Loki 写入失败。错误率和延迟告警要求窗口内至少 20 个请求，避免低流量单次失败误报。

诊断端口默认不发布。需要从宿主机验收时：

```powershell
docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml up -d
Invoke-RestMethod http://127.0.0.1:9090/api/v1/targets
Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/rules?type=alert'
Invoke-RestMethod http://127.0.0.1:9093/-/ready
Invoke-RestMethod 'http://127.0.0.1:9115/probe?module=http_2xx&target=http://frontend/healthz'
```

生产 profile 在内部管理端口 `8081` 暴露 Actuator。生产告警使用独立配置：

```powershell
$env:ALERTMANAGER_PRODUCTION_CONFIG_FILE = 'C:\restricted\alertmanager.yml'
$env:PROMETHEUS_ENVIRONMENT = 'production'
docker compose --env-file .env.production -f docker-compose.yml -f docker-compose.production.yml -f docker-compose.production-observability.yml up -d
```

生产配置按 `environment/service/alertname` 分组，必须替换 SMTP、收件人、Webhook 和密钥；新增告警应附带可用的 runbook。

## 业务指标

### 业务数仓

启用数仓后，`bcd_warehouse_pending_events` 与 `bcd_warehouse_oldest_pending_age_seconds` 表示源端积压；`bcd_warehouse_query_available`、`bcd_warehouse_report_ready` 和 `bcd_warehouse_publication_age_seconds` 表示只读查询可用性、首次发布和发布延迟。探测由独立定时任务执行，不参与主业务健康检查。

`BetterCloudDriveWarehouseUnavailable` 在已启用的查询持续失败五分钟后告警；`BetterCloudDriveWarehouseDelayed` 在发布或未导出事件超过两小时且持续十分钟后告警。数仓未启用时不触发这两类告警。终态保存失败通过 `bcd_warehouse_capture_failures_total` 计数，相关下载保留未确认状态。

排障先检查源数据库、ClickHouse、Airflow 的 DAG 导入错误和任务状态，再查看管理后台运行记录与本机 dbt artifacts。不要通过清空未导出事件、修改发布标记或用当前状态覆盖历史快照来消除告警。重跑和补数步骤见[业务数仓](../../warehouse/README.md)。

### 对象存储

- `bcd_storage_operations_total{operation,outcome}`：`put/get/delete/head/copy` 成功或失败次数。
- `bcd_storage_operation_duration_seconds`：固定操作和结果维度的耗时。
- `bcd_storage_bytes_total{direction}`：实际上传和下载字节。

标签不包含 bucket、对象 Key、文件名、用户或团队 ID。连续十分钟超过五次失败会触发存储告警。

### 队列和死信

- `bcd_queue_messages_ready`、`bcd_queue_consumer_inflight`
- `bcd_queue_records_pending`、`bcd_queue_records_retry`
- `bcd_queue_processing_results_total`、`bcd_queue_processing_duration_seconds`
- `bcd_queue_dead_letters`、`bcd_queue_dead_letter_actions_total`

RabbitMQ 死信由单并发消费者持久化到 `dead_letter_records`，成功入库后才 ACK。管理员应先用 request/trace ID 关联日志，再确认业务事实仍存在，随后重放或填写原因丢弃；不要从 RabbitMQ 管理页直接清空 DLQ。

### OnlyOffice 和团队配额

- `bcd_onlyoffice_callbacks_total`、`bcd_onlyoffice_save_results_total`、`bcd_onlyoffice_save_duration_seconds`
- `bcd_team_quota_used_bytes`、`bcd_team_quota_limit_bytes`、`bcd_team_quota_teams`
- `bcd_team_quota_alerts_total`、`bcd_team_quota_notification_failures_total`

单团队明细保留在业务数据库和管理后台，Prometheus 只存总量和固定分桶。

### 数据新鲜度

`bcd_observation_last_success_timestamp_seconds`、`bcd_observation_refresh_failures_total` 和 `bcd_observation_refresh_duration_seconds` 用于区分真实业务 Gauge 与停止刷新的旧值。超过五分钟没有成功刷新会触发告警。

## 日志、安全和升级

- Loki、Prometheus、Alertmanager、Blackbox 和 Alloy 默认只在容器网络内访问；诊断 overlay 也只绑定 `127.0.0.1`。
- 请求正文默认不采集。开启审计正文后仍只接受限长、可解析并脱敏的 JSON。
- 只有来自 `TRUSTED_PROXY_CIDRS` 的直接代理请求才采用转发客户端地址；解析会从代理链右侧逐跳排除受信代理，防止客户端伪造最左侧地址。旧变量 `OBSERVABILITY_TRUSTED_PROXY_CIDRS` 仅作为兼容回退。
- `drive_rate_limit_rejected_total` 按固定的 `bucket`、`subject_type` 标签记录拒绝，`drive_rate_limit_redis_fail_open_total` 记录 Redis 故障时的放行；两者均不包含用户 ID 或 IP。
- 审计事实以 PostgreSQL 为准；Loki 日志按保留期自动淘汰。
- 从旧版升级不会自动删除历史 `tempodata` 等 Docker 卷。确认不再需要旧 Trace 后，可先用 `docker volume ls` 确认项目卷名，再人工删除对应孤立卷；部署或升级脚本不得自动执行该操作。

告警触发后的保守处置顺序见[生产故障 Runbook](INCIDENT_RUNBOOK.md)，组件或应用版本变更见[升级与回滚](UPGRADE_ROLLBACK.md)。

完整验收：

```powershell
.\scripts\verify-all.ps1 -Module observability
```
