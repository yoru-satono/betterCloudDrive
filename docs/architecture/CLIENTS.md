# 客户端架构

本文面向 Web、Tauri 和 Android 开发者，说明三端共享的协议、代码复用边界和平台差异。复用策略见[客户端边界 ADR](../decisions/006-client-boundaries.md)；接口字段以 [API 参考](../reference/API.md) 为准，本地化消息协议以[本地化参考](../reference/LOCALIZATION.md)为准。

## 共同边界

三端都把后端 REST API 作为业务事实来源，不在本地重新实现用户、团队、文件或权限规则。客户端可以缓存界面状态和传输进度，但服务端响应决定资源是否存在、当前角色和操作是否允许。

共同协议包括：

- `/api/v1` 下的 JSON API 与统一错误响应。
- Bearer access token、refresh token 和登出撤销语义。
- 个人/团队双作用域的文件、上传、分享、标签、版本与回收站接口。
- 团队文件返回的有效权限，客户端据此隐藏或禁用操作，但服务端仍会再次校验。
- 后端消息 key 与参数，由各端本地化目录渲染。

## Web

Web 客户端位于 `frontend/src`，使用 Vue、Vue Router、Pinia、Axios 和组合式 API。

```text
Page / Component
  -> Pinia Store or Composable
  -> src/api domain wrapper
  -> shared Axios client
  -> REST API
```

- Router 划分公开页面、登录后的个人/团队区域和 `/admin` 管理区域。
- `src/api` 按认证、文件、上传、团队、分享、通知等领域封装协议。
- Store 负责当前会话、文件列表、团队列表、上传队列和传输状态；页面不应散落重复的 Axios 配置。
- Web 提供最完整的管理界面，包括用户、团队、系统日志和死信管理。
- 浏览器下载依赖服务端响应或下载 ticket；浏览器能力受同源、CORS 和文件系统 API 限制。

## Tauri 桌面端

Tauri 复用同一套 Vue 页面、Router、Store 和大部分 API wrapper。运行时通过 Tauri command 增加原生文件系统选择、断点传输、目录递归和持久传输队列。

Rust 边界位于 `frontend/src-tauri/src`：

| 模块 | 职责 |
|------|------|
| `settings.rs` | 服务地址、默认下载目录和上传/下载限速 |
| `transfer.rs` | 传输任务、节点状态、暂停/恢复/取消和持久化 |
| `upload.rs` | 本地文件/目录遍历、分片上传和恢复 |
| `download.rs` | 文件与目录下载、目标目录选择和本地写入 |

前端通过运行时判断调用桌面 API；普通业务请求仍走共享 REST client。不要在 Rust 层复制团队权限或文件状态机。Tauri 的价值是平台 I/O 与长时间传输，不是建立第二套业务后端。

## Android

Android 位于 `android/app`，使用 Jetpack Compose、Navigation Compose、Hilt、Retrofit/OkHttp、DataStore、Coroutines、Coil 和 Media3。

```text
Compose Screen
  -> ViewModel
  -> Repository
  -> Retrofit ApiService / TransferRepository
  -> REST API or local device storage
```

- `data/remote/ApiService.kt` 是 Android 的 HTTP 契约入口。
- `data/repository` 组合远端调用、认证刷新和上传/下载行为。
- ViewModel 暴露界面状态与动作，Composable 负责渲染和用户交互。
- Navigation 定义登录、个人文件、团队、分享、收藏、标签、版本、通知、传输和个人资料等页面。
- DataStore 保存适合本地持久化的设置/token；客户端不能把它当作服务器状态副本。
- Android 支持团队文件、成员、邀请、任务、权限、版本和 OnlyOffice 入口，但全局管理功能范围受 [Android 管理范围 ADR](../decisions/001-android-admin-scope.md)约束。

## 能力矩阵

| 能力 | Web | Tauri | Android |
|------|-----|-------|---------|
| 认证、个人文件、回收站、分享、标签、收藏、版本 | 是 | 复用 Web | 是 |
| 团队文件、成员、邀请、权限与任务 | 是 | 复用 Web | 是 |
| 浏览器在线预览/OnlyOffice | 是 | 复用 WebView 页面 | 通过 Android 页面/WebView 入口 |
| 原生目录上传与持久传输队列 | 受浏览器能力限制 | 是，Rust 实现 | 是，Android Repository/设备存储 |
| 全局管理员界面 | 是 | 复用 Web | 非当前完整支持范围 |
| WebDAV | 外部 WebDAV 客户端使用 | 外部客户端使用 | 外部客户端使用 |

矩阵描述架构边界，不代替逐接口支持清单。修改共同协议时，应同时检索 Web API wrapper、Tauri 调用适配和 Android `ApiService`。

## 认证与刷新

各端应通过自己的统一 HTTP client 注入 access token，并在约定条件下刷新 token。刷新并发必须合并，避免多个失败请求同时旋转 refresh token。登出后清理本地凭据与用户相关缓存；服务端撤销仍是最终安全边界。

SSE 通知不能把 access token 放在查询参数。客户端先以 Bearer token 创建一次性 stream ticket，再用 ticket 建立 `EventSource`。不支持持续 SSE 的平台可以轮询通知列表和未读数。

## 权限驱动 UI

团队角色用于团队级导航和管理入口，文件有效权限用于具体按钮。客户端应区分：

- `canRead`：是否展示/进入资源。
- `canWrite`：新建、重命名、移动、上传等修改动作。
- `canShare`、`canDelete`：分享与删除动作。
- `canManage`：查看和修改该资源的权限覆盖。

隐藏按钮不是授权机制。任何请求都必须允许服务端返回 403 后刷新界面状态，详细解析顺序见[权限模型](PERMISSIONS.md)。

## 客户端变更规则

1. API 路径或 DTO 改动同时更新三端契约入口和端点契约测试。
2. 新增业务错误码时，保持通用错误处理可回退，不在页面硬编码后端英文消息。
3. 新增消息 key 时同步 Web、Android 和后端 catalog，并运行本地化检查。
4. Tauri 原生 command 只处理平台能力；可共享的页面和协议逻辑留在 TypeScript。
5. Android 测试以行为、状态转换和端到端场景为指标，不生成或校验代码行覆盖率。
