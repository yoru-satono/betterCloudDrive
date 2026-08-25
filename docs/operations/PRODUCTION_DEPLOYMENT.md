# 生产部署

本文面向使用仓库自带 Docker Compose 在单台 Linux 主机部署 Better Cloud Drive 的维护者。精确变量含义见[配置参考](../reference/CONFIGURATION.md)；本文只定义上线顺序、暴露边界和验收条件。

## 当前能力边界

- `docker-compose.production.yml` 是基础 Compose 的安全 overlay，不能单独运行。
- 应用只有一个后端和一个前端实例，容器名固定；重建期间会短暂中断，不是零停机部署。
- PostgreSQL、Redis、RabbitMQ、SeaweedFS、Mailpit 和后端不发布宿主机端口；前端与 OnlyOffice 只绑定 `127.0.0.1`，由宿主机 Caddy 提供 HTTPS。
- Compose 在本机从源码构建后端和前端，没有镜像仓库、签名、自动升级或自动回滚能力。
- 数据库由后端启动时执行 Flyway。当前只有完整基线 `V1__schema.sql` 和重复迁移 `R__development_data.sql`，没有向下迁移。

## 上线前阻断项

满足以下条件前不要开放公网流量：

1. 选择并记录固定 Compose project name。新部署建议使用 `betterclouddrive`；已有部署必须先读取当前容器标签，不能随意改名，否则会连接到另一组命名卷。
2. 处理 `R__development_data.sql` 中的 `chain`、`admin` 开发账号。该迁移当前会随生产后端执行；正式发布物必须移除开发种子数据或实现明确的生产禁用机制，并确认生产库不存在这些默认账号。
3. 替换所有示例密码和密钥，至少包括 PostgreSQL、RabbitMQ、S3、JWT access/refresh、分享密码、OnlyOffice、Grafana Auth Proxy 和 Grafana 管理员密码。
4. 配置真实 SMTP。基础 Compose 仍会启动 Mailpit；如果生产需要外发邮件，后端 `mail.env` 必须指向受控 SMTP，而不是默认 `mailpit:1025`。
5. 已建立并演练[备份与恢复](BACKUP_RESTORE.md)，且本次发布的数据库和对象存储备份处于同一恢复点。
6. 域名已经解析到主机，防火墙只公开 TCP 80/443；SSH 或其他运维端口限制来源地址。

查看现有部署使用的 project name：

```powershell
docker inspect bcd-postgres --format '{{ index .Config.Labels "com.docker.compose.project" }}'
```

## 准备配置

仅在全新部署目录中从模板创建不提交到 Git 的实际配置。下面的命令遇到已有文件会停止，避免覆盖生产值：

```powershell
if (Test-Path .env.production) { throw '.env.production 已存在，请人工审查而非覆盖' }
Copy-Item .env.production.example .env.production
Get-ChildItem config/env-files -Filter '*.env.example' | ForEach-Object {
  $Destination = $_.FullName -replace '\.example$',''
  if (Test-Path $Destination) { throw "$Destination 已存在，请人工审查而非覆盖" }
  Copy-Item $_.FullName $Destination
}
```

`.env.production` 只负责 Compose 插值和生产公开地址，后端依赖的数据库、队列、对象存储、安全、邮件和观测值仍来自 `config/env-files/*.env`。两处出现的 `ONLYOFFICE_JWT_SECRET` 必须保持一致。

将 `TRUSTED_PROXY_CIDRS` 收窄为实际反向代理或容器网络。只有直接来源命中该列表时，后端才采用 `X-Forwarded-For` 或 `X-Real-IP`；配置过宽会允许同网段不受信主机影响匿名认证限流。默认限流为普通 API 每用户 300 次/60 秒、匿名认证每 IP 30 次/60 秒、上传控制每用户 120 次/60 秒、上传分片每用户 600 次/60 秒，可按容量测试结果显式覆盖。

