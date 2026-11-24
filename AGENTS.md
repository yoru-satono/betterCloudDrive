# BetterCloudDrive Agent Guide

本文件适用于整个仓库。子目录存在更具体的 `AGENTS.md` 时，以距离目标文件最近的说明为准。

## 必须读取本地说明

开始任何工作前，开发者或代理必须读取仓库根目录的 `AGENTS.local.md`。

- 如果 `AGENTS.local.md` 不存在，必须先参考 `AGENTS.local.md.example` 自行创建，再继续工作。
- `AGENTS.local.md` 只记录本机路径、SDK 位置、设备序列号、AVD 名称、端口占用和已知环境限制等本地差异。
- 不得在 `AGENTS.local.md` 中保存密码、Token、私钥或其他真实凭据。
- `AGENTS.local.md` 已被 Git 忽略，不得强制加入版本控制；可共享的通用规则应更新本文件或正式文档。
- 本地说明只能补充环境信息，不能放宽本文件、子目录 `AGENTS.md` 或项目安全规则。

## 项目定位

BetterCloudDrive 是同时提供个人空间和团队空间的云盘系统，包含统一后端、Web、Qt 桌面端、Kotlin Multiplatform 移动端、WebDAV 和完整观测栈。

- `backend/`：Spring Boot/Maven 模块化单体，负责认证、权限、元数据、传输、分享、版本、通知和异步任务。
- `backend-test/`：基于 pytest/httpx 的真实 API 黑盒及 security、WebDAV、OnlyOffice、observability、resilience 专项验收。
- `frontend/`：Vue Web 前端及共享页面，统一使用 pnpm；共享页面只依赖中性桌面接口。
- `desktop/`：PySide6 + Qt WebEngine 桌面客户端、独立 Vue 构建、原生传输和播放器。
- `mobile/`：Kotlin Multiplatform 移动端。`shared` 承载共享业务与 Compose UI，`androidApp` 是当前 Android 应用壳；iOS 当前保持共享源码元数据可编译。
- `observability/`：Prometheus、Loki、Alloy、Alertmanager 和 Grafana 配置。
- `config/env-files/`：Compose 服务环境变量模板与本地配置。
- `docs/`：架构、开发、接口、配置、决策和运维文档。

不要把 `mobile/` 描述成 Android-only 工程。Android 是当前已有完整应用壳和设备验证的目标；共享实现必须继续保持可供后续 iOS 应用复用。

## 架构与实现原则

- 优先修复根因，不通过放宽断言、吞掉异常、无条件等待或扩大重试掩盖问题。
- 保持模块边界。协议变化应同步检查后端、Web、Qt 和 KMP 移动端的 DTO、错误语义、权限与调用方。
- 后端业务约束与事务放在 Service，Controller 负责 HTTP 协议，Repository 只负责持久化，Storage 只封装对象存储。
- 数据库结构变化使用新的 Flyway migration；不要修改已发布的 migration。
- Web 与 Qt 共用的页面、Store 和协议逻辑留在 TypeScript；Python 原生层只处理桌面平台能力。
- KMP 的 `commonMain` 不得直接依赖 Android API。平台能力放入对应 source set，并通过窄接口或必要的 `expect`/`actual` 暴露。
- 客户端按钮与路由守卫不能替代后端授权；权限拒绝必须保留明确的刷新和恢复路径。
- Token、ticket、密码、Cookie、验证码、请求正文和 SAF URI 等敏感信息不得写入源码、日志、测试报告或提交。
- 不直接编辑 `build/`、`target/`、`dist/`、测试报告、Gradle 缓存或其他生成目录。
- 保留已有工作区改动，不重置、不覆盖、不顺带提交无关文件。

## 依赖与构建约定

- 后端使用仓库 Maven 配置和对应模块命令。
- Web/Qt 使用 pnpm，不使用 npm 或 yarn 生成额外锁文件。
- KMP 使用 `mobile/gradlew` 或 `mobile/gradlew.bat`，不要使用系统 Gradle 替代 wrapper。
- 修改依赖或工具链版本时，以构建文件和锁文件为准，并同步更新相关文档基线。

## 统一验证入口

Linux/macOS 使用 Bash 入口：

```sh
bash scripts/verify-all.sh --module <module>
```

Windows 使用 PowerShell 入口：

```powershell
.\scripts\verify-all.ps1 -Module <module>
```

