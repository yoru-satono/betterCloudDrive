# Better Cloud Drive 配置与部署文档

## 适用范围

本文档说明 Better Cloud Drive 的部署方式、配置文件位置、环境变量含义，以及 Docker Compose 和生产环境拆分部署的配置建议。

项目当前包含这些运行组件：

| 组件 | 说明 |
|------|------|
| `backend` | Spring Boot 后端 API、WebDAV 代理、上传下载、审计与系统日志查询 |
| `frontend` | Vue Web 前端，Docker 镜像中由 Nginx 托管 |
| `postgres` | PostgreSQL 主数据库 |
| `redis` | Redis，用于缓存和服务状态 |
| `seaweedfs` | S3 兼容对象存储，当前 Docker Compose 使用 SeaweedFS |
| `mailpit` | 本地开发邮件服务 |
| `loki` | 系统日志存储 |
| `promtail` | 后端日志采集器 |
| `tempo` | Trace 存储 |
| `otel-collector` | OpenTelemetry Collector |
| `grafana` | 日志和 Trace 查询界面，通过前端 Nginx 同域代理并由后端管理员身份鉴权 |

## 配置文件位置

| 路径 | 用途 |
|------|------|
| `.env` | Docker Compose 的宿主机端口和前端 Docker 构建参数 |
| `.env.example` | 根 `.env` 模板 |
| `config/env-files/database.env` | PostgreSQL、Redis 连接和容器变量 |
| `config/env-files/storage.env` | SeaweedFS / S3 存储变量 |
| `config/env-files/security.env` | JWT 和分享密码加密密钥 |
| `config/env-files/backend.env` | 后端端口、日志目录 |
| `config/env-files/frontend.env` | 前端 Nginx 代理、Web/Tauri/Android 构建与测试地址 |
| `config/env-files/mail.env` | 邮件服务变量 |
| `config/env-files/observability.env` | Loki、Tempo、OTel、Grafana、审计日志变量 |
| `config/redis/redis.conf` | Redis 配置 |
| `backend/betterclouddrive-start/src/main/resources/application.yml` | 后端默认配置 |
| `backend/betterclouddrive-start/src/main/resources/application-prod.yml` | 后端生产 profile 配置 |
| `frontend/nginx.conf` | 前端容器 Nginx 模板 |
| `observability/*.yml` | Loki、Tempo、Promtail、OTel Collector、Grafana provisioning 配置 |

`docker-compose.yml` 会自动读取根目录 `.env` 进行变量替换。服务运行时变量通过各服务的 `env_file` 注入。

## Docker Compose 部署

### 1. 准备配置

复制示例配置：

```powershell
Copy-Item .env.example .env
Copy-Item config/env-files/database.env.example config/env-files/database.env
Copy-Item config/env-files/storage.env.example config/env-files/storage.env
Copy-Item config/env-files/security.env.example config/env-files/security.env
Copy-Item config/env-files/backend.env.example config/env-files/backend.env
Copy-Item config/env-files/frontend.env.example config/env-files/frontend.env
Copy-Item config/env-files/mail.env.example config/env-files/mail.env
Copy-Item config/env-files/observability.env.example config/env-files/observability.env
```

生产或长期运行前必须替换这些密钥：

| 变量 | 要求 |
|------|------|
| `POSTGRES_PASSWORD` / `DB_PASSWORD` | 强随机数据库密码，两者应保持一致 |
| `STORAGE_S3_SECRET_KEY` / `S3_SECRET_KEY` | 强随机对象存储密钥 |
| `JWT_ACCESS_SECRET` | 独立随机 Base64 密钥，建议 32 字节以上 |
| `JWT_REFRESH_SECRET` | 独立随机 Base64 密钥，不能和 access secret 相同 |
| `SHARE_PASSWORD_ENCRYPTION_KEY` | 独立随机 Base64 32 字节密钥 |
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

默认入口：

| 服务 | 默认地址 |
|------|----------|
| Web 前端 | `http://localhost:3000` |
| 后端 API | `http://localhost:8080/api/v1` |
| WebDAV | `http://localhost:3000/webdav/` 或后端直连 `/webdav/` |
| Mailpit | `http://localhost:8025` |
| Grafana | 通过管理后台日志中心按钮进入，同域路径 `/grafana/` |
| Loki | `http://localhost:3100` |
| Tempo | `http://localhost:3200` |

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

清空并重建数据卷会删除数据库、Redis、对象存储和可观测性数据：

```powershell
docker compose down -v
docker compose up -d --build
```

## 生产环境拆分部署

生产环境可以继续使用 Docker Compose，也可以将各组件拆到不同主机、Kubernetes、托管数据库、托管对象存储和独立日志平台。拆分部署时建议遵循下列拓扑。

### 推荐拓扑

