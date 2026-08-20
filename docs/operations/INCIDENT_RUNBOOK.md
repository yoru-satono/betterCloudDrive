# 生产故障 Runbook

本文用于单机 Compose 生产部署的初始处置。它给出保守的诊断顺序，不替代组织的值班、沟通和合规流程。指标含义见[可观测性](OBSERVABILITY.md)，恢复与版本处置分别见[备份恢复](BACKUP_RESTORE.md)和[升级回滚](UPGRADE_ROLLBACK.md)。

## 分级与第一响应

| 级别 | 示例 | 第一目标 |
|------|------|----------|
| P0 | 数据损坏、密钥泄露、广泛越权 | 立即阻止新增损害，保全证据，启动负责人和安全响应 |
| P1 | 登录、上传下载或数据库整体不可用 | 恢复核心读写或进入受控停写，持续通报 |
| P2 | Office、邮件、队列、Grafana 等单项不可用 | 隔离子系统，保证核心文件能力，安排修复 |
| P3 | 性能退化、非关键告警、单用户异常 | 收集证据并在正常变更窗口处理 |

开始处置时记录事件时间、报告人、影响范围、最近发布和配置变化。不要先执行 `down -v`、删除 volume、清空队列、重建数据库或批量重试死信。

使用与生产相同的 Compose project name、env 文件和 overlay。以下命令假设已经按[生产部署](PRODUCTION_DEPLOYMENT.md)设置 `$ComposeArgs`。

## 通用诊断

```powershell
Get-Date -Format o
docker compose @ComposeArgs ps
docker compose @ComposeArgs top
docker compose @ComposeArgs logs --since 30m --timestamps backend frontend postgres redis rabbitmq seaweedfs onlyoffice-documentserver
docker system df
```

同时检查：

- 外部 `https://<drive-domain>/healthz` 与 `https://<office-domain>/healthcheck`。
- Prometheus 告警开始时间、Grafana 同期指标和 Loki `requestId` 关联日志。
- 主机磁盘、inode、内存、CPU、时间同步、证书有效期和 DNS。
- 最近 Git commit、容器镜像 ID、Flyway 历史、配置和密钥轮换记录。

日志、数据库快照和配置证据应保存到受限事件目录。导出前先脱敏 token、密码、Cookie、Authorization 和用户内容。

## 应用整体不可用

对应告警通常包括 `BetterCloudDriveHttpProbeFailed`、`BetterCloudDriveBackendDown` 或高 5xx。

1. 判断是 Caddy/证书、前端、后端还是依赖服务失败。
2. 如果前端健康但 API 502，查看后端健康检查与启动日志。
3. 如果后端正在执行 Flyway，不要并发启动多个不同版本的后端。
4. 如果故障紧随发布，按升级记录判断应用级回滚是否仍与数据库兼容。

```powershell
docker compose @ComposeArgs logs --tail 300 frontend backend
docker compose @ComposeArgs exec -T backend wget -q -O - http://127.0.0.1:8081/actuator/health
docker compose @ComposeArgs exec -T postgres pg_isready -U bcd_user -d better_cloud_drive
```

只有在日志表明瞬态失败、依赖已恢复且没有迁移进行中时，才重建单个应用服务：

```powershell
docker compose @ComposeArgs up -d --force-recreate backend
docker compose @ComposeArgs up -d --force-recreate frontend
```

## PostgreSQL 或连接池故障

对应告警包括 `BetterCloudDriveDatabasePoolSaturation`、后端健康失败和 SQL 异常。

```powershell
docker compose @ComposeArgs exec -T postgres pg_isready -U bcd_user -d better_cloud_drive
docker compose @ComposeArgs exec -T postgres psql -U bcd_user -d better_cloud_drive -c "SELECT now(), count(*) FROM pg_stat_activity;"
docker compose @ComposeArgs exec -T postgres psql -U bcd_user -d better_cloud_drive -c "SELECT pid, state, wait_event_type, wait_event, query_start FROM pg_stat_activity WHERE datname='better_cloud_drive' ORDER BY query_start;"
```

- 连接数高时先找长事务、锁等待和突增流量，不要直接提高连接池上限。
- 磁盘满或 WAL 异常时先阻止新写入并扩容/释放已确认可删除的非数据库文件；不要手工删除 `pgdata` 内容。
- Flyway `success=false` 时保留日志和数据库副本，在隔离环境分析；不要直接编辑 `flyway_schema_history` 伪造成功。
- 需要恢复时，数据库和对象存储必须回到同一恢复点。

## 对象存储故障

对应告警为 `BetterCloudDriveStorageFailures`，用户可能能列出元数据但无法上传、下载或预览。

```powershell
docker compose @ComposeArgs ps seaweedfs
docker compose @ComposeArgs logs --since 30m seaweedfs backend
docker compose @ComposeArgs exec -T seaweedfs wget -q -O - http://127.0.0.1:9333/cluster/status
```

