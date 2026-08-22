# 阶段 0 调研结论

## 决策

采用 Compose Multiplatform，以 Android 优先的方式推进 KMP 重写。在新应用达到功能对等之前，保留现有 `android/` 应用作为行为参考和回滚方案。

阶段 0 的模块边界如下：

- `androidApp`：仅作为 Android 应用外壳。
- `shared/commonMain`：包含 Compose UI、DTO、服务端地址处理、Ktor 工作流和 Room 数据库结构。
- `shared/androidMain`：包含 OkHttp 引擎、Android 文件选择器、Room 数据库构建和 OnlyOffice WebView。
- iOS 目标：仅在使用 `-PenableIos=true` 时注册；阶段 0 不包含 iOS 应用或平台实现。

项目未配置桌面端、JVM 应用、JS、Wasm 或 Web 目标。

## 冻结的技术基线

| 组件 | 阶段 0 选型 |
| --- | --- |
| Kotlin | 2.4.10 |
| Compose Multiplatform | 1.11.1 |
| Android Gradle Plugin | 9.1.1 |
| Gradle Wrapper | 9.3.1 |
| Android SDK | 编译/目标版本 37，最低版本 26 |
| Ktor | 3.5.1；Android 使用 OkHttp，iOS 预留 Darwin |
| Room | 2.8.4，搭配 bundled SQLite 2.6.2 和 KSP 2.3.10 |
| Coroutines / Serialization | 1.11.0 / 1.11.0 |

Android 外壳特意划分为独立模块，因为 AGP 9 的 KMP Android Library 插件不会生成应用 APK。

## 技术探针结果

| 能力 | 结果 | 阶段 0 证据 |
| --- | --- | --- |
| 共享 Compose UI | 通过 | Android 应用可以基于 `commonMain` 中的界面完成编译 |
| 动态后端地址 | 通过 | URL 规范化已有主机端测试；真机通过选定的局域网地址访问了后端 |
| 现有认证和文件 API | 通过 | 真机登录成功；上传后根目录列表从 0 项变为 1 项 |
| 系统文件选择器 | 通过 | Android `OpenDocument` 从 Downloads 选择并读取了一个 1,541 字节的文件 |
| 单分片上传 | 通过 | 初始化、multipart 分片 0、完成上传及后续根目录查询均成功；后端返回 `fileId=2` |
| Room KMP | 通过 | KSP 生成了数据库结构文件 `1.json`；真机写入/读取探针返回 `Room KMP 验证成功` |
| 团队 OnlyOffice 文档渲染 | 通过 | Android 收到 `onDocumentReady`；测试文档标题和标记文本已在移动编辑器中渲染 |
| 团队 OnlyOffice 移动端策略 | 通过 | 移动端请求获得后端签名的只读配置；Community 9.3 可以渲染文档且不暴露编辑能力 |
| Debug APK | 通过 | `:androidApp:assembleDebug` |
| Release/R8/lint vital | 通过 | `:androidApp:assembleRelease`；产物按设计未签名 |
| iOS 能力预留 | 通过 | 启用开关后会注册 arm64 真机/模拟器 Framework、Compose 资源任务、Darwin 客户端依赖和 Room KSP 任务 |

## 运行时验收环境

运行时验收于 2026-08-20 完成，环境矩阵如下：

| 组件 | 运行环境 |
| --- | --- |
| Android 设备 | vivo V2284A，Android 15 / API 35，无线 ADB |
| 后端 | 本地 Docker Compose 服务栈；backend、PostgreSQL、Redis、RabbitMQ、SeaweedFS、Mailpit 和 OnlyOffice 均健康 |
| 设备访问地址 | 后端 `http://192.168.0.161:8080`；OnlyOffice `http://192.168.0.161:8082` |
| OnlyOffice | Document Server Community `9.3.0.1` |
| 测试文档 | 带有唯一运行时标记的团队文档 `stage0-office.docx` |

首次真机登录暴露出 JSON 请求缺少内容类型的问题。虽然已经安装 `ContentNegotiation`，Ktor 仍无法序列化 `LoginRequest`。登录和上传初始化请求现在会显式声明 `application/json`；`BackendClientTest` 会同时检查发出请求的内容类型和序列化正文，防止问题回归。