| 层级 | 组件 | 生产建议 |
|------|------|----------|
| 接入层 | Nginx / Ingress / 负载均衡 | 统一 HTTPS 入口，代理 `/api/`、`/webdav/`、`/grafana/` |
| 前端 | 静态文件服务 | 可用项目 `frontend` 镜像，也可将 `dist` 部署到 Nginx/CDN |
| 后端 | Spring Boot JAR 或容器 | 至少 1 个实例；多实例时共享同一 PostgreSQL、Redis 和对象存储 |
| 数据库 | PostgreSQL | 使用托管 PostgreSQL 或独立高可用集群，开启备份 |
| 缓存 | Redis | 使用独立 Redis，建议开启持久化或托管服务 |
| 对象存储 | S3 兼容存储 | 可用 SeaweedFS、MinIO、AWS S3、阿里云 OSS S3 兼容网关等 |
| 日志 | Loki / 其他日志平台 | Promtail 或替代采集器采集后端 JSON 日志 |
| Trace | Tempo / 其他 OTLP 后端 | 后端通过 OTLP HTTP 上报 |
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
| `VITE_DESKTOP_API_BASE_URL` | Tauri 客户端默认 API 根地址 |
| `VITE_DESKTOP_WEB_BASE_URL` | Tauri 客户端默认 Web 根地址 |

构建：

```bash
cd frontend
npm ci
VITE_API_BASE_URL=https://drive.example.com/api/v1 \
VITE_WEB_BASE_URL=https://drive.example.com \
npm run build
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

关键变量：

| 变量 | 说明 |
|------|------|
| `GRAFANA_BASE_URL` | 后端生成 Grafana Explore 链接时使用的路径，默认 `/grafana` |
| `GRAFANA_AUTH_PROXY_SECRET` | 后端签名 Grafana 短期 cookie 的密钥 |
| `GRAFANA_AUTH_PROXY_TTL_SECONDS` | Grafana 短期 cookie 有效期，默认 300 秒 |
| `GF_SERVER_ROOT_URL` | Grafana 自身生成资源链接使用的外部根地址 |
| `GF_SERVER_SERVE_FROM_SUB_PATH` | Grafana 是否运行在子路径，当前为 `true` |
| `GF_AUTH_PROXY_ENABLED` | 启用 Grafana Auth Proxy |
| `GF_AUTH_PROXY_HEADER_NAME` | Grafana 从哪个请求头读取用户名，当前 `X-WEBAUTH-USER` |

如果 Grafana 不是 `/grafana/` 子路径，而是独立域名，需要重新设计 cookie 域、Nginx 鉴权路径和 `GRAFANA_BASE_URL`，不能只改 Grafana 端口。

## 环境变量详解

### 根目录 `.env`

这些变量只用于 Docker Compose 的宿主机端口映射和前端镜像构建参数。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `HOST_POSTGRES_PORT` | `5432` | PostgreSQL 暴露到宿主机的端口 |
| `HOST_REDIS_PORT` | `6379` | Redis 暴露到宿主机的端口 |
| `HOST_SEAWEEDFS_S3_PORT` | `8333` | SeaweedFS S3 API 宿主机端口 |
| `HOST_SEAWEEDFS_WEBDAV_PORT` | `8888` | SeaweedFS WebDAV 宿主机端口 |
| `HOST_SEAWEEDFS_MASTER_PORT` | `9333` | SeaweedFS Master UI/API 宿主机端口 |
| `HOST_MAILPIT_SMTP_PORT` | `1025` | Mailpit SMTP 宿主机端口 |
| `HOST_MAILPIT_UI_PORT` | `8025` | Mailpit Web UI 宿主机端口 |
| `HOST_LOKI_PORT` | `3100` | Loki 宿主机端口 |
| `HOST_TEMPO_PORT` | `3200` | Tempo HTTP 宿主机端口 |
| `HOST_OTEL_GRPC_PORT` | `4317` | OTLP gRPC 宿主机端口 |
| `HOST_OTEL_HTTP_PORT` | `4318` | OTLP HTTP 宿主机端口 |
| `HOST_BACKEND_PORT` | `8080` | 后端宿主机端口 |
| `HOST_FRONTEND_PORT` | `3000` | 前端宿主机端口 |
| `VITE_API_BASE_URL` | 空 | Web 前端构建时 API 根地址。空值表示使用 `/api/v1` |
| `VITE_WEB_BASE_URL` | 空 | Web 前端构建时 Web 根地址。空值表示使用当前 origin |
| `VITE_DESKTOP_API_BASE_URL` | `http://127.0.0.1:8080/api/v1` | Tauri 客户端默认 API 根地址 |
| `VITE_DESKTOP_WEB_BASE_URL` | `http://127.0.0.1:3000` | Tauri 客户端默认 Web 根地址 |

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
| `VITE_DESKTOP_API_BASE_URL` | Tauri 客户端默认 API 根地址 |
| `VITE_DESKTOP_WEB_BASE_URL` | Tauri 客户端默认 Web 根地址 |
| `ANDROID_DEFAULT_SERVER_BASE_URL` | Android 默认服务器地址构建参数 |
| `ANDROID_E2E_SERVER_BASE_URL` | Android E2E 后端地址 |
| `ANDROID_E2E_MAILPIT_BASE_URL` | Android E2E Mailpit 地址 |

