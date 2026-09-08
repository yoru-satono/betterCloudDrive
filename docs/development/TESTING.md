# 测试策略

本文面向所有代码贡献者，定义测试分层、按修改范围选择的最低验证、完整验收和报告位置。可执行事实来源是 `scripts/verify-all.ps1`、`scripts/verify-all.sh` 与各模块构建文件。`mobile/` 是跨平台 KMP 工程：当前已有 Android 应用与设备套件，iOS 目标已进入共享源码元数据编译门禁，后续可继续增加 iOS 应用和平台测试。

Java 与 Web 生成覆盖率报告，用于辅助发现未验证实现；KMP 移动端的 Android 目标按行为、状态转换和用户场景验收，不生成或检查代码行覆盖率。iOS 当前验证共享源码元数据，完整应用与平台测试随 iOS 应用壳补充。

## 验证层次

| 层 | 主要目标 | 是否需要外部环境 |
|----|----------|------------------|
| Java/TypeScript/Rust/KMP 主机单元 | 纯逻辑、状态机、协议封装和错误分支 | 通常不需要 |
| Testcontainers 集成 | PostgreSQL Repository、SeaweedFS、RabbitMQ 与 trace 传播 | Docker |
| Python API 黑盒 | 真实 HTTP 契约和跨模块业务链路 | Compose 后端栈 |
| Security/WebDAV/Observability/Resilience 专项 | 安全边界、协议、运维与故障恢复 | Compose 及专项工具 |
| Web Playwright | 真实浏览器、权限、上传下载和 OnlyOffice | Compose 完整栈 |
| Tauri E2E | 真实桌面窗口和原生传输桥接 | Rust、平台 driver、后端栈 |
| KMP Android instrumentation | Compose UI、导航、设备 I/O 和端到端场景 | SDK、adb、设备、后端栈 |
| KMP iOS 共享源码编译 | iOS source set、依赖和公开 API 的编译边界 | Kotlin/Native 工具链；应用壳测试后续补充 |

单元测试不能代替资源作用域、真实数据库约束或客户端用户流程；端到端测试也不能代替对复杂状态机的精确单元断言。

## 一键验证

Windows PowerShell：

```powershell
.\scripts\verify-all.ps1
```

Linux/macOS（需要 Bash 3.2 或更高版本）：

```bash
bash scripts/verify-all.sh
```

`all` 选择 backend、integration、blackbox、security、web、tauri、KMP 移动端、office、webdav、observability 和 resilience。完整运行需要 Docker、Maven/JDK、Python/uv、Node/pnpm、Rust、rclone、Android SDK、adb 和可用设备。旧版 `android/` 仅保留为 KMP 迁移参考，不属于任何验证模块，也不会被 `all` 构建或测试。

脚本会复制缺失的本地 env 模板但不覆盖已有配置，按需启动 Compose，等待服务健康，记录各阶段结果，并默认清理本次 E2E 数据。Web、OnlyOffice 和 Tauri E2E runner 会加载根 `.env`、`config/env-files/database.env` 与 `config/env-files/backend.env`，将与 Compose 一致的数据库端口、凭据和 OnlyOffice JWT 传给测试子进程；配置不一致时只报告键名，不输出密钥。`all` 是完整验收，不能再配合跳过 Tauri、KMP Android 或 OnlyOffice 的参数。

常用子集：

```powershell
.\scripts\verify-all.ps1 -Module backend
.\scripts\verify-all.ps1 -Module backend,integration,blackbox
.\scripts\verify-all.ps1 -Module web -SkipDockerBuild
.\scripts\verify-all.ps1 -Module mobile-unit
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial emulator-5556
.\scripts\verify-all.ps1 -Module observability -KeepGoing
```

```bash
bash scripts/verify-all.sh --module backend --module integration
bash scripts/verify-all.sh --module web --skip-docker-build
bash scripts/verify-all.sh --module mobile-unit
bash scripts/verify-all.sh --module mobile-android --android-serial emulator-5556
bash scripts/verify-all.sh --module observability --keep-going
```

