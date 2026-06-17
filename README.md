# BetterCloudDrive

全栈云盘系统，支持 Web 端和 Android 端访问。

## 技术栈

| 层 | 技术 |
|---|------|
| **后端** | Spring Boot 4.0 + JDK 25 + Spring Data JPA 4.0 |
| **数据库** | PostgreSQL 16 + Flyway 迁移 |
| **缓存** | Redis 7（限流、上传进度、配额同步、Token 黑名单） |
| **存储** | SeaweedFS（S3 兼容对象存储） |
| **邮件** | Mailpit（开发环境邮件模拟） |
| **可观测性** | Spring Boot Actuator + OpenTelemetry + Loki + Promtail + Tempo + Grafana |
| **Web 前端** | Vue 3 + Vite + Pinia + Vue Router 4 |
| **Android** | Kotlin + Jetpack Compose + Material 3 + Hilt + Retrofit |
| **测试** | JUnit 5 + Mockito（后端）、pytest + httpx（黑盒）、Vitest + Playwright（前端） |
| **部署** | Docker Compose 一键编排 |

## 功能

- **用户认证**：注册/登录/JWT 双令牌/登出/Token 自动刷新
- **文件管理**：浏览/新建文件夹/重命名/移动/复制/删除/搜索
- **分片上传**：5MB 分片 + 秒传（MD5 去重）+ 断点续传
- **下载预览**：流式下载（Range 支持）/图片/视频/文本在线预览
- **回收站**：30 天自动清理/恢复/彻底删除
- **分享链接**：创建/密码保护/过期时间/访问次数限制
- **收藏 & 标签**：文件收藏/标签分类管理
- **版本管理**：文件多版本保留/历史版本删除
- **注册验证码 & 密码重置**：Mailpit 模拟邮件
- **存储配额**：默认 10GB/用户，管理员可调整
- **管理员面板**：用户管理/配额调整/日志中心/系统统计
- **响应式 UI**：Web 端桌面+移动端，Android 端手机+平板双布局

## 项目结构

```
betterCloudDrive/
├── backend/                 # Spring Boot 多模块后端
│   ├── betterclouddrive-common/     # DTO、枚举、异常
│   ├── betterclouddrive-dal/        # Spring Data JPA 实体/Repository
│   ├── betterclouddrive-storage/    # SeaweedFS S3 适配
│   ├── betterclouddrive-service/    # 业务逻辑层
│   ├── betterclouddrive-web/        # Controller、Security
│   ├── betterclouddrive-scheduler/  # 定时任务
│   └── betterclouddrive-start/      # 启动入口 + Flyway 迁移
├── frontend/                # Vue 3 Web 前端
├── android/                 # Kotlin Android 应用
├── backend-test/            # Python 黑盒 API 测试
├── observability/           # Loki/Promtail/Tempo/OTel/Grafana 配置
├── docs/API.md              # API 文档
├── docs/CONFIGURATION.md    # 配置与部署文档
├── config/redis/redis.conf  # Redis 配置
└── docker-compose.yml       # 服务编排
```

## 快速启动

### 方式一：Docker Compose 启动全栈

环境变量按模块拆分在 `config/env-files/` 下，并由 `docker-compose.yml` 的服务级 `env_file` 显式注入。复制各模块模板并设置密钥后，可直接使用 Docker Compose 启动；根目录 `.env` 只保留宿主机端口和前端 Docker 构建参数：

```bash
cp config/env-files/database.env.example config/env-files/database.env
cp config/env-files/storage.env.example config/env-files/storage.env
cp config/env-files/security.env.example config/env-files/security.env
cp config/env-files/backend.env.example config/env-files/backend.env
cp config/env-files/frontend.env.example config/env-files/frontend.env
cp config/env-files/observability.env.example config/env-files/observability.env
cp config/env-files/mail.env.example config/env-files/mail.env
# 编辑 config/env-files/security.env，设置 JWT_*_SECRET 和 SHARE_PASSWORD_ENCRYPTION_KEY
docker compose up -d --build
```

