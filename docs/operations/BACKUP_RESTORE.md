# 数据备份与恢复

本文定义单机 Compose 部署的恢复集、停写备份、空环境恢复和演练要求。仓库当前没有自动备份或自动恢复脚本；示例命令需要由部署方纳入受控调度、加密、异地复制和监控系统。

## 备份边界

一次可恢复的团队备份至少包含：

| 数据 | 事实来源 | 必须备份 |
|------|----------|----------|
| 团队、成员、权限、邀请、分享、标签、审计、任务 | PostgreSQL | 是 |
| 文件当前内容、缩略图、历史版本、任务结果 | SeaweedFS / S3 | 是 |
| 分享密码加密和 JWT 密钥 | `.env`、`config/env-files/*.env` 或外部密钥系统 | 是 |
| 队列临时状态 | RabbitMQ | 可选，数据库 outbox/task 是事实来源 |
| 缓存和服务状态 | Redis | 按业务恢复时间要求选择备份 |
| Document Server 本地状态 | `onlyofficedata` | 可选 |
| OnlyOffice 日志 | `onlyofficelogs` | 按审计保留策略 |

团队文档历史版本不存放在 OnlyOffice 卷中。版本元数据位于 PostgreSQL `file_versions`，版本对象位于 SeaweedFS；因此数据库和对象存储必须恢复到同一个备份时间点。团队归档、软删除和计划清理状态位于 `teams.archived_at`、`deleted_at`、`purge_after` 等数据库字段。

## RPO、RTO 和保留策略

代码仓库无法替部署组织承诺业务 SLA。每个生产环境必须在上线记录中填写并通过恢复演练验证以下目标：

| 项目 | 必填定义 | 无外部要求时的初始演练基线 |
|------|----------|----------------------------|
| RPO | 最多可接受丢失多少时间的数据 | 24 小时以内 |
| RTO | 从宣布恢复到核心文件读写可用的时间 | 4 小时以内 |
| 完整备份频率 | PostgreSQL、对象存储和密钥形成一致恢复集 | 至少每日一次 |
| 隔离恢复演练 | 从备份恢复到非生产环境并完成抽查 | 至少每季度一次 |
| 异地副本 | 不与生产主机、账号或故障域共享 | 至少一份加密副本 |

这些数值是初始演练目标，不是未经验证的可用性承诺。数据增长后应按实际备份时长、上传变化率和恢复测速调整。若 24 小时 RPO 不可接受，应使用 PostgreSQL WAL/PITR 与支持版本或快照的外部对象存储；仓库中的冷备命令本身无法提供更细粒度恢复。

建议保留基线为最近 7 个日备份、4 个周备份和 12 个成功月备份，最终期限服从容量、合规和隐私要求。保留清理只能删除已经存在另一份已验证副本的恢复集；数据库、对象、密钥和清单必须以同一恢复集标识共同保留或共同淘汰。

## 一致性备份

以下示例用于生产 Compose 单机部署。先设置与生产完全相同的 project name 和 overlay；已有部署必须使用容器标签中的实际 project name：

```powershell
$ComposeProject = 'betterclouddrive'
$ComposeArgs = @(
  '--project-name', $ComposeProject,
  '--env-file', '.env.production',
  '-f', 'docker-compose.yml',
  '-f', 'docker-compose.production.yml'
)
docker compose @ComposeArgs config --quiet
```

如果生产启用了观测 overlay，也必须追加同一个文件。随后创建备份目录并暂停所有写入来源：

```powershell
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupDir = (New-Item -ItemType Directory -Path "backups/$Stamp").FullName
docker compose @ComposeArgs stop frontend alloy onlyoffice-documentserver backend
```

备份 PostgreSQL：

```powershell
docker compose @ComposeArgs exec -T postgres pg_dump -U bcd_user -d better_cloud_drive --format=custom --file=/tmp/better-cloud-drive.dump
docker cp bcd-postgres:/tmp/better-cloud-drive.dump "$BackupDir/postgres.dump"
docker compose @ComposeArgs exec -T postgres rm -f /tmp/better-cloud-drive.dump
```

停止 SeaweedFS 后冷备对象卷。通过容器挂载信息获取实际 volume 名，避免依赖 Compose 项目前缀：

```powershell
docker compose @ComposeArgs stop seaweedfs
$SeaweedVolume = docker inspect bcd-seaweedfs --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
docker run --rm -v "${SeaweedVolume}:/source:ro" -v "${BackupDir}:/backup" alpine:3.22 sh -c "cd /source && tar czf /backup/seaweeddata.tgz ."
```

如需保留 Document Server 本地状态，可同样备份 `onlyofficedata`。这不是团队历史版本的替代品：