## 独立命令

| 验证 | 命令 | 说明 |
|------|------|------|
| Java 单元 | `cd backend && mvn test` | 全部 Maven 模块单元测试与 Java JaCoCo 报告 |
| DAL/S3/RabbitMQ 集成 | `cd backend && mvn verify -Pintegration -DskipUnitTests -pl betterclouddrive-dal,betterclouddrive-storage,betterclouddrive-service -am` | Testcontainers 真实依赖 |
| API 黑盒 | `cd backend-test && uv run pytest -m "not security and not webdav and not office and not observability and not resilience"` | 核心 HTTP 验收 |
| 安全 | `cd backend-test && uv run pytest -m security` | token、ticket、IDOR 与输入边界 |
| Web 单元 | `cd frontend && pnpm run test` | i18n 检查 + Vitest |
| Web 单元与报告 | `cd frontend && pnpm run test:coverage` | V8 coverage |
| Web E2E | `cd frontend && pnpm run test:e2e:docker` | 启动/复用 Docker 测试环境 |
| Tauri Rust | `cd frontend/src-tauri && cargo test --locked` | 传输、设置和路径逻辑 |
| Tauri 桌面 | `cd frontend && pnpm run tauri:e2e:build && pnpm run test:e2e:tauri` | 真实桌面窗口 |
| KMP 移动端主机 | `cd mobile && sh ./gradlew :shared:testAndroidHostTest` | 认证、并发刷新、语义错误与传输状态机 |
| KMP Android 设备 | `cd mobile && ANDROID_SERIAL=<device-id> sh ./gradlew :androidApp:connectedDebugAndroidTest` | 产品导航、Keystore、平台调度和设备 I/O |
| KMP Android Release | `cd mobile && sh ./gradlew :androidApp:assembleRelease` | Release、R8 与 lint vital |
| KMP iOS 共享源码 | `cd mobile && sh ./gradlew :shared:compileIosMainKotlinMetadata` | iOS source set 和跨平台依赖编译边界；不等同于完整 iOS 应用构建 |

KMP 传输改动必须在 Android 9/API 28 与 Android 14/API 34 上分别运行设备测试。API 28 用例不得跳过 WorkManager 集成测试；API 34 允许该低版本专用用例按条件跳过，但必须通过 UIDT 选择、通知和真实 OkHttp 流式 Range 恢复测试。每次运行都显式传入 `AndroidSerial`，不能依赖当前默认设备。

KMP 产品外壳测试覆盖四个顶层入口、返回行为、页面状态保留、窄屏底部导航、宽屏侧边导航以及 Release 诊断不可达。个人文件浏览改动还必须覆盖多级目录和路径重建、50 条分页去重与尾部重试、排序失败回滚、列表/网格锚点、手机底部详情和宽屏详情栏。涉及真实登录、文件或传输旅程时先启动完整默认 Compose 栈；仅启动 backend 不能作为完整环境验收结果。

个人传输改动还必须覆盖 URI 去重与 100 项上限、部分成功反馈、打开选择器时的目录快照、批量原子持久化、批次字节估算、串行执行、单项暂停/取消后继续、临时错误退避、启动恢复批次、调度失败、SAF 权限释放和终态清理。设备层必须证明 API 28 每批只有一个 WorkManager Worker、API 34 每批只有一个 UIDT Job，并检查一个批次只显示一条活动通知。真实大文件旅程需核对上传缺失分片恢复、下载非零 Range 恢复和最终 MD5。

个人搜索与预览改动必须覆盖 300 ms 防抖、旧请求取消、50 条分页去重、尾部重试、目录/搜索返回栈恢复、打开所在位置和预览类型分类。文本预览验证认证 Range、UTF-8 BOM、512 KiB 截断及空文件；图片验证 Coil 不落盘缓存 ticket；视频验证 Media3 Range、拖动、前后台暂停和播放器释放；个人 OnlyOffice 验证 `mobile=true`、只读和无商业许可警告。Logcat 扫描必须确认 ticket、Token、正文和 SAF URI 未泄漏。

