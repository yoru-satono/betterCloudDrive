# 客户端架构

本文面向 Web、Tauri 和 KMP 移动端开发者，说明三端共享的协议、代码复用边界和平台差异。复用策略见[KMP 移动端边界 ADR](../decisions/009-kmp-mobile-boundary.md)；接口字段以 [API 参考](../reference/API.md) 为准，本地化消息协议以[本地化参考](../reference/LOCALIZATION.md)为准。

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

## KMP 移动端

移动端位于 `mobile/`，采用 Kotlin Multiplatform、Compose Multiplatform、Ktor、Room 和手动 `AppContainer`。Android 是当前已有完整应用壳和设备验证的目标；iOS 当前保持公共代码编译能力，后续补充应用壳与平台服务。

```text
Compose Screen
  -> feature Store / AppCoordinator（仅会话协调）
  -> narrow Repository / TransferEngine
  -> domain API / AuthenticatedHttpClient / Room
  -> Android Keystore、SAF、UIDT 或 WorkManager
```

- `AppContainer` 显式创建认证 HTTP 核心、领域 API、窄 Repository 和业务 Store。不存在聚合全应用状态的 Controller，页面也不接收全局 actions 集合。
- `AuthStore`、个人功能 Store、`TeamsStore`、`TeamBrowserStore`、`TeamFileActionsStore`、`TeamSearchStore`、`TeamPreviewStore`、`TeamManagementStore`、`TransferQueueStore` 和 `DiagnosticsStore` 各自拥有独立 `StateFlow`、作业、缓存与重置边界，不直接读写其他 Store。
- `AppCoordinator` 只负责启动、会话生命周期和全局重置。跨域行为使用明确窄端口或事件：例如个人或团队上传批次结束事件只让对应浏览 Store 失效目标目录。
- `AuthenticatedHttpClient` 是唯一 Token 所有者，集中实现单飞刷新、一次重放、统一 envelope/错误解析和受控流式响应。各领域 API 不自行保存或刷新 Token。
- DataStore 保存服务器设置，完整 Token 对由 Android Keystore AES-256-GCM 加密后原子保存。
- 认证层合并并发刷新，旋转 Token 后最多重放原请求一次；登出或切换服务器会清除服务器绑定的令牌、任务和临时文件。
- Room v2 是传输状态事实来源，v1→v2 迁移为任务增加可空历史版本号并保留旧任务。Android 14+ 使用 UIDT，较低版本回退 WorkManager；一次可见用户操作提交一个最多包含 100 个任务 ID 的系统批次，真实参数仍从 Room 读取，全局传输互斥保证逐项执行。
- 上传按 5 MiB 流式分片并通过 `missingChunks` 恢复；下载通过受控流式响应先写私有 `.part`，校验 Range、长度和可选 MD5 后再提交到 SAF 目标。Android 9 WorkManager 与 Android 14 UIDT 的进程重建续传均已验收。
- 界面、无障碍描述、稳定错误和 Android 通知通过共享 Compose Multiplatform 资源本地化。默认目录保存英语，CN 与 SG 地区目录使用相同稳定键保存简体中文；繁体中文、无地区中文及其他未支持语言回退英语。认证页和“我的”提供 `en-US`、`zh-CN`、`zh-SG`，登录后精确同步账号偏好；历史 `zh-Hans` 账号在 KMP 中映射为 `zh-CN`。
- “我的”按独立状态加载只读账号资料和容量，设备主题支持跟随系统、浅色与深色。切换服务器先确认，再清除旧服务器会话、后台任务、Room 传输和临时文件；语言与主题作为设备偏好保留。
- 共享 Navigation Compose 产品外壳固定提供个人、团队、传输和我的四个顶层入口。团队分支从团队分页列表进入工作区，支持创建、切换、目录分页排序、列表/网格、搜索、只读预览、成员管理，以及受有效权限控制的文件修改、权限覆盖和传输；进程重启后仍从个人根目录开始，重新进入团队入口时不恢复持久化团队选择。
- Android SAF 多文件选择只保留去重后的前 100 项，上传目标绑定打开选择器时的个人或团队目录。成功或取消后释放 URI 权限；失败、暂停和等待登录保留权限以便恢复，终态记录由用户确认后统一清理。
- 个人文件分支使用类型安全文件夹路由。每个目录按 50 条滚动分页，固定文件夹优先并支持名称、修改时间和大小升降序；目录返回栈分别保留内容、分页与滚动锚点。
- 个人收藏和标签属于个人导航分支，通过个人空间顶栏的“整理”菜单进入。收藏与标签文件按 50 条分页并支持 300 ms 防抖搜索；手机使用独立页面，宽屏标签页采用标签与文件双栏。文件标签保存使用一次读取和一次原子替换，避免逐标签请求产生部分成功。
- 个人分享也属于个人导航分支。创建入口复用于目录、搜索、收藏、标签文件和详情，管理列表按 50 条分页并支持搜索与密码/过期筛选。分享完整链接以服务端 `shareUrl` 为准；明文密码仅在用户主动查看后短期保留于详情状态，离开页面立即清除，剪贴板写入也必须由用户主动触发。
- 个人回收站从“整理”菜单进入，按删除根分页和搜索，支持单项恢复、永久删除及清空。普通文件的各入口可打开版本历史；版本下载作为带 `remoteVersionNumber` 的普通传输任务进入同一可恢复队列，继续使用 SAF、`.part`、Range、MD5 和后台调度。
- 个人搜索也是类型安全的个人导航分支：输入防抖 300 ms，每页 50 项并按 ID 去重；打开文件夹或所在位置时只导航到目标目录，不把临时下载凭证写入路由。
- KMP 个人预览按 Office、图片、UTF-8 文本、视频和不支持类型分类。图片与视频复用内存中的下载 ticket，Coil 禁用 ticket 磁盘缓存，Media3 不启用媒体缓存；文本只读取前 512 KiB；OnlyOffice 原样使用后端签名的 `mobile=true` 只读配置。
- KMP 团队浏览、成员、权限、文件操作和生命周期管理使用独立 API、Repository 与 Store，不复用个人状态。`TeamMembersStore` 管理分页成员及角色操作，`TeamManagementStore` 管理改名、退出、软删除和所有权转让，`TeamPermissionsStore` 管理单个资源的有效权限、来源链和直接覆盖；授权或角色变化通过显式事件使 `TeamFileActionsStore` 的权限缓存失效。按钮只用于降低误操作，后端 403 仍是最终裁决。团队目录支持新建文件夹、单项重命名与移动、当前目录多选删除、最多 100 个文件的当前目录上传及单文件下载；团队回收站与历史版本使用各自的独立 Store，复制和文件夹 ZIP 暂不提供。图片和视频 ticket 仅存在 `TeamPreviewStore` 内存，媒体键包含团队 ID，文本限制为 512 KiB，OnlyOffice 仍原样使用后端签名的 `mobile=true` 配置。
- `TeamInvitesStore` 管理成员页的邀请标签，`IncomingInviteStore` 只在内存中持有当前邀请 token；Android 仅接受严格匹配的 `betterclouddrive://team-invites/{token}`。未登录用户可先查看邀请并续接登录或锁定邀请邮箱的注册流程，换服务器、普通退出或会话失效会清除 token。
- `NotificationsStore` 负责分页、筛选、已读状态、未读徽标和前台 SSE。流连接只在登录且应用前台时存在，每次连接使用新的单次 ticket，并按 1、2、5、10、30 秒上限退避；ticket 不进入路由、Room、DataStore 或日志。通知目标只解析邀请、团队目录和团队成员设置白名单，其他链接不会作为外部 URL 打开。
- `TeamManagementStore` 只持有一个绑定团队的详情和修改状态。改名或角色变化通过容器同步到 `TeamsStore` 与 `TeamBrowserStore`，并使角色相关权限状态失效；退出或删除会取消并清空所有团队功能 Store，但保留既有团队传输任务，由服务端权限决定后续结果。
- `TeamAuditStore` 与 `TeamTasksStore` 分别绑定一个团队。审计仅允许 OWNER/ADMIN 进入，草稿筛选在显式应用后同时用于稳定分页和异步 CSV 导出；导出成功直接跳转并高亮对应任务。任务中心按服务端可见性支持状态/类型分页、失败重试和 SAF 结果保存，仅在页面前台且存在 `PENDING/RUNNING` 任务时每 5 秒刷新。任务 DTO 不暴露服务端 `payload`、`resultPath` 或 trace 字段，结果下载要求完整 `200` 响应并经长度校验，先写入私有临时文件，再提交到用户选择的 SAF 目标；SAF Provider 不保证跨 Provider 原子替换。
- 顶层传输页是个人与团队任务的唯一管理入口，支持按方向和 `全部/个人/团队` 作用域过滤。团队任务在 Room 中保存 `teamId` 与目标父目录，继续复用同一全局串行引擎、UIDT/WorkManager、分片会话、Range、MD5、通知节流和 URI 生命周期；团队上传批次全部离开活动状态后只刷新目标目录一次。
- 普通文件详情只展示服务端元数据和单文件下载：手机使用底部页，宽度不小于 600dp 时使用右侧详情栏。个人目录支持新建文件夹，以及单项重命名、移动、递归复制和删除；长按进入的多选模式只提供批量移入回收站。文件修改后重新加载当前已加载页并保持滚动锚点，目标目录缓存同步失效。
- OnlyOffice 诊断探针只在独立 Debug 诊断页出现；个人 Office 文件则可从产品预览页加载。两者都原样使用后端签名的 `mobile=true` 只读配置，Release 不注册诊断入口和诊断路由。

