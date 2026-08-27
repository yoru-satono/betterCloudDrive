# 测试策略

本文面向所有代码贡献者，定义测试分层、按修改范围选择的最低验证、完整验收和报告位置。可执行事实来源是 `scripts/verify-all.ps1`、`scripts/verify-all.sh` 与各模块构建文件。

Java 与 Web 生成覆盖率报告，用于辅助发现未验证实现；Android 只按行为、状态转换和用户场景验收，不生成或检查代码行覆盖率。

## 验证层次

| 层 | 主要目标 | 是否需要外部环境 |
|----|----------|------------------|
| Java/TypeScript/Rust/Android JVM 单元 | 纯逻辑、状态机、协议封装和错误分支 | 通常不需要 |
| Testcontainers 集成 | PostgreSQL Repository、SeaweedFS、RabbitMQ 与 trace 传播 | Docker |
| Python API 黑盒 | 真实 HTTP 契约和跨模块业务链路 | Compose 后端栈 |
| Security/WebDAV/Observability/Resilience 专项 | 安全边界、协议、运维与故障恢复 | Compose 及专项工具 |
| Web Playwright | 真实浏览器、权限、上传下载和 OnlyOffice | Compose 完整栈 |
| Tauri E2E | 真实桌面窗口和原生传输桥接 | Rust、平台 driver、后端栈 |
| Android instrumentation | Compose UI、导航、设备 I/O 和端到端场景 | SDK、adb、设备、后端栈 |

单元测试不能代替资源作用域、真实数据库约束或客户端用户流程；端到端测试也不能代替对复杂状态机的精确单元断言。

## 一键验证

Windows PowerShell：

```powershell
.\scripts\verify-all.ps1
```

Linux/macOS：

```bash
./scripts/verify-all.sh
```

`all` 选择 backend、integration、blackbox、security、web、tauri、旧 Android、KMP 移动端、office、webdav、observability 和 resilience。完整运行需要 Docker、Maven/JDK、Python/uv、Node/pnpm、Rust、rclone、Android SDK、adb 和可用设备。

脚本会复制缺失的本地 env 模板但不覆盖已有配置，按需启动 Compose，等待服务健康，记录各阶段结果，并默认清理本次 E2E 数据。`all` 是完整验收，不能再配合跳过 Tauri、Android 或 OnlyOffice 的参数。

常用子集：

```powershell
.\scripts\verify-all.ps1 -Module backend
.\scripts\verify-all.ps1 -Module backend,integration,blackbox
.\scripts\verify-all.ps1 -Module web -SkipDockerBuild
.\scripts\verify-all.ps1 -Module android-unit
.\scripts\verify-all.ps1 -Module android -AndroidSerial localhost:16416
.\scripts\verify-all.ps1 -Module mobile-unit
.\scripts\verify-all.ps1 -Module mobile-android -AndroidSerial emulator-5556
.\scripts\verify-all.ps1 -Module observability -KeepGoing
```