阶段 7/8 的完成验收除自动化外，还必须包含 64 MiB 批量上传、断网、强制停止、非零 Range 下载、MD5 与 URI/WAL 清理联合旅程，以及真实三页搜索和全部当前预览类型旅程。2026-08-25 的 API 34 联合旅程已覆盖这些门槛；后续修改相关链路时按同一矩阵回归。

个人文件管理改动必须同时覆盖后端父目录所有权与类型、名称规范化、共享名称空间冲突、幂等操作、非法子树目标、递归复制、配额和批量删除原子性；KMP 测试覆盖五类请求、单飞提交、选择生命周期、目标文件夹分页和缓存刷新。API 28 与 API 34 的 Compose 测试需覆盖创建/重命名对话框、显式更多菜单、长按多选、批量删除和手机/宽屏目标选择器。

KMP 本地化改动必须运行 `checkMobileI18n`、主机 locale/资源键测试和 Android 设备 Compose 测试。门禁要求默认英语、CN 简体和 SG 简体目录具有相同资源键及格式参数，且资源名不得带语言前缀。API 34 还需验证系统应用语言清单；API 28 与 API 34 均使用真实资源配置验证英语、两套简体中文、繁体/无地区中文回退和传输通知。业务状态断言使用稳定枚举或消息键，不依赖翻译文本。

KMP 账号与设置改动还必须覆盖公开认证请求不携带已有 Token、注册倒计时、Activity 重建、注册/找回返回栈、精确账号地区、历史 `zh-Hans` 映射、资料刷新保留旧值、主题跨重启，以及服务器切换的清理顺序。真实旅程应从 Mailpit 获取验证码完成注册和密码重置，并证明旧密码失效、新密码可用；切换服务器前至少保留一条未结束任务，切换后核对令牌、Room、系统任务、临时文件和持久化 URI 权限均已清除。

KMP 个人收藏与标签改动必须覆盖个人/团队隔离、收藏与标签文件三页分页、300 ms 搜索防抖、标签名称和颜色校验、稳定冲突错误、标签数量统计、单项目标签原子替换和失败回滚。Compose 测试还应覆盖个人空间“整理”入口、手机页面、宽屏标签双栏、标签分配底部页/对话框，以及返回后个人目录位置保持。

KMP 个人分享改动必须覆盖 canonical `shareUrl`、明确时区的 epoch 过期时间、个人/团队隔离、稳定三页分页、300 ms 搜索防抖、密码和过期筛选、密码按需读取及离页清除、更新字段保持与显式清空语义、幂等取消和剪贴板反馈。设备测试还应覆盖手机创建底部页、宽屏列表/详情双栏、Android 日期时间选择、Activity 重建和登出后的敏感状态清除；真实旅程必须验证公开 Web 访问、密码错误/正确、计数与上限，以及取消后链接立即失效。

KMP 架构边界改动必须为每个 Store 覆盖初始状态、并发防护、取消、分页或防抖、失败保留、重试与 `reset()`，并单独验证 `AppCoordinator` 的登录恢复、会话失效、退出和换服务器顺序。网络回归必须保证所有路径和 JSON 契约不变、并发 `401001` 仍只刷新一次，受控流式响应不逸出作用域。静态检查应确认生产代码不存在全应用 Controller、聚合 UI state、全局 actions 集合或单体网络/Repository 入口；页面只依赖对应 Store 状态和事件。

KMP 个人回收站与版本改动必须覆盖删除根稳定分页、搜索防抖、已删除祖先不可见、恢复位置回退、名称冲突、子树配额、递归清理和安全 DTO。版本侧覆盖权限隐藏、倒序列表、恢复前归档当前内容、版本计数、配额、`409003` 最后历史版本保护及 `200/206/416` 下载。Room 必须从真实 v1 文件升级到 v2并保留旧任务；API 28/34 均回归版本 SAF 下载，API 34 的 64 MiB 旅程还应证明强制停止后以非零 Range 继续且最终 MD5 一致。

