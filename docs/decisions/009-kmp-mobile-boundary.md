# ADR-009：KMP 移动端共享业务与界面边界

- 状态：已接受
- 日期：2026-09-12
- 取代：[ADR-006](006-client-boundaries.md) 中 Android-only 的实现边界
- 适用范围：KMP 移动端、Android 应用壳、后续 iOS 应用壳和共同 REST 协议

## 背景与约束

旧 Android 应用以 Compose、Retrofit、ViewModel 和 Repository 独立实现移动业务，无法直接复用于 iOS，也让移动协议和状态机存在重复维护风险。现有 KMP 工程已经具备 Android、`iosArm64` 与 `iosSimulatorArm64` 目标，并完成认证、个人空间、团队、预览和可恢复传输迁移。

移动平台仍需要 Keystore、SAF、通知、后台调度和系统选择器等原生能力；这些能力不能污染共享业务代码，也不能由客户端重新定义后端权限规则。

## 决策

移动端以 `mobile/shared` 作为业务与 Compose Multiplatform UI 的唯一实现。共享源码包含领域模型、Ktor API、认证 HTTP 核心、窄 Repository、feature Store、状态转换、导航和可复用界面；Android 和后续 iOS 应用壳只组装入口及平台服务。

平台能力通过窄接口或 `expect`/`actual` 暴露。Android 的 Keystore、SAF、Room 驱动、通知、UIDT 与 WorkManager 留在 `androidMain`/应用壳，公共源码不得直接依赖 Android API。后端仍是资源、角色、有效权限和任务状态的事实来源。

旧 `android/` 工程及其测试在业务行为完成分层迁移后删除，不再作为功能实现、验证入口或回滚方案。KMP 共享行为由主机测试覆盖，Android 平台交互由设备测试覆盖，纯服务端规则继续由后端单元、集成和黑盒测试覆盖。

## 考虑过的替代方案

- 继续维护两套 Android 实现：短期风险较低，但协议、状态和测试会持续分叉。
- 只共享模型和网络层：减少部分重复，但 Store、导航和 Compose 行为仍无法复用。
- 在公共源码中直接调用 Android API：实现更快，但会破坏 iOS 编译边界。

## 后果

- 正面：移动业务、UI 状态和协议修复可以被 Android 与后续 iOS 壳共同复用。
- 正面：平台能力具有显式边界，Android 设备测试可以专注真实系统交互。
- 正面：旧测试按业务意图进入合适层级，避免复制实现细节和重复真实后端 E2E。
- 负面：iOS 目前只有共享源码元数据门禁，完整应用构建与平台测试仍需后续应用壳补齐。
- 负面：Compose Multiplatform 与平台适配升级需要同时维护共享和各平台 source set 的兼容性。

## 实现与验证

- 当前能力与调用层：[客户端架构](../architecture/CLIENTS.md)
- 移动端开发：[KMP Android 开发](../development/KMP.md)
- 迁移边界：[阶段 11.5 架构收口](../../mobile/docs/stage-11-5-architecture-boundaries.md)
- 最终处置与测试审计：[阶段 22 发布与切换](../../mobile/docs/stage-22-release-cutover.md)
- 验证：`mobile-unit`、API 28/API 34 的 `mobile-android`，以及共同协议对应的后端/Web/Tauri 验收
