# KMP 移动端迁移阶段 19：团队回收站与版本

## 范围

阶段 19 将团队回收站和团队文件历史版本完整迁移到 KMP 共享代码。应用版本为 `versionCode=20`、`versionName=0.20.0-stage19`。Android 是当前已有完整设备验证的应用壳；API、Repository、Store、类型安全路由和 Compose 界面位于共享源码，iOS 继续保持共享源码元数据可编译。

本阶段包含团队删除根列表、搜索、分页、刷新、恢复、永久删除和清空，以及普通文件历史版本查看、恢复、下载和删除。不包含团队生命周期管理、审计中心、任务中心、版本预览、版本比较或 OnlyOffice 移动端编辑。旧版 `android/` 只作为行为参考，没有修改或参与构建。

## 客户端行为

- `TeamRecoveryApi` 下的团队回收站与版本端点由独立的 `TeamRecycleBinRepository`、`TeamVersionsRepository`、`TeamRecycleBinStore` 和 `TeamVersionsStore` 使用，不复用个人恢复状态。
- `TeamRecycleBinRoute(teamId)` 固定每页 50 项，搜索防抖 300 ms，支持刷新、分页去重和尾部重试。操作菜单按需加载目标项目有效权限：恢复要求 `canWrite`，永久删除要求 `canDelete`，清空只向 OWNER/ADMIN 展示。
- `TeamVersionsRoute(teamId, fileId)` 展示普通文件元数据和倒序历史版本。下载要求 `canRead`，恢复要求 `canWrite`，删除要求 `canDelete`；历史版本下载继续使用平台文档选择器和 Room 可恢复传输队列，并明确保存 `TransferScope.Team(teamId)` 与版本号。
- 团队工作区“整理”菜单增加回收站入口；团队目录、搜索、收藏、标签文件列表、文件详情和预览均可进入对应文件的版本页。手机使用独立目的地，宽屏保持共享自适应布局。
- Store 绑定当前 `teamId`/`fileId`，团队或文件切换会取消列表、权限、修改和下载入队任务；迟到响应通过请求世代与绑定 ID 丢弃。退出、会话失效和切换服务器时，`AppCoordinator` 会重置两套状态。

## 服务端契约

- 回收站仅列出删除根，按 `deletedAt DESC, id DESC` 稳定排序，在有效 `canRead` 过滤后分页，并返回不含对象存储路径和所有者字段的安全 DTO。
- 恢复要求删除根 `canWrite` 及目标父目录可写；失效父目录回退团队根目录。同一删除标记的完整子树递归恢复，当前内容和历史版本重新计入团队配额。
- 永久删除要求完整删除子树有效 `canDelete`；清空只允许 OWNER/ADMIN。恢复、永久删除和清空继续写入团队审计，清空保留既有团队任务记录。
- 版本端点只接受当前团队中未删除、祖先有效的普通文件。列表使用安全 `FileVersionResponse`；恢复前归档当前内容并检查团队配额，恢复和删除都会更新团队容量增量。
- 沿用现有 `/api/v1/teams/{teamId}/recycle-bin` 和 `/api/v1/teams/{teamId}/files/{fileId}/versions` 协议，不新增数据库结构或 Flyway migration。Web/Tauri 继续使用同一协议，版本类型不再声明服务端不会返回的 `storagePath`。

## 验证

阶段实现包含：

- 后端单元测试：删除根过滤和稳定排序、有效读取过滤、递归恢复/永久删除、团队配额、版本活动祖先校验、团队容量增减与审计。
- KMP MockEngine 和 Store 测试：全部路径、查询参数、Range 请求、300 ms 防抖、分页去重、权限矩阵、团队切换、迟到响应、历史版本团队作用域下载和全局清理。
- Android Compose 设备测试：团队整理入口、文件版本入口、回收站与版本手机/宽屏状态，以及未经授权的破坏性操作隐藏。
- `backend-test/tests/test_stage19_team_recovery_versions.py`：105 个删除根三页旅程、安全 DTO、读取过滤、递归文件夹恢复、权限矩阵、版本恢复/删除、Range 下载、权限撤销、清空和团队审计。

本次交付已通过后端单元与 Testcontainers 集成测试、152 项黑盒（包含阶段 19 真实 API 旅程）、7 项安全验收、171 项 Web 单元测试、30 项 Web E2E、14 项 Tauri Rust 测试与 3 个桌面 E2E 规格。`mobile-unit` 已覆盖 KMP 主机测试、Android Debug/Release 与 R8、Android 设备测试源码和 iOS 共享源码元数据编译；API 28 设备套件 38/38 通过，API 34 为 37 通过、1 项按平台条件跳过、0 失败。API 34 在线时执行的完整 `all --keep-going` 也已通过，包括 OnlyOffice、WebDAV、可观测性和韧性专项。旧版 `android/` 按迁移约束未修改、未构建。
