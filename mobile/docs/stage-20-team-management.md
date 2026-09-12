# KMP 移动端迁移阶段 20：团队管理

## 范围

阶段 20 将团队改名、退出、软删除和所有权转让迁移到 KMP 共享代码。应用版本为 `versionCode=21`、`versionName=0.21.0-stage20`。Android 是当前已有完整设备验证的应用壳；API、Repository、Store、类型安全路由和 Compose 界面继续位于共享源码，iOS 保持共享源码 metadata 可编译。

本阶段不包含管理员归档、恢复、立即清除、删除撤销、成员批量操作，以及阶段 21 的审计与任务中心界面。旧版 `android/` 仅用于行为参考，没有修改或参与构建。

## 客户端行为

- 独立的 `TeamManagementApi`、`TeamManagementRepository` 和 `TeamManagementStore` 负责团队详情刷新、改名、退出、删除和所有权转让，不复用浏览或成员请求状态。
- `TeamSettingsRoute(teamId)` 从团队工作区顶栏进入。手机使用单列滚动页；宽屏把概览/名称设置与危险操作分栏展示。页面显示当前角色、已用容量和总容量。
- 团队名称会去除首尾空白，最终长度必须为 1～80；仅 OWNER/ADMIN 可以保存。成功响应同步更新团队列表、工作区标题、设置状态和依赖角色的操作入口。
- 非 OWNER 只显示“退出团队”，OWNER 只显示“删除团队”。确认框明确显示团队名、立即失去访问权限或全员不可访问的影响；删除提示既有 30 天保留期。
- 所有权转让复用成员列表，只向 OWNER 对其他活动非 OWNER 成员显示。确认框明确目标会成为 OWNER、当前用户会降为 ADMIN；成功后刷新成员并立即传播新角色。
- Store 使用绑定团队 ID、请求世代和协程取消共同隔离迟到响应。退出、删除、注销、会话失效或切换服务器会清理浏览、搜索、成员、邀请、权限、收藏、标签、分享、回收站、版本和管理状态。团队传输任务按既有约定保留。

## 服务端契约

- 沿用 `GET/PUT /api/v1/teams/{teamId}`、`POST /leave`、`DELETE /api/v1/teams/{teamId}` 和 `POST /owner`，没有新增数据库字段或 Flyway migration，Web/Tauri 响应结构保持兼容。
- 改名允许 OWNER/ADMIN；退出允许 ADMIN/EDITOR/VIEWER；删除仅 OWNER；转让仅 OWNER，目标必须是其他活动成员。
- 转让后目标成为 OWNER，原 OWNER 固定降为 ADMIN，`TeamResponse.role` 返回原 OWNER 的新角色。
- 四项写操作都先锁定团队行，再重新校验活动成员和角色。并发转让最多一个成功，避免产生多个 OWNER；删除保持幂等并继续写入 30 天清理时间、团队通知和结构化审计。

## 验证

阶段实现包含后端单元测试、KMP MockEngine 契约测试、Store 请求隔离与状态传播测试、Android Compose 手机/宽屏与角色矩阵测试，以及 `backend-test/tests/test_stage20_team_management.py` 的真实 API 生命周期和并发转让旅程。

2026-09-10 已完成以下验收：

- `backend`、`integration`、`blackbox`、`security`、`web`、`tauri`、`mobile-unit` 均独立通过；黑盒共 154 passed、20 deselected，包含阶段 20 的两条真实 API 旅程。
- 使用 `-gpu host` 依次在 API 28 和 API 34 上通过 `mobile-android`；API 28 完成 40 条测试，API 34 完成 41 条测试，其中 1 条 WorkManager 平台条件测试按预期跳过。
- 保持 API 34 在线执行 `all --keep-going --skip-docker-build`，包括数据清理在内的 21 个步骤全部 PASS。Web E2E 30/30、OnlyOffice E2E 7/7、Tauri Rust 14/14、Tauri E2E 3/3、WebDAV 4/4、观测 7/7、韧性 2/2 均通过。
- KMP 门禁覆盖主机测试、Android Debug/Release/R8、Android 测试编译和 `:shared:compileIosMainKotlinMetadata`。验证过程没有构建旧版 `android/`。

Tauri 运行期间的宿主依赖与 `tauri-driver` 探测会输出现有诊断警告，实际 WebKitGTK 会话和三组规格均通过；没有遗留实现失败或环境失败。
