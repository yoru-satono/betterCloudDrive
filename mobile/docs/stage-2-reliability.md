# 阶段 2 传输可靠性与验收

## 状态与范围

阶段 2 只关闭阶段 1 暴露的传输可靠性缺口，不增加导航、目录管理、团队界面或后端协议。应用 ID 保持 `com.betterclouddrive.mobile.spike`，版本更新为 `3 / 0.3.0-stage2`。

截至 2026-08-22，流式下载、认证重放、通知节流、主机测试、生产构建、Android 9 与 Android 14 自动化，以及两个版本的 Docker 后端 64 MiB 传输旅程均已完成。阶段 2 验收通过。

## 流式下载

- `BackendClient.download` 使用 Ktor `prepareGet().execute {}`，状态、响应头和 `ByteReadChannel` 只在响应作用域内交给消费者，不再等待普通 `get` 返回后才开始写文件。
- 首次 `401001` 会关闭当前响应、单飞刷新令牌并重放一次；第二次未授权、`401002` 或刷新失败会清除会话。取消直接向上传播，不会被包装为网络失败或清除有效会话。
- 传输引擎继续严格处理 `200/206/416`。恢复偏移以真实 `.part` 长度为准，服务端忽略 Range 时从零重启，长度和可选 MD5 校验通过后才提交 SAF 目标。
- Room schema 和现有临时文件命名不变，不需要数据库迁移。

## Android 通知

每次后台执行创建独立的进度限制器，使用单调时钟判断间隔。首次状态立即投递，活动传输最多每秒更新一次，终态在被观察到时立即投递。WorkManager 前台通知、UIDT 通知和已传输字节上报共用限制后的流；Room 与 Compose 仍消费完整进度。

## 自动化证据

| 项目 | 结果 |
| --- | --- |
| Android 主机测试 | 32 项通过；新增流式下载刷新/取消/单次重放和通知限制器测试 |
| Android 14/API 34 设备测试 | 8 项执行，7 项通过，低版本专用 WorkManager 用例按条件跳过 |
| 真实 OkHttp 限速测试 | 通过；2 MiB 响应结束前 `.part` 已增长，取消后第二次请求携带非零 Range，最终长度和 MD5 一致 |
| Android 14 Docker 旅程 | 通过；64 MiB 上传、暂停/继续、断网/恢复、下载、强制停止/重启及非零偏移恢复均完成 |
| Android 14 UIDT | JobScheduler 显示 `userInitiatedApproved: true` 且 `started as UIJ: true` |
| Android 14 断点证据 | `.part` 在强制停止前后均为 523,019 字节；重启后继续增长到 608,873 字节，没有清零 |
| Android 14 完整性 | 源文件、普通下载和强制停止后恢复下载的 MD5 均为 `4d2a882f2bd9185ac59bd9eccc5d53d0` |
| Android 14 Logcat | 未发现 `Package enqueue rate`、`Shedding`、应用 ANR、崩溃、Bearer 令牌或 HTTP 正文日志 |
| Android 9/API 28 设备测试 | 8 项全部通过；WorkManager 专用用例实际执行，没有跳过 |
| Android 9 WorkManager | 系统前台通知可见，包含暂停/取消操作；JobScheduler 记录的服务为 WorkManager `SystemJobService` |
| Android 9 Docker 旅程 | 通过；64 MiB 上传在后端限流重试后复用会话完成，下载暂停/继续、断网/恢复和强制停止/重启均完成 |
| Android 9 暂停证据 | 暂停后 `.part` 稳定保持 27,031,085 字节，任务状态为 `PAUSED` |
| Android 9 强停证据 | 强制停止后 `.part` 保持 22,392,397 字节；重启后继续增长到 26,635,025 字节，没有清零 |
| Android 9 完整性 | 源文件、普通恢复下载和强制停止后恢复下载的 MD5 均为 `916a82f863dec1524e06a1d3ed9c71f5` |
| Android 9 Logcat | 未发现 `Package enqueue rate`、`Shedding`、应用 ANR、崩溃、Bearer 令牌或 HTTP 正文日志 |
| Debug / Release | 通过；Release 的 R8 与 lint vital 通过 |
| iOS 公共代码 | `compileIosMainKotlinMetadata` 通过 |

统一命令：

```powershell
.\scripts\verify-all.ps1 -Module mobile-unit
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial '<API 28 序列号>'
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial '<API 34 序列号>'
```

## 完成结论

阶段 2 的代码、构建、双版本自动化和真实后端旅程均已达到完成门槛。Android 9 使用 WorkManager，Android 14 使用 UIDT；两条路径均能在暂停、断网和进程终止后恢复，且最终文件完整。下一阶段可以进入产品应用外壳建设。