KMP 团队浏览改动必须覆盖团队列表稳定三页分页、创建与切换、目录权限过滤后分页、三种排序、路径重建、列表/网格、300 ms 搜索防抖、尾部重试和工作区失效。预览回归图片、零字节/超长文本、视频 Range 和 OnlyOffice `mobile=true`；API 28/34 均需运行 Compose 设备测试，API 34 真实旅程还应扫描 Logcat/Loki，确认 Token、ticket、正文和本地 URI 不泄漏。

KMP 团队文件协作改动还必须覆盖根角色与子目录有效权限、权限加载失败时关闭写入口、403 后缓存失效、创建/重命名/移动/多选删除、移动目标权限与团队上传下载契约。后端需验证团队事务锁、名称修剪和共享名称空间冲突、幂等重命名/移动、非法后代目标、递归删除权限与原子回滚、已删除祖先在所有入口不可见以及包含版本的配额扣减。设备层验证最多 100 个当前目录上传、单批通知、UIDT/WorkManager、断点恢复、SAF 权限释放和顶层传输页的方向/作用域组合筛选；运行中撤销权限必须以永久 403 结束且不得无限重试。

KMP 团队成员与文件权限改动必须覆盖稳定成员分页、Owner/Admin 管理矩阵、成员重新激活、角色变更和移除，以及有效权限来源链、用户优先、最近祖先、直接覆盖保存/删除和事务级自锁保护。设备测试同时覆盖手机底部页、宽屏对话框、权限变化后的操作入口失效、Activity 重建和三种语言资源；任何客户端可见性断言都不能替代后端 403 验证。

KMP 团队邀请与通知改动必须覆盖邀请稳定分页、同邮箱待处理邀请刷新、过期重发、旧 token 失效、取消与相同终态幂等、账号邮箱匹配及 OWNER/ADMIN 角色矩阵。设备测试应分别验证深链冷启动和 `onNewIntent`、未登录登录/注册续接、换账号与换服务器清理、成员/邀请标签、全局铃铛和 `99+` 徽标。通知测试需使用可控前后台生命周期证明后台不保留 SSE，重连每次申请新 ticket 并按 1/2/5/10/30 秒退避；全部 14 类稳定消息键、未知键回退、分页筛选、已读失败不阻塞合法导航和链接白名单都要覆盖。日志扫描必须包含邀请 URL token 与 SSE ticket 查询参数。

KMP 团队收藏与标签改动必须覆盖团队切换和个人/团队隔离、收藏与标签文件三页稳定分页、300 ms 搜索防抖、收藏状态按需读取、标签名称和颜色规范化、OWNER/ADMIN 管理矩阵、目标文件有效读写权限和原子替换失败回滚。Compose 设备测试需覆盖团队工作区“整理”入口、手机页面、宽屏标签双栏、Editor/Viewer 隐藏标签管理入口，以及有 `canWrite` 时的文件标签编辑。网络契约测试必须断言团队文件标签使用一次 `PUT /teams/{teamId}/files/{fileId}/tags`，Web/Tauri 不得通过逐条解绑和绑定模拟替换。

2026-09-08 的阶段 17 实现加入 105 个团队收藏与标签文件的三页真实 API 旅程、原子替换与清空、跨团队标签拒绝、KMP Store/MockEngine 契约和 Android Compose 权限入口覆盖。该高频旅程同时验证通知和团队任务优先于无需外部投递的普通审计 outbox 事实，避免批量整理操作延迟后续邀请通知。Android API 28/34、完整黑盒与全栈结果以本阶段实际验收记录为准，不以 iOS 共享源码元数据编译替代未来 iOS 应用测试。