首次 OnlyOffice 渲染使用后端的桌面端配置，导致 Android WebView 出现无法使用的空白视口。最初在客户端覆盖 `type=mobile` 后可以渲染文档，但配置仍携带已签名的编辑权限，因此 Community 9.3 会显示移动编辑商业许可警告。最终实现会请求 `mobile=true`；后端签发不含回调的移动端只读配置，Android 原样加载配置，并通过共享 UI 上报 `onAppReady`、`onDocumentReady` 和 `onError`。在同一台 vivo 设备上复测时，应用进入 `OnlyOffice 文档已就绪` 状态并清晰显示测试标记；等待超过 10 秒后未出现许可警告节点，Logcat 中也没有严重错误或网络错误。

## 平台代码边界

阶段 0 的 Kotlin 源码统计如下：

- 共享 `commonMain`：6 个文件，共 496 行。
- Android 平台实现及应用外壳：2 个文件，共 177 行。
- 按 Kotlin 代码行计算的共享比例：约 74%。

该比例是有意采用的保守统计：当前技术探针包含相对较大的 WebView 适配器，但只覆盖了很小一部分业务功能。随着业务界面和工作流逐步加入 `commonMain`，共享比例应继续提高。平台代码在实际项目中无法完全归零，因为生命周期入口、文件句柄、HTTP 引擎、安全存储、后台传输、通知和 WebView 集成都依赖操作系统 API。

源码扫描确认 `commonMain` 不包含 `android.*`、`java.*`、Activity 或 OkHttp 导入。后续新增的平台能力应继续通过 `PlatformContracts.kt` 中职责收敛的接口接入。

## 阶段 0 接受的已知限制

以下内容是阶段 0 结束时的历史快照，不代表当前阶段 1 实现；已解除的限制在文末“阶段 1 交接状态”中说明。

- Access Token 和 Refresh Token 暂存于内存中。安全持久化和刷新流程编排属于阶段 1。
- 上传功能最多将 5 MB 数据读入内存，并且只发送一个分片。流式传输、断点续传、哈希、重试、WorkManager 和进度展示属于后续传输实现阶段。
- 当前仅展示个人根目录文件列表。目录导航、团队空间、搜索、排序和修改操作不属于阶段 0。
- OnlyOffice 适配器会把 `localhost`/`127.0.0.1` 映射到模拟器宿主地址 `10.0.2.2`，并允许开发环境使用明文流量。生产环境网络策略必须以 HTTPS 为基础。
- 移动端 OnlyOffice 按产品决策仅支持查看。以后启用移动编辑需要明确的产品决策、兼容的商业许可或替代集成方案，并重新验证保存和回调流程。
- iOS 已在结构上启用，但按计划尚未实现，也无法在 Windows 上完成链接。Framework 编译和 iOS 宿主应用需要 macOS/Xcode。
- Release APK 未签名，并使用技术探针专用的应用 ID，因此可以与旧版应用共存。

## 可复现的验收命令

在 `mobile/` 目录执行：

```powershell
.\gradlew.bat --no-parallel --max-workers=2 :shared:testAndroidHostTest :androidApp:assembleDebug
.\gradlew.bat --no-parallel --max-workers=2 :androidApp:assembleRelease
.\gradlew.bat -PenableIos=true :shared:tasks --all
```

开发网络曾间歇性无法访问 Google Maven 端点。因此，`settings.gradle.kts` 会优先检查仅包含 Google 构件的镜像，并保留官方 Google Maven 作为回退；所有非 Google 依赖组仍从 Maven Central 解析。

## 阶段 1 交接状态

阶段 1 已在不调整技术基线的前提下建立生产基础原型，但不代表生产可靠性或旧 Android 业务迁移已经完成。以下阶段 0 准入项已经落地，当前实现与运行时证据见[阶段 1 架构与验收](stage-1-architecture.md)：

1. 共享代码已按 core、network、database、design-system 和 feature 包拆分，并由手动 `AppContainer` 装配。
2. Android 已实现 Keystore 加密会话、刷新串行化、退出登录和切换服务端时的状态失效。
3. 原单分片内存探针已替换为 Room 持久化、流式可恢复传输，以及 UIDT/WorkManager 后台执行。
4. 主机和设备测试已覆盖认证并发刷新、语义错误、Range 恢复、Keystore 损坏和平台调度选择等核心边界。
5. 移动端继续原样使用后端签名的 `mobile=true` 只读 OnlyOffice 配置。
