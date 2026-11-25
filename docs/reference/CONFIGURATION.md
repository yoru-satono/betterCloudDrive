# Better Cloud Drive 配置与部署文档

存储巡检使用 `drive.inspection.*` 配置，默认每日 02:15（Asia/Shanghai）、分页 1000、稳定窗口 10 分钟、对象保护 24 小时、单次上限 2 小时。完整配置、源时间时区和计账切换 gate 见[存储巡检](../operations/STORAGE_INSPECTIONS.md#配置与资源边界)。已有数据库升级需要显式离线迁移，不应通过环境开关跳过。

## 适用范围

本文档说明 Better Cloud Drive 的配置文件位置、环境变量含义，以及 Docker Compose 和生产环境拆分部署的配置约束。生产上线顺序、升级回滚、备份和密钥轮换分别见[运维手册](../operations/README.md)。

项目当前包含这些运行组件：

| 组件 | 说明 |
|------|------|
| `backend` | Spring Boot 后端 API、WebDAV 代理、上传下载、审计与系统日志查询 |
| `frontend` | Vue Web 前端，Docker 镜像中由 Nginx 托管 |
| `postgres` | PostgreSQL 主数据库 |
| `redis` | Redis，用于缓存和服务状态 |
| `rabbitmq` | RabbitMQ，用于团队通知和后台任务消息队列 |
| `seaweedfs` | S3 兼容对象存储，当前 Docker Compose 使用 SeaweedFS |
| `mailpit` | 本地开发邮件服务 |
| `loki` | 系统日志存储 |
| `alloy` | 后端日志采集器，持久保存读取位置 |
| `prometheus` | 应用、RabbitMQ 和关键 HTTP 端点指标 |
| `alertmanager` | 核心告警的邮件和 Webhook 路由 |
| `blackbox-exporter` | 前端、后端、SeaweedFS S3 和 OnlyOffice 探活 |
| `grafana` | 指标与日志查询界面，通过前端 Nginx 同域代理并由后端管理员身份鉴权 |
| `clickhouse`、`warehouse-airflow-*` | 可选业务数仓，通过 `docker-compose.warehouse.yml` 叠加启动；[部署与口径](../../warehouse/README.md) |

## 配置文件位置

| 路径 | 用途 |
|------|------|
| `.env` | 本地 Compose 插值：宿主机端口、共享应用地址、观测选项、限流和前端构建参数 |
| `.env.example` | 根 `.env` 模板 |
| `.env.production` | 本机忽略的生产 Compose 插值实际值 |
| `.env.production.example` | 生产 Compose 插值变量模板，不包含真实密钥 |
| `docker-compose.yml` | 完整基础服务拓扑 |
| `docker-compose.production.yml` | 生产端口收紧 overlay |
| `docker-compose.production-observability.yml` | 生产观测配置 overlay |
| `docker-compose.diagnostics.yml` | 将诊断端口绑定到宿主机回环地址的 overlay |
| `docker-compose.warehouse.yml` | 独立 ClickHouse、Airflow 与一次性最小权限初始化 |
| `config/env-files/warehouse*.env` | 数仓加工、数据库、一次性初始化与后端只读身份；从对应 example 生成，不纳入 Git |
| `deploy/Caddyfile.production.example` | 宿主机 Caddy HTTPS 双域名反向代理示例 |
| `config/env-files/database.env` | PostgreSQL、Redis 连接和容器变量 |
| `config/env-files/rabbitmq.env` | RabbitMQ 容器与后端连接变量 |
| `config/env-files/storage.env` | SeaweedFS / S3 存储变量 |
| `config/env-files/security.env` | JWT 和分享密码加密密钥 |
| `config/env-files/backend.env` | 后端日志、OnlyOffice、队列和后台任务变量 |
| `config/env-files/frontend.env` | 前端 Nginx 代理、Web/KMP 移动端构建与测试地址 |
| `config/env-files/mail.env` | 邮件服务变量 |
| `config/env-files/observability.env` | Prometheus、Loki、Alloy、Alertmanager、Grafana 和审计日志变量 |
| `config/redis/redis.conf` | Redis 配置 |
| `backend/betterclouddrive-start/src/main/resources/application.yml` | 后端默认配置 |
| `backend/betterclouddrive-start/src/main/resources/application-prod.yml` | 后端生产 profile 配置 |
| `frontend/nginx.conf` | 前端容器 Nginx 模板 |
| `observability/*` | Prometheus、Loki、Alloy、Alertmanager、Blackbox 和 Grafana provisioning 配置 |

`docker-compose.yml` 会自动读取根目录 `.env` 进行变量替换。服务运行时变量通过各服务的 `env_file` 注入。

## Docker Compose 部署

### 1. 准备配置

首次运行时按 [Quickstart 的本地配置步骤](../getting-started/QUICKSTART.md#1-创建本地配置)只创建缺失的根 `.env` 和八个模块 env 文件。已有实际配置不得用 example 模板覆盖；本页以下各表负责解释完整变量含义，不再维护第二份复制命令清单。

生产或长期运行前必须替换这些密钥：

| 变量 | 要求 |
|------|------|
| `POSTGRES_PASSWORD` / `DB_PASSWORD` | 强随机数据库密码，两者应保持一致 |
| `STORAGE_S3_SECRET_KEY` / `S3_SECRET_KEY` | 强随机对象存储密钥 |
| `RABBITMQ_DEFAULT_PASS` / `RABBITMQ_PASSWORD` | 强随机 RabbitMQ 密码；初始化值与后端连接值应保持一致 |
| `JWT_ACCESS_SECRET` | 独立随机 Base64 密钥，建议 32 字节以上 |
| `JWT_REFRESH_SECRET` | 独立随机 Base64 密钥，不能和 access secret 相同 |
| `SHARE_PASSWORD_ENCRYPTION_KEY` | 独立随机 Base64 32 字节密钥 |
| `ONLYOFFICE_JWT_SECRET` | 独立随机密钥；根 Compose 插值值、后端值和 Document Server 值必须一致 |
| `GRAFANA_AUTH_PROXY_SECRET` | 独立随机密钥，用于签名 Grafana 短期会话 cookie |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana 管理员密码，虽然 Grafana 对外通过后端管理员鉴权，也应替换默认值 |

生成 32 字节 Base64 密钥示例：

```bash
openssl rand -base64 32
```

### 2. 启动服务

```powershell
docker compose up -d --build
```

查看状态：

```powershell
docker compose ps
docker compose logs -f backend
```

`postgres`、`redis`、`rabbitmq`、`seaweedfs`、`mailpit`、`onlyoffice-documentserver`、`blackbox-exporter`、`backend` 和 `frontend` 都定义了容器健康检查。后端只会在数据库、Redis、RabbitMQ、对象存储、邮件服务和 OnlyOffice 健康后启动；Prometheus 会等待后端和 Blackbox Exporter 健康，前端只会在后端健康后启动。

| 服务 | 容器内健康检查 |
|------|----------------|
| PostgreSQL | `pg_isready` |
| Redis | `redis-cli ping` |
| RabbitMQ | `rabbitmq-diagnostics -q ping` |
| SeaweedFS | Master `/cluster/status` |
| Mailpit | `/api/v1/info` |
| OnlyOffice | `/healthcheck` |
| Blackbox Exporter | `/-/healthy` |
| 后端 | `/actuator/health` |
| 前端 | `/healthz` |

默认入口：

| 服务 | 默认地址 |
|------|----------|
| Web 前端 | `http://localhost:3000` |
| 后端 API | `http://localhost:8080/api/v1` |
| WebDAV | `http://localhost:3000/webdav/` 或后端直连 `/webdav/` |
| Mailpit | `http://localhost:8025` |
| Grafana | 通过管理后台日志中心按钮进入，同域路径 `/grafana/` |
| Loki | `http://localhost:3100` |

Grafana 不再直接暴露宿主机端口。前端 Nginx 会对 `/grafana/` 执行 `auth_request`，调用后端 `/api/v1/grafana/auth`。只有当前 Better Cloud Drive 用户仍是系统管理员时才会放行。

### 3. 常用维护命令

重建应用镜像：

```powershell
docker compose up -d --build backend frontend
```

重启单个服务：

```powershell
docker compose up -d redis
docker compose restart backend
```

查看 Redis 是否可用：

```powershell
docker compose exec redis redis-cli ping
```

仅在确认本地开发数据全部可以丢弃时，才可清空并重建数据卷。执行前应核对当前 Compose project 和命名卷；以下命令会永久删除数据库、Redis、RabbitMQ、对象存储和可观测性数据，生产环境禁止使用：

```powershell
docker compose down -v
docker compose up -d --build
```

## 生产环境拆分部署

生产环境可以继续使用 Docker Compose，也可以将各组件拆到不同主机、Kubernetes、托管数据库、托管对象存储和独立日志平台。拆分部署时建议遵循下列拓扑。

### 单机 Compose 安全部署

基础 `docker-compose.yml` 保持面向本地开发，会发布数据库、中间件和调试端口。生产环境必须叠加 `docker-compose.production.yml`，不要单独使用基础配置：

```bash
cp .env.production.example .env.production
# 编辑 .env.production，替换域名、OnlyOffice JWT 和告警配置路径。

docker compose \
  --env-file .env.production \
  -f docker-compose.yml \
  -f docker-compose.production.yml \
  -f docker-compose.production-observability.yml \
  up -d --build
```

生产 overlay 会移除 PostgreSQL、Redis、RabbitMQ、SeaweedFS、Mailpit 和后端的全部宿主机端口，只保留两个回环地址：

| 宿主机地址 | 用途 | 公网入口 |
|------|------|------|
| `127.0.0.1:3000` | 前端及同域 API、WebDAV、Grafana 代理 | `https://drive.example.com` |
| `127.0.0.1:8082` | OnlyOffice Document Server | `https://office.example.com` |

将 `deploy/Caddyfile.production.example` 安装为宿主机 Caddy 配置，并在 Caddy 服务环境中设置 `BCD_PUBLIC_DOMAIN` 和 `BCD_ONLYOFFICE_DOMAIN`。两个域名必须解析到部署主机；Caddy 对公网监听 TCP 80/443 并自动管理 HTTPS 证书。防火墙只应放行 80/443，以及限制来源地址的运维端口。

生产环境不要叠加 `docker-compose.diagnostics.yml`。如需临时诊断，优先使用 `docker compose exec`；确需发布诊断端口时，应限定在维护窗口，并在完成后移除 diagnostics overlay 并重建相关容器。

### 推荐拓扑

| 层级 | 组件 | 生产建议 |
|------|------|----------|
| 接入层 | Nginx / Ingress / 负载均衡 | 统一 HTTPS 入口，代理 `/api/`、`/webdav/`、`/grafana/` |
| 前端 | 静态文件服务 | 可用项目 `frontend` 镜像，也可将 `dist` 部署到 Nginx/CDN |
| 后端 | Spring Boot JAR 或容器 | 至少 1 个实例；多实例时共享同一 PostgreSQL、Redis、RabbitMQ 和对象存储 |
| 数据库 | PostgreSQL | 使用托管 PostgreSQL 或独立高可用集群，开启备份 |
| 缓存 | Redis | 使用独立 Redis，建议开启持久化或托管服务 |
| 消息队列 | RabbitMQ | 使用持久化队列、Publisher Confirm 和 DLQ |
| 对象存储 | S3 兼容存储 | 可用 SeaweedFS、MinIO、AWS S3、阿里云 OSS S3 兼容网关等 |
| 日志 | Loki / 其他日志平台 | Grafana Alloy 采集后端 JSON 日志 |
| 请求关联 | 进程内 Micrometer/OpenTelemetry | 不导出完整 Trace，以 requestId/traceId 关联日志与审计 |
| 看板 | Grafana | 推荐仍通过前端同域路径和后端管理员鉴权访问 |

### 后端单独部署

构建 JAR：

```powershell
cd backend
mvn -B -pl betterclouddrive-start -am package -Dmaven.test.skip=true
```

运行 JAR：

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_HOST=postgres.example.internal \
DB_PORT=5432 \
DB_NAME=better_cloud_drive \
DB_USER=bcd_user \
DB_PASSWORD='replace_me' \
REDIS_HOST=redis.example.internal \
REDIS_PORT=6379 \
STORAGE_S3_ENDPOINT=https://s3.example.com \
STORAGE_S3_ACCESS_KEY='replace_me' \
STORAGE_S3_SECRET_KEY='replace_me' \
JWT_ACCESS_SECRET='base64_secret' \
JWT_REFRESH_SECRET='another_base64_secret' \
SHARE_PASSWORD_ENCRYPTION_KEY='base64_32_bytes' \
GRAFANA_AUTH_PROXY_SECRET='random_secret' \
LOG_DIR=/var/log/cloud-drive \
java -jar betterclouddrive-start/target/betterclouddrive-start.jar
```

容器部署时使用同样的环境变量，并挂载日志目录：

```bash
docker run -d \
  --name bcd-backend \
  --env-file config/env-files/database.env \
  --env-file config/env-files/storage.env \
  --env-file config/env-files/security.env \
  --env-file config/env-files/backend.env \
  --env-file config/env-files/observability.env \
  --env-file config/env-files/mail.env \
  -e SPRING_PROFILES_ACTIVE=prod \
  -v bcd-backendlogs:/var/log/cloud-drive \
  -p 8080:8080 \
  betterclouddrive-backend
```

### 前端单独部署

前端 Web 默认使用同域 `/api/v1` 访问 API，因此生产推荐把前端和后端放在同一域名下：

| 路径 | 代理目标 |
|------|----------|
| `/` | 前端静态文件 |
| `/api/` | 后端 `http://backend:8080/api/` |
| `/webdav/` | 后端 `http://backend:8080/webdav/` |
| `/grafana/` | Grafana `http://grafana:3000/`，并配置 `auth_request` |

如果前端和 API 不同域，需要在构建前设置：

| 构建变量 | 说明 |
|----------|------|
| `VITE_API_BASE_URL` | Web 前端 API 根地址，例如 `https://api.example.com/api/v1` |
| `VITE_WEB_BASE_URL` | 分享链接使用的 Web 根地址，例如 `https://drive.example.com` |

Qt 默认地址在宿主机启动进程中设置，不进入 Web 构建，也不自动读取仓库 `.env` 或 Compose 服务环境：

| 启动环境变量 | 说明 |
|----------|------|
| `BCD_DESKTOP_API_BASE` | Qt 默认 API 根地址，可由 `--api-base` 覆盖 |
| `BCD_DESKTOP_WEB_BASE` | Qt 默认 Web 根地址，可由 `--web-base` 覆盖 |

已保存的 Profile 连接设置优先于这些启动默认值。旧 `VITE_DESKTOP_API_BASE_URL`、`VITE_DESKTOP_WEB_BASE_URL` 已失效，不应继续配置。

构建：

```bash
cd frontend
pnpm install --frozen-lockfile
VITE_API_BASE_URL=https://drive.example.com/api/v1 \
VITE_WEB_BASE_URL=https://drive.example.com \
pnpm run build
```

将 `frontend/dist` 部署到 Nginx 或静态托管服务。若要复用项目 Nginx 模板，需要提供 `FRONTEND_BACKEND_PROXY_BASE`，并确保 Nginx 支持 `auth_request` 模块。

### Grafana 同域鉴权

生产环境推荐保留当前设计：

1. 用户登录 Better Cloud Drive。
2. 系统管理员在管理后台点击 Grafana。
3. 前端调用后端 `POST /api/v1/admin/grafana/session`。
4. 后端确认用户是 `ROLE_ADMIN`，写入短期 HttpOnly cookie。
5. Nginx 对 `/grafana/` 发起 `auth_request /grafana-auth`。
6. `/grafana-auth` 转发到后端 `/api/v1/grafana/auth`。
7. 后端验证 cookie 和管理员身份，返回 `X-WEBAUTH-*` 头。
8. Nginx 将请求代理给 Grafana Auth Proxy。

创建 Grafana 会话和管理员修改语言偏好时，后端会使用 Auth Proxy 身份调用 Grafana 用户偏好 API。Better Cloud Drive 的 `zh-Hans`、`zh-CN`、`zh-SG` 都映射为 Grafana 的 `zh-Hans`，`en-US` 保持不变；Nginx 同时把映射后的语言作为 `Accept-Language` 传给 Grafana。同步失败只记录警告，不阻断 Grafana 访问。已经打开的 Grafana 页面需要刷新或重新打开才能重新加载对应语言资源。

关键变量：

| 变量 | 说明 |
|------|------|
| `GRAFANA_BASE_URL` | 后端生成 Grafana Explore 链接时使用的路径，默认 `/grafana` |
| `GRAFANA_AUTH_PROXY_SECRET` | 后端签名 Grafana 短期 cookie 的密钥 |
| `GRAFANA_AUTH_PROXY_TTL_SECONDS` | Grafana 短期 cookie 有效期，默认 300 秒 |
| `GRAFANA_INTERNAL_URL` | 后端同步 Grafana 用户偏好的容器内地址，Compose 默认 `http://grafana:3000` |
| `GRAFANA_LOCALE_SYNC_ENABLED` | 是否将管理员语言偏好同步到 Grafana，默认 `true` |
| `GRAFANA_LOCALE_SYNC_TIMEOUT_MS` | Grafana 偏好同步的连接和读取超时，默认 2000 毫秒 |
| `GF_SERVER_ROOT_URL` | Grafana 自身生成资源链接使用的外部根地址 |
| `GF_SERVER_SERVE_FROM_SUB_PATH` | Grafana 是否运行在子路径，当前为 `true` |
| `GF_AUTH_PROXY_ENABLED` | 启用 Grafana Auth Proxy |
| `GF_AUTH_PROXY_HEADER_NAME` | Grafana 从哪个请求头读取用户名，当前 `X-WEBAUTH-USER` |

如果 Grafana 不是 `/grafana/` 子路径，而是独立域名，需要重新设计 cookie 域、Nginx 鉴权路径和 `GRAFANA_BASE_URL`，不能只改 Grafana 端口。

## 环境变量详解

### 根目录 `.env`

这些变量由 Docker Compose 用于宿主机端口映射、共享应用地址、观测配置选择和前端镜像构建参数。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `HOST_POSTGRES_PORT` | `5432` | PostgreSQL 暴露到宿主机的端口 |
| `HOST_REDIS_PORT` | `6379` | Redis 暴露到宿主机的端口 |
| `HOST_RABBITMQ_PORT` | `5672` | RabbitMQ AMQP 端口 |
| `HOST_RABBITMQ_MANAGEMENT_PORT` | `15672` | RabbitMQ 管理界面端口 |
| `HOST_SEAWEEDFS_S3_PORT` | `8333` | SeaweedFS S3 API 宿主机端口 |
| `HOST_SEAWEEDFS_WEBDAV_PORT` | `8888` | SeaweedFS WebDAV 宿主机端口 |
| `HOST_SEAWEEDFS_MASTER_PORT` | `9333` | SeaweedFS Master UI/API 宿主机端口 |
| `HOST_MAILPIT_SMTP_PORT` | `1025` | Mailpit SMTP 宿主机端口 |
| `HOST_MAILPIT_UI_PORT` | `8025` | Mailpit Web UI 宿主机端口 |
| `HOST_LOKI_PORT` | `3100` | Loki 宿主机端口 |
| `HOST_PROMETHEUS_PORT` | `9090` | 诊断 overlay 将 Prometheus 绑定到回环地址时使用的宿主机端口 |
| `HOST_ALERTMANAGER_PORT` | `9093` | 诊断 overlay 将 Alertmanager 绑定到回环地址时使用的宿主机端口 |
| `HOST_BLACKBOX_EXPORTER_PORT` | `9115` | 诊断 overlay 将 Blackbox Exporter 绑定到回环地址时使用的宿主机端口 |
| `HOST_MANAGEMENT_PORT` | `8081` | 诊断 overlay 将生产 Actuator 管理端口绑定到回环地址时使用的宿主机端口 |
| `HOST_ALLOY_PORT` | `12345` | 诊断 overlay 将 Alloy 管理端口绑定到回环地址时使用的宿主机端口 |
| `HOST_BACKEND_PORT` | `8080` | 后端宿主机端口 |
| `HOST_FRONTEND_PORT` | `3000` | 前端宿主机端口 |
| `HOST_ONLYOFFICE_PORT` | `8082` | OnlyOffice Document Server 宿主机端口 |
| `APP_PUBLIC_URL` | `http://localhost:3000` | 邮件链接等服务端生成的应用公开根地址；生产必须使用外部 HTTPS 地址 |
| `MAIL_FROM` | `noreply@betterclouddrive.local` | 后端发送邮件时使用的 From 地址 |
| `POSTGRES_LOG_MIN_DURATION_MS` | `500` | PostgreSQL 慢查询日志阈值，毫秒；`-1` 表示关闭 |
| `ALERTMANAGER_CONFIG_FILE` | `./observability/alertmanager.yml` | 基础 Compose 挂载的 Alertmanager 配置文件 |
| `ALERTMANAGER_PRODUCTION_CONFIG_FILE` | `./observability/alertmanager-production.yml` | 生产观测 overlay 挂载的已审查 Alertmanager 配置文件 |
| `ALERTMANAGER_RETENTION_TIME` | `72h` | Alertmanager 状态数据保留时间 |
| `PROMETHEUS_ENVIRONMENT` | `development` | Prometheus 外部 `environment` 标签；生产 overlay 通常设为 `production` |
| `TRUSTED_PROXY_CIDRS` | `172.16.0.0/12` | 允许提供客户端转发地址的代理 CIDR 列表；应收窄为实际反向代理网络 |
| `DRIVE_RATE_LIMIT_DEFAULT_PERMITS` | `300` | 已登录用户的普通 API 在每个限流周期内允许的请求数；Compose 将其传给后端 |
| `DRIVE_RATE_LIMIT_DEFAULT_PERIOD_SECONDS` | `60` | 默认接口限流周期，单位秒 |
| `DRIVE_RATE_LIMIT_AUTH_PERMITS` | `30` | 登录、注册、验证码、刷新和密码恢复接口按客户端 IP 计算的许可数 |
| `DRIVE_RATE_LIMIT_AUTH_PERIOD_SECONDS` | `60` | 匿名认证接口限流周期，单位秒 |
| `DRIVE_RATE_LIMIT_UPLOAD_PERMITS` | `120` | 上传初始化、状态、完成和取消等控制请求按用户计算的许可数 |
| `DRIVE_RATE_LIMIT_UPLOAD_PERIOD_SECONDS` | `60` | 上传接口限流周期，单位秒 |
| `DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERMITS` | `600` | 上传分片请求按用户计算的许可数 |
| `DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERIOD_SECONDS` | `60` | 上传分片限流周期，单位秒 |
| `ONLYOFFICE_JWT_SECRET` | 本地开发密钥 | OnlyOffice Document Server 和后端共享的 JWT 密钥，生产必须替换 |
| `VITE_API_BASE_URL` | 空 | Web 前端构建时 API 根地址。空值表示使用 `/api/v1` |
| `VITE_WEB_BASE_URL` | 空 | Web 前端构建时 Web 根地址。空值表示使用当前 origin |

### `database.env`

| 变量 | 说明 |
|------|------|
| `POSTGRES_DB` | PostgreSQL 容器初始化数据库名 |
| `POSTGRES_USER` | PostgreSQL 容器初始化用户名 |
| `POSTGRES_PASSWORD` | PostgreSQL 容器初始化密码 |
| `POSTGRES_PORT` | PostgreSQL 容器内部端口标记，主要用于配置引用 |
| `DB_HOST` | 后端连接 PostgreSQL 的主机 |
| `DB_PORT` | 后端连接 PostgreSQL 的端口 |
| `DB_NAME` | 后端连接 PostgreSQL 的数据库名 |
| `DB_USER` | 后端连接 PostgreSQL 的用户名 |
| `DB_PASSWORD` | 后端连接 PostgreSQL 的密码 |
| `REDIS_PORT` | Redis 容器内部端口标记 |
| `REDIS_HOST` | 后端连接 Redis 的主机 |
| `SPRING_DATA_REDIS_HOST` | Spring Boot 兼容变量，目前主要保留给运行环境覆盖 |
| `SPRING_DATA_REDIS_PORT` | Spring Boot 兼容变量，目前主要保留给运行环境覆盖 |

注意：当前后端 `application.yml` 实际读取 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。如果 Redis 设置了密码，需要补充 `REDIS_PASSWORD`。

模板中的 `DATA_SOURCE_URI`、`DATA_SOURCE_USER`、`DATA_SOURCE_PASS` 和 `REDIS_ADDR` 当前没有 Compose、Spring 或脚本消费者，不属于有效运行配置。它们将在后续配置清理中删除，或在引入明确消费者后再纳入参考文档。

### `rabbitmq.env`

| 变量 | 说明 |
|------|------|
| `RABBITMQ_DEFAULT_USER` | RabbitMQ 官方镜像首次初始化时创建的管理员用户名 |
| `RABBITMQ_DEFAULT_PASS` | RabbitMQ 官方镜像首次初始化时创建的管理员密码 |
| `RABBITMQ_HOST` | 后端连接 RabbitMQ 的主机 |
| `RABBITMQ_PORT` | 后端连接 RabbitMQ 的 AMQP 端口 |
| `RABBITMQ_USERNAME` | 后端连接 RabbitMQ 的用户名，应与初始化用户或已有授权用户一致 |
| `RABBITMQ_PASSWORD` | 后端连接 RabbitMQ 的密码 |
| `RABBITMQ_VHOST` | 后端连接的 RabbitMQ virtual host，默认 `/` |

`RABBITMQ_DEFAULT_USER`、`RABBITMQ_DEFAULT_PASS` 只影响空 RabbitMQ 数据卷的首次初始化；已有 `rabbitmqdata` 卷不会因修改这两个变量而自动重建用户。

### `storage.env`

| 变量 | 说明 |
|------|------|
| `S3_ACCESS_KEY` | SeaweedFS S3 服务端访问 key |
| `S3_SECRET_KEY` | SeaweedFS S3 服务端密钥 |
| `STORAGE_S3_ENDPOINT` | 后端访问 S3 兼容存储的 endpoint |
| `STORAGE_S3_ACCESS_KEY` | 后端访问 S3 的 access key |
| `STORAGE_S3_SECRET_KEY` | 后端访问 S3 的 secret key |
| `SEAWEEDFS_S3_PORT` | SeaweedFS S3 容器端口标记 |
| `SEAWEEDFS_WEBDAV_PORT` | SeaweedFS WebDAV 容器端口标记 |
| `SEAWEEDFS_MASTER_PORT` | SeaweedFS Master 容器端口标记 |

如果生产使用 AWS S3、MinIO 或其他 S3 兼容服务，通常只需要替换 `STORAGE_S3_ENDPOINT`、`STORAGE_S3_ACCESS_KEY`、`STORAGE_S3_SECRET_KEY`。后端固定使用 bucket `cloud-drive`，需要提前创建或确保服务允许自动创建。

### `security.env`

| 变量 | 说明 |
|------|------|
| `JWT_ACCESS_SECRET` | Access Token 签名密钥 |
| `JWT_REFRESH_SECRET` | Refresh Token 签名密钥 |
| `SHARE_PASSWORD_ENCRYPTION_KEY` | 分享密码加密密钥 |

要求：

| 项 | 要求 |
|----|------|
| JWT 密钥 | 建议 Base64 32 字节以上，access 和 refresh 必须不同 |
| 分享密码密钥 | Base64 32 字节；修改后旧分享密码密文将无法解密 |
| 泄露处理 | 一旦泄露，应轮换密钥并让现有 token 或分享密码失效 |

### `backend.env`

| 变量 | 说明 |
|------|------|
| `BACKEND_PORT` | 后端端口标记。Spring Boot 实际端口由 `server.port` 控制，默认 8080 |
| `LOG_DIR` | 后端日志输出目录。Docker Compose 默认为 `/var/log/cloud-drive` |
| `BACKEND_LOG_DIR` | 后端日志目录标记，主要供外部脚本或部署系统引用 |
| `ONLYOFFICE_INTERNAL_URL` | 后端容器访问 OnlyOffice 的内部地址，Docker Compose 默认 `http://onlyoffice-documentserver` |
| `ONLYOFFICE_PUBLIC_URL` | 浏览器访问 OnlyOffice 的外部地址，本地默认 `http://localhost:8082` |
| `APP_INTERNAL_API_BASE_URL` | OnlyOffice 回调和内容 URL 使用的后端内部 API 地址，Docker Compose 默认 `http://backend:8080/api/v1` |
| `ONLYOFFICE_JWT_SECRET` | OnlyOffice JWT 密钥，必须与根目录 `.env` 中传给 Document Server 的值一致 |
| `ONLYOFFICE_TOKEN_TTL_SECONDS` | OnlyOffice 内容和回调 token 有效期，默认 86400 秒 |
| `ONLYOFFICE_CALLBACK_DOWNLOAD_TIMEOUT_SECONDS` | 后端从 Document Server 下载编辑结果的超时时间，默认 30 秒 |
| `QUEUE_ENABLED` | 是否启用 RabbitMQ 队列，默认 `true` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | RabbitMQ 地址，默认 `localhost:5672` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | RabbitMQ 登录凭据 |
| `RABBITMQ_VHOST` | RabbitMQ virtual host，默认 `/` |
| `QUEUE_RABBITMQ_EXCHANGE` | 业务事件 Topic Exchange 名称，默认 `bcd.events` |
| `QUEUE_RABBITMQ_DEAD_LETTER_EXCHANGE` | 死信 Exchange 名称，默认 `bcd.dead-letter` |
| `QUEUE_RABBITMQ_NOTIFICATIONS_QUEUE` | 通知队列名称，默认 `bcd.notifications` |
| `QUEUE_RABBITMQ_TEAM_TASKS_QUEUE` | 团队任务队列名称，默认 `bcd.team-tasks` |
| `QUEUE_RABBITMQ_DEAD_LETTER_QUEUE` | 死信队列名称，默认 `bcd.dead-letter` |
| `QUEUE_RABBITMQ_NOTIFICATIONS_ROUTING_KEY` | 通知消息 routing key，默认 `notification.dispatch` |
| `QUEUE_RABBITMQ_TEAM_TASKS_ROUTING_KEY` | 团队任务唤醒消息 routing key，默认 `team-task.wakeup` |
| `QUEUE_RABBITMQ_PREFETCH` | 每个消费者预取消息数，默认 20 |
| `QUEUE_RABBITMQ_CONCURRENCY` | Listener 初始并发数，默认 1 |
| `QUEUE_RABBITMQ_MAX_CONCURRENCY` | Listener 最大并发数，默认 4 |
| `QUEUE_RABBITMQ_MAX_DELIVERIES` | Outbox 最大投递次数，默认 5 |
| `QUEUE_RABBITMQ_OUTBOX_PUBLISH_BATCH_SIZE` | 单次扫描 Outbox 的最大数量，默认 50 |
| `QUEUE_RABBITMQ_OUTBOX_PUBLISH_INTERVAL_MS` | Outbox 发布间隔，默认 5000 毫秒 |
| `QUEUE_RABBITMQ_BACKLOG_WARN_THRESHOLD` | RabbitMQ ready 或数据库 pending/retry 告警阈值，默认 100 |
| `QUEUE_RABBITMQ_DEAD_LETTER_WARN_THRESHOLD` | DLQ 告警阈值，默认 1 |
| `QUEUE_METRICS_REFRESH_INTERVAL_MS` | 刷新 RabbitMQ 和数据库队列指标的间隔，默认 15000 毫秒 |
| `TEAM_QUOTA_METRICS_REFRESH_INTERVAL_MS` | 从数据库刷新团队配额 gauge 的间隔，默认 60000 毫秒 |
| `TEAM_TASK_RESULT_DIRECTORY` | 团队后台任务结果文件目录，默认 `./data/team-task-results` |
| `TEAM_TASK_RESULT_RETENTION_HOURS` | 团队后台任务结果文件保留小时数，默认 24 |
| `TEAM_AUDIT_EXPORT_BATCH_SIZE` | 单次处理团队审计导出任务数量，默认 10 |
| `TEAM_AUDIT_EXPORT_INTERVAL_MS` | 团队审计导出数据库兜底扫描间隔，默认 60000 毫秒 |
| `THUMBNAIL_TASK_BATCH_SIZE` | 单次处理缩略图任务数量，默认 10 |
| `THUMBNAIL_TASK_INTERVAL_MS` | 缩略图数据库兜底扫描间隔，默认 60000 毫秒 |
| `FOLDER_ZIP_TASK_BATCH_SIZE` | 单次处理团队文件夹 ZIP 任务数量，默认 5 |
| `FOLDER_ZIP_TASK_INTERVAL_MS` | 团队文件夹 ZIP 数据库兜底扫描间隔，默认 60000 毫秒 |
| `TEAM_TASK_RESULT_CLEANUP_INTERVAL_MS` | 团队任务结果文件过期清理间隔，默认 3600000 毫秒 |
| `TEAM_TASK_RUNNING_TIMEOUT_SECONDS` | `RUNNING` 团队任务被视为超时并恢复为 `PENDING` 的时间，默认 1800 秒 |

队列拓扑和任务超时变量由 `application.yml` 直接读取。它们当前没有写入 `backend.env.example`，未配置时使用上表默认值；如需覆盖，可将对应变量添加到实际的 `backend.env`。

后端日志包含：

| 文件 | 说明 |
|------|------|
| `runtime.log` | 运行日志，结构化 JSON |
| `audit.log` | 审计日志，结构化 JSON |

### `frontend.env`

| 变量 | 说明 |
|------|------|
| `FRONTEND_PORT` | 前端容器端口标记 |
| `FRONTEND_BACKEND_PROXY_BASE` | 前端 Nginx 代理后端的内部地址，Docker Compose 默认 `http://backend:8080` |
| `VITE_API_BASE_URL` | Web 构建时 API 根地址 |
| `VITE_WEB_BASE_URL` | Web 构建时 Web 根地址 |
| `VITE_DEV_BACKEND_PROXY_BASE` | Vite dev server 的 `/api`、`/webdav` 代理目标 |

Qt 启动另从宿主机环境读取 `BCD_DESKTOP_API_BASE`（默认 `http://127.0.0.1:8080/api/v1`）与 `BCD_DESKTOP_WEB_BASE`（默认 `http://127.0.0.1:3000`），不从此 Compose env 文件加载。

KMP Android Debug 在官方模拟器中默认使用 `http://10.0.2.2:8080`，登录页可保存其他规范化服务器地址；Release 只接受 HTTPS。设备测试的 ADB serial 通过 `scripts/verify-all.ps1 -AndroidSerial` 或 `scripts/verify-all.sh --android-serial` 显式传入，不属于 `frontend.env`。

Web 前端地址解析顺序：

| 场景 | API 地址优先级 |
|------|----------------|
| Web | localStorage 自定义值 > `VITE_API_BASE_URL` > `/api/v1` |
| Qt | localStorage 自定义值 > 启动参数 `--api-base` > 启动环境 `BCD_DESKTOP_API_BASE` > 默认回环地址 |

分享链接 Web 根地址解析顺序：

| 场景 | Web 地址优先级 |
|------|----------------|
| Web | localStorage 自定义值 > `VITE_WEB_BASE_URL` > `window.location.origin` |
| Qt | localStorage 自定义值 > 启动参数 `--web-base` > 启动环境 `BCD_DESKTOP_WEB_BASE` > 默认回环地址 |

### `mail.env`

| 变量 | 说明 |
|------|------|
| `MAILPIT_SMTP_PORT` | Mailpit SMTP 容器端口标记 |
| `MAILPIT_UI_PORT` | Mailpit Web UI 容器端口标记 |
| `MAIL_HOST` | 后端 SMTP 主机 |
| `MAIL_PORT` | 后端 SMTP 端口 |

当前 `application.yml` 还支持：

| 变量 | 说明 |
|------|------|
| `MAIL_USERNAME` | SMTP 用户名 |
| `MAIL_PASSWORD` | SMTP 密码 |

生产接入真实 SMTP 时，还需要按邮件服务要求调整认证、TLS 等 Spring Mail 属性。目前默认配置适合 Mailpit。

### `observability.env`

| 变量 | 说明 |
|------|------|
| `GRAFANA_BASE_URL` | 后端生成 Grafana Explore 链接时使用的路径 |
| `GRAFANA_AUTH_PROXY_SECRET` | Grafana 短期登录 cookie 签名密钥 |
| `GRAFANA_AUTH_PROXY_TTL_SECONDS` | Grafana 短期登录 cookie 有效期 |
| `GRAFANA_AUTH_COOKIE_SECURE` | 是否为 Grafana 短期 cookie 设置 `Secure`；dev 默认 `false`，prod 默认 `true` |
| `GF_SERVER_ROOT_URL` | Grafana 外部根地址 |
| `GF_SECURITY_ADMIN_USER` | Grafana 内置管理员用户名 |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana 内置管理员密码 |
| `GRAFANA_LOKI_DATASOURCE` | 后端生成 Explore 链接使用的 Loki 数据源名称 |
| `GRAFANA_DS_LOKI_URL` | Grafana provisioning 中 Loki 数据源地址 |
| `GRAFANA_DS_PROMETHEUS_URL` | Grafana provisioning 中 Prometheus 数据源地址 |
| `GRAFANA_DS_ALERTMANAGER_URL` | Grafana provisioning 中 Alertmanager 数据源地址 |
| `PROMETHEUS_RETENTION_TIME` | Prometheus 本地时序数据保留时间 |
| `PROMETHEUS_ENVIRONMENT` | Prometheus 写入所有时间序列的外部环境标签 |
| `ALERTMANAGER_RETENTION_TIME` | Alertmanager 状态数据保留时间，默认 72 小时 |
| `LOKI_RETENTION_PERIOD` | Loki 日志保留时间，默认 14 天 |
| `LOKI_PORT` | Loki 容器端口标记 |
| `LOKI_BASE_URL` | 后端查询 Loki 的 HTTP 地址 |
| `METRICS_EXPORT_OTLP_ENABLED` | 是否通过 OTLP 导出 metrics |
| `APP_ENV` | Micrometer 指标的 `environment` 公共标签，默认 `development` |
| `OBSERVABILITY_ENABLED` | 是否启用请求 Trace 上下文和审计过滤器，默认 `true` |
| `TRACING_ENABLED` | 是否创建用于日志和审计关联的进程内 Trace 上下文 |
| `TRACING_SAMPLING_PROBABILITY` | 进程内 Trace 采样率；dev 默认 `1.0`，prod 默认 `0.1` |
| `TRACING_EXPORT_OTLP_ENABLED` | 固定为 `false`，默认部署不导出完整 Trace |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 启用 OTLP Trace 导出时的 HTTP endpoint，默认 `http://localhost:4318/v1/traces` |
| `ALLOY_LOKI_PUSH_URL` | Alloy 推送 Loki 的地址 |
| `MANAGEMENT_SERVER_PORT` | prod profile 的独立 Actuator 管理端口，默认 8081 |
| `SYSTEM_LOG_DEFAULT_LIMIT` | 管理后台系统日志默认返回条数 |
| `SYSTEM_LOG_MAX_LIMIT` | 管理后台系统日志最大返回条数 |
| `AUDIT_LOG_ENABLED` | 是否启用审计日志 |
| `AUDIT_EVENT_ENABLED` | 是否写入完整审计事件；兼容旧变量 `AUDIT_FULL_EVENT_ENABLED` |
| `AUDIT_CAPTURE_BODY` | 是否采集限长且脱敏的 JSON 请求正文，默认关闭 |
| `OBSERVABILITY_TRUSTED_PROXY_CIDRS` | 已弃用的兼容变量；仅在根配置未设置 `TRUSTED_PROXY_CIDRS` 时作为回退 |
| `AUDIT_MAX_BODY_BYTES` | 审计日志最多记录的请求体字节数 |

`APP_ENV`、`OBSERVABILITY_ENABLED` 和 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 当前没有写入 `observability.env.example`，未配置时使用上表默认值；如需覆盖，可将它们添加到实际的 `observability.env`。默认 Compose 明确设置 `TRACING_EXPORT_OTLP_ENABLED=false`，因此只修改 OTLP endpoint 不会启用完整 Trace 导出。

## 后端配置项

后端主要配置来自 `application.yml`，生产环境叠加 `application-prod.yml`。

| 配置 | 默认值 | 环境变量 | 说明 |
|------|--------|----------|------|
| `server.port` | `8080` | 无直接占位 | 后端 HTTP 端口 |
| `spring.profiles.active` | `dev` | `SPRING_PROFILES_ACTIVE` | 当前运行 profile |
| `spring.datasource.url` | `localhost:5432/better_cloud_drive` | `DB_HOST`、`DB_PORT`、`DB_NAME` | PostgreSQL JDBC 地址 |
| `spring.datasource.username` | `bcd_user` | `DB_USER` | 数据库用户名 |
| `spring.datasource.password` | `bcd_pass_2026` | `DB_PASSWORD` | 数据库密码 |
| `spring.data.redis.host` | `localhost` | `REDIS_HOST` | Redis 主机 |
| `spring.data.redis.port` | `6379` | `REDIS_PORT` | Redis 端口 |
| `spring.data.redis.password` | 空 | `REDIS_PASSWORD` | Redis 密码 |
| `spring.flyway.enabled` | `true` | 无直接占位 | 是否启用 Flyway |
| `spring.jpa.hibernate.ddl-auto` | `validate` | 无直接占位 | 只校验 schema，不自动建表 |
| `storage.s3.endpoint` | `http://localhost:8333` | `STORAGE_S3_ENDPOINT` | S3 endpoint |
| `storage.s3.access-key` | `any` | `STORAGE_S3_ACCESS_KEY` | S3 access key |
| `storage.s3.secret-key` | `any` | `STORAGE_S3_SECRET_KEY` | S3 secret key |
| `storage.s3.bucket` | `cloud-drive` | 无直接占位 | S3 bucket 名称 |
| `jwt.access-token.secret` | 空 | `JWT_ACCESS_SECRET` | Access token 密钥 |
| `jwt.access-token.expiration-ms` | `1800000` | 无直接占位 | Access token 有效期，默认 30 分钟 |
| `jwt.refresh-token.secret` | 空 | `JWT_REFRESH_SECRET` | Refresh token 密钥 |
| `jwt.refresh-token.expiration-ms` | `2592000000` | 无直接占位 | Refresh token 有效期，默认 30 天 |
| `share.password-encryption-key` | 空 | `SHARE_PASSWORD_ENCRYPTION_KEY` | 分享密码加密密钥 |
| `drive.recycle-bin.retention-days` | `30` | 无直接占位 | 回收站保留天数 |
| `drive.upload.chunk-size` | `5242880` | 无直接占位 | 上传分片大小，默认 5MB |
| `drive.upload.session-expire-hours` | `24` | 无直接占位 | 上传会话过期小时数 |
| `drive.download.folder-zip.max-files` | `1000` | 无直接占位 | 文件夹打包最大文件数 |
| `drive.download.folder-zip.max-size-bytes` | `1073741824` | 无直接占位 | 文件夹打包最大体积，默认 1GB |
| `drive.version.max-versions` | `10` | 无直接占位 | 单文件最大版本数 |
| `drive.storage.default-quota-bytes` | `10737418240` | 无直接占位 | 新用户默认配额，默认 10GB |
| `drive.client-ip.trusted-proxy-cidrs` | 空 | `TRUSTED_PROXY_CIDRS`，旧变量作为回退 | 允许采用 `X-Forwarded-For` / `X-Real-IP` 的直接代理 CIDR |
| `drive.rate-limit.default-permits` | `300` | `DRIVE_RATE_LIMIT_DEFAULT_PERMITS` | 普通 API 每用户许可数 |
| `drive.rate-limit.default-period-seconds` | `60` | `DRIVE_RATE_LIMIT_DEFAULT_PERIOD_SECONDS` | 默认限流周期 |
| `drive.rate-limit.auth-permits` | `30` | `DRIVE_RATE_LIMIT_AUTH_PERMITS` | 匿名认证接口每 IP 许可数 |
| `drive.rate-limit.auth-period-seconds` | `60` | `DRIVE_RATE_LIMIT_AUTH_PERIOD_SECONDS` | 匿名认证接口限流周期 |
| `drive.rate-limit.upload-permits` | `120` | `DRIVE_RATE_LIMIT_UPLOAD_PERMITS` | 上传控制接口每用户许可数 |
| `drive.rate-limit.upload-period-seconds` | `60` | `DRIVE_RATE_LIMIT_UPLOAD_PERIOD_SECONDS` | 上传接口限流周期 |
| `drive.rate-limit.upload-chunk-permits` | `600` | `DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERMITS` | 上传分片接口每用户许可数 |
| `drive.rate-limit.upload-chunk-period-seconds` | `60` | `DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERIOD_SECONDS` | 上传分片限流周期 |
| `logging.file.path` | `./logs`，prod 为 `/var/log/cloud-drive` | `LOG_DIR` | 后端日志目录 |
| `observability.system-logs.loki-base-url` | `http://localhost:3100` | `LOKI_BASE_URL` | 后端查询 Loki 地址 |
| `observability.system-logs.grafana-base-url` | `/grafana` | `GRAFANA_BASE_URL` | Grafana Explore 链接前缀 |
| `observability.grafana-auth.secret` | 空 | `GRAFANA_AUTH_PROXY_SECRET` | Grafana auth cookie 签名密钥 |
| `observability.audit.enabled` | `true` | `AUDIT_LOG_ENABLED` | 是否启用审计 |

生产 profile 的差异：

| 配置 | prod 值 |
|------|---------|
| 数据库默认主机 | `postgres` |
| Hikari 最大连接数 | `30` |
| Hikari 最小空闲连接 | `10` |
| 日志级别 | `root=WARN`、`com.betterclouddrive=WARN` |
| 日志目录 | `/var/log/cloud-drive` |
| JSON 日志附加字段 | `env=prod` |

## Nginx 和反向代理配置

前端容器的 Nginx 模板是 `frontend/nginx.conf`。关键路径：

| 路径 | 行为 |
|------|------|
| `/` | 静态文件，SPA fallback 到 `index.html` |
| `/api/` | 代理到 `${FRONTEND_BACKEND_PROXY_BASE}/api/` |
| `/webdav/` | 代理到 `${FRONTEND_BACKEND_PROXY_BASE}/webdav/` |
| `/grafana-auth` | internal 子请求，转发到后端 Grafana 鉴权接口 |
| `/grafana/` | 先执行 `auth_request`，成功后代理 Grafana |

生产 HTTPS 反向代理建议：

| Header | 建议 |
|--------|------|
| `Host` | 保留原始 host |
| `X-Real-IP` | 传递客户端 IP |
| `X-Forwarded-For` | 追加代理链 |
| `X-Forwarded-Proto` | 传递 `https` |
| `X-Forwarded-Host` | 传递外部 host |

WebDAV 可能使用非标准 HTTP 方法，前端代理、外层代理和 WAF 都需要允许 WebDAV 方法。

## Redis 配置

Redis 配置文件位于 `config/redis/redis.conf`，Docker Compose 挂载到容器内 `/usr/local/etc/redis/redis.conf`。

当前关键设置：

| 配置 | 值 | 说明 |
|------|----|------|
| `appendonly` | `yes` | 启用 AOF |
| `appendfsync` | `everysec` | 每秒刷盘 |
| `save` | `900 1`、`300 10`、`60 10000` | RDB 快照策略 |
| `maxmemory-policy` | `noeviction` | 内存满时不驱逐 key |
| `protected-mode` | `no` | Docker 内网部署使用；生产公网 Redis 不应直接暴露 |
| `dir` | `/data` | Redis 数据目录 |

生产建议 Redis 只允许内网访问。如果启用 Redis 密码，需要同步设置后端 `REDIS_PASSWORD`。

## 数据和备份

完整的停写备份、空环境恢复和演练验收步骤见 [团队数据备份与恢复](../operations/BACKUP_RESTORE.md)。OnlyOffice 地址矩阵、JWT、保存链路和常见错误见 [OnlyOffice Document Server 部署与排障](../operations/ONLYOFFICE_DEPLOYMENT.md)。

Docker Compose 使用这些 volume：

| Volume | 内容 |
|--------|------|
| `pgdata` | PostgreSQL 数据 |
| `redisdata` | Redis 数据 |
| `rabbitmqdata` | RabbitMQ 持久化消息与元数据 |
| `seaweeddata` | SeaweedFS 对象存储数据 |
| `backendlogs` | 后端运行日志和审计日志 |
| `lokidata` | Loki 数据 |
| `grafanadata` | Grafana 数据 |
| `onlyofficedata` | OnlyOffice Document Server 数据 |
| `onlyofficelogs` | OnlyOffice Document Server 日志 |

生产备份优先级：

1. PostgreSQL：必须定期备份。
2. 对象存储：必须备份或使用高可靠存储。
3. Redis：视业务数据重要性开启持久化和备份。
4. OnlyOffice：如生产保留 Document Server 本地状态，需要备份 `onlyofficedata`。
5. `security.env` 中的密钥：必须安全备份，尤其 `SHARE_PASSWORD_ENCRYPTION_KEY`。
6. 日志和指标：按合规要求设置保留期。

## 安全建议

| 项 | 建议 |
|----|------|
| HTTPS | 生产必须启用 HTTPS |
| 密钥 | 所有 `change_me`、示例密钥和默认密码必须替换 |
| 数据库 | 不要将 PostgreSQL 暴露到公网 |
| Redis | 不要将 Redis 暴露到公网；启用密码和安全组 |
| S3 | access key 只授予必要 bucket 权限 |
| Grafana | 通过 `/grafana/` 同域鉴权访问，不建议开放 Grafana 直连端口 |
| 日志 | 审计日志会脱敏常见敏感字段，但仍应限制访问 |
| CORS | 如果前端和后端跨域部署，需要显式审查 CORS 配置 |
| WebDAV | WebDAV 强制要求独立密码，避免复用主账号密码 |

## 完整验证

完整验证入口为 `scripts/verify-all.ps1` 和 `scripts/verify-all.sh`。模块选择、工具依赖、Android 设备、OnlyOffice、测试数据清理和报告路径统一维护在[开发测试策略](../development/TESTING.md)，本配置参考不复制这些易变化的执行细节。

配置变更至少应先验证 Compose 能解析：

```bash
docker compose config --quiet
```

涉及运行时地址、代理、密钥或观测组件时，再按测试策略选择相应专项模块。

## 配置检查清单

上线前检查：

| 检查项 | 要求 |
|--------|------|
| `.env` | 宿主机端口没有冲突，`HOST_ONLYOFFICE_PORT` 与 `ONLYOFFICE_JWT_SECRET` 已确认 |
| `database.env` | 数据库密码已替换，连接地址正确 |
| `storage.env` | S3 endpoint 和密钥正确，bucket 可用 |
| `security.env` | JWT 和分享密码密钥已替换并备份 |
| `backend.env` | `LOG_DIR` 可写，OnlyOffice internal/public URL 和 JWT secret 与部署一致 |
| `frontend.env` | `FRONTEND_BACKEND_PROXY_BASE` 指向后端内网地址 |
| `observability.env` | Grafana、Loki、Prometheus 和 Alertmanager 地址正确 |
| Grafana | `/grafana/` 未登录返回 401，管理员入口可进入 |
| 后端 | `/actuator/health` 返回健康 |
| 日志 | `runtime.log`、`audit.log` 正常写入 |
| 备份 | PostgreSQL、对象存储、密钥备份策略已建立 |

常用验证命令：

```bash
docker compose config --quiet
docker compose ps
curl -i http://localhost:3000/api/v1/grafana/auth
curl -i http://localhost:3000/grafana/
docker compose exec redis redis-cli ping
```

未登录访问 `/api/v1/grafana/auth` 和 `/grafana/` 返回 401 是正常结果，说明代理链路和鉴权接口可达。

Qt 的 `BCD_DESKTOP_API_BASE`、`BCD_DESKTOP_WEB_BASE` 在桌面启动进程环境中设置，由 Qt 向可信主页面注入公开默认值；Web 和 Qt 使用同一份构建产物；不由 Compose 的前端服务 env 文件自动传给宿主机桌面进程。