可用模块为 `backend`、`integration`、`blackbox`、`security`、`web`、`qt`、`mobile-unit`、`mobile-android`、`office`、`webdav`、`observability`、`resilience`、`warehouse`、`inspection` 和 `all`。`qt` 为通用桌面开发验证入口，已并入 `all`；单独选择时不启动 Docker，真实 Qt 后端验收另按 `desktop/README.md` 执行。

- 优先运行与改动范围匹配的模块；共同协议、全局配置或发布准备再扩大验证范围。
- `all` 是完整验收，需要 Docker、Maven/JDK、Python、Node/pnpm、rclone、Android SDK、adb 和可用设备。
- `scripts/verify-all.sh` 最低兼容 Bash 3.2，不得使用 Bash 4 才支持的关联数组等语法。
- 修改验证流程时同步维护 `scripts/verify-all.sh`、`scripts/verify-all.ps1` 和 `docs/development/TESTING.md` 的模块含义。
- 修改 Bash 验证脚本至少运行 `bash -n`、帮助、错误参数检查和一个实际模块路径。

常用命令：

```sh
bash scripts/verify-all.sh --module backend --module integration
bash scripts/verify-all.sh --module web --skip-docker-build
bash scripts/verify-all.sh --module mobile-unit
bash scripts/verify-all.sh --module mobile-android --android-serial <device-id>
```

## 分模块最低检查

- 后端 Java：运行相关 Maven 单元测试；Repository、存储、队列或事务变化再运行 `integration`。
- API、安全和跨模块契约：运行相关后端测试，并按影响增加 `blackbox` 或 `security`。
- Vue/Pinia：运行相关 Vitest；路由、认证、传输或真实用户流程再运行 Web Playwright。
- Qt 原生层或桥接：运行 `qt`；窗口、身份或真实传输变化再运行桌面专项验收。
- `mobile/shared`：至少运行 `mobile-unit`；涉及 Keystore、SAF、通知或平台调度时增加 `mobile-android`。
- `mobile/androidApp`、Manifest、网络安全配置或 R8：运行 Android 设备测试和 Release 构建。
- Android 设备相关变化：分别在 API 28 和 API 34 验证，以覆盖 WorkManager 与 UIDT；设备和 AVD 细节从 `AGENTS.local.md` 读取。
- iOS source set 或公共平台接口：运行 `mobile-unit`，或独立执行 `cd mobile && sh ./gradlew -PenableIos=true :shared:compileIosMainKotlinMetadata`。该任务不等同于完整 iOS 应用构建或平台测试。
- Compose、代理或观测配置：先渲染或启动相关 Compose 组合，再运行健康检查和对应专项测试。

## 配置、Docker 与测试数据

- 不提交 `.env` 或 `config/env-files/*.env` 中的实际值；模板只提供结构和安全占位说明。
- 新环境生成独立密钥，不复用其他机器或外部环境中的凭据，也不用模板覆盖已有本地配置。
- Compose 服务保持 `restart: "no"`，除非用户明确改变该策略。
- 常规全栈使用 `docker-compose.yml`；需要宿主机观测端口时叠加 `docker-compose.diagnostics.yml`。
- 测试数据使用运行 ID 和受控前缀。E2E 临时放宽限流后，必须在成功、失败和信号退出路径恢复默认值并执行范围受限的清理。
- 删除卷、清空队列、清理数据库或执行其他破坏性操作前，必须确认精确目标和可恢复点。

## 文档规则

- 当前行为变化应同步更新对应架构、开发、API、配置或运维文档。
- HTTP 路径和字段以 Controller、SecurityConfig、DTO 及 `docs/reference` 为准；运行命令以构建文件和验证脚本为准。
- KMP 移动端文档使用“KMP 移动端”作为客户端身份；只有描述应用壳、source set、SDK、设备或平台 API 时才使用 Android。
- 历史 ADR、阶段记录和归档只用于追溯，不应反推当前实现。
- 新增或移动 Markdown 后检查所有相对链接，避免产生重复权威入口。

## 提交与交付

- 提交前至少运行相关语法检查、目标模块测试和 `git diff --check`。
- 不提交本地 SDK、AVD、IDE 配置、缓存、构建产物、测试结果或 `AGENTS.local.md`。
- 代理可以自行决定向本地 Git 仓库创建提交的时机；只有用户主动明确要求时才能推送到远程 Git 仓库；提交信息应准确描述单一变更。
- 交付说明列出已运行和未运行的检查，并区分实现问题、环境问题和已知平台限制。
