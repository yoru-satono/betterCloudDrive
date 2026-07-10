# 团队模块阶段发布说明

本文档记录当前团队模块阶段性能力、迁移要求、环境要求和已知限制，供本地验证、测试交接和部署前检查使用。

## 已实现能力

### 团队基础

- 团队创建、重命名、列表、详情、成员列表。
- 成员角色：`OWNER`、`ADMIN`、`EDITOR`、`VIEWER`。
- 成员添加、角色调整、移除、退出团队、转让团队所有权。
- 团队软删除、恢复、归档、取消归档、永久清理。
- 团队删除和清理具备幂等处理，重复调用返回稳定结果。

### 邀请流程

- 团队邀请创建、列表、取消。
- 邀请链接查询、接受、拒绝。
- 团队邀请会进入异步通知队列并生成站内通知。
- Android 端已补邀请链接接受/拒绝页面和深链入口。

### 团队文件

- 团队文件夹创建、文件上传、列表、搜索、预览、下载票据、下载。
- 团队文件重命名、移动、复制、删除、回收站列表、恢复、永久删除。
- 团队分享、标签、收藏能力已接入，并按可见权限过滤。
- 团队分页列表在权限过滤后重新分页，`total` 反映用户实际可见数据。

### 权限

- 支持团队角色基础权限和文件/文件夹级权限覆盖。
- 支持用户级覆盖、角色级覆盖、文件夹继承到子文件。
- 后端强制权限位自洽：`write/share/delete/manage` 不能在 `read=false` 时单独成立。
- 支持自锁保护，避免管理者把自己锁出权限管理。
- OnlyOffice 团队文档预览和编辑已按文件级有效权限判断，而不是只按团队角色判断。

### 审计和通知

- 团队关键动作写入团队审计日志，包括团队、成员、邀请、文件、分享、标签、收藏、权限、配额、删除/恢复/归档/清理。
- 团队审计列表和导出时间参数解析一致。
- 团队审计 CSV 导出与 admin 导出共用导出逻辑。
- 团队活动流已提供成员可见的精简时间线，文件相关活动会按当前用户文件读权限过滤。
- 团队通知、未读计数、批量已读、配额告警基础能力已实现；成员、邀请、权限、配额、删除通知已通过 Redis Streams 异步化，失败可重试。

### 配额

- 支持团队配额查看和 admin 配额调整。
- 上传完成接口具备幂等处理，避免重复完成导致文件和配额重复计量。
- 团队配额告警会进入异步通知队列并生成通知。

### OnlyOffice

- Docker Compose 接入 OnlyOffice Document Server。
- 支持个人 Office 文件只读预览。
- 支持团队 Office 文件按权限返回只读或可编辑配置。
- 支持团队文档编辑保存为新版本。
- OnlyOffice callback 具备幂等保护，重复 callback 不生成重复版本。
- OnlyOffice 保存 callback 已异步化，HTTP callback 只入队并快速返回；后台 worker 负责下载编辑结果、保存版本，失败后可在团队任务中心重试。
- OnlyOffice 保存失败会生成失败任务、失败审计和 `TEAM_OFFICE_SAVE_FAILED` 站内通知，管理员可在团队审计中过滤排查。
- Web OnlyOffice 真实保存 E2E 已覆盖 docx、xlsx、pptx 三类团队可编辑文件。
- Web OnlyOffice 个人只读预览 E2E 已覆盖 docx、xlsx、pptx；个人文件配置不生成保存 callback，后端个人 callback 路由不可用。
- OnlyOffice 配置已关闭插件和宏，并显式禁用 AI 插件；Web E2E 会检查编辑器中不出现 AI 或插件入口。
- Web OnlyOffice 预览弹窗已提供团队文档历史版本入口；可写成员可下载和恢复旧版本，只读成员仅可下载。
- 团队文档历史版本支持列表、下载、恢复。

### Admin 后台

- Admin 可查看团队列表、详情、成员、文件、审计、配额。
- Admin 可归档、取消归档、恢复、永久清理团队。
- Admin 可导出团队审计。
- Admin 支持团队删除后的恢复/归档后台能力，并展示删除原因、删除人、计划清理时间、文件/成员计数和最近审计，便于判断恢复或永久清理。

