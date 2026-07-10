# 数据库查询性能

## 团队核心查询

`V14__team_query_indexes.sql` 针对当前 repository 生成的查询补充以下索引：

| 查询 | 索引 |
|------|------|
| 团队目录按文件类型、名称排序 | `idx_files_team_parent_listing` |
| 审计日志按操作者筛选 | `idx_team_operation_logs_team_actor_created` |
| 审计日志按动作筛选 | `idx_team_operation_logs_team_action_created` |
| 审计日志按目标类型筛选 | `idx_team_operation_logs_team_target_type_created` |
| 团队活动或审计结果筛选 | `idx_team_operation_logs_team_result_created` |
| 通知按类型筛选 | `idx_notifications_user_type_created` |
| 未读通知分页 | `idx_notifications_user_unread_created` |

团队文件权限没有新增索引。现有 `idx_team_file_permissions_file`、`uk_team_file_permissions_user` 和 `uk_team_file_permissions_role` 已覆盖权限列表及精确主体查询。

## EXPLAIN 验证

脚本会在事务内创建临时表并生成 10 万至 20 万行测试数据，不修改业务表：

```powershell
Get-Content -Raw scripts/explain-team-core.sql |
  docker exec -i bcd-postgres psql -U bcd_user -d better_cloud_drive
```

输出应包含脚本中标注的索引名，且核心分页查询不应出现业务表全表顺序扫描。迁移应用后，可用以下命令检查生产表索引：

```powershell
docker exec bcd-postgres psql -U bcd_user -d better_cloud_drive `
  -c "SELECT tablename, indexname FROM pg_indexes WHERE indexname LIKE 'idx_%team%' OR indexname LIKE 'idx_notifications_user_%' ORDER BY tablename, indexname;"
```

## 慢查询记录

Compose 默认将 PostgreSQL `log_min_duration_statement` 设置为 `500ms`。通过根目录 `.env` 的 `POSTGRES_LOG_MIN_DURATION_MS` 调整阈值，设置为 `-1` 可关闭。查看近期慢查询：

```powershell
docker compose logs --since 30m postgres | Select-String "duration:"
```

排查时先记录 SQL、参数规模和执行计划，再决定是否新增索引。不要为低频查询添加与现有索引前缀重复的索引。
