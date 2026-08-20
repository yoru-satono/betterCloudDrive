# 开发环境准备

本文面向需要修改代码的开发者，给出按模块安装工具和组合运行的方法。无需为了只改一个客户端安装全部工具。

## 工具矩阵

| 工作范围 | 必需工具 | 仓库基线 |
|----------|----------|----------|
| 基础设施或全栈 | Docker + Compose v2 | 版本由 Compose 镜像固定 |
| 后端 | JDK、Maven | JDK 25；Maven 使用项目 POM |
| Python 黑盒测试 | Python、推荐 `uv` | Python 3.12+ |
| Web | Node.js、pnpm | Node.js 24.18.0、pnpm 11.16.0 |
| Tauri | Web 工具、Rust、平台依赖 | Rust 1.88.0，由 `rust-toolchain.toml` 固定 |
| Android | Android Studio/SDK、JDK | SDK 37、Gradle wrapper 9.6.1、构建 JVM 17 |
| 完整验收 | 上述工具、rclone、adb 和可用 Android 设备 | 由验证脚本预检 |

精确依赖版本以各构建文件为准。升级工具链时同时更新文档入口中的版本基线。

## 初始化配置

先按 [Quickstart](QUICKSTART.md#1-创建本地配置) 创建 `.env` 和八个模块配置文件，并设置安全密钥。验证脚本会自动复制缺失模板，但不会替换已有文件。

## 推荐开发组合

### 后端本机运行，基础设施使用容器

先确保没有 Compose `backend` 占用 8080，然后启动依赖：

```bash
docker compose up -d postgres redis rabbitmq seaweedfs mailpit
```

设置三个安全变量后构建并运行：

```bash
cd backend
mvn -pl betterclouddrive-start -am package -DskipTests
java -jar betterclouddrive-start/target/betterclouddrive-start.jar
```

本机运行默认使用 `dev` profile，连接 localhost 上的 PostgreSQL、Redis、RabbitMQ 和 SeaweedFS。也可以在 IDE 中运行 `com.betterclouddrive.BetterCloudDriveApplication`，但必须传入相同环境变量。

需要联调 OnlyOffice 时额外启动 Document Server：

```bash
docker compose up -d onlyoffice-documentserver
```

本机后端访问它应使用 `ONLYOFFICE_INTERNAL_URL=http://localhost:8082`；容器中的 Document Server 回调本机后端时，`APP_INTERNAL_API_BASE_URL` 需要使用 Docker 能访问的宿主机地址，例如 Docker Desktop 的 `http://host.docker.internal:8080/api/v1`。

### Web 热更新

保持后端位于 8080：

```bash
cd frontend
pnpm install
pnpm run dev
```

访问 `http://localhost:5173`。Vite 将 `/api` 和 `/webdav` 代理到 `VITE_DEV_BACKEND_PROXY_BASE`，默认是 `http://localhost:8080`。

### Tauri 桌面端

完成 Web 和平台 Rust 依赖安装后：

```bash
cd frontend
pnpm install
pnpm run tauri:dev
```

桌面端默认连接 `http://127.0.0.1:8080/api/v1`，登录页可覆盖 API 和 Web 分享地址。具体平台依赖与调试方法见 [Tauri 开发指南](../development/TAURI.md)。

### Android

用 Android Studio 打开 `android`，同步 Gradle 并选择 API 26+ 的设备。Android 模拟器访问宿主机后端时使用 `http://10.0.2.2:8080`，真机必须使用电脑在局域网中的可达地址。

命令行构建：

```bash
cd android
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat`。详细结构和测试见 [Android 开发指南](../development/ANDROID.md)。

## 按修改范围选择启动项

| 修改范围 | 最小运行组合 |
|----------|--------------|
| 通用 Java/纯业务单元 | Maven 对应模块测试，不必启动 Compose |
| Repository/数据库行为 | Docker 引擎 + Testcontainers 集成测试 |
| REST/安全/队列端到端 | Compose 后端栈 + Python 验收 |
| Vue 页面/Store/API wrapper | 本机或 Compose 后端 + Vite |
| Tauri 原生传输 | 后端 + Tauri dev；目录/下载功能还需本地文件系统权限 |
| Android Repository/ViewModel | JVM 测试；真实传输/Compose 场景再连接设备 |
| OnlyOffice | 后端、前端、Document Server，并确认浏览器与容器双向地址 |

最低验证矩阵见[测试策略](../development/TESTING.md)。

## 避免环境互相干扰

- 不要同时运行 Compose backend 与本机 backend，除非修改其中一个端口。
- Testcontainers 使用随机端口和独立数据，不应连接开发数据库。
- E2E 数据使用运行 ID 和测试邮箱域；运行结束保留默认清理步骤。
- 客户端调试地址区分 `localhost`、容器服务名、`host.docker.internal`、Android 模拟器 `10.0.2.2` 和真机局域网地址。
- 不要把真实密钥写入 `.env.example`、模块模板或测试代码。