```powershell
$OnlyOfficeVolume = docker inspect bcd-onlyoffice --format '{{range .Mounts}}{{if eq .Destination "/var/www/onlyoffice/Data"}}{{.Name}}{{end}}{{end}}'
docker run --rm -v "${OnlyOfficeVolume}:/source:ro" -v "${BackupDir}:/backup" alpine:3.22 sh -c "cd /source && tar czf /backup/onlyofficedata.tgz ."
```

备份环境模板和生成校验清单。生产密钥应进入加密备份或密钥管理系统，不应提交到 Git：

```powershell
Copy-Item .env.production "$BackupDir/root.env.production"
Copy-Item config/env-files "$BackupDir/env-files" -Recurse
Get-ChildItem $BackupDir -File -Recurse | Get-FileHash -Algorithm SHA256 |
  Select-Object Path,Hash | Export-Csv "$BackupDir/SHA256SUMS.csv" -NoTypeInformation
docker compose @ComposeArgs up -d
docker compose @ComposeArgs ps
```

环境文件副本仍是明文暂存，必须使用组织批准的加密工具封装后再复制到备份介质，并限制读取权限；确认加密副本可解密后，才清理暂存明文。不要把备份目录放入 Git 工作区的提交范围。

备份窗口内后端、OnlyOffice 和 SeaweedFS 保持停止，数据库 dump 与对象卷之间不会产生新的文件写入。需要更低停机时间时，应使用支持时间点恢复的 PostgreSQL 和具备快照一致性的外部 S3，而不是分别做无协调的热备。

## 恢复到空环境

优先在隔离主机或独立 Compose project 中演练，不要直接覆盖唯一生产副本。确认 `postgres.dump`、`seaweeddata.tgz` 和密钥备份的 SHA-256 后，再执行恢复。

先启动空 PostgreSQL，复制并恢复 dump：

```powershell
docker compose @ComposeArgs up -d postgres
docker cp "$BackupDir/postgres.dump" bcd-postgres:/tmp/better-cloud-drive.dump
docker exec bcd-postgres psql -U bcd_user -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'better_cloud_drive' AND pid <> pg_backend_pid();"
docker exec bcd-postgres dropdb -U bcd_user --if-exists better_cloud_drive
docker exec bcd-postgres createdb -U bcd_user better_cloud_drive
docker exec bcd-postgres pg_restore -U bcd_user -d better_cloud_drive --no-owner --no-privileges /tmp/better-cloud-drive.dump
```

恢复 SeaweedFS 前必须确认目标 volume 是空环境。下面的清理命令会永久删除目标卷现有内容：

```powershell
docker compose @ComposeArgs stop seaweedfs
$SeaweedVolume = docker inspect bcd-seaweedfs --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
docker run --rm -v "${SeaweedVolume}:/target" -v "${BackupDir}:/backup:ro" alpine:3.22 sh -c "find /target -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar xzf /backup/seaweeddata.tgz -C /target"
```

如需恢复 `onlyofficedata`，使用相同方式恢复对应 volume。随后恢复与备份时一致的密钥和地址配置，再启动全栈：

```powershell
docker compose @ComposeArgs up -d --build
docker compose @ComposeArgs ps
```

## 恢复验收

1. `docker compose ps` 中 Postgres、SeaweedFS、OnlyOffice、后端和前端为 `healthy`。
2. `flyway_schema_history` 最新迁移成功，团队、文件和版本记录数量与备份清单一致。
3. 抽查个人文件和团队文件下载，内容哈希与备份前一致。
4. 抽查一个有历史版本的团队文档：当前版本可预览，旧版本可下载，具备写权限的用户可恢复旧版本。
5. 抽查归档团队、软删除团队和 `purge_after`，确认恢复后台显示的状态和最近审计正确。
6. 运行后端黑盒和 OnlyOffice 保存验证。

```powershell
docker exec bcd-postgres psql -U bcd_user -d better_cloud_drive -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
docker exec bcd-postgres psql -U bcd_user -d better_cloud_drive -c "SELECT count(*) AS teams FROM teams; SELECT count(*) AS files FROM files; SELECT count(*) AS versions FROM file_versions;"
docker exec bcd-postgres psql -U bcd_user -d better_cloud_drive -c "SELECT id, name, archived_at, deleted_at, purge_after FROM teams WHERE archived_at IS NOT NULL OR deleted_at IS NOT NULL ORDER BY id;"
.\scripts\verify-all.ps1 -Module backend,blackbox,office -SkipDockerBuild
```

至少每季度做一次隔离恢复演练，并记录恢复集标识、备份结束时间、恢复开始/结束时间、实际 RPO/RTO、抽查文件、版本数量和失败项。优先使用独立主机或独立 Docker daemon；当前 Compose 固定了容器名，因此仅更换 project name 不能在同一 daemon 上与生产栈安全并行。只有成功恢复并通过抽查的备份才算可用备份。

自动化系统可以执行备份、加密、校验、异地复制、保留清理和失败告警，但不得自动执行生产恢复、删除唯一副本或清空目标卷。恢复必须由授权人员确认精确目标和恢复点。
