# Android 开发指南

本文面向修改 Android 客户端的开发者，说明旧版 `android/` 与 KMP 重写 `mobile/` 的工程结构、服务地址、设备调试和测试约定。两者使用同一后端 REST 协议，不在本地复制服务端权限规则。新应用达到功能对等前不得删除旧应用。

## KMP 快速入口

KMP 工程使用 Kotlin 2.4.10、Compose Multiplatform 1.11.1、AGP 9.1.1、Gradle 9.3.1、Ktor 3.5.1、Room 2.8.4、DataStore 1.2.1 和 WorkManager 2.11.2。Android 14+ 的长传输由 UIDT 执行，Android 13 及以下回退 WorkManager。

```powershell
.\mobile\gradlew.bat -p mobile :shared:testAndroidHostTest :androidApp:assembleDebug
$env:ANDROID_SERIAL = '<adb 设备序列号>'
.\mobile\gradlew.bat -p mobile :androidApp:connectedDebugAndroidTest
.\mobile\gradlew.bat -p mobile :androidApp:assembleRelease
```

KMP 当前依赖与状态边界见[阶段 11.5 架构收口](../../mobile/docs/stage-11-5-architecture-boundaries.md)，个人恢复与版本见[阶段 12 个人回收站与文件版本](../../mobile/docs/stage-12-personal-recovery-versions.md)。以下旧版说明继续有效，但其 Gradle 命令应在 `android/` 目录执行。

## 工具与构建

- Android Studio 或命令行 Android SDK。
- compileSdk/targetSdk 37，minSdk 26。
- 项目 Gradle wrapper 9.6.1；Gradle 构建 JVM 使用 Java 17。

```bash
cd android
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat assembleDebug`。APK 位于 `app/build/outputs/apk/debug/`。

## 代码结构

```text
app/src/main/java/com/betterclouddrive/android/
├── data/
│   ├── remote/       # Retrofit ApiService、HTTP client、协议模型
│   └── repository/   # 认证、文件、团队、上传下载等数据操作
├── di/               # Hilt 模块
├── domain/           # 客户端领域模型
├── ui/               # Compose Screen、ViewModel、导航和主题
└── util/             # 格式化及平台辅助
```

推荐方向是 Composable → ViewModel → Repository → `ApiService`/设备存储。Composable 不直接发 Retrofit 请求；Repository 不持有界面生命周期对象；服务端状态进入明确的 loading/success/error UI state。

## 服务器地址

debug 默认服务器地址来自 `ANDROID_DEFAULT_SERVER_BASE_URL`，未设置时为 `http://10.0.2.2:8080`。地址会编译进 `BuildConfig`，用户也可以在登录页选择服务器。

| 环境 | 推荐地址 |
|------|----------|
| Android 官方模拟器 → 宿主机 | `http://10.0.2.2:8080` |
| 真机 → 开发电脑 | `http://<电脑局域网IP>:8080` |
| Android 测试连接网络 adb 设备 | 设置 `ANDROID_SERIAL=<host:port>` |

修改环境变量后需要重新构建应用。生产服务器应使用 HTTPS；不要把个人开发 IP 或凭据提交到源码。

KMP 登录页直接保存规范化服务器地址。标准 Android Emulator 使用 `http://10.0.2.2:8080`；部分第三方模拟器没有该宿主别名，此时使用开发机局域网地址。KMP 不把个人开发地址编译进生产状态，也不得记录密码、Token、请求正文或 SAF URI。

KMP 认证页提供注册验证码、注册、忘记密码和重置密码。应用语言通过 Android per-app locale 保存，登录后以账号精确地区为准；主题通过独立 DataStore 保存并在登出、切换服务器和重启后保留。服务器切换必须先规范化和确认，随后按“尽力登出、取消调度、释放 URI 权限、删除传输与临时文件、清除令牌、保存新地址”的边界执行。

## API 与认证

`data/remote/ApiService.kt` 是 Android 路径和请求形式的集中入口。新增协议时：

1. 更新远端 DTO 与 `ApiService`。
2. 在对应 Repository 统一处理 API envelope、错误和认证刷新。
3. ViewModel 将结果转换为稳定 UI state。
4. Screen 处理加载、空、失败、权限拒绝和重试。

token/服务器设置使用 DataStore 等本地存储；登出应清理用户凭据和会话数据，但保留明确属于设备的主题偏好。并发 401 应合并刷新请求，避免 refresh token 竞争。

## 团队权限与导航

团队角色控制根目录能力和成员/设置入口，子目录及单项操作按需读取服务端有效权限。创建、重命名、移动、上传、下载和删除都必须接受后端 403 后使权限缓存失效并刷新状态，客户端不能把本地按钮状态当作授权结果。批量删除只允许当前目录已加载且全部具有删除权限的项目；目标选择器只允许确认有效权限可写的目录。

