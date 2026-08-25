# BetterCloudDrive KMP 移动端

这是 Android 优先的 Kotlin Multiplatform 重写工程。阶段 6 已在个人文件浏览器上加入新建文件夹、重命名、移动、递归复制、当前目录多选和批量移入回收站；共享国际化资源继续完整支持英语、中国大陆简体中文和新加坡简体中文。旧版 `../android` 应用保持不变，继续作为行为参考和回滚方案。

## 目标与范围

- Android 是当前唯一可运行、可交付的平台。
- `iosArm64` 和 `iosSimulatorArm64` 通过 `-PenableIos=true` 保留公共代码与 Framework 编译能力，阶段 1 不提供 iOS 平台服务或宿主应用。
- 不配置桌面、JVM 应用、JS、Wasm 或 Web 目标。
- 当前可见功能包括启动恢复、登录、个人目录浏览与管理、分页与排序、列表/网格、只读文件详情、根目录上传、单文件下载、独立传输队列、个人设置和独立 Debug 诊断；团队入口仍只显示占位状态，不请求团队接口。

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

共享文案按 Compose Multiplatform 标准目录组织：`values` 保存默认英语，`values-zh-rCN` 与 `values-zh-rSG` 保存两套同名简体中文资源。当前仅 `zh-CN`、`zh-SG` 及带对应地区的 `zh-Hans` 标签选择简体中文；无地区 `zh-Hans`、繁体中文及其他语言回退英语。Android 13+ 的系统“应用语言”页面提供英语、中国大陆简体中文和新加坡简体中文入口；阶段 9 前不提供应用内选择或账号语言同步。`checkMobileI18n` 同时阻止生产 Kotlin 界面硬编码，并校验各翻译目录的键和格式参数一致。

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

阶段 6 继续在 Android 9/API 28 和 Android 14/API 34 上运行设备测试，覆盖个人文件修改、选择模式、中英文界面以及既有 WorkManager/UIDT 回归。标准 Android Emulator 可使用 `http://10.0.2.2:8080` 访问宿主机；若模拟器厂商没有该别名，则在登录页使用开发机局域网地址。Debug 允许本地 HTTP，Release 强制 HTTPS。

当前实现与验收结果见[阶段 6 个人文件管理](docs/stage-6-file-management.md)，国际化基线见[阶段 5 国际化基础](docs/stage-5-localization.md)，个人文件浏览基线见[阶段 4 个人文件浏览](docs/stage-4-personal-browser.md)。
