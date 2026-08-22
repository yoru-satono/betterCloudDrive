# 阶段 1 架构与验收

## 交付范围

阶段 1 将阶段 0 技术探针替换为生产基础原型，但不代表生产可靠性已经收口，也不追求旧 Android 的完整业务对等。应用 ID 保持 `com.betterclouddrive.mobile.spike`，版本为 `2 / 0.2.0-stage1`，可与旧应用同时安装。

共享代码按 `core/network`、`core/database`、`core/platform`、`designsystem`、`feature/files` 和 `feature/transfers` 组织，仍只有一个 `shared` Gradle 模块。`AppContainer` 是 Application、Activity、UIDT JobService 和 WorkManager Worker 的共同依赖入口。

## 会话与网络

- DataStore 保存规范化服务器地址。
- Android Keystore 生成不可导出的 AES-256-GCM 密钥；每次写入使用随机 IV，AAD 绑定应用 ID、服务器地址和格式版本。
- Access Token、Refresh Token、到期时间和服务器来源作为一个加密载荷原子保存。密文损坏、密钥失效或服务器不匹配会清空会话。
- 启动时恢复会话，提前 60 秒刷新即将到期的 Token，再调用 `/auth/me` 验证用户。
- `401001` 通过 `Mutex` 单飞刷新，旋转 Token 对后只重放一次；`401002` 或刷新失败会清空会话并把活动传输置为等待登录。
- UI 只展示已知语义错误的本地文案，日志不输出密码、Token、请求正文或本地 URI。
- Manifest 禁用 Android 备份，避免令牌、Room 任务和临时文件进入系统备份。

## 可恢复传输

Room v1 保存方向、作用域、状态、服务器、远端 ID、源/目标句柄、字节进度、上传会话、MD5、重试次数和稳定错误键。同一进程最多运行一个传输引擎实例。

上传使用 SAF 持久读取权限和流式 I/O：计算完整 MD5，尝试秒传，未命中后按固定 5 MiB 分片初始化；恢复时查询 `missingChunks`，只补传缺失分片，最后利用 complete 的幂等语义收口。暂停保留会话，取消会尽力通知后端。429、5xx、网络中断和超时可退避重试；权限、配额、同名冲突和失效句柄直接失败。

下载先写应用私有 `<taskId>.part`：已有字节会转成 `Range` 请求；`206` 必须匹配 `Content-Range`，`200` 表示服务端忽略 Range，客户端清空临时文件并从零开始；合法 `416` 仅在本地长度等于总长度时完成。成功后再复制到用户选择的目标 URI，取消或退出登录时清理临时文件。

## Android 后台执行

| 系统版本 | 实现 | 约束 |
| --- | --- | --- |
| Android 14/API 34 及以上 | JobScheduler UIDT | `setUserInitiated(true)`、持久任务、联网约束、估算字节和传输通知 |
| Android 13 及以下 | WorkManager CoroutineWorker | 唯一 Work、联网约束、指数退避和前台通知 |

通知提供暂停和取消广播入口。Activity 回到前台时把进程中断时处于准备/运行状态的任务恢复为排队，并重新核对 Room 与平台调度；UIDT Service 和 Worker 也复用同一 `AppContainer`。iOS 只编译公共模型与接口，不包含安全存储、文件访问和后台调度实现。

## 验收记录

### 2026-08-20 设备基线

默认设备为 `emulator-5556`，Android 14/API 34。该模拟器不提供标准 `10.0.2.2` 宿主别名，因此运行时使用开发机局域网后端；Docker backend、PostgreSQL、Redis、RabbitMQ、SeaweedFS、Mailpit 和 OnlyOffice 均保持健康。

| 项目 | 结果 |
| --- | --- |
| 登录与进程重建恢复 | 通过；强制结束应用后重新启动，自动恢复账号和个人根目录 |
| Keystore 设备测试 | 通过；DataStore 原始载荷不含明文 Token，损坏密文会安全登出 |
| 32 MiB 上传 | 通过；系统记录 `userInitiatedApproved=true`，按 7 个分片上传；complete 遇到 429 后复用会话重试并成功 |
| 32 MiB 下载 | 通过；私有临时文件完成后写入 SAF 目标，最终 MD5 与上传源一致 |
| Range 状态机 | 通过主机测试；覆盖合法 `206 Content-Range` 与服务端返回 `200` 时从零重启 |
| OnlyOffice | Debug 诊断继续请求后端签名的 `mobile=true` 只读配置；WebView 原样使用配置，未恢复客户端改签逻辑 |
| Android 设备测试 | 当日 `:androidApp:connectedDebugAndroidTest` 通过 5 项；覆盖 Keystore、Room 重建、临时文件清理、通知操作和调度选择 |
| 主机测试 | 当日 `:shared:testAndroidHostTest` 通过 19 项；覆盖认证、取消传播、上传恢复/幂等完成和下载 Range 状态机 |
| Debug / Release | `assembleDebug` 与 `assembleRelease` 通过，Release 的 R8 与 lint vital 通过 |
| iOS 能力 | `-PenableIos=true :shared:compileIosMainKotlinMetadata` 通过；Windows 不执行链接 |

