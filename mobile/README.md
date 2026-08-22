# BetterCloudDrive KMP 移动端

这是 Android 优先的 Kotlin Multiplatform 重写工程。阶段 4 已将个人空间扩展为支持目录导航、50 条分页、稳定排序、列表/网格和只读文件详情的文件浏览器；登录后仍通过“个人、团队、传输、我的”四个固定入口切换页面。旧版 `../android` 应用保持不变，继续作为行为参考和回滚方案。

## 目标与范围

- Android 是当前唯一可运行、可交付的平台。
- `iosArm64` 和 `iosSimulatorArm64` 通过 `-PenableIos=true` 保留公共代码与 Framework 编译能力，阶段 1 不提供 iOS 平台服务或宿主应用。
- 不配置桌面、JVM 应用、JS、Wasm 或 Web 目标。
- 当前可见功能包括启动恢复、登录、个人目录浏览、分页与排序、列表/网格、只读文件详情、根目录上传、单文件下载、独立传输队列、个人设置和独立 Debug 诊断；团队入口仍只显示占位状态，不请求团队接口。

## 工程结构

```text
mobile/
├── androidApp/                         # Android Application、Activity、Manifest、设备测试
└── shared/src/
    ├── commonMain/.../core/            # DTO、网络、Room、窄平台接口
    ├── commonMain/.../app/             # 类型安全路由、产品外壳和页面状态
    ├── commonMain/.../designsystem/    # Compose Multiplatform 浅色/深色主题
    ├── commonMain/.../feature/         # 文件与可恢复传输状态机
    └── androidMain/.../platform/       # Keystore、SAF、UIDT/WorkManager、WebView
```

依赖由手动 `AppContainer` 创建，不引入第三方 DI。共享 UI 使用 Navigation Compose 2.9.2 类型安全路由；Activity 只提供 Compose 入口和 Android 平台服务。传输任务完整保存到 Room，平台调度只传任务 ID。Android 14+ 使用 UIDT，Android 13 及以下使用 WorkManager。下载通过 Ktor `prepareGet().execute {}` 在响应作用域内直接写入应用私有 `.part`，通知进度最多每秒投递一次，Compose 和 Room 进度不受影响。

## 构建与测试

在仓库根目录执行：

```powershell
.\mobile\gradlew.bat -p mobile :shared:testAndroidHostTest
.\mobile\gradlew.bat -p mobile :androidApp:assembleDebug
.\mobile\gradlew.bat -p mobile :androidApp:assembleRelease
$env:ANDROID_SERIAL = '<adb 设备序列号>'
.\mobile\gradlew.bat -p mobile :androidApp:connectedDebugAndroidTest
```

验证 iOS 公共代码元数据：

```powershell
.\mobile\gradlew.bat -p mobile -PenableIos=true :shared:compileIosMainKotlinMetadata
```

Windows 无法链接 iOS Framework；完整 iOS 构建仍需 macOS/Xcode。首次启用 iOS 会下载 Kotlin/Native 工具链，后续构建会复用缓存。

也可以通过仓库统一验证脚本执行：

```powershell
.\scripts\verify-all.ps1 -Module mobile-unit
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial '<adb 设备序列号>'
```

阶段 4 继续在 Android 9/API 28 和 Android 14/API 34 上运行设备测试，覆盖多级目录、面包屑、分页、排序、响应式详情以及既有 WorkManager/UIDT 回归。标准 Android Emulator 可使用 `http://10.0.2.2:8080` 访问宿主机；若模拟器厂商没有该别名，则在登录页使用开发机局域网地址。Debug 允许本地 HTTP，Release 强制 HTTPS。

当前实现与验收结果见[阶段 4 个人文件浏览](docs/stage-4-personal-browser.md)，产品外壳基线见[阶段 3 产品应用外壳](docs/stage-3-product-shell.md)，传输可靠性证据见[阶段 2 传输可靠性与验收](docs/stage-2-reliability.md)，阶段 1 基础快照见[阶段 1 架构与验收](docs/stage-1-architecture.md)，阶段 0 的调研证据见[阶段 0 调研结论](docs/stage-0-findings.md)。
