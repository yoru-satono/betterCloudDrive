# 生产升级与回滚

本文定义单机 Compose 部署的发布、数据库迁移判断和回滚边界。当前部署不是滚动发布；每次升级都应安排维护窗口，并先阅读[生产部署](PRODUCTION_DEPLOYMENT.md)和[备份恢复](BACKUP_RESTORE.md)。

## 发布记录

每次发布至少记录：

| 项目 | 示例 |
|------|------|
| 发布标识 | Git tag 或完整 commit SHA |
| Compose project name | 已有容器的 `com.docker.compose.project` 标签 |
| 配置版本 | 加密配置库或变更单版本，不记录明文密钥 |
| 基础镜像变化 | PostgreSQL、Redis、RabbitMQ、SeaweedFS、OnlyOffice、观测组件 |
| 数据库变化 | 新增的 Flyway 版本及其向后兼容结论 |
| 备份恢复点 | PostgreSQL、对象存储和密钥备份标识 |
| 验收结果 | 健康、登录、上传下载、队列、Office、告警 |

不要直接在长期工作目录里执行不明确版本的 `git pull` 后上线。生产应从已评审的 tag 或 commit 构建，并保留上一版本源码或应用镜像，以便复现。

## 变更分级

| 变更 | 最低处理 |
|------|----------|
| 仅文案或前端静态资源 | 构建前端，检查 API 兼容和缓存 |
| 后端代码，无数据库迁移 | 构建后端，运行后端/blackbox 验证 |
| 新增 Flyway 迁移 | 一致性备份、隔离恢复演练、旧应用兼容性判断 |
| 基础组件镜像升级 | 查阅对应组件升级路径，单独备份其持久化数据 |
| OnlyOffice 升级 | 验证 JWT、内容地址、回调和真实保存流程 |
| 密钥或凭据变更 | 按[密钥轮换](SECRET_ROTATION.md)独立执行，不与普通版本升级混在一起 |

当前仓库没有向下迁移脚本。只要新版本执行了破坏性数据库迁移，就不能假设重新启动旧后端等同于回滚。

## 升级前检查

1. 阅读从当前版本到目标版本的代码、Compose、环境模板和 Flyway 差异。
2. 确认目标构建不包含生产不应执行的种子数据；当前 `R__development_data.sql` 是明确的上线阻断项。
3. 执行与修改范围匹配的验证，至少包含后端、blackbox、Web 和 Office 关键路径。
4. 用生产同版本数据的隔离副本启动目标版本，记录 Flyway 执行时间和兼容性。
5. 完成一致性备份并验证校验和；未经恢复演练的备份不能作为唯一回滚保障。
6. 冻结配置变更，通知维护窗口和回滚决策人。

固定 Compose 参数，必须与现有部署使用相同 project name：

```powershell
$ComposeProject = 'betterclouddrive' # 替换为现有部署实际值
$ComposeArgs = @(
  '--project-name', $ComposeProject,
  '--env-file', '.env.production',
  '-f', 'docker-compose.yml',
  '-f', 'docker-compose.production.yml'
)
docker compose @ComposeArgs config --quiet
```

启用生产观测 overlay 的部署应把它追加到 `$ComposeArgs`，后续所有命令保持完全相同的文件集合。

## 升级步骤

记录升级前状态：

```powershell
$Release = git rev-parse HEAD
docker compose @ComposeArgs ps
docker compose @ComposeArgs images | Tee-Object -FilePath "release-$Release-compose-images.txt"
docker image inspect betterclouddrive-backend --format '{{.Id}}' | Tee-Object -FilePath "release-$Release-backend-image.txt"
docker compose @ComposeArgs exec -T postgres psql -U bcd_user -d better_cloud_drive -c "SELECT installed_rank, version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank;" | Tee-Object -FilePath "release-$Release-flyway-before.txt"
```

在维护窗口停止写入入口，构建目标版本并启动：

```powershell
docker compose @ComposeArgs stop frontend onlyoffice-documentserver backend
docker compose @ComposeArgs build --pull backend frontend
docker compose @ComposeArgs up -d
docker compose @ComposeArgs ps
```

观察后端启动和迁移：

```powershell
docker compose @ComposeArgs logs --tail 300 backend
docker compose @ComposeArgs exec -T postgres psql -U bcd_user -d better_cloud_drive -c "SELECT installed_rank, version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank;"
```

## 升级验收

按顺序检查：

1. 所有必需容器达到预期状态，后端和前端健康检查通过。
2. 外部 HTTPS 首页、API、WebDAV、OnlyOffice 和 Grafana 路径可达。
3. 登录和 token 刷新正常；普通文件和团队文件各完成一次上传下载。
4. RabbitMQ ready、in-flight、数据库 pending 和死信没有持续增长。
5. Office 文件能打开、修改、关闭并形成新版本。
6. Prometheus 数据保持新鲜，没有新的服务不可用、存储失败或数据库池告警。
7. 在维护记录中写入实际停机时间和决定继续或回滚的负责人。

## 回滚决策

| 情况 | 处理 |
|------|------|
| 新版本未执行数据库迁移 | 切回上一版本源码/镜像并重建应用，保留当前数据 |
| 只执行了可证明向后兼容的增量迁移 | 可暂时切回旧应用，但必须有隔离验证证据 |
| 执行了删除、重命名、不可逆数据转换 | 停止写入，恢复升级前 PostgreSQL 与对象存储一致性备份 |
| 基础组件数据格式已升级 | 按组件官方降级限制恢复其升级前数据，不直接启动旧镜像读取新格式 |
| 配置或密钥不匹配 | 恢复上一配置版本；不要通过随机尝试多个密钥继续写入 |

### 应用级回滚

仅在数据库仍兼容时，从上一版本源码目录使用同一 project name 和同一组 Compose 文件构建：

```powershell
docker compose @ComposeArgs stop frontend onlyoffice-documentserver backend
docker compose @ComposeArgs build backend frontend
docker compose @ComposeArgs up -d
docker compose @ComposeArgs ps
```

如果上一版本镜像已单独保存和标记，应修改受控 Compose override 指向该不可变镜像；不要依赖本地 `latest` 或无标签构建缓存判断版本。

### 数据级回滚

数据级回滚意味着丢弃升级后的写入，只能在负责人确认数据损失窗口后执行：

1. 停止前端、后端和 OnlyOffice，阻止新写入。
2. 保存故障版本日志、Flyway 历史和数据库快照用于分析。
3. 在隔离环境恢复升级前 PostgreSQL、对象存储和同版本密钥。
4. 用上一应用版本完成恢复验收。
5. 切换生产流量；不要直接在唯一生产卷上边试边清理。

具体恢复命令见[备份与恢复](BACKUP_RESTORE.md)。

## 失败收尾

无论升级成功还是回滚，都记录根因、受影响时间、数据损失判断、执行命令、使用的恢复点和遗留事项。升级失败后不要立即删除新镜像、日志或故障数据库；先完成取证，并为再次发布创建新的发布记录。
