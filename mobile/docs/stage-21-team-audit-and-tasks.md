# KMP 移动端迁移阶段 21：团队审计与任务中心

## 范围

阶段 21 将团队审计日志、筛选与异步导出，以及异步任务查看、筛选、重试和结果下载迁移到 KMP 共享代码。应用版本为 `versionCode=22`、`versionName=0.22.0-stage21`。Android 是当前已有完整设备验证的应用壳；API、Repository、Store、类型安全路由和 Compose 界面均位于共享源码，iOS 继续保持共享源码 metadata 可编译。

本阶段不加入全局管理员审计、后台任务运维、Range 续传或 Room 持久传输，也不修改或构建旧版 `android/`。发布、升级、监控和旧应用切换仍由阶段 22 处理。

## 客户端行为

- 团队设置增加治理入口。所有活动成员可打开任务中心；审计入口只对 OWNER/ADMIN 可见，直接进入审计路由时仍按当前团队角色拒绝非管理员。
- `TeamAuditApi`、`TeamAuditRepository` 和 `TeamAuditStore` 独立负责审计。筛选支持操作者用户 ID、动作、目标类型、成功/失败与起止时间；用户显式应用后，同一组已应用筛选用于分页和异步导出。操作者必须为正整数，起始时间不得晚于结束时间。
- 手机审计页使用可折叠筛选与独立日志列表，宽屏使用筛选/日志双栏。详情展示操作者、目标、结果、HTTP 状态、request ID、trace ID 和结构化详情。导出调用 `POST /audit-logs/export-tasks`，创建成功后跳转到任务中心并高亮新任务。
- `TeamTasksApi`、`TeamTasksRepository` 和 `TeamTasksStore` 独立负责按状态与类型分页、刷新、失败重试和结果保存。OWNER/ADMIN 与普通成员看到的任务范围完全以后端当前权限判定为准。
- 任务页只在前台可见且当前页存在 `PENDING` 或 `RUNNING` 任务时每 5 秒刷新；离开页面、任务终止、团队切换或会话重置立即停止轮询。按钮可见性只用于交互引导，后端仍执行最终权限判定。
- 结果通过系统 `CreateDocument` 选择目标，并经共享 `TransferFileAccess` 流式写入应用私有临时文件。客户端只接受完整 `200`，校验 `Content-Length` 与任务结果大小，成功后写入目标并释放 URI；失败或取消会删除临时文件并释放 URI。本流程不支持 Range、不写 Room，也不会与可恢复文件传输队列混用。Android SAF Provider 不保证跨 Provider 原子替换。
- 两个 Store 均绑定 `teamId` 并使用请求世代、协程取消和响应匹配丢弃迟到结果。退出、删除、注销、会话失效和切换服务器会统一清理审计与任务状态；客户端 `TeamTask` 不建模服务端内部 `payload`、`resultPath` 或 trace 字段。任务角色变化会立即清空旧列表并按新角色重新加载。

## 服务端契约

- 沿用 `GET /api/v1/teams/{teamId}/audit-logs`、`POST /audit-logs/export-tasks`、`GET /tasks`、`POST /tasks/{taskId}/retry` 和 `GET /tasks/{taskId}/download`，没有数据库字段或 Flyway migration。保留同步审计 CSV 端点供既有 Web/Tauri 调用方使用。
- 审计列表与任务列表固定按 `createdAt DESC, id DESC` 排序。列表和导出沿用同一审计筛选语义；移动端只创建不受同步 10000 行上限影响的异步导出任务。
- 任务列表、重试和结果下载继续按任务类型及当前角色、发起人和目标文件有效权限裁剪。服务端结构化文案 key 由客户端白名单解析，未知 key 使用安全回退。
- 重试先按团队锁定任务行，再重新校验当前成员与目标权限及失败状态，随后清除旧结果、增加重试次数并重新写入 outbox。并发重试只有一个请求可以把同一失败任务改为 `PENDING`。

## 验证

实现包含 KMP MockEngine 协议测试、审计/任务 Store 的筛选、导出、分页、重试、下载、团队切换和迟到响应测试，Android Compose 手机/宽屏与权限可见性测试，以及 `backend-test/tests/test_stage21_team_governance.py` 的真实 API 权限、异步 CSV 和并发重试旅程。

2026-09-12 已完成以下收尾验收：

- `backend`、`integration`、`blackbox`、`security`、`web`、`tauri`、`observability`、`resilience` 和 `mobile-unit` 均通过。黑盒共 156 passed、20 deselected；阶段 20/21 任务专项在强制重建后通过，覆盖安全任务响应、ZIP 发起人下载权限、非法审计筛选、并发转让和并发重试。
- Web 单元测试 171 项通过，Core Playwright 30/30 通过；Tauri Rust 14 项、桌面 E2E 3/3 通过；Prometheus 规则和可观测性 7 项、韧性 2 项通过。
- KMP `mobile-unit` 通过，包含国际化门禁、Host 测试、Android Debug/Release/R8 和 `:shared:compileIosMainKotlinMetadata`。服务端任务接口现使用安全 DTO，不返回 `payload`、`resultPath` 或 trace 字段；Android SAF 结果保存明确为私有临时文件校验后写入目标，不承诺跨 Provider 原子替换。
- API 28 `bcd_api28` 设备测试 42/42 通过；API 34 `bcd_api34` 设备测试报告 43 项，其中 1 项 WorkManager 平台条件用例按预期跳过、0 失败。两套设备均使用 `-gpu host` 启动，覆盖 Android 设备级 Compose、SAF、角色切换和系统调度门禁。
