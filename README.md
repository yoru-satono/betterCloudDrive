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
- **管理员面板**：用户管理/配额调整/操作日志/系统统计
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
├── docs/API.md              # API 文档（57 端点）
├── redis/redis.conf         # Redis 配置
└── docker-compose.yml       # 服务编排
```

## 快速启动

### 方式一：Docker Compose 启动全栈

设置 JWT 密钥后启动全栈服务。Compose 会自动构建后端 JAR 和前端静态资源，不需要提前生成 `target/`：

```bash
export JWT_ACCESS_SECRET=dGhpcy1pcy1hLTMyLWJ5dGUtc2VjcmV0LWtleS1mb3ItYWNjIQ==
export JWT_REFRESH_SECRET=dGhpcy1pcy1hLTMyLWJ5dGUtcmVmcmVzaC1zZWNyZXQta2V5ISE=
export SHARE_PASSWORD_ENCRYPTION_KEY=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=
docker compose up -d --build
```

默认访问地址：

- Web 前端：`http://localhost:3000`
- 后端 API：`http://localhost:8080`
- Mailpit：`http://localhost:8025`
- SeaweedFS S3：`http://localhost:8333`

前端容器使用 nginx 托管构建产物，并将 `/api/` 和 `/webdav/` 反向代理到 Compose 网络内的 `backend:8080`。可通过 `FRONTEND_PORT`、`BACKEND_PORT`、`POSTGRES_PORT`、`REDIS_PORT`、`MAILPIT_UI_PORT` 等环境变量调整宿主机端口。

### 方式二：本地开发启动

启动依赖和后端：

```bash
docker compose up -d postgres redis seaweedfs mailpit
export JWT_ACCESS_SECRET=dGhpcy1pcy1hLTMyLWJ5dGUtc2VjcmV0LWtleS1mb3ItYWNjIQ==
export JWT_REFRESH_SECRET=dGhpcy1pcy1hLTMyLWJ5dGUtcmVmcmVzaC1zZWNyZXQta2V5ISE=
export SHARE_PASSWORD_ENCRYPTION_KEY=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=
docker compose up -d --build backend
```

启动 Web 前端开发服务器：

```bash
cd frontend && npm install && npm run dev
```

开发服务器访问 `http://localhost:5173`，Vite 会把 `/api` 和 `/webdav` 代理到 `http://localhost:8080`。

### 构建 Android 应用

```bash
cd android && ./gradlew assembleDebug
```

APK 输出在 `android/app/build/outputs/apk/debug/`。

## API 文档

完整 API 文档见 [docs/API.md](docs/API.md)，涵盖认证、文件管理、上传下载、回收站、分享、收藏、标签、版本管理、注册验证码、密码重置、管理员面板。

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

管理员可访问 `/api/v1/admin/**` 端点进行用户管理、配额调整、操作日志查看和系统统计。

## 邮件测试

开发环境使用 Mailpit 模拟邮件：

- SMTP：`localhost:1025`（无需认证）
- Web UI：`http://localhost:8025`（查看已发送邮件）

## 测试

```bash
# 后端单元测试
cd backend && mvn test

# Python 黑盒测试
cd backend-test && uv sync && uv run pytest -v

# 前端单元测试
cd frontend && npx vitest run

# 前端 E2E 测试（需 distrobox）
distrobox enter ubuntu2404-e2e -- npx playwright test
```