为后续命令固定同一组 Compose 参数：

```powershell
$ComposeProject = 'betterclouddrive' # 已有部署改为实际 project name
$ComposeArgs = @(
  '--project-name', $ComposeProject,
  '--env-file', '.env.production',
  '-f', 'docker-compose.yml',
  '-f', 'docker-compose.production.yml'
)
```

如果启用生产告警配置，再追加观测 overlay：

```powershell
$ComposeArgs += @('-f', 'docker-compose.production-observability.yml')
```

先渲染配置，不启动容器：

```powershell
docker compose @ComposeArgs config --quiet
docker compose @ComposeArgs config --services
```

渲染失败通常表示 `.env.production` 缺少 OnlyOffice、公开地址或 Alertmanager 配置。渲染后的完整配置可能包含敏感值，不要把 `docker compose config` 输出提交到 Git 或附在公开工单中。

## HTTPS 接入

`deploy/Caddyfile.production.example` 定义两个外部域名：

| 域名 | 回环目标 | 用途 |
|------|----------|------|
| `BCD_PUBLIC_DOMAIN` | `127.0.0.1:3000` | Web、API、WebDAV、Grafana 子路径 |
| `BCD_ONLYOFFICE_DOMAIN` | `127.0.0.1:8082` | OnlyOffice 浏览器 SDK 和编辑器 |

将模板安装到宿主机 Caddy，并在 Caddy 服务环境中设置两个域名。确认 `APP_PUBLIC_URL` 和 `ONLYOFFICE_PUBLIC_URL` 都是对应的 HTTPS 地址。不要把后端 8080、管理端口 8081 或基础设施端口直接代理到公网。

## 首次启动

先拉取固定基础镜像并构建应用，再启动完整服务：

```powershell
docker compose @ComposeArgs pull --ignore-buildable
docker compose @ComposeArgs build --pull backend frontend
docker compose @ComposeArgs up -d
docker compose @ComposeArgs ps
```

`pull` 不会替代变更评审；升级 PostgreSQL、Redis、RabbitMQ、SeaweedFS、OnlyOffice 或观测组件前仍需单独检查兼容性。

后端首次启动会执行 Flyway。不要在其迁移期间反复重启；如果健康检查失败，先查看日志和 `flyway_schema_history`：

```powershell
docker compose @ComposeArgs logs --tail 200 backend
docker compose @ComposeArgs exec -T postgres psql -U bcd_user -d better_cloud_drive -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

## 上线验收

至少完成以下检查：

```powershell
docker compose @ComposeArgs ps
Invoke-WebRequest "https://drive.example.com/healthz" -UseBasicParsing
Invoke-WebRequest "https://office.example.com/healthcheck" -UseBasicParsing
```

- 未登录访问应用首页正常；未认证 API 返回 401，而不是 502 或静态页。
- 注册验证码能通过真实 SMTP 到达；生产库不存在开发种子账号。
- 普通文件上传、下载和 Range 请求正常。
- 连续触发匿名认证限流时返回 HTTP 429、稳定业务错误和 `Retry-After`；已登录用户互不共享普通 API 与上传额度。
- 创建并保存一份 Office 文档，OnlyOffice 回调成功。
- 管理员可从应用入口进入 `/grafana/`，直接绕过应用身份访问被拒绝。
- PostgreSQL、对象存储、队列、日志和告警均出现当前部署数据。
- 记录 Git commit、Compose project name、镜像 ID、配置版本、上线时间和验收人。

## 停止与重新启动

维护窗口只停止应用写入源时，优先停止前端、后端和 OnlyOffice；不要使用 `down -v`：

```powershell
docker compose @ComposeArgs stop frontend backend onlyoffice-documentserver
docker compose @ComposeArgs up -d
```

`docker compose down` 会删除容器和网络，但正常情况下保留命名卷；`down -v` 会删除数据库、对象和其他命名卷，生产运维流程禁止使用。