- 首先区分认证失败、容量耗尽、网络错误和对象缺失。
- 停止批量上传、缩略图、ZIP 或版本任务，避免扩大重试积压。
- 数据库有文件元数据不代表对象存在；不要通过删除数据库记录来“修复”对象缺失。
- 恢复存储后抽查普通文件、团队文件、缩略图、历史版本和 Range 下载。

## RabbitMQ、任务积压和死信

相关告警包括 `BetterCloudDriveQueueBacklog`、`BetterCloudDriveDeadLettersPresent`、`BetterCloudDriveQueueObservationUnavailable` 和 RabbitMQ 资源告警。

```powershell
docker compose @ComposeArgs exec -T rabbitmq rabbitmq-diagnostics -q ping
docker compose @ComposeArgs exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
docker compose @ComposeArgs logs --since 30m rabbitmq backend
```

1. 比较 RabbitMQ ready/in-flight 与数据库 outbox、pending/retry，判断发布、消费还是观测失败。
2. 资源告警时先处理磁盘或内存水位，不要盲目增加消费者。
3. 查明死信类别和原始错误后，通过[管理员死信接口](../reference/api/ADMIN.md)执行单条或受控批量重试。
4. 重试前确认操作幂等、下游依赖已恢复且不会重复生成文件或通知。
5. 清空死信是破坏性操作，只能在明确不再需要且已导出证据后由授权人员执行。

RabbitMQ 数据不是唯一事实来源；outbox 和团队任务状态位于 PostgreSQL。但这不意味着可以直接删除队列卷，恢复前仍要对账。

## OnlyOffice 无法打开或保存

对应告警为 `BetterCloudDriveOnlyOfficeSaveFailures`。先按[OnlyOffice 手册](ONLYOFFICE_DEPLOYMENT.md)检查三类 URL 和 JWT。

```powershell
docker compose @ComposeArgs logs --since 30m onlyoffice-documentserver backend
Invoke-WebRequest "https://office.example.com/healthcheck" -UseBasicParsing
```

- 能打开但保存 401：核对后端和 Document Server 的 `ONLYOFFICE_JWT_SECRET`，检查是否发生了部分轮换。
- 浏览器混合内容或 SDK 加载失败：核对 HTTPS public URL、DNS 和证书。
- 回调下载失败：确认 Document Server 能访问 `APP_INTERNAL_API_BASE_URL`，后端能访问 OnlyOffice internal URL。
- 故障期间普通上传下载通常仍可用；可以临时关闭编辑入口，但不要删除 OnlyOffice 卷或伪造保存成功。
- 恢复后必须进行一次真实编辑、关闭、回调和版本生成验证。

## 观测系统自身故障

`BetterCloudDriveObservabilityTargetDown`、Prometheus 存储失败或 Loki ingestion failure 表示观测能力不完整，不等于业务一定不可用。

- 直接检查应用健康和容器日志，避免因仪表盘空白误判全站故障。
- `bcd_observation_last_success_timestamp_seconds` 超过五分钟未更新时，业务 Gauge 可能是旧值。
- Prometheus/Loki 磁盘故障时保留应用数据库和对象存储优先级；不要为恢复指标删除业务卷。
- 观测恢复后确认告警状态和数据时间戳重新前进。

## 磁盘或资源耗尽

```powershell
docker system df
docker volume ls
docker stats --no-stream
```

先确定空间由容器日志、构建缓存、Prometheus/Loki 保留、数据库、对象存储还是任务结果占用。只清理有明确保留策略和可重建来源的数据。生产流程禁止使用无法限定目标的系统级 prune，也不要删除名称相似但归属不明的 volume。

如果需要移动或删除任何卷，先通过容器挂载和 Compose project 标签确认精确归属，并完成备份。对象存储、PostgreSQL 和密钥是同一恢复集，不能独立丢弃。

## 凭据泄露或越权

1. 立即限制公网入口或受影响功能，保存访问日志、审计记录和配置版本。
2. 确认泄露的是 JWT、分享密钥、OnlyOffice、Grafana、数据库、队列、对象存储还是 SMTP 凭据。
3. 按[密钥轮换](SECRET_ROTATION.md)执行；不要把所有密钥一次性无序更换。
4. JWT 轮换会强制重新登录；分享密码密钥没有无损直接轮换能力。
5. 检查管理员角色、分享、团队成员、审计日志和异常下载范围，按组织要求通知和留证。

## 恢复确认和关闭事件

事件只能在以下条件满足后关闭：

- 核心用户路径恢复并持续稳定一个观察窗口。
- 队列、死信、错误率、延迟和观测新鲜度恢复正常。
- 数据完整性抽查完成，明确是否发生数据丢失或重复处理。
- 临时端口、诊断 overlay、临时账号和放宽的安全规则已经撤销。
- 事件时间线、根因、处置、证据、用户影响和后续行动有负责人和期限。

不要在事件关闭前删除故障容器日志、升级前备份或受影响配置版本。