旧版 Android 工程已在阶段 22 完成业务测试迁移后删除。当前团队成员与权限边界见[阶段 15 团队成员与文件权限](../../mobile/docs/stage-15-team-members-permissions.md)，架构边界见[阶段 11.5 KMP 架构边界收口](../../mobile/docs/stage-11-5-architecture-boundaries.md)，最终处置见[阶段 22 发布与切换](../../mobile/docs/stage-22-release-cutover.md)。

阶段 7/8 已在 Android 14 上完成 64 MiB 上传会话恢复、非零 Range 下载、三页搜索和全部当前预览类型的真实旅程，并在 Android 9/14 设备矩阵回归。阶段 12 又完成了 64 MiB 历史版本下载的两次进程中断与非零 Range 恢复、Room v1→v2 和回收站删除根真实验收。个人和团队业务均已达到迁移路线定义的功能对等，阶段 22 已完成测试迁移、正式应用标识和旧工程退役。

## 能力矩阵

| 能力 | Web | Tauri | Android |
|------|-----|-------|---------|
| 认证、个人文件、回收站、分享、标签、收藏、版本 | 是 | 复用 Web | 是 |
| 团队文件、成员、邀请、权限与任务 | 是 | 复用 Web | 是 |
| 浏览器在线预览/OnlyOffice | 按权限查看或编辑 | 复用 Web 页面 | Android WebView 只读查看 |
| 原生目录上传与持久传输队列 | 受浏览器能力限制 | 是，Rust 实现 | KMP 使用 Room + UIDT/WorkManager |
| 全局管理员界面 | 是 | 复用 Web | 非当前完整支持范围 |
| WebDAV | 外部 WebDAV 客户端使用 | 外部客户端使用 | 外部客户端使用 |

矩阵描述架构边界，不代替逐接口支持清单。修改共同协议时，应同时检索 Web API wrapper、Tauri 调用适配和 KMP 各领域 API。

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