Web 前端地址解析顺序：

| 场景 | API 地址优先级 |
|------|----------------|
| Web | localStorage 自定义值 > `VITE_API_BASE_URL` > `/api/v1` |
| Tauri | localStorage 自定义值 > `VITE_API_BASE_URL` > `VITE_DESKTOP_API_BASE_URL` |

分享链接 Web 根地址解析顺序：

| 场景 | Web 地址优先级 |
|------|----------------|
| Web | localStorage 自定义值 > `VITE_WEB_BASE_URL` > `window.location.origin` |
| Tauri | localStorage 自定义值 > `VITE_WEB_BASE_URL` > `VITE_DESKTOP_WEB_BASE_URL` |

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
| `GF_SERVER_ROOT_URL` | Grafana 外部根地址 |
| `GF_SECURITY_ADMIN_USER` | Grafana 内置管理员用户名 |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana 内置管理员密码 |
| `GRAFANA_LOKI_DATASOURCE` | 后端生成 Explore 链接使用的 Loki 数据源名称 |
| `GRAFANA_DS_LOKI_URL` | Grafana provisioning 中 Loki 数据源地址 |
| `GRAFANA_DS_TEMPO_URL` | Grafana provisioning 中 Tempo 数据源地址 |
| `LOKI_PORT` | Loki 容器端口标记 |
| `LOKI_BASE_URL` | 后端查询 Loki 的 HTTP 地址 |
| `TEMPO_PORT` | Tempo HTTP 端口标记 |
| `OTEL_GRPC_PORT` | OTel Collector gRPC 端口标记 |
| `OTEL_HTTP_PORT` | OTel Collector HTTP 端口标记 |
| `METRICS_EXPORT_OTLP_ENABLED` | 是否通过 OTLP 导出 metrics |
| `TRACING_ENABLED` | 是否启用 tracing 导出 |
| `TRACING_SAMPLING_PROBABILITY` | trace 采样率，`1.0` 表示全量 |
| `TRACING_EXPORT_OTLP_ENABLED` | 是否通过 OTLP 导出 traces |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | 通用 OTLP endpoint |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 后端 trace OTLP HTTP endpoint |
| `OTEL_COLLECTOR_TEMPO_ENDPOINT` | Collector 转发到 Tempo 的 gRPC 地址 |
| `PROMTAIL_LOKI_PUSH_URL` | Promtail 推送 Loki 的地址 |
| `SYSTEM_LOG_DEFAULT_LIMIT` | 管理后台系统日志默认返回条数 |
| `SYSTEM_LOG_MAX_LIMIT` | 管理后台系统日志最大返回条数 |
| `AUDIT_LOG_ENABLED` | 是否启用审计日志 |
| `AUDIT_FULL_EVENT_ENABLED` | 是否记录完整审计事件 |
| `AUDIT_MAX_BODY_BYTES` | 审计日志最多记录的请求体字节数 |

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
| `drive.rate-limit.default-permits` | `100` | 无直接占位 | 默认限流许可数 |
| `drive.rate-limit.default-period-seconds` | `60` | 无直接占位 | 默认限流周期 |
| `drive.rate-limit.upload-permits` | `10` | 无直接占位 | 上传接口限流许可数 |
| `drive.rate-limit.upload-period-seconds` | `60` | 无直接占位 | 上传接口限流周期 |
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

Docker Compose 使用这些 volume：

| Volume | 内容 |
|--------|------|
| `pgdata` | PostgreSQL 数据 |
| `redisdata` | Redis 数据 |
| `seaweeddata` | SeaweedFS 对象存储数据 |
| `backendlogs` | 后端运行日志和审计日志 |
| `lokidata` | Loki 数据 |
| `tempodata` | Tempo 数据 |
| `grafanadata` | Grafana 数据 |

生产备份优先级：

1. PostgreSQL：必须定期备份。
2. 对象存储：必须备份或使用高可靠存储。
3. Redis：视业务数据重要性开启持久化和备份。
4. `security.env` 中的密钥：必须安全备份，尤其 `SHARE_PASSWORD_ENCRYPTION_KEY`。
5. 日志和 Trace：按合规要求设置保留期。

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

## 配置检查清单

上线前检查：

| 检查项 | 要求 |
|--------|------|
| `.env` | 宿主机端口没有冲突 |
| `database.env` | 数据库密码已替换，连接地址正确 |
| `storage.env` | S3 endpoint 和密钥正确，bucket 可用 |
| `security.env` | JWT 和分享密码密钥已替换并备份 |
| `backend.env` | `LOG_DIR` 可写 |
| `frontend.env` | `FRONTEND_BACKEND_PROXY_BASE` 指向后端内网地址 |
| `observability.env` | Grafana、Loki、Tempo、OTel 地址正确 |
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