上传验收发现并修复了一个真实边界：后端限流错误 429 原先会进入永久失败，现在进入可退避重试。测试同时确认 `419xxx` 业务错误不能因为数值大于 500 被误判为服务端 5xx。

### 2026-08-21 阶段 1 补完

- 会话恢复现在区分“无会话”“已恢复”和“临时网络失败”；临时失败不会误删有效凭据，损坏密文、服务器来源不匹配和 Refresh Token 失效会安全清理并通知传输队列等待重新登录。
- 登录失败会回滚刚写入的 Token；并发 `401001` 继续单飞刷新，刷新失败和重放后再次过期都不会无限循环。
- 上传会重新核对来源文件的真实大小和 MD5，分片内进度可见；暂停、取消或登出后的并发写入不能把任务复活。下载严格校验 `200/206/416`、长度和可选 MD5，不把损坏临时文件提交给用户。
- SAF 在入队和执行前校验持久权限；通知拒绝不丢任务。Android 14+ 只走 UIDT，低版本只走唯一 WorkManager 任务，Room 始终是真实参数来源。
- Release 禁止明文 HTTP，Debug 仍可连接本地开发后端；Manifest 禁止应用数据备份，OnlyOffice WebView 只接受 HTTP(S) 地址并销毁已释放实例。
- 最新 26 项主机测试、Debug、Release/R8、iOS 元数据编译和 AndroidTest APK 打包已经通过。`emulator-5556`（Android 14/API 34）设备测试共 7 项，其中 UIDT、Keystore、Room、私有临时文件、失效 URI 权限和通知测试 6 项通过，低版本专用 WorkManager 测试按条件跳过；Debug 应用冷启动成功且 Logcat 无崩溃。Android 9/API 28 的 WorkManager 设备回归仍需在该模拟器在线后执行。

### 2026-08-21 Docker 联调复测

Docker backend、PostgreSQL、Redis、RabbitMQ、SeaweedFS、Mailpit 和 OnlyOffice 均处于健康状态。使用 `emulator-5556` 与局域网地址完成了登录、进程重建后的会话恢复、32 MiB 随机文件的 7 分片上传、暂停/继续、网络中断重试、取消、正常下载和退出登录。上传与下载后的 MD5 一致，取消下载没有向目标写入损坏内容。

OnlyOffice 在线配置和 WebView 验收通过：`mobile=true` 返回 `editable=false`、`type=mobile`、`mode=view`，编辑、审阅、评论和表单填写权限均关闭，不含 `callbackUrl`；解码后的 JWT 与最终只读配置一致。测试文档显示“OnlyOffice 文档已就绪”，等待超过 10 秒未出现商业许可警告，更多菜单不含编辑或桌面模式入口，相关 Logcat 无崩溃或网络错误。

本轮自动化复测结果为：后端 OnlyOffice 相关测试 13 项全部通过；KMP Android 主机测试 26 项全部通过；API 34 设备测试 7 项中 6 项通过、1 项按条件跳过；Release/R8 与 lint vital 强制重新执行并通过。

仍有以下未通过或待补项：

- 真实下载断点续传未通过。限速下载在进程终止前没有生成 `.part` 数据，重启后从 0 开始；当前 `BackendClient.download` 在 `client.get` 返回后才把响应交给消费者，实际响应体先被 Ktor/OkHttp 缓冲。主机状态机测试通过不能替代这一端到端缺口。
- 高频进度更新触发 Android `NotificationService` 的 `Package enqueue rate ... Shedding` 限流，需要对通知刷新做节流或按进度阈值合并。
- Android 9/API 28 模拟器当前不在线，因此 WorkManager 分支的设备回归仍未执行。

统一命令为 `scripts/verify-all.ps1 -Module mobile-unit` 和 `scripts/verify-all.ps1 -Module mobile-android -AndroidSerial <序列号>`。默认模拟器序列号为 `emulator-5556`，如实际端口不同必须显式传入。

## 后续阶段

下一步必须先关闭真实下载断点续传、通知更新限流和 Android 9 设备回归缺口。目录导航、账号扩展、个人内容服务和团队能力不属于阶段 1。只有产品决定支持移动编辑时，才重新评估 OnlyOffice 商业许可与保存回调。
