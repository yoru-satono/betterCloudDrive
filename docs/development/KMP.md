# KMP 移动端开发指南

本文面向修改 `mobile/` 的开发者，说明共享业务代码、当前 Android 应用壳、平台边界和验证方式。该工程是 Kotlin Multiplatform 移动端：Android 已有完整应用壳和设备验证，iOS 当前只验证共享源码元数据，尚无 iOS 应用壳或平台测试。

## 快速入口

当前基线为 Kotlin 2.4.10、Compose Multiplatform 1.11.1、Android Gradle Plugin 9.1.1、Gradle 9.3.1、Ktor 3.5.1 和 Room 2.8.4。应用 ID 为 `com.betterclouddrive.mobile`，当前版本为 `versionCode=23`、`versionName=1.0.0`。

在仓库根目录优先使用统一验证入口：

```bash
bash scripts/verify-all.sh --module mobile-unit
bash scripts/verify-all.sh --module mobile-android --android-serial emulator-5556
```

`mobile-unit` 运行共享主机测试、Android Debug/Release 构建和 iOS 共享源码元数据编译；`mobile-android` 需要显式指定可用的 adb 设备。Windows 对应使用 `scripts/verify-all.ps1` 的 `-Module` 和 `-AndroidSerial` 参数。

## 工程结构

```text
mobile/
├── androidApp/
│   └── src/
│       ├── main/                  # Android Application、Activity 与 Manifest
│       └── androidTest/           # Compose、设备 I/O 与平台能力测试
├── shared/
│   └── src/
│       ├── commonMain/            # 共享 UI、Store、Repository、API、模型与窄平台接口
│       ├── commonTest/            # 共享业务行为、状态机与协议测试
│       ├── androidMain/           # Keystore、SAF、Room、调度、通知和 WebView 实现
│       └── androidHostTest/       # Android/JVM 平台边界测试
├── gradle/libs.versions.toml      # KMP 与 Android 依赖版本
└── gradle/wrapper/                # Gradle 9.3.1 wrapper
```

`shared` 声明 Android、`iosArm64` 和 `iosSimulatorArm64` 目标。iOS 目标通过 `-PenableIos=true` 启用，并从公共源码与依赖生成 `BetterCloudDriveShared` 静态 Framework；当前没有仓库内 iOS 应用壳。

依赖方向保持为 Compose Screen → feature Store → 窄 Repository / `TransferEngine` → domain API / `AuthenticatedHttpClient` / Room → 平台接口。Composable 不直接发网络请求，Repository 不持有界面生命周期对象，`AppCoordinator` 只协调启动、会话失效、退出和服务器切换。

## 服务器地址

登录页负责规范化并持久化服务器地址。Debug 允许本地 HTTP，Release 只接受 HTTPS；不得把个人开发地址或凭据编译进生产状态。

| 调试环境 | 后端地址 |
|----------|----------|
| Android 官方模拟器访问宿主机 | `http://10.0.2.2:8080` |
| Android 真机访问开发电脑 | `http://<电脑局域网 IP>:8080` |
| 第三方模拟器 | 使用该模拟器可访问的宿主机或局域网地址 |

Android 模拟器访问宿主机上的 OnlyOffice 和 Mailpit 时同样使用 `10.0.2.2`。服务器切换必须按既有顺序尽力登出、取消平台任务、释放 URI 权限、删除传输与临时文件、清除令牌，再保存新地址；主题和语言等设备偏好按其独立生命周期保留。

## API 与认证

`mobile/shared/src/commonMain/kotlin/com/betterclouddrive/mobile/core/network/DomainApis.kt` 集中定义移动端请求形式，`AuthenticatedHttpClient` 统一处理 Bearer token、响应 envelope、结构化错误、单飞刷新和最多一次重放。新增或修改协议时：

1. 更新共享 DTO 和对应领域 API，并补充 MockEngine 契约测试。
2. 在窄 Repository 中完成领域映射，不复制认证或 token 刷新逻辑。
3. 由 feature Store 转换为稳定的 loading/success/error 状态，并覆盖取消、重试和 `reset()`。
4. Screen 只接收对应状态与事件，处理加载、空、失败和权限拒绝。

Token、ticket、密码、请求正文和 SAF URI 不得进入日志、路由、Room 中的非必要字段或测试报告。登出、会话失效和服务器切换必须清理服务器绑定的敏感状态。

## 权限与导航

共享产品外壳使用类型安全的 Navigation Compose 路由，固定提供个人、团队、传输和我的四个顶层入口。窄窗口使用底部导航，宽窗口使用侧边导航；路由只携带文件 ID、团队 ID 等稳定标识，不携带下载 ticket 或邀请 token。