Windows PowerShell 可使用：

```powershell
Copy-Item config/env-files/database.env.example config/env-files/database.env
Copy-Item config/env-files/storage.env.example config/env-files/storage.env
Copy-Item config/env-files/security.env.example config/env-files/security.env
Copy-Item config/env-files/backend.env.example config/env-files/backend.env
Copy-Item config/env-files/frontend.env.example config/env-files/frontend.env
Copy-Item config/env-files/observability.env.example config/env-files/observability.env
Copy-Item config/env-files/mail.env.example config/env-files/mail.env
# 编辑 config/env-files/security.env，设置 JWT_*_SECRET 和 SHARE_PASSWORD_ENCRYPTION_KEY
docker compose up -d --build
```

`SHARE_PASSWORD_ENCRYPTION_KEY` 用于加密分享链接密码，建议使用 32 字节随机密钥的 Base64 字符串生成，例如 `openssl rand -base64 32`。该值必须长期保持稳定，否则已加密的分享密码无法解密。

默认访问地址：

- Web 前端：`http://localhost:3000`
- 后端 API：`http://localhost:8080`
- Mailpit：`http://localhost:8025`
- SeaweedFS S3：`http://localhost:8333`
- Grafana：通过管理后台日志中心的 Grafana 链接访问，同域路径为 `/grafana/`，仅系统管理员可进入。

前端容器使用 nginx 托管构建产物，并将 `/api/`、`/webdav/` 和 `/grafana/` 分别反向代理到后端、WebDAV 和 Grafana。宿主机端口和前端 Docker 构建参数通过根目录 `.env` 调整；容器运行时变量、后端依赖地址、可观测性组件地址和 Grafana 同域访问地址通过 `config/env-files/*.env` 调整。Web 构建可用 `VITE_API_BASE_URL`、`VITE_WEB_BASE_URL` 覆盖默认地址；Tauri 默认地址可用 `VITE_DESKTOP_API_BASE_URL`、`VITE_DESKTOP_WEB_BASE_URL` 覆盖；Android 默认服务器地址可用 `ANDROID_DEFAULT_SERVER_BASE_URL` 覆盖，Android E2E 可用 `ANDROID_E2E_SERVER_BASE_URL`、`ANDROID_E2E_MAILPIT_BASE_URL` 覆盖，用户仍可在登录页设置自己的服务器地址。

## 可观测性

后端运行日志和审计日志已分离：

- `runtime.log`：Spring Boot 结构化 JSON 运行日志，包含 `request_id`、`trace_id`、`span_id`、`user_id` 等 MDC 字段。
- `audit.log`：独立审计 JSON 行日志，记录认证用户、请求路径、状态码、耗时、脱敏请求头和受控大小的请求体。
- 本地审计表 `operation_logs` 保留完整脱敏审计记录，并新增 `request_id`、`trace_id`、`status_code`、`error_code` 字段，便于和外部日志/trace 关联。

Docker Compose 默认启动 Loki、Promtail、Tempo、OpenTelemetry Collector 和 Grafana。Promtail 从后端日志 volume 采集 runtime/audit 两类日志，应用通过 OTLP HTTP 发送 traces 到 Collector，再转发到 Tempo。相关地址由 `config/env-files/observability.env` 中的 `LOKI_BASE_URL`、`PROMTAIL_LOKI_PUSH_URL`、`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`、`OTEL_COLLECTOR_TEMPO_ENDPOINT`、`GRAFANA_DS_LOKI_URL` 和 `GRAFANA_DS_TEMPO_URL` 控制。OTLP metrics 默认关闭，可通过 `METRICS_EXPORT_OTLP_ENABLED=true` 开启并为 Collector 增加 metrics pipeline。响应头会返回 `X-Request-ID` 和 `X-Trace-ID`，客户端排查问题时可直接用这两个 ID 检索日志。