Navigation Compose 的 route 集中在 `ui/navigation`。新增页面应使用稳定参数，敏感 token 只在必要链路短期传递，避免写入日志或可恢复 back stack 文本。

## 上传、下载与预览

- `UploadRepository` 负责分片/秒传协议和团队/个人作用域。
- `TransferRepository` 负责设备文件 I/O、队列状态和下载。
- 图片使用 Coil，音视频使用 Media3，文本/Office 使用对应预览页面。
- 大文件不得整体读入 UI 层内存；取消、重试和进程重建应保留明确状态。
- Android 模拟器访问 OnlyOffice 和 Mailpit 时同样使用 `10.0.2.2` 地址。

KMP 端完整任务状态保存在 Room；上传源和下载目标使用可持久化 SAF 权限，不能把整个文件读入内存。个人与团队目录都通过 `OpenMultipleDocuments` 一次接受去重后的前 100 项，先原子持久化任务，再由一个 WorkManager 或 UIDT 批次逐项执行。任务作用域明确保存个人父目录或团队 ID/父目录；暂停或取消单项不会取消整个批次，临时错误阻塞当前批次并交给系统退避，永久失败（包括团队权限变更产生的 403）后继续下一项。下载使用带 MIME 建议的 `CreateDocument`，Ktor 受控流式响应直接写入应用私有 `.part`，只有 Range、长度和可选 MD5 校验成功后才复制到用户目标。

WorkManager 与 UIDT 的批次通知最多每秒更新一次，应用内 Room 进度保持实时。成功或取消会释放不再使用的 URI 权限；失败、暂停和等待登录保留权限以便恢复。用户确认清除终态任务时还要删除失败下载临时文件、尽力取消遗留上传会话并释放权限。退出登录和切换服务器会取消全部平台任务、清除传输记录及临时文件。OnlyOffice 手工探针仅位于 Debug 诊断页；个人 Office 文件也可从产品预览页只读打开，两者都必须原样使用后端签名的移动配置。

Room schema 当前为 v2。`remoteVersionNumber=null` 表示普通文件下载，非空时 `TransferApi` 使用历史版本下载 URL；完整 v1→v2 migration 必须保留既有上传、下载进度、会话和 SAF 句柄。历史版本下载与普通下载共用 `.part`、Range 校验、暂停、恢复、后台调度和终态清理，平台 Worker/Job 仍只接收任务 ID。

KMP 个人搜索调用现有 `/files/search`，输入停止 300 ms 后请求，每页 50 项。搜索和预览路由只能携带文件 ID 等稳定标识，不能携带下载 ticket。图片由 Coil 加载并禁用 ticket URL 的磁盘缓存；视频由无缓存的 Media3 播放器执行 Range 请求，页面离开前台时暂停，销毁时释放。UTF-8 文本通过认证 Range 请求最多读取前 512 KiB。个人 Office 预览固定请求 `mobile=true`，Android WebView 不覆盖签名配置中的任何字段。

清除终态传输时，除删除 Room 行、临时文件和 URI 权限外，还必须截断 SQLite WAL 并压缩数据库，避免已删除的 SAF 句柄继续残留在数据库旁路文件中。该行为由 API 28/34 设备测试覆盖。

KMP 登录后的共享产品外壳使用 Navigation Compose 类型安全路由。个人、团队、传输和我的始终具有固定顶层位置；窗口宽度小于 600dp 时使用底部导航栏，宽窗口使用侧边导航栏。页面 Composable 只接收状态和事件，不持有 `NavController` 或完整 `AppContainer`。

团队分支使用 `TeamsRoute`、`TeamWorkspaceRoute`、`TeamFolderRoute`、`TeamSearchRoute`、`TeamPreviewRoute` 和 `TeamMembersRoute`。团队列表、目录、文件操作、成员、权限、搜索和预览分别由独立 Store 管理；团队切换取消旧请求，退出或换服务器清除全部团队缓存和 ticket。`TeamMembersStore` 按角色矩阵提供成员操作，`TeamPermissionsStore` 一次管理一个文件或文件夹的来源链和直接覆盖；保存后通过明确授权变更事件刷新 `TeamFileActionsStore`，不允许 Store 直接修改彼此状态。团队文本通过认证 Range 最多读取 512 KiB，图片/视频 ticket 不持久化，OnlyOffice 固定请求 `mobile=true` 并原样渲染签名配置。

个人文件夹同样使用类型安全路由。目录请求固定每页 50 条，接近列表或网格末尾时加载下一页；请求必须携带白名单排序字段和方向。快速切换目录、排序或刷新时取消旧任务并忽略迟到响应。目录返回栈缓存仅保留当前面包屑分支，滚动位置使用首个可见文件 ID 作为锚点。所有个人目录均显示上传入口，目标目录必须在打开系统选择器时冻结；批次完成后只刷新对应目录一次。

