# BetterCloudDrive

全栈云盘系统，支持 Web、Tauri 桌面端、Android 和 WebDAV 访问。

## 技术栈

| 层 | 技术 |
|---|------|
| **后端** | Spring Boot 4.1.0 + JDK 25 + Spring Data JPA |
| **数据库** | PostgreSQL 18.4 + Flyway 迁移 |
| **缓存** | Redis 8.8.1（限流、上传进度、配额同步、Token 黑名单） |
| **消息队列** | RabbitMQ 4（团队通知、后台任务、死信队列） |
| **存储** | SeaweedFS（S3 兼容对象存储） |
| **邮件** | Mailpit（开发环境邮件模拟） |
| **可观测性** | Actuator/Micrometer + Prometheus + Alertmanager + Loki + Grafana Alloy + Grafana |
| **Web 前端** | Vue 3.5.40 + Vite 8.1.5 + Pinia 4.0.2 + Vue Router 5.2.0 |
| **桌面端** | Tauri 2.11.4 + Rust 1.88.0 |
| **Android** | Kotlin 2.3.21 + Jetpack Compose 2026.06.00 + Android SDK 37 + Hilt + Retrofit |
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
- **团队空间**：团队文件、成员/邀请、角色权限、团队分享/标签/收藏、版本恢复、审计日志、管理员配额运维
- **分区导航**：个人网盘与团队空间使用独立首页和导航，通过应用级工作空间切换器双向切换；通知、资料和传输队列为公共功能
- **注册验证码 & 密码重置**：Mailpit 模拟邮件
- **存储配额**：默认 10GB/用户，管理员可调整
- **管理员面板**：用户管理/配额调整/日志中心/系统统计
- **响应式 UI**：Web 端桌面+移动端；KMP Android 端手机+平板双布局，共享业务层面向后续 iOS 客户端复用

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
├── frontend/                # Vue 3 Web 前端与 Tauri 桌面壳
├── mobile/                  # 活跃的 Kotlin Multiplatform 移动端
├── android/                 # 仅供 KMP 迁移参考的旧 Android 应用
├── backend-test/            # Python 黑盒 API 测试
├── observability/           # Prometheus/Loki/Alloy/Alertmanager/Grafana 配置
├── docs/                    # 文档索引、参考资料、运维手册和决策记录
│   └── README.md            # 文档入口
├── config/redis/redis.conf  # Redis 配置
└── docker-compose.yml       # 服务编排
```

## 快速启动

### 方式一：Docker Compose 启动全栈

环境变量按模块拆分在 `config/env-files/` 下，并由 `docker-compose.yml` 的服务级 `env_file` 显式注入。根目录 `.env` 负责 Compose 插值，包括宿主机端口、共享应用地址、观测选项和前端构建参数。首次启动请先按 [Docker Compose 快速启动](docs/getting-started/QUICKSTART.md#1-创建本地配置)创建缺失配置并替换本地密钥；已有实际配置不得用模板覆盖。

配置完成后启动全栈：

```bash
docker compose up -d --build
```

`SHARE_PASSWORD_ENCRYPTION_KEY` 用于加密分享链接密码，建议使用 32 字节随机密钥的 Base64 字符串生成，例如 `openssl rand -base64 32`。该值必须长期保持稳定，否则已加密的分享密码无法解密。

完整健康检查和首次注册步骤仍以 [Docker Compose 快速启动](docs/getting-started/QUICKSTART.md)为准。

默认访问地址：

- Web 前端：`http://localhost:3000`
- 后端 API：`http://localhost:8080`
- Mailpit：`http://localhost:8025`
- OnlyOffice Document Server：`http://localhost:8082`
- SeaweedFS S3：`http://localhost:8333`
- Prometheus、Alertmanager、Loki：默认仅容器网络可访问；使用诊断 overlay 后分别开放在本机 `9090`、`9093`、`3100`
- Grafana：通过管理后台日志中心的 Grafana 链接访问，同域路径为 `/grafana/`，仅系统管理员可进入。

部署细节见 [OnlyOffice 部署与排障](docs/operations/ONLYOFFICE_DEPLOYMENT.md) 和 [数据备份与恢复](docs/operations/BACKUP_RESTORE.md)。

前端容器使用 nginx 托管构建产物，并将 `/api/`、`/webdav/` 和 `/grafana/` 分别反向代理到后端、WebDAV 和 Grafana。宿主机端口和前端 Docker 构建参数通过根目录 `.env` 调整；容器运行时变量、后端依赖地址、可观测性组件地址和 Grafana 同域访问地址通过 `config/env-files/*.env` 调整。Web 构建可用 `VITE_API_BASE_URL`、`VITE_WEB_BASE_URL` 覆盖默认地址；Tauri 默认地址可用 `VITE_DESKTOP_API_BASE_URL`、`VITE_DESKTOP_WEB_BASE_URL` 覆盖；Android 默认服务器地址可用 `ANDROID_DEFAULT_SERVER_BASE_URL` 覆盖，Android E2E 可用 `ANDROID_E2E_SERVER_BASE_URL`、`ANDROID_E2E_MAILPIT_BASE_URL` 覆盖，用户仍可在登录页设置自己的服务器地址。

## 可观测性

后端运行日志和审计日志已分离：