Grafana 通过 Nginx `auth_request` 接入 BetterCloudDrive 管理员身份。前端点击 Grafana 前会向后端换取短期 HttpOnly cookie，后端确认当前用户仍是 `ROLE_ADMIN` 后，Nginx 才会把请求代理到 Grafana Auth Proxy。`GRAFANA_AUTH_PROXY_SECRET` 用于签名该短期 cookie，生产环境必须替换为随机密钥；`GF_SERVER_ROOT_URL` 控制 Grafana 自身生成的外部访问地址，默认同域挂载在 `/grafana/`。

管理员后台的“日志中心”包含：

- 审计日志：读取 `operation_logs`，支持按用户、动作、requestId、traceId、状态码和结果筛选，并可展开完整脱敏 JSON。
- 系统日志：后端通过 `LOKI_BASE_URL` 代理查询 Loki，只返回概要；每条记录按 traceId/requestId/时间戳生成 ID，并提供 Grafana Explore 链接。后端生成的 Grafana 链接路径由 `GRAFANA_BASE_URL` 控制，默认是同域 `/grafana`；Grafana 自身根地址由 `GF_SERVER_ROOT_URL` 控制。Loki 数据源标识由 `GRAFANA_LOKI_DATASOURCE` 控制，默认使用数据源名称 `Loki`。

### 方式二：本地开发启动

启动依赖和后端：

```bash
docker compose up -d postgres redis seaweedfs mailpit
docker compose up -d --build backend
```

Windows PowerShell：

```powershell
docker compose up -d postgres redis seaweedfs mailpit
docker compose up -d --build backend
```

启动 Web 前端开发服务器：

```bash
cd frontend && npm install && npm run dev
```

开发服务器访问 `http://localhost:5173`，Vite 会把 `/api` 和 `/webdav` 代理到 `VITE_DEV_BACKEND_PROXY_BASE`，默认是 `http://localhost:8080`。

### 构建 Android 应用

```bash
cd android && ./gradlew assembleDebug
```

APK 输出在 `android/app/build/outputs/apk/debug/`。

## API 文档

完整 API 文档见 [docs/API.md](docs/API.md)，涵盖认证、文件管理、上传下载、回收站、分享、收藏、标签、版本管理、注册验证码、密码重置、管理员面板。

## 配置文档

- [配置与部署文档](docs/CONFIGURATION.md)

### 公开端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register-code/send` | 发送注册验证码 |
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/refresh` | 刷新令牌 |
| POST | `/api/v1/auth/forgot-password` | 忘记密码 |
| POST | `/api/v1/auth/reset-password` | 重置密码 |
| POST | `/api/v1/shares/access/{code}` | 访问分享 |
| GET | `/api/v1/shares/access/{code}/files` | 分享文件列表 |

其余端点需 `Authorization: Bearer <accessToken>`。

## 管理员

首个管理员通过数据库手动创建：

```sql
UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'admin';
```

或直接注册后执行上述 SQL。

管理员可访问 `/api/v1/admin/**` 端点进行用户管理、配额调整、日志查看和系统统计。

## 邮件测试

开发环境使用 Mailpit 模拟邮件：

- SMTP：`localhost:1025`（无需认证）
- Web UI：`http://localhost:8025`（查看已发送邮件）

## 测试

```bash
# 后端单元测试
cd backend && mvn test

# Python 黑盒测试
cd backend-test && UV_CACHE_DIR=../.uv-cache uv run pytest -v

# Windows PowerShell:
# cd backend-test
# $env:UV_CACHE_DIR = '..\.uv-cache'
# uv run pytest -v

# 前端单元测试
cd frontend && npx vitest run

# 前端 E2E 测试（需 distrobox）
distrobox enter ubuntu2404-e2e -- npx playwright test
```
