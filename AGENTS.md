# BetterCloudDrive Agent Guide

本文件适用于整个仓库。若子目录以后出现更具体的 `AGENTS.md`，以距离目标文件最近的说明为准。

## 项目定位

- `mobile/` 是当前活跃的 Kotlin Multiplatform 移动端，不是 Android-only 工程。
- `mobile/shared` 已包含 Android、`iosArm64` 和 `iosSimulatorArm64` 目标。新增共享业务逻辑应保持可供后续 iOS 应用复用。
- `mobile/androidApp` 是当前已有的 Android 应用壳；iOS 应用壳和平台测试会在后续继续补充。
- 旧版 `android/` 工程已在阶段 22 删除；历史行为以 Git 记录和阶段验收文档为准，不要重新创建旧工程。
- 不要把 KMP 移动端描述为只支持 Android。Android 是当前已有完整设备验证的目标，iOS 目前至少需要保持共享源码元数据可编译。

## 目录职责

- `backend/`：Spring Boot/Maven 多模块后端。
- `backend-test/`：基于 pytest/httpx 的真实 API 黑盒与专项验收。
- `frontend/`：Vue Web 前端和 Tauri 桌面壳；统一使用 pnpm。
- `mobile/`：活跃的 KMP 移动端及 Android 应用壳。
- `observability/`：Prometheus、Loki、Alloy、Alertmanager 和 Grafana 配置。
- `config/env-files/`：Compose 服务环境变量模板与本地配置。
- `docs/`：架构、开发、配置和运维文档；行为变化应同步更新相应文档。

## 实现原则

- 优先修复根因，不通过放宽断言、吞掉异常或增加无条件等待掩盖问题。
- 保持现有模块边界。协议变化应同步检查后端、Web、Tauri 和 KMP 客户端的 DTO、错误语义与调用方。
- KMP 共享代码不得直接依赖 Android API。平台能力放入对应 source set，必要时通过窄接口或 `expect`/`actual` 暴露。
- 敏感信息不得写入源码、日志、测试报告或提交。Token、ticket、密码、请求正文和 SAF URI 必须遵守现有脱敏规则。
- 数据库结构变化使用新的 Flyway migration；不要修改已经发布的 migration。
- 不直接编辑生成目录，例如 `build/`、`target/`、`dist/`、测试报告或 Gradle 缓存。
- 保留用户已有的工作区改动；不要为了完成任务重置或覆盖无关文件。

## 验证入口

Linux/macOS 统一使用 Bash 入口：

```sh
bash scripts/verify-all.sh --module <module>
```

可用模块：`backend`、`integration`、`blackbox`、`security`、`web`、`tauri`、`mobile-unit`、`mobile-android`、`office`、`webdav`、`observability`、`resilience` 和 `all`。

- 旧版 `android` 和 `android-unit` 验证模块已经退役，不得重新加入。
- `scripts/verify-all.sh` 以 Bash 3.2 为最低兼容版本，可以使用索引数组、`[[ ... ]]`、`pipefail` 和 `BASH_SOURCE`；不要引入 Bash 4 才支持的关联数组等语法。
- 修改验证流程时同步维护 `scripts/verify-all.ps1` 的模块含义和 `docs/development/TESTING.md`。
- `all` 是完整验收，包含 KMP Android 设备测试，需要可用的 adb 设备或模拟器。
- 优先运行与改动范围匹配的模块；跨模块协议或全局配置变化再扩大验证范围。

常用命令：

```sh
# KMP 主机测试、Android Debug/Release 和 iOS 共享源码元数据
bash scripts/verify-all.sh --module mobile-unit

# KMP Android 设备测试
bash scripts/verify-all.sh --module mobile-android --android-serial emulator-5556

# 后端与真实依赖
bash scripts/verify-all.sh --module backend --module integration

# 已启动 Compose 时跳过镜像重建
bash scripts/verify-all.sh --module security --skip-docker-build
```

AVD 生命周期继续在验证脚本外管理。当前本机使用 Android Emulator 37.1.11 无窗口启动时，SwiftShader 软件渲染会在 `RenderThread` 中崩溃，因此应在单独终端显式传入 `-gpu host`：

```sh
# Android 9 / API 28
emulator -avd bcd_api28 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -gpu host -camera-back none -camera-front none

# Android 14 / API 34
emulator -avd bcd_api34 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -gpu host -camera-back none -camera-front none
```

两套 AVD 共用 `emulator-5556`，必须依次启动和验证；切换前使用 `adb -s emulator-5556 emu kill` 关闭当前实例，并等待设备从 `adb devices` 消失。

KMP 设备相关变更应在 API 28 和 API 34 上分别验证，以覆盖 WorkManager 与 UIDT。当前 iOS 门禁为：

```sh
cd mobile
sh ./gradlew :shared:compileIosMainKotlinMetadata
```

该任务只验证共享 iOS source set 的编译边界，不等同于完整 iOS 应用构建或平台测试。

## 分模块最低检查

- 后端 Java：运行相关 Maven 单元测试；Repository、存储、队列或事务变化再运行 `integration`。
- API、安全和跨模块契约：运行相应后端测试，并按影响补充 `blackbox` 或 `security`。
- Vue/Pinia：运行相关 Vitest；路由、认证、上传下载或真实用户流程再运行 Web Playwright。
- Tauri Rust 或原生桥：运行 `cargo test --locked`；窗口和端到端流程再运行 `tauri`。
- `mobile/shared`：至少运行 `mobile-unit`；涉及 Keystore、SAF、通知或平台调度时增加 `mobile-android`。
- `mobile/androidApp`、Manifest 或 R8：运行 Android 设备测试和 Release 构建。
- iOS source set：至少运行 `:shared:compileIosMainKotlinMetadata`；未来存在 iOS 应用壳后再加入对应构建和平台测试。
- Compose、代理或观测配置：先渲染/启动相关 Compose 组合，再运行健康检查和对应专项测试。

## Docker 与本地配置

- Compose 服务不得设置开机自启；保持 `restart: "no"`，除非用户明确改变这一策略。
- 不提交 `.env` 或 `config/env-files/*.env` 中的实际密钥。模板文件只提供结构和安全占位说明。
- 新环境生成本机独立密钥，不复用其他系统盘、旧机器或外部环境中的密钥。
- 不用模板覆盖已存在的本地配置。
- 常规全栈使用 `docker-compose.yml`；宿主机观测端口需要时叠加 `docker-compose.diagnostics.yml`。
- E2E 临时放宽限流后必须在成功、失败和信号退出路径恢复默认值，并执行受范围约束的数据清理。

## 提交与交付

- 提交前至少运行相关语法检查、目标模块测试和 `git diff --check`。
- `scripts/verify-all.sh` 变化至少运行 `bash -n`、帮助与错误参数检查，并实际执行一个模块路径。
- 不提交本地 SDK、AVD、IDE 配置、缓存、构建产物或测试结果。
- 只有用户明确要求时才创建 Git 提交。提交信息应准确描述本次单一变更，不夹带无关文件。
- 交付说明应列出已运行的检查、未运行的检查及原因，并明确剩余失败是实现问题、环境问题还是已知平台限制。
