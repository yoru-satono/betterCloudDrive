# Android 开发指南

本文面向修改 Android 客户端的开发者，说明工程结构、服务地址、数据流、设备调试和测试约定。Android 使用后端同一 REST 协议，不在本地复制服务端权限规则。

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
