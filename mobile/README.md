# BetterCloudDrive KMP 移动端

这是 Kotlin Multiplatform 移动端工程。当前 Android 应用壳已覆盖个人文件完整工作流，以及团队列表、创建、目录浏览、搜索、只读预览、基础文件修改、传输、成员、文件权限、邀请、站内通知、收藏、标签、分享、回收站和历史版本；共享业务代码同时面向 Android 与后续 iOS 应用复用。共享国际化资源支持英语、中国大陆简体中文和新加坡简体中文。旧版 `../android` 应用保持不变，仅作为迁移行为参考。

## 目标与范围

- Android 是当前已有完整应用壳和设备验证的目标。
- `iosArm64` 和 `iosSimulatorArm64` 保持共享源码与 Framework 编译能力；iOS 应用壳和平台测试将在后续阶段补充。
- 不配置桌面、JVM 应用、JS、Wasm 或 Web 目标。
- 当前可见功能包括启动恢复、登录、注册与密码找回、个人目录浏览与管理、分页与排序、列表/网格、全局文件名搜索、只读文件详情与预览、任意目录多文件上传、普通及历史版本下载、独立传输队列、个人收藏、标签、分享、回收站、版本恢复与删除、账号资料与容量、语言、主题、服务器切换和独立 Debug 诊断。团队入口支持团队分页、创建、工作区切换、目录浏览、搜索、详情、图片/文本/视频/OnlyOffice 只读预览、成员分页与角色管理、有效权限来源和直接覆盖管理、收藏、标签、分享、回收站与历史版本，以及按有效权限显示的新建文件夹、重命名、移动、当前目录多选删除、多文件上传和单文件/历史版本下载。

## 工程结构

```text
mobile/
├── androidApp/                         # Android Application、Activity、Manifest、设备测试
└── shared/src/
    ├── commonMain/.../core/            # DTO、认证 HTTP 核心、Room、窄平台接口
    ├── commonMain/.../app/             # 手动容器、协调器、类型安全路由和外壳
    ├── commonMain/.../designsystem/    # Compose Multiplatform 浅色/深色主题
    ├── commonMain/.../feature/         # 各业务域 Repository、Store 与可恢复传输状态机
    └── androidMain/.../platform/       # Keystore、SAF、UIDT/WorkManager、WebView
```

依赖由手动 `AppContainer` 创建，不引入第三方 DI。`AuthenticatedHttpClient` 是唯一 Token 所有者，其上按认证、个人文件、个人整理、团队工作区、预览和传输划分领域 API 与窄 Repository。个人与团队功能分别暴露自己的 `StateFlow` Store；`AppCoordinator` 只协调启动、会话失效、退出和换服务器时的全局清理，不持有文件、标签、分享或预览状态。根组件只收集认证、主题和未结束传输数，各目的地只收集对应 Store。

共享 UI 使用 Navigation Compose 2.9.2 类型安全路由；Activity 只提供 Compose 入口和 Android 平台服务。传输任务完整保存到 Room v2，`remoteVersionNumber` 用于区分普通文件与历史版本下载；v1→v2 迁移保留已有任务。一次用户操作最多形成一个包含 100 个任务 ID 的系统批次，批次执行器仍通过全局互斥逐项传输。Android 14+ 使用 UIDT，Android 13 及以下使用 WorkManager。下载通过 Ktor `prepareGet().execute {}` 在响应作用域内直接写入应用私有 `.part`，通知进度最多每秒投递一次，Compose 和 Room 进度不受影响。完成或取消的任务会释放 SAF 权限，传输页可确认后清除全部终态任务。

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

阶段 12 已在 Android 9/API 28 和 Android 14/API 34 上完成设备测试，覆盖个人“整理”导航、回收站、文件版本、手机与宽屏布局，并继续回归 WorkManager/UIDT、Keystore、Room v1→v2 与 SAF。标准 Android Emulator 可使用 `http://10.0.2.2:8080` 访问宿主机；若模拟器厂商没有该别名，则在登录页使用开发机局域网地址。Debug 允许本地 HTTP，Release 强制 HTTPS。

截至 2026-08-25，阶段 7/8 已完成 API 34 的 64 MiB 分片续传、非零 Range 下载与 MD5 联合旅程，以及三页搜索、图片、文本、视频和 OnlyOffice 只读预览旅程；API 28/34 自动化、完整 Docker 栈、黑盒与可观测性门槛均通过。

