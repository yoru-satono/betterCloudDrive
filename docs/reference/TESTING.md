# 测试策略

BetterCloudDrive 使用分层测试，覆盖后端业务逻辑、真实数据库和对象存储、HTTP API、Web/Tauri/Android 客户端及专项运维能力。覆盖率当前只生成基线报告，不设置失败阈值。

## 一键验证

Windows PowerShell：

```powershell
.\scripts\verify-all.ps1
```

Linux/macOS：

```bash
./scripts/verify-all.sh
```

`all` 包含 `backend`、`integration`、`blackbox`、`security`、`web`、`tauri`、`android`、`office`、`webdav`、`observability` 和 `resilience`。完整验证要求 Docker、rclone、Rust、pnpm、Android SDK 和可用设备。

## 分层与独立命令

| 层 | 命令 | 说明 |
|---|---|---|
| Java 单元测试 | `cd backend && mvn test` | 自动生成各 Maven 模块的 JaCoCo 报告 |
| DAL/S3 集成测试 | `cd backend && mvn verify -Pintegration -DskipUnitTests -pl betterclouddrive-dal,betterclouddrive-storage -am` | Testcontainers 启动 PostgreSQL 18 和 SeaweedFS |
| API 黑盒 | `cd backend-test && uv run pytest -m "not security and not webdav and not office and not observability and not resilience"` | 运行核心 HTTP 验收 |
| 安全验收 | `cd backend-test && uv run pytest -m security` | Token、下载凭证、IDOR 和路径边界 |
| Web 单测/覆盖率 | `cd frontend && pnpm run test:coverage` | Vitest + V8 coverage |
| Tauri Rust 单测 | `cd frontend/src-tauri && cargo test --locked` | 上传、下载、传输和设置逻辑 |
| Android 快速门禁 | `./scripts/verify-all.sh --module android-unit` | JVM 单测、JaCoCo 报告和业务代码 80% 行覆盖率门槛 |
| Android 完整验证 | `./scripts/verify-all.sh --module android` | 快速门禁、设备 Compose/E2E 和仪器覆盖率报告 |

Testcontainers 测试不连接开发数据库，也不复用 Compose 数据；容器端口随机映射，测试结束后自动销毁。Docker 引擎不可用时 `integration` 必须失败，不能静默跳过。

## 覆盖率报告

报告统一写入未跟踪的 `test-results/coverage/`：

- `java/<artifactId>/index.html`
- `web/index.html` 和 `web/coverage-summary.json`
- `android/html/index.html` 和 `android/jacoco.xml`

Android 的 80% 门槛统计 Repository、网络拦截器、本地设置、工具类和 ViewModel；Compose/Hilt/Kotlin 编译生成类、纯资源与 DTO 不进入业务门槛。设备测试通过 AGP 的 `createDebugAndroidTestCoverageReport` 另外生成报告，避免快速门禁依赖模拟器。

覆盖率用于暴露未测试实现。建立稳定基线前不设置阈值；后续门禁应按模块分别设置，避免高覆盖模块掩盖关键模块的空洞。

## 新测试约定

- 单元测试不得依赖执行顺序或共享固定 ID。
- 数据库、对象存储和 E2E 数据使用本次运行的随机前缀，并在测试结束清理。
- 安全测试使用 `security` marker；其他专项测试继续使用 `webdav`、`office`、`observability` 和 `resilience` marker。
- 权限拒绝允许根据隐藏资源策略返回 403 或 404，但不得接受语义 `code=200`。
- 不通过降低断言、增加无条件重试或跳过 Docker 测试来掩盖失败。
