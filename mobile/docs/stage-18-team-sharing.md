# KMP 移动端迁移阶段 18：团队分享

## 范围

阶段 18 将团队分享创建、管理、搜索筛选、分页、详情、统计、密码、限制编辑、链接复制和取消迁移到 KMP 共享代码。应用版本为 `versionCode=19`、`versionName=0.19.0-stage18`。Android 是当前已有完整设备验证的应用壳；团队分享 API、Repository、Store、路由和 Compose 界面均位于共享源码，iOS 继续保持共享源码元数据可编译。

本阶段不包含公开分享浏览、保存他人分享、分享深链、批量分享或团队回收站/版本功能。旧版 `android/` 仅作为迁移参考，没有修改或参与构建。

## 客户端行为

- 团队分享使用独立的 `TeamSharesApi`、`TeamSharesRepository` 和 `TeamSharesStore`，不会复用个人分享请求状态。
- `TeamSharesRoute(teamId)` 在手机展示列表并导航到独立详情页，在宽屏展示列表/详情双栏；列表每页 50 条，搜索防抖 300 ms，支持密码和有效期筛选、刷新、分页去重与尾部重试。
- 团队工作区“整理”菜单提供分享管理入口。目录、搜索、收藏、标签文件列表和文件详情在目标项目有效 `canShare` 时显示“创建分享”。这些入口会懒加载目标项目的有效权限。
- 个人与团队分享共用 `ShareManagementState` 和分享表单/详情组件，统一校验 4～16 字符密码、正整数访问上限和未来有效期，并支持永不过期、1 天、7 天、30 天、自定义时间及显式清空限制。
- 详情使用服务端返回的 canonical `shareUrl`，并展示分享码、文件 ID/类型/大小、访问/下载统计、状态和有效期。链接和已授权的明文密码均通过平台剪贴板复制。
- 明文密码仅保存在当前详情内存中。关闭详情、切换团队、退出、会话失效或切换服务器会立即清除；请求以团队/账号世代和详情 ID 双重校验，迟到响应不能污染当前状态。

## 权限与服务端契约

- 列表、详情和复制链接要求目标项目有效 `canRead`；创建要求有效 `canShare`。
- 密码读取、编辑和取消要求有效 `canShare`，且操作者为分享创建者，或拥有目标项目有效 `canManage`；OWNER/ADMIN 默认满足管理条件。
- `/api/v1/teams/{teamId}/shares/{shareId}/password` 已收紧为与编辑、取消相同的所有权规则，越权返回 403。
- 团队分享列表在有效读取权限过滤后分页，并固定按 `createdAt DESC, id DESC` 排序。协议与数据库结构没有新增字段或 migration。

## 验证

阶段实现包含：

- 后端分享服务测试：权限过滤后的分页、稳定排序、创建者/管理者密码读取、普通成员拒绝、显式清空、幂等取消和审计。
- KMP MockEngine 契约测试：团队分享全部 CRUD 路径、查询参数和 JSON。
- KMP Store 测试：三页分页去重、300 ms 防抖、筛选、团队/账号/角色切换、迟到响应丢弃、权限矩阵、显式清空和密码清理。
- Android Compose 设备测试：团队工作区分享管理入口、`canShare` 控制的创建入口，以及手机列表导航和宽屏列表/详情布局。
- `backend-test/tests/test_stage18_team_sharing.py`：105 条团队分享三页旅程、筛选、文件/文件夹、统计、访问上限、权限撤销、取消失效与审计。

本次交付实际检查：

- 通过：`backend`、`blackbox`（151 项）、`security`（7 项）、`web`（170 项单元测试和 30 项 E2E）、`tauri`（14 项 Rust、3 个 E2E spec）及 `mobile-unit`。
- 通过：Android 测试源码编译、Python/Bash 语法检查、移动端国际化门禁和 `git diff --check`。
- 通过：2026-09-09 在 Android Emulator 37.1.11 上显式使用宿主 GPU 冷启动 `bcd_api28` 和 `bcd_api34`；API 28 完成 37 项设备测试，API 34 完成 38 项设备测试（其中 1 项低版本专用 WorkManager 用例按设计跳过），均无失败。
- AVD 排障结论：KVM、系统镜像和 ADB 均正常；SwiftShader、SwiftShader indirect 与 guest/lavapipe 会在 Emulator 自带 `libGLESv2.so` 的 `RenderThread` 中触发 SIGSEGV。无窗口启动显式传入 `-gpu host` 后两套 AVD 均稳定启动并完成设备门禁。
- 通过：API 34 在线时执行完整 `all --keep-going`，后端、集成、黑盒、安全、Web、OnlyOffice、Tauri、WebDAV、可观测性、韧性、KMP 主机/iOS 元数据和 Android 设备共 21 个步骤全部通过。旧版 `android/` 按迁移约束未构建。
