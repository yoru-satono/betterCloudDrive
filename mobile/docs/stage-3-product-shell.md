# 阶段 3 产品应用外壳

## 状态与范围

阶段 3 将阶段 2 的单页长列表拆分为正式产品外壳，应用版本为 `4 / 0.4.0-stage3`。本阶段只建设导航、页面结构、页面级状态和 Debug 诊断隔离，不增加目录管理、团队业务、账号扩展或后端协议。

Android 是当前交付目标；共享路由、界面和状态继续保留 iOS 编译能力。旧 `android/` 应用不删除、不替换。

## 产品外壳

- 登录和会话恢复完成后进入以个人空间为起点的共享 Navigation Compose 图。
- 顶层固定为“个人、团队、传输、我的”四个入口。小于 600dp 的窗口使用底部导航栏，宽窗口使用侧边导航栏。
- 顶层切换保存页面状态且不累积重复目的地；非个人顶层页面返回个人空间，诊断页面返回“我的”。
- 团队页面只展示后续阶段提示，不调用团队接口。
- Debug 诊断从个人文件列表移到独立页面；Release 不注册入口和目的地。

## 页面与状态

- 个人空间继续提供根目录刷新、上传和下载，但不进入文件夹。初次加载、已有内容刷新、空目录和失败重试使用独立状态；刷新失败保留已有内容。
- 传输队列独立显示 Room 中的任务和实时进度，并保留暂停、继续、取消和重试操作。
- “我的”只读展示账号、邮箱和当前服务器，并提供退出登录；主题、语言、服务器编辑和资料修改留到后续阶段。
- 登录/恢复错误与个人文件错误不再共享全局 `busy/error`，传输进度更新不会阻塞导航。
- 共享 Material 3 主题沿用 BetterCloudDrive 品牌色，并自动跟随系统浅色或深色模式。

## 验收命令

```powershell
.\scripts\verify-all.ps1 -Module mobile-unit
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial '<API 28 序列号>'
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial '<API 34 序列号>'
.\mobile\gradlew.bat -p mobile :androidApp:assembleRelease
.\mobile\gradlew.bat -p mobile -PenableIos=true :shared:compileIosMainKotlinMetadata
```

真实后端验收使用完整默认 Compose 栈，不以仅启动 `backend` 代替完整环境。可观测性专项通过 `scripts/verify-all.ps1 -Module observability` 验证。

## 验收结果

阶段 3 已于 2026-08-22 完成验收：

- `mobile-unit` 通过，共执行 36 个主机测试；Debug、Release/R8 和 iOS 公共代码元数据编译均成功。
- Android 9/API 28 的 12 个设备测试全部通过，实际执行 WorkManager 分支。
- Android 14/API 34 的 13 个设备用例中 12 个通过，低版本专用 WorkManager 用例按预期跳过；UIDT、Keystore、Room、通知、流式下载、导航和滚动位置恢复均通过。
- 完整默认 Docker 栈已使用 `docker compose up -d --build` 启动，backend、PostgreSQL、Redis、RabbitMQ、SeaweedFS、OnlyOffice、frontend、Loki、Alloy、Prometheus、Alertmanager、Blackbox Exporter 和 Grafana 均达到正常状态。
- `observability` 专项的 7 个用例全部通过。为保证冷启动阶段也能生成 HTTP 可用率记录规则，Prometheus 分母在没有请求样本时显式回退到零向量。

API 34 真实用户旅程通过：登录后默认进入个人空间；四个入口切换和团队占位状态正确；上传并下载同一个 64 KiB 文件，两个持久化任务均完成且文件长度一致；强制停止后会话自动恢复并重新加载个人根目录；“我的”正确显示用户名、邮箱和当前服务器，退出后回到登录页。Debug 诊断显示 Room v1 正常且包含 2 个任务。

OnlyOffice 使用有效团队测试文档完成复测。文档正文和测试标记可见，状态回调为“OnlyOffice 文档已就绪”；等待超过 20 秒未出现商业许可警告，编辑区域保持只读，Logcat 未出现应用崩溃、ANR、通知投递限流或网络正文泄露。验收截图保存在 `mobile/build/reports/stage3-onlyoffice-api34.png`。

## 范围外

- 目录层级、分页、排序、列表/网格和文件详情属于阶段 4。
- 多文件和目录上下文传输属于阶段 6。
- 主题、语言和完整账号设置属于阶段 8。
- 团队列表、工作区和团队文件浏览属于阶段 12。
