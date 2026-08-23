# Android 开发指南

本文面向修改 Android 客户端的开发者，说明旧版 `android/` 与 KMP 重写 `mobile/` 的工程结构、服务地址、设备调试和测试约定。两者使用同一后端 REST 协议，不在本地复制服务端权限规则。新应用达到功能对等前不得删除旧应用。

## KMP 阶段 5 快速入口

KMP 工程使用 Kotlin 2.4.10、Compose Multiplatform 1.11.1、AGP 9.1.1、Gradle 9.3.1、Ktor 3.5.1、Room 2.8.4、DataStore 1.2.1 和 WorkManager 2.11.2。Android 14+ 的长传输由 UIDT 执行，Android 13 及以下回退 WorkManager。

```powershell
.\mobile\gradlew.bat -p mobile :shared:testAndroidHostTest :androidApp:assembleDebug
$env:ANDROID_SERIAL = '<adb 设备序列号>'
.\mobile\gradlew.bat -p mobile :androidApp:connectedDebugAndroidTest
.\mobile\gradlew.bat -p mobile :androidApp:assembleRelease
```

KMP 当前国际化实现见[阶段 5 国际化基础](../../mobile/docs/stage-5-localization.md)，个人文件浏览基线见[阶段 4 个人文件浏览](../../mobile/docs/stage-4-personal-browser.md)。以下旧版说明继续有效，但其 Gradle 命令应在 `android/` 目录执行。

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

## API 与认证

`data/remote/ApiService.kt` 是 Android 路径和请求形式的集中入口。新增协议时：

1. 更新远端 DTO 与 `ApiService`。
2. 在对应 Repository 统一处理 API envelope、错误和认证刷新。
3. ViewModel 将结果转换为稳定 UI state。
4. Screen 处理加载、空、失败、权限拒绝和重试。

token/服务器设置使用 DataStore 等本地存储；登出应清理用户凭据和会话数据，但保留明确属于设备的主题偏好。并发 401 应合并刷新请求，避免 refresh token 竞争。

## 团队权限与导航

团队角色控制成员/设置入口，具体文件操作读取服务端有效权限。移动、上传、分享、删除和权限管理都必须接受后端 403 后刷新状态，客户端不能把本地按钮状态当作授权结果。

Navigation Compose 的 route 集中在 `ui/navigation`。新增页面应使用稳定参数，敏感 token 只在必要链路短期传递，避免写入日志或可恢复 back stack 文本。

## 上传、下载与预览

- `UploadRepository` 负责分片/秒传协议和团队/个人作用域。
- `TransferRepository` 负责设备文件 I/O、队列状态和下载。
- 图片使用 Coil，音视频使用 Media3，文本/Office 使用对应预览页面。
- 大文件不得整体读入 UI 层内存；取消、重试和进程重建应保留明确状态。
- Android 模拟器访问 OnlyOffice 和 Mailpit 时同样使用 `10.0.2.2` 地址。

KMP 端完整任务状态保存在 Room；上传源和下载目标使用可持久化 SAF 权限，不能把整个文件读入内存。下载使用 Ktor 受控流式响应直接写入应用私有 `.part`，只有 Range、长度和可选 MD5 校验成功后才复制到用户目标。WorkManager 与 UIDT 的系统通知最多每秒更新一次，应用内进度保持实时。退出登录和切换服务器会取消平台任务、清除传输记录及临时文件。OnlyOffice 仅位于 Debug 诊断页，必须原样使用后端签名的移动只读配置。

KMP 登录后的共享产品外壳使用 Navigation Compose 类型安全路由。个人、团队、传输和我的始终具有固定顶层位置；团队页在接入团队业务前不得请求接口。窗口宽度小于 600dp 时使用底部导航栏，宽窗口使用侧边导航栏。页面 Composable 只接收状态和事件，不持有 `NavController` 或完整 `AppContainer`。

个人文件夹同样使用类型安全路由。目录请求固定每页 50 条，接近列表或网格末尾时加载下一页；请求必须携带白名单排序字段和方向。快速切换目录、排序或刷新时取消旧任务并忽略迟到响应。目录返回栈缓存仅保留当前面包屑分支，滚动位置使用首个可见文件 ID 作为锚点。根目录可上传，子目录暂不显示上传入口。

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