KMP 不使用全应用 Controller 或单体 Repository。`AuthenticatedHttpClient` 集中管理 Token、单飞刷新和一次重放；认证、个人文件、整理、分享、预览和传输各自使用领域 API 与窄 Repository。页面只收集对应 Store 的 `StateFlow`；`AppCoordinator` 只在启动、会话失效、退出和换服务器时协调重置。新功能应建立自己的 Store/Repository/API 边界，禁止向根状态或全局 actions 集合追加字段。

个人文件修改由 `PersonalBrowserStore` 通过窄 `PersonalFilesRepository` 串行提交，成功后按原已加载页数刷新当前目录并尽量保留滚动锚点。移动和复制使用独立文件夹选择器，选择器每页读取 100 条并只展示文件夹；文件夹源在选择器中隐藏，客户端禁用同父目录移动，但服务端仍是权限、冲突和非法目标的最终裁决者。多选集合只包含当前目录已加载项目，并且只允许批量移入回收站。

个人收藏与标签入口位于个人空间顶栏菜单，不增加新的顶层导航项。收藏状态只在打开菜单或详情时按需读取，禁止在目录列表逐项请求。标签分配一次处理一个个人项目，先并行读取个人标签和项目当前关联，再通过 `PUT /files/{fileId}/tags` 原子替换；团队项目和团队标签不得进入此流程。

个人分享管理同样从个人空间“整理”菜单进入。客户端必须使用后端返回的 `shareUrl`，不能根据 API 地址推断 Web 端口。分享密码只能在当前详情页按需读取并存在内存，离开详情、登出或切换服务器时立即清除；密码、链接均不得写入日志、路由或持久存储。自定义过期时间由 Android 日期选择器按设备时区转换为 epoch 毫秒，非法过去时间在提交前拦截。

个人回收站和文件版本分别由独立 Store 管理。恢复、永久删除、清空或版本恢复成功后，通过容器提供的内容变更端口统一失效浏览、搜索、收藏、标签、分享和预览缓存；Store 之间不得直接改写状态。回收站只展示删除根，版本入口只对普通个人文件可见。

## 测试

JVM 测试：

```bash
./gradlew testDebugUnitTest
```

设备 Compose/instrumentation：

```bash
ANDROID_SERIAL=<device-id> ./gradlew connectedDebugAndroidTest
```

Windows：

```powershell
$env:ANDROID_SERIAL = '<device-id>'
.\gradlew.bat connectedDebugAndroidTest
```

Android 测试以 Repository 协议、ViewModel 状态转换、错误处理、Compose 交互和端到端用户场景为验收依据，不生成覆盖率报告，也不以代码行数、行覆盖率或类似统计作为指标。

修改范围对应最低验证：

| 修改 | 最低验证 |
|------|----------|
| util/纯模型 | JVM 行为测试 |
| Retrofit DTO/ApiService | MockWebServer 契约测试 + 相关 Repository 测试 |
| Repository/认证刷新/传输 | JVM 状态与错误测试；真实 I/O 再加设备测试 |
| ViewModel | Coroutines/Turbine 状态序列测试 |
| Compose/导航/权限动作 | instrumentation 场景 |
| 服务器共同协议 | Android 测试 + Web/Tauri/后端契约测试 |

完整测试策略见 [TESTING.md](TESTING.md)。

KMP 设备测试必须显式选择序列号。阶段 2 必须在 Android 9/API 28 和 Android 14/API 34 上各运行一次，以分别覆盖 WorkManager 与 UIDT；真实 OkHttp 测试还会验证限速响应在结束前写出 `.part`，并以非零 `Range` 恢复。设备测试报告位于 `mobile/androidApp/build/reports/androidTests/connected/`，主机测试报告位于 `mobile/shared/build/reports/tests/testAndroidHostTest/`。

阶段 3 继续使用 API 28/API 34 设备矩阵，新增四个顶层入口、返回栈、窄屏底栏、宽屏侧栏以及 Debug/Release 诊断隔离测试。真实后端旅程必须使用完整默认 Compose 栈，包括可观测性服务。

阶段 4 在同一设备矩阵增加多级目录、面包屑与系统返回、滚动分页、排序、列表/网格和响应式文件详情测试。API 34 的真实旅程需使用包含至少三级目录和超过 100 个文件的测试目录，并验证跨页无重复、下载正确和强制停止后回到个人根目录。

阶段 5 在同一矩阵验证默认英语、CN/SG 简体中文资源、繁体与无地区中文回退英语、Android 13+ 系统应用语言入口，以及 WorkManager/UIDT 通知文案。新增用户可见文本必须进入共享资源；`checkMobileI18n` 会阻止生产 Kotlin 源码中的界面字符串字面量，并要求三个资源目录的键集合和格式参数一致。