团队角色决定团队级入口，服务端返回的有效权限决定具体文件操作。客户端可据此隐藏或禁用按钮，但后端 403/404 仍是最终授权结果；权限拒绝后应使相关缓存失效并刷新。个人与团队 Store 必须隔离作用域，切换团队、退出或切换服务器时取消旧请求并清除对应缓存。

## 传输与预览

- `TransferApi` 和 `TransferEngine` 处理分片/秒传、流式下载、Range 恢复及个人/团队作用域。
- Room 保存完整任务状态；平台 Worker 或 Job 只接收任务 ID，真实参数从数据库读取。
- Android 14+ 长传输使用 UIDT，Android 13 及以下回退 WorkManager；上传源和下载目标通过 SAF 获取可持久化 URI 权限。
- 下载先流式写入应用私有 `.part`，校验 Range、长度和可选 MD5 后再提交到用户目标；大文件不得整体读入 UI 层内存。
- 图片使用 Coil 且不持久缓存 ticket URL，视频使用无媒体缓存的 Media3，UTF-8 文本最多读取前 512 KiB，OnlyOffice 原样渲染后端签名的 `mobile=true` 只读配置。

成功或取消的任务应释放不再使用的 URI 权限；失败、暂停和等待登录可保留权限以便恢复。退出和服务器切换还要取消平台任务、清理 Room 记录与临时文件。平台通知可以使用共享语义资源，但不得泄漏服务器凭据或本地 URI。

## 平台边界

`commonMain` 不得直接依赖 Android API。Keystore、SAF、UIDT/WorkManager、通知、WebView、Media3 和 Android per-app locale 等能力留在 `androidMain` 或 `androidApp`，通过 `core/platform/PlatformContracts.kt` 中的窄接口向共享代码提供。

新增平台能力时优先扩展小型接口；只有编译期平台差异无法通过注入表达时才使用 `expect`/`actual`。共享 DTO、领域规则、Repository、Store、Compose UI 和导航应保持可供 iOS 复用。iOS 当前门禁只证明 source set 与公开 API 的编译边界，不等同于 Framework 链接、应用构建或平台行为测试。

## 测试矩阵

| 修改范围 | 最低验证 |
|----------|----------|
| `commonMain` 模型、API、Repository 或 Store | `bash scripts/verify-all.sh --module mobile-unit` |
| Ktor DTO 或共同 API | MockEngine 契约、相关 Store/Repository 测试，并按协议影响补后端、Web 和 Qt 验证 |
| Keystore、SAF、Room、通知或平台调度 | `mobile-unit`，并在 API 28 和 API 34 分别运行 `mobile-android` |
| `androidApp`、Manifest、网络安全配置或 R8 | Android 设备测试和 Release 构建 |
| Compose UI、导航、权限动作或系统选择器 | 相关共享状态测试和 Android instrumentation |
| iOS source set 或公共平台接口 | `mobile-unit` 中的 iOS metadata 门禁；有 macOS/iOS 应用壳后再增加平台构建与测试 |

需要独立排查时可运行：

```bash
cd mobile
bash ./gradlew :shared:testAndroidHostTest
bash ./gradlew :androidApp:assembleDebug :androidApp:assembleRelease
bash ./gradlew -PenableIos=true :shared:compileIosMainKotlinMetadata
ANDROID_SERIAL=<device-id> bash ./gradlew :androidApp:connectedDebugAndroidTest
```

API 28 与 API 34 分别覆盖 WorkManager 和 UIDT。AVD 生命周期在验证脚本外管理；本机 Emulator 37.1.11 无窗口运行时应按[测试策略](TESTING.md)使用 `-gpu host`，两套 AVD 依次占用同一端口。

## 提交前检查

- 确认共享代码没有新增 Android API 依赖，敏感值没有进入日志、持久状态或报告。
- 运行与修改范围匹配的 `mobile-unit` / `mobile-android`，设备相关改动覆盖 API 28 和 API 34。
- 跨客户端协议变化同步检查后端、Web、Qt、KMP DTO、错误语义和调用方。
- 更新相关架构、API、本地化、配置或测试文档，并运行 Markdown 相对链接检查。
- 运行 `git diff --check`，不提交 SDK、AVD、IDE 配置、Gradle 缓存、构建产物或测试报告。

完整分层与环境要求见[测试策略](TESTING.md)，稳定客户端边界见[客户端架构](../architecture/CLIENTS.md)。