2026-09-06 的阶段 16 验收使用受控夹具验证了 105 条邀请和 105 条通知的三页稳定分页，不通过生产接口批量造数，因此没有放宽限额。API 28/API 34 完整设备套件和深链注册专项、Web/Tauri、后端黑盒、一次性 SSE ticket、Prometheus 规则及 7 项可观测性测试均通过；真实 ticket 被禁止写入 pytest、应用和结构化审计日志。

2026-09-01 的阶段 12 验收已完成上述矩阵。API 34 的 64 MiB 历史版本 SAF 下载在两次强制停止后分别从 1,261,535 和 20,060,313 字节偏移获得 `206` 并继续，最终文件大小和 MD5 与源文件一致；最终黑盒运行通过 148 项测试，可观测性专项通过 Prometheus 规则测试和 7 项测试。

Windows KMP Android 使用 `mobile/gradlew.bat`。完整专项命令优先通过验证脚本运行，因为脚本还会设置运行 ID、报告位置、服务健康和数据清理。

## 修改范围到最低验证

最低验证表示提交前不能少于这些检查；跨边界修改应合并多行要求。

| 修改范围 | 最低验证 |
|----------|----------|
| `common` 纯 Java、无协议变化 | 对应 Maven 模块单元测试 |
| Service 状态机/事务 | Service 单元测试；涉及数据库/队列再加 integration |
| Entity、Repository、Flyway | DAL 单元/集成 + `integration` |
| Storage 对象操作 | Storage 单元 + S3 Testcontainers |
| Controller、DTO、Security matcher | Web 模块单元 + blackbox；授权变化加 security |
| Outbox、RabbitMQ、DLQ、恢复 | Service/Scheduler 单元 + integration + resilience |
| Vue component/composable/store | 相关 Vitest + `pnpm run build` |
| Web 路由、认证、团队权限、传输 | Web 单元 +相关 Playwright |
| Tauri Rust command/路径/恢复 | `cargo test --locked` + Tauri E2E |
| `mobile/shared` 会话或传输状态机 | `:shared:testAndroidHostTest`；涉及 Keystore、SAF 或调度再加 KMP Android 设备测试 |
| `mobile/androidApp`、Manifest 或 R8 | KMP Android 设备测试 + `:androidApp:assembleRelease` |
| API 或本地化共同协议 | 后端测试 + Web、Tauri、KMP Android 受影响契约/场景 |
| Compose/生产代理/观测配置 | 配置渲染/健康检查 + observability；故障路径加 resilience |
| OnlyOffice 回调或编辑 | 后端单元 + office E2E + 保存失败/重试场景 |

## Testcontainers 约定

集成测试不连接开发数据库，也不复用 Compose 数据；容器端口随机映射，运行结束自动销毁。Docker 引擎不可用时 integration 必须失败，不能静默跳过。

数据库用例应证明约束、锁、索引相关查询或 PostgreSQL 特性，而不是仅重复 mock 测试。RabbitMQ 用例应覆盖真实 broker 的投递确认、消费和 W3C trace context；SeaweedFS 用例应验证对象上传、组合、范围读取和删除语义。

## E2E 数据与清理

- 每次运行使用 `BCD_TEST_RUN_ID` 或脚本生成的唯一运行 ID。
- 用户名、邮箱、分享码、文件名和对象前缀避免固定全局值。
- 脚本默认执行受范围约束的数据清理；`--skip-e2e-cleanup`/`-SkipE2ECleanup` 只用于调试残留数据。
- 手工清理前先 dry run：`scripts/cleanup-e2e-data.ps1 -DryRun`。
- 清理 SQL 只应匹配已知测试邮箱域、用户前缀和显式 E2E marker，不能扩大到普通开发数据。

## 专项验收

- `security`：认证刷新、撤销、下载凭证、IDOR、权限隐藏和输入边界。
- `webdav`：WebDAV 方法与 rclone 互操作，需要 `rclone`。
- `office`：真实 OnlyOffice SDK、预览、编辑回调、异步保存和版本。
- `observability`：Prometheus config/rules/tests、Grafana Auth Proxy、Loki 查询与 Dashboard。
- `resilience`：Outbox/队列/死信和可恢复失败路径。

