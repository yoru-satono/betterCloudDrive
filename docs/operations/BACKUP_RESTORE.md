# 团队数据备份与恢复

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

## 一致性备份

以下示例用于 Compose 单机部署。先创建备份目录并暂停所有写入来源：

```powershell
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupDir = (New-Item -ItemType Directory -Path "backups/$Stamp").FullName
docker compose stop frontend promtail onlyoffice-documentserver backend
```

备份 PostgreSQL：

```powershell
docker compose exec -T postgres pg_dump -U bcd_user -d better_cloud_drive --format=custom --file=/tmp/better-cloud-drive.dump
docker cp bcd-postgres:/tmp/better-cloud-drive.dump "$BackupDir/postgres.dump"
docker compose exec -T postgres rm -f /tmp/better-cloud-drive.dump
```

停止 SeaweedFS 后冷备对象卷。通过容器挂载信息获取实际 volume 名，避免依赖 Compose 项目前缀：

```powershell
docker compose stop seaweedfs
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
Copy-Item .env "$BackupDir/root.env"
Copy-Item config/env-files "$BackupDir/env-files" -Recurse
Get-ChildItem $BackupDir -File | Get-FileHash -Algorithm SHA256 |
  Select-Object Path,Hash | Export-Csv "$BackupDir/SHA256SUMS.csv" -NoTypeInformation
docker compose up -d
docker compose ps
```

备份窗口内后端、OnlyOffice 和 SeaweedFS保持停止，数据库 dump 与对象卷之间不会产生新的文件写入。需要更低停机时间时，应使用支持时间点恢复的 PostgreSQL 和具备快照一致性的外部 S3，而不是分别做无协调的热备。

## 恢复到空环境

优先在隔离主机或独立 Compose project 中演练，不要直接覆盖唯一生产副本。确认 `postgres.dump`、`seaweeddata.tgz` 和密钥备份的 SHA-256 后，再执行恢复。

先启动空 PostgreSQL，复制并恢复 dump：

```powershell
docker compose up -d postgres
docker cp "$BackupDir/postgres.dump" bcd-postgres:/tmp/better-cloud-drive.dump
docker exec bcd-postgres psql -U bcd_user -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'better_cloud_drive' AND pid <> pg_backend_pid();"
docker exec bcd-postgres dropdb -U bcd_user --if-exists better_cloud_drive
docker exec bcd-postgres createdb -U bcd_user better_cloud_drive
docker exec bcd-postgres pg_restore -U bcd_user -d better_cloud_drive --no-owner --no-privileges /tmp/better-cloud-drive.dump
```

恢复 SeaweedFS 前必须确认目标 volume 是空环境。下面的清理命令会永久删除目标卷现有内容：

```powershell
docker compose stop seaweedfs
$SeaweedVolume = docker inspect bcd-seaweedfs --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
docker run --rm -v "${SeaweedVolume}:/target" -v "${BackupDir}:/backup:ro" alpine:3.22 sh -c "find /target -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar xzf /backup/seaweeddata.tgz -C /target"
```

如需恢复 `onlyofficedata`，使用相同方式恢复对应 volume。随后恢复与备份时一致的密钥和地址配置，再启动全栈：

```powershell
docker compose up -d --build
docker compose ps
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

至少每季度做一次隔离恢复演练，并记录备份时间、恢复耗时、抽查文件、版本数量和失败项。只有成功恢复并通过抽查的备份才算可用备份。