- `runtime.log`：Spring Boot 结构化 JSON 运行日志，包含 `request_id`、`trace_id`、`span_id`、`user_id` 等 MDC 字段。
- `audit.log`：独立审计 JSON 行日志，记录认证用户、请求路径、状态码、耗时、脱敏请求头和受控大小的请求体。
- 本地审计表 `operation_logs` 保留完整脱敏审计记录，并新增 `request_id`、`trace_id`、`status_code`、`error_code` 字段，便于和外部日志/trace 关联。

Docker Compose 默认启动 Prometheus、Alertmanager、Loki、Grafana Alloy、Blackbox Exporter 和 Grafana。Prometheus 采集应用、RabbitMQ、观测组件及四个关键 HTTP 探测目标；Alertmanager 在开发环境将告警邮件发送到 Mailpit。Alloy 从后端日志 volume 采集 runtime/audit 两类日志。应用保留进程内 Trace 上下文但不导出完整 Trace，响应头中的 `X-Request-ID` 和 `X-Trace-ID` 用于关联审计记录与 Loki 日志。Grafana 预置应用总览、存储传输和异步协作三个 Dashboard。

观测诊断端口默认不发布。需要从宿主机运行验收或排障时使用：

```powershell
docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml up -d
```

Grafana 通过 Nginx `auth_request` 接入 BetterCloudDrive 管理员身份。前端点击 Grafana 前会向后端换取短期 HttpOnly cookie，后端确认当前用户仍是 `ROLE_ADMIN` 后，Nginx 才会把请求代理到 Grafana Auth Proxy。`GRAFANA_AUTH_PROXY_SECRET` 用于签名该短期 cookie，生产环境必须替换为随机密钥；`GF_SERVER_ROOT_URL` 控制 Grafana 自身生成的外部访问地址，默认同域挂载在 `/grafana/`。

管理员后台的“日志中心”包含：

- 审计日志：读取 `operation_logs`，支持按用户、动作、requestId、traceId、状态码和结果筛选，并可展开完整脱敏 JSON。
- 系统日志：后端通过 `LOKI_BASE_URL` 代理查询 Loki，只返回概要；每条记录按 traceId/requestId/时间戳生成 ID，并提供 Grafana Explore 链接。后端生成的 Grafana 链接路径由 `GRAFANA_BASE_URL` 控制，默认是同域 `/grafana`；Grafana 自身根地址由 `GF_SERVER_ROOT_URL` 控制。Loki 数据源标识由 `GRAFANA_LOKI_DATASOURCE` 控制，默认使用数据源名称 `Loki`。

### 方式二：本地开发启动

按后端、Web、Tauri 或 Android 组合本地环境的完整说明见[开发环境准备](docs/getting-started/DEVELOPMENT_SETUP.md)和[专项开发指南](docs/development/README.md)。下面仅保留最短 Web 联调入口。

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
cd frontend && pnpm install && pnpm run dev
```

开发服务器访问 `http://localhost:5173`，Vite 会把 `/api` 和 `/webdav` 代理到 `VITE_DEV_BACKEND_PROXY_BASE`，默认是 `http://localhost:8080`。

### 构建 KMP 移动端

```bash
cd mobile
sh ./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileIosMainKotlinMetadata
```

Android APK 输出在 `mobile/androidApp/build/outputs/apk/debug/`。`shared` 已声明 `iosArm64` 和 `iosSimulatorArm64` 目标，当前门禁会编译 iOS 共享源码元数据；后续 iOS 应用壳仍属于 KMP 移动端规划。旧版 `android/` 不再纳入构建和测试入口。

## API 文档

完整 API 参考从 [API 总入口](docs/reference/API.md)进入；通用响应和错误码保留在总入口，具体端点按认证、文件管理、传输、分享、团队、通知、OnlyOffice 和管理员等业务域组织。

客户端展示文案采用后端语义 key + 结构化参数协议，详见 [客户端本地化与语义文案协议](docs/reference/LOCALIZATION.md)。此协议不包含自然语言兜底字段，旧客户端不能与当前后端混用。

Web、Tauri 与 Android 支持“跟随系统、浅色、深色”三种主题模式。首次启动默认跟随系统，用户可在个人设置的“外观”区域选择固定主题；偏好仅保存在当前设备并跨登录状态保留。

## 文档

- [统一文档入口](docs/README.md)
- [入门指南](docs/getting-started/README.md)
- [架构文档](docs/architecture/README.md)
- [开发指南](docs/development/README.md)
- [参考资料](docs/reference/README.md)
- [运维手册](docs/operations/README.md)

公开端点、Bearer Token 例外、下载 ticket、OnlyOffice token 和 WebDAV Basic Auth 的精确定义见 [API 通用认证约定](docs/reference/API.md#71-认证)。管理员接口和权限要求见[管理员 API](docs/reference/api/ADMIN.md)。

## 邮件测试

开发环境使用 Mailpit 接收验证码、邀请和密码重置邮件。端口与使用方式见 [Quickstart](docs/getting-started/QUICKSTART.md)；生产 SMTP 配置见[配置参考](docs/reference/CONFIGURATION.md)。

## 测试

测试分层、独立命令、最低验证矩阵、Testcontainers 依赖和报告位置见[测试策略](docs/development/TESTING.md)。Android 测试只按行为和场景断言验收，不生成或检查代码行覆盖率。

Windows PowerShell：

```powershell
.\scripts\verify-all.ps1
```

Linux/macOS：

```bash
./scripts/verify-all.sh
```

完整运行包含需要 Docker、Rust、rclone 和 Android 设备的阶段；开发时应按[最低验证矩阵](docs/development/TESTING.md#修改范围到最低验证)选择子集。
