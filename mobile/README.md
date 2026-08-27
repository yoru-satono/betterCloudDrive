# BetterCloudDrive KMP 移动端

这是 Android 优先的 Kotlin Multiplatform 重写工程。当前版本在个人文件浏览、管理、传输、搜索和只读预览基础上，补齐了注册、密码找回、账号资料、容量、应用语言、主题与服务器切换。共享国际化资源支持英语、中国大陆简体中文和新加坡简体中文。旧版 `../android` 应用保持不变，继续作为行为参考和回滚方案。

## 目标与范围

- Android 是当前唯一可运行、可交付的平台。
- `iosArm64` 和 `iosSimulatorArm64` 通过 `-PenableIos=true` 保留公共代码与 Framework 编译能力，阶段 1 不提供 iOS 平台服务或宿主应用。
- 不配置桌面、JVM 应用、JS、Wasm 或 Web 目标。
- 当前可见功能包括启动恢复、登录、注册与密码找回、个人目录浏览与管理、分页与排序、列表/网格、全局文件名搜索、只读文件详情与预览、任意目录多文件上传、单文件下载、独立传输队列、账号资料与容量、语言、主题、服务器切换和独立 Debug 诊断；团队入口仍只显示占位状态，不请求团队接口。

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

依赖由手动 `AppContainer` 创建，不引入第三方 DI。共享 UI 使用 Navigation Compose 2.9.2 类型安全路由；Activity 只提供 Compose 入口和 Android 平台服务。传输任务完整保存到 Room；一次用户操作最多形成一个包含 100 个任务 ID 的系统批次，批次执行器仍通过全局互斥逐项传输。Android 14+ 使用 UIDT，Android 13 及以下使用 WorkManager。下载通过 Ktor `prepareGet().execute {}` 在响应作用域内直接写入应用私有 `.part`，通知进度最多每秒投递一次，Compose 和 Room 进度不受影响。完成或取消的任务会释放 SAF 权限，传输页可确认后清除全部终态任务。

共享文案按 Compose Multiplatform 标准目录组织：`values` 保存默认英语，`values-zh-rCN` 与 `values-zh-rSG` 保存两套同名简体中文资源。当前仅 `zh-CN`、`zh-SG` 及带对应地区的 `zh-Hans` 标签选择简体中文；无地区 `zh-Hans`、繁体中文及其他语言回退英语。认证页和“我的”提供三个明确地区选项，Android 13+ 系统“应用语言”页面使用相同列表；登录后以账号精确地区为准，历史账号值 `zh-Hans` 兼容映射为 `zh-CN`。`checkMobileI18n` 同时阻止生产 Kotlin 界面硬编码，并校验各翻译目录的键和格式参数一致。

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

阶段 9 已在 Android 9/API 28 和 Android 14/API 34 上完成设备测试，覆盖公开账号流程、Activity 重建、应用语言、主题持久化和服务器切换清理，并继续回归 WorkManager/UIDT、Keystore、Room 与 SAF。标准 Android Emulator 可使用 `http://10.0.2.2:8080` 访问宿主机；若模拟器厂商没有该别名，则在登录页使用开发机局域网地址。Debug 允许本地 HTTP，Release 强制 HTTPS。

截至 2026-08-25，阶段 7/8 已完成 API 34 的 64 MiB 分片续传、非零 Range 下载与 MD5 联合旅程，以及三页搜索、图片、文本、视频和 OnlyOffice 只读预览旅程；API 28/34 自动化、完整 Docker 栈、黑盒与可观测性门槛均通过。

截至 2026-08-27，阶段 9 已完成 SG 简体注册与邮件、账号精确地区同步、资料与容量、三种语言切换、主题持久化、服务器切换清理和密码重置真实旅程；移动端发布构建、Web/Tauri 兼容、后端黑盒和完整可观测性门槛均通过。

当前账号与设置边界见[阶段 9 账号生命周期与个人设置](docs/stage-9-account-settings.md)，预览与搜索基线见[阶段 8 个人预览与搜索](docs/stage-8-personal-preview-search.md)，传输基线见[阶段 7 个人传输体验](docs/stage-7-personal-transfers.md)。