Prometheus 告警测试应证明健康基线、`for` 等待窗口、firing 和恢复；比例类 recording rule 还要包含正常流量、失败比例、空序列和 counter reset。

## 覆盖率报告

报告统一写入未跟踪的 `test-results/coverage/`：

- `java/<artifactId>/index.html`
- `web/index.html`、`web/coverage-summary.json` 和 `web/lcov.info`

Java/Web 覆盖率是辅助诊断，不替代关键行为断言。KMP Android 不生成覆盖率报告，也不以代码行数、行覆盖比例或类似统计作为通过条件；其质量由 Store/Repository 协议、状态转换、错误处理、Compose 交互和端到端场景判断。

## 通用测试规则

- 测试不得依赖执行顺序、共享固定 ID 或前一次运行残留。
- 权限拒绝可根据资源隐藏策略返回 403 或 404，但不能接受业务 `code=200`。
- 时间、重试和并发测试使用可控时钟/事件，避免无条件 sleep。
- 不通过降低断言、扩大重试、吞掉异常或跳过 Docker 测试掩盖失败。
- 失败报告必须保留首个错误、阶段名和必要 request ID/trace ID，同时脱敏 token、Cookie、密码和 payload。

## 当前验证快照

2026-09-02 完成阶段 13 验收：团队网络和 Store 主机测试、后端团队排序与权限过滤测试、API 28/34 设备矩阵、Debug、Release/R8、iOS 元数据、Web/Tauri、148 项黑盒及 7 项可观测性验收通过。API 34 真实旅程覆盖 106 个团队、105 个搜索结果、三级目录和全部团队预览类型；视频 Range 返回 `206`，OnlyOffice 等待 25 秒保持只读且无许可警告。团队浏览未创建 Room 传输任务，媒体 ticket 在客户端不持久化并由后端审计日志脱敏。

2026-08-30 完成阶段 11.5 架构收口验收：KMP 74 项主机测试、国际化门禁、Debug、Release/R8、iOS 元数据，以及 API 28/34 各 27 项设备测试套件均通过；重构前的 Store 与 Compose 回归断言已迁移到新边界。完整 Docker 栈重新构建后 7 项可观测性验收通过。API 34 一次性账号旅程验证真实登录、根目录加载、强制停止后会话与目录恢复，且 Logcat 无崩溃或 ANR。

2026-08-28 完成阶段 11 验收：后端单元与 Testcontainers 集成、Web 165 个单元测试和 30 条 Core E2E、Tauri 14 个 Rust 单元测试和 3 个桌面 E2E spec、后端黑盒 145 项、可观测性 7 项，以及 KMP 主机、Debug、Release/R8、iOS 元数据和 API 28/34 设备矩阵均通过。黑盒真实 API 旅程包含 105 个个人分享的稳定三页分页、筛选、个人/团队隔离、密码和限制生命周期、公开访问与下载、访问上限及取消后立即失效。

2026-08-28 完成阶段 10 验收：后端单元与 Testcontainers 集成、Web 165 个单元测试和 30 条 Core E2E、Tauri 14 个 Rust 单元测试和 3 个桌面 E2E spec、后端黑盒 143 项、可观测性 7 项，以及 KMP 主机、发布构建、iOS 元数据和 API 28/34 设备矩阵均通过。黑盒包含 105 个个人项目与团队项目隔离的收藏/标签三页真实 API 旅程。

2026-07-15 曾完成一次全量验证：Testcontainers 4 项、API 黑盒 139 项、安全 7 项、Web 单元 156 项、Web Core E2E 30 项、OnlyOffice E2E 7 项、Tauri Rust 单元 14 项、Tauri 桌面 E2E 3 个 spec、WebDAV 4 项、可观测性 3 项、韧性 2 项、Android JVM 48 项和 instrumentation 30 项通过。

该数量仅是带日期的历史快照，不是固定门禁；新增、合并或重命名测试后应更新快照日期，不以测试数量替代场景完整性。
