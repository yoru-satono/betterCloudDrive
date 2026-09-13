# ADR-006：Web/Tauri 复用界面，Android 独立实现

- 状态：已取代（由 [ADR-009](009-kmp-mobile-boundary.md) 取代）
- 日期：2026-08-20（对既有实现的追认记录）
- 适用范围：Web、Tauri、Android、共同 REST 协议和平台能力

## 背景与约束

Web 与桌面端共享页面、路由、状态和大部分 HTTP 协议，但桌面端还需要原生文件选择、目录遍历、持久传输和本地写入。Android 使用 Compose、系统存储和移动端生命周期，无法直接复用 Vue UI。

三个客户端都需要一致地理解认证、文件、团队权限、错误码和语义文案。如果平台层重新实现业务权限或状态机，会产生与服务端不一致的第二套规则。

## 决策

Tauri 复用 `frontend/src` 的 Vue 页面、Router、Pinia Store 和 API wrapper，只在 Rust command 层扩展原生 I/O、目录处理和长时间传输。Tauri 不是独立业务客户端，也不复制团队权限和文件状态规则。

Android 使用独立的 Compose/ViewModel/Repository/Retrofit 实现，以适应移动平台生命周期和交互，但消费同一 REST API、错误码、有效权限与语义文案协议。

所有客户端把后端作为资源、角色、权限和任务状态的事实来源。本地缓存只用于界面、凭据和传输体验；隐藏按钮不能替代服务端授权。

## 考虑过的替代方案

- Tauri 完全独立前端：平台自由度高，但会重复页面、协议和状态管理。
- 只提供 Web/PWA：部署最简单，但无法满足桌面目录传输和持久本地任务。
- Android 使用 WebView 包装全部 Web：共享程度高，但移动导航、存储、后台传输和无障碍体验较弱。
- 共享跨平台 UI 框架重写三端：理论上一套代码，但迁移成本高且仍需大量平台适配。

## 后果

- 正面：Web/Tauri 的业务界面与协议修复可以共享，桌面平台能力仍有明确原生边界。
- 正面：Android 能采用原生生命周期和交互，同时维持服务端协议一致。
- 负面：Android 与 Vue 仍需分别维护界面和 DTO 映射，协议变化必须同步验证。
- 负面：Tauri 运行时分支需要防止浏览器路径误调用原生命令。

## 实现与验证

本记录描述 2026-08-20 的 Android-only 边界。移动端迁移为 KMP 后，当前实现与验证入口以 [ADR-009](009-kmp-mobile-boundary.md) 为准。

- 能力矩阵和调用层：[客户端架构](../architecture/CLIENTS.md)
- 专项开发：[Web](../development/WEB.md)、[Tauri](../development/TAURI.md)、[Android](../development/KMP.md)
- 协议参考：[API 总入口](../reference/API.md)、[本地化协议](../reference/LOCALIZATION.md)
- 验证：共享 Web 单元/E2E、Tauri 原生命令/E2E、Android Repository/ViewModel/instrumentation 场景