截至 2026-08-27，阶段 9 已完成 SG 简体注册与邮件、账号精确地区同步、资料与容量、三种语言切换、主题持久化、服务器切换清理和密码重置真实旅程；移动端发布构建、Web/Tauri 兼容、后端黑盒和完整可观测性门槛均通过。

截至 2026-08-28，阶段 10 已完成 105 个个人项目的收藏与标签三页真实后端旅程、团队内容隔离、标签原子替换与清空；API 28/34 设备矩阵、Web/Tauri、完整 Docker 栈、Testcontainers、黑盒和可观测性门槛均通过。

截至 2026-08-28，阶段 11 已完成 105 个个人分享的三页真实后端旅程，覆盖密码和过期筛选、个人/团队隔离、文件与文件夹分享、限制编辑与清空、公开访问和下载计数、访问上限、幂等取消及链接立即失效；API 28/34 设备矩阵、Web/Tauri、发布构建、黑盒和可观测性门槛均通过。

截至 2026-08-30，阶段 11.5 已完成 KMP 架构边界收口：认证 HTTP 核心、领域 API、窄 Repository、独立 Store、会话协调器和 feature 导航图已替代旧聚合边界；74 项主机测试、API 28/34 各 27 项设备测试套件、发布构建、iOS 元数据、完整 Docker 栈和 API 34 真实会话恢复旅程均通过。

截至 2026-09-01，阶段 12 已完成个人回收站与文件版本：删除根可见性、递归恢复和清理、安全版本 DTO、版本恢复与最后版本保护、Room v1→v2 和历史版本可恢复下载均通过。API 34 的 64 MiB 历史版本 SAF 下载在两次强制停止后分别从非零偏移恢复，最终 MD5 与源文件一致；后端、Web/Tauri、API 28/34、黑盒和完整可观测性门槛均通过，个人功能对等里程碑达成。

截至 2026-09-02，阶段 13 已完成团队工作区与只读浏览：团队稳定分页和创建、三级目录、排序、列表/网格、全局搜索、打开所在位置及图片、文本、视频和 OnlyOffice 预览均通过。API 28/34 设备矩阵、发布构建、完整 Docker 栈和可观测性门槛通过；团队浏览不会创建传输任务，媒体 ticket 在客户端仅驻留内存并在后端审计日志中统一脱敏。

截至 2026-09-05，阶段 15 已完成团队成员与文件权限：成员稳定分页、OWNER/ADMIN 管理矩阵、有效权限来源链和直接覆盖管理均已接入；权限变化会使文件操作权限缓存失效，服务端事务阻止操作者自锁。API 28/34、发布构建、完整 Docker 栈、黑盒与可观测性门槛均通过。

截至 2026-09-06，阶段 16 已完成团队邀请与站内通知：成员/邀请标签、创建/重发/取消、未登录认证续接、严格 Android 深链、全局未读徽标、分页筛选和前台 SSE 均已接入。105 条邀请和通知三页稳定分页、API 28/34、Web/Tauri、发布构建、黑盒和完整可观测性门槛通过，团队日常协作可用里程碑达成。

截至 2026-09-08，阶段 17 已完成团队收藏与标签实现：团队内独立分页、300 ms 搜索防抖、按需收藏状态、Owner/Admin 标签管理、有效文件权限门禁和单项目标签原子替换已接入；Android 是当前设备验证目标，iOS 共享源码仍保持可编译。

截至 2026-09-09，阶段 18 已完成团队分享实现与设备补验：独立团队分享状态、三页分页与搜索筛选、手机/宽屏详情、有效权限入口、密码和限制编辑、统计、复制及取消已接入共享源码；后端、真实 API、Web/Tauri、移动端主机、iOS 元数据和 API 28/34 设备门禁通过。

截至 2026-09-09，阶段 19 已完成团队回收站与历史版本实现：删除根稳定分页、搜索、恢复、永久删除、清空、版本查看/恢复/下载/删除以及团队切换和全局状态清理已接入共享源码；当前验收结果见阶段文档。

当前恢复与版本能力边界见[阶段 19 团队回收站与版本](docs/stage-19-team-recovery-versions.md)，分享能力边界见[阶段 18 团队分享](docs/stage-18-team-sharing.md)，整理能力边界见[阶段 17 团队收藏与标签](docs/stage-17-team-organization.md)，邀请与通知边界见[阶段 16 团队邀请与通知](docs/stage-16-team-invitations-notifications.md)，团队成员与权限边界见[阶段 15 团队成员与文件权限](docs/stage-15-team-members-permissions.md)。