### 客户端

- Web/Tauri 已接入团队核心流程、团队设置、连接设置、上下文搜索和 OnlyOffice 预览/编辑入口。
- Tauri 标题栏保留连接设置入口，登录后通过弹窗管理 API 地址和 Web 分享地址。
- Android 已接入团队 MVP、邀请深链、团队文件、分享、通知、OnlyOffice 入口和部分团队权限测试覆盖。

## 迁移要求

- 数据库迁移使用 Flyway，部署时必须执行到当前最新版本：
  - `V2__team_space_mvp.sql`
  - `V3__team_share_links.sql`
  - `V4__team_invites_audit_tags.sql`
  - `V5__retain_team_audit_logs.sql`
  - `V6__team_notifications_permissions_archival.sql`
  - `V7__upload_completion_idempotency.sql`
  - `V8__onlyoffice_callback_idempotency.sql`
  - `V9__team_event_outbox.sql`
  - `V10__notification_preferences.sql`
  - `V11__team_tasks.sql`
  - `V12__onlyoffice_async_save_tasks.sql`
- PostgreSQL 版本按当前环境模板使用 PostgreSQL 18。
- 生产环境必须替换默认 JWT、数据库、邮箱、OnlyOffice JWT secret 等密钥。
- 升级前建议备份 PostgreSQL 和 SeaweedFS 数据；团队文件、分享、版本、审计都依赖这两类存储。

## 环境要求

- Docker Compose 需要启动：PostgreSQL、Redis、SeaweedFS、Mailpit、OnlyOffice Document Server、后端、前端。
- OnlyOffice 关键配置必须一致：
  - `ONLYOFFICE_INTERNAL_URL`
  - `ONLYOFFICE_PUBLIC_URL`
  - `APP_INTERNAL_API_BASE_URL`
  - `ONLYOFFICE_JWT_SECRET`
- Web/Tauri 使用前端环境变量配置 API 地址和 Web 分享地址。
- Android E2E 当前使用 Android 15 模拟器，连接地址为 `localhost:16416`。
- 本地一键验证脚本为 `scripts/verify-all.ps1`，可按需传入 `-AndroidSerial localhost:16416`。

## 验证记录

最近一次团队权限矩阵收尾验证：

- `mvn test`
- `backend-test/.venv/Scripts/pytest.exe tests/test_teams.py -v`
- `backend-test/.venv/Scripts/pytest.exe -v`

结果：

- 后端 Maven 全量测试通过。
- backend-test 全量黑盒测试通过：`135 passed, 1 skipped`。

## 已知限制

- 团队事件 outbox、Redis Streams 队列基础设施和团队通知异步化已落地，后续业务异步化可继续消费队列。
- 团队通知已提供 Web/Tauri SSE 实时未读数刷新，用户可在个人资料页配置团队通知类型偏好。
- 团队任务中心已提供审计导出、ZIP、缩略图、OnlyOffice 保存和团队回收站清理的状态入口；审计导出会创建后台任务并在成功后提供 CSV 下载结果，结果文件按保留时间自动清理；团队文件夹 ZIP 请求和图片上传会创建后台任务，失败后可在任务中心重试；OnlyOffice 保存失败重试会把任务和 callback 重新置为 `PENDING`，由后台 worker 再处理。
- Redis Streams 与 PostgreSQL 队列事实表已提供 stream length、consumer pending、pending/retry、dead letter、处理结果/耗时和指标源可用性观测；达到配置阈值时记录一次告警，恢复后记录恢复日志。
- 权限来源说明尚未完整展示，前端只能看到最终效果或基础权限配置。
- Web/Tauri/Android 的完整团队 E2E 和契约测试仍需继续补齐。
- 清理等业务异步化仍可按实际负载继续扩展；当前 ROADMAP 中的队列化、Compose 健康检查、OnlyOffice 部署说明、备份恢复手册和观测指标均已落地。