```bash
./scripts/verify-all.sh --module backend --module integration
./scripts/verify-all.sh --module web --skip-docker-build
./scripts/verify-all.sh --module android-unit
./scripts/verify-all.sh --module android --android-serial localhost:16416
./scripts/verify-all.sh --module mobile-unit
./scripts/verify-all.sh --module mobile-android --android-serial emulator-5556
./scripts/verify-all.sh --module observability --keep-going
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
| Android JVM | `cd android && ./gradlew testDebugUnitTest` | Repository、ViewModel、工具和协议行为 |
| Android 设备 | `cd android && ./gradlew connectedDebugAndroidTest` | Compose/instrumentation 场景 |
| KMP 移动端主机 | `cd mobile && ./gradlew :shared:testAndroidHostTest` | 认证、并发刷新、语义错误与传输状态机 |
| KMP Android 设备 | `cd mobile && ./gradlew :androidApp:connectedDebugAndroidTest` | 产品导航、Keystore、平台调度和设备 I/O |
| KMP Android Release | `cd mobile && ./gradlew :androidApp:assembleRelease` | Release、R8 与 lint vital |

KMP 传输改动必须在 Android 9/API 28 与 Android 14/API 34 上分别运行设备测试。API 28 用例不得跳过 WorkManager 集成测试；API 34 允许该低版本专用用例按条件跳过，但必须通过 UIDT 选择、通知和真实 OkHttp 流式 Range 恢复测试。每次运行都显式传入 `AndroidSerial`，不能依赖当前默认设备。

KMP 产品外壳测试覆盖四个顶层入口、返回行为、页面状态保留、窄屏底部导航、宽屏侧边导航以及 Release 诊断不可达。个人文件浏览改动还必须覆盖多级目录和路径重建、50 条分页去重与尾部重试、排序失败回滚、列表/网格锚点、手机底部详情和宽屏详情栏。涉及真实登录、文件或传输旅程时先启动完整默认 Compose 栈；仅启动 backend 不能作为完整环境验收结果。

个人传输改动还必须覆盖 URI 去重与 100 项上限、部分成功反馈、打开选择器时的目录快照、批量原子持久化、批次字节估算、串行执行、单项暂停/取消后继续、临时错误退避、启动恢复批次、调度失败、SAF 权限释放和终态清理。设备层必须证明 API 28 每批只有一个 WorkManager Worker、API 34 每批只有一个 UIDT Job，并检查一个批次只显示一条活动通知。真实大文件旅程需核对上传缺失分片恢复、下载非零 Range 恢复和最终 MD5。

个人搜索与预览改动必须覆盖 300 ms 防抖、旧请求取消、50 条分页去重、尾部重试、目录/搜索返回栈恢复、打开所在位置和预览类型分类。文本预览验证认证 Range、UTF-8 BOM、512 KiB 截断及空文件；图片验证 Coil 不落盘缓存 ticket；视频验证 Media3 Range、拖动、前后台暂停和播放器释放；个人 OnlyOffice 验证 `mobile=true`、只读和无商业许可警告。Logcat 扫描必须确认 ticket、Token、正文和 SAF URI 未泄漏。

阶段 7/8 的完成验收除自动化外，还必须包含 64 MiB 批量上传、断网、强制停止、非零 Range 下载、MD5 与 URI/WAL 清理联合旅程，以及真实三页搜索和全部当前预览类型旅程。2026-08-25 的 API 34 联合旅程已覆盖这些门槛；后续修改相关链路时按同一矩阵回归。

个人文件管理改动必须同时覆盖后端父目录所有权与类型、名称规范化、共享名称空间冲突、幂等操作、非法子树目标、递归复制、配额和批量删除原子性；KMP 测试覆盖五类请求、单飞提交、选择生命周期、目标文件夹分页和缓存刷新。API 28 与 API 34 的 Compose 测试需覆盖创建/重命名对话框、显式更多菜单、长按多选、批量删除和手机/宽屏目标选择器。

KMP 本地化改动必须运行 `checkMobileI18n`、主机 locale/资源键测试和 Android 设备 Compose 测试。门禁要求默认英语、CN 简体和 SG 简体目录具有相同资源键及格式参数，且资源名不得带语言前缀。API 34 还需验证系统应用语言清单；API 28 与 API 34 均使用真实资源配置验证英语、两套简体中文、繁体/无地区中文回退和传输通知。业务状态断言使用稳定枚举或消息键，不依赖翻译文本。

KMP 账号与设置改动还必须覆盖公开认证请求不携带已有 Token、注册倒计时、Activity 重建、注册/找回返回栈、精确账号地区、历史 `zh-Hans` 映射、资料刷新保留旧值、主题跨重启，以及服务器切换的清理顺序。真实旅程应从 Mailpit 获取验证码完成注册和密码重置，并证明旧密码失效、新密码可用；切换服务器前至少保留一条未结束任务，切换后核对令牌、Room、系统任务、临时文件和持久化 URI 权限均已清除。

Windows Android 使用 `gradlew.bat`。完整专项命令优先通过验证脚本运行，因为脚本还会设置运行 ID、报告位置、服务健康和数据清理。

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
| Android util/model | JVM 行为测试 |
| Android Repository/ViewModel | JVM 协议和状态测试 |
| Android Compose/导航/设备 I/O | JVM 相关测试 + instrumentation |
| `mobile/shared` 会话或传输状态机 | `:shared:testAndroidHostTest`；涉及 Keystore、SAF 或调度再加 KMP Android 设备测试 |
| `mobile/androidApp`、Manifest 或 R8 | KMP Android 设备测试 + `:androidApp:assembleRelease` |
| API 或本地化共同协议 | 后端测试 + Web、Tauri、Android 受影响契约/场景 |
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

Java/Web 覆盖率是辅助诊断，不替代关键行为断言。Android 不生成覆盖率报告，也不以代码行数、行覆盖比例或类似统计作为通过条件；Android 质量由 Repository 协议、ViewModel 状态转换、错误处理、Compose 交互和端到端场景判断。

## 通用测试规则

- 测试不得依赖执行顺序、共享固定 ID 或前一次运行残留。
- 权限拒绝可根据资源隐藏策略返回 403 或 404，但不能接受业务 `code=200`。
- 时间、重试和并发测试使用可控时钟/事件，避免无条件 sleep。
- 不通过降低断言、扩大重试、吞掉异常或跳过 Docker 测试掩盖失败。
- 失败报告必须保留首个错误、阶段名和必要 request ID/trace ID，同时脱敏 token、Cookie、密码和 payload。

## 当前验证快照

2026-07-15 曾完成一次全量验证：Testcontainers 4 项、API 黑盒 139 项、安全 7 项、Web 单元 156 项、Web Core E2E 30 项、OnlyOffice E2E 7 项、Tauri Rust 单元 14 项、Tauri 桌面 E2E 3 个 spec、WebDAV 4 项、可观测性 3 项、韧性 2 项、Android JVM 48 项和 instrumentation 30 项通过。

该数量仅是带日期的历史快照，不是固定门禁；新增、合并或重命名测试后应更新快照日期，不以测试数量替代场景完整性。
