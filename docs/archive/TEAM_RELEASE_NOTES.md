# 团队模块阶段发布说明

> 历史归档：本文档是 2026-07-13 的阶段性快照，仅供追溯，不作为当前功能、接口或部署方式的权威说明。当前运行时消息队列已从 Redis Streams 迁移到 RabbitMQ；当前架构请参阅 [消息队列选型](../decisions/MESSAGE_QUEUE_SELECTION.md)。

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
- 后端展示协议已升级为纯语义 `messageKey/messageParams`；通知、任务、活动和权限来源使用各自的 key/params 字段，不再返回自然语言兜底。
- Web/Tauri 使用 `vue-i18n` 简体中文资源，Android 使用 `strings.xml` 与集中式 resolver；未知键只显示客户端通用提示。
- Web、Tauri 和 Android 支持跟随系统、浅色和深色主题；首次默认跟随系统，用户选择保存在当前设备并跨登录状态保留。

## 迁移要求

- 数据库使用合并后的 Flyway baseline：`V1__schema.sql` 定义最终结构，`R__development_data.sql` 提供幂等开发数据。
- 合并后的 baseline 仅支持全新数据库；旧版 Flyway 数据库升级前必须备份并重建。
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

最近一次完整验证（2026-07-13）：

- `mvn test`
- `backend-test/.venv/Scripts/pytest.exe tests/test_teams.py -v`
- `backend-test/.venv/Scripts/pytest.exe -v`
- `scripts/verify-all.ps1 -KeepGoing`

结果：

- 后端 Maven 全量测试通过。
- backend-test 全量黑盒测试通过：`136 passed`。
- 全量验证 15/15 阶段通过：后端单元 429 项、黑盒 136 项、前端单元 145 项、Web E2E 29 项、WebDAV 4 项、Android 单元 18 项、Android E2E 26 项，并覆盖 Tauri、可观测性和韧性测试。

## 已知限制

- 团队事件 outbox、Redis Streams 队列基础设施和团队通知异步化已落地，后续业务异步化可继续消费队列。
- 团队通知已提供 Web/Tauri SSE 实时未读数刷新，用户可在个人资料页配置团队通知类型偏好。
- 团队任务中心已提供审计导出、ZIP、缩略图、OnlyOffice 保存和团队回收站清理的状态入口；审计导出会创建后台任务并在成功后提供 CSV 下载结果，结果文件按保留时间自动清理；团队文件夹 ZIP 请求和图片上传会创建后台任务，失败后可在任务中心重试；OnlyOffice 保存失败重试会把任务和 callback 重新置为 `PENDING`，由后台 worker 再处理。
- Redis Streams 与 PostgreSQL 队列事实表已提供 stream length、consumer pending、pending/retry、dead letter、处理结果/耗时和指标源可用性观测；达到配置阈值时记录一次告警，恢复后记录恢复日志。
- 权限来源说明尚未完整展示，前端只能看到最终效果或基础权限配置。
- Web/Tauri/Android 的完整团队 E2E 和契约测试已补齐，后续可继续扩展更细的场景覆盖。
- 清理等业务异步化仍可按实际负载继续扩展；原 ROADMAP 中的队列化、Compose 健康检查、OnlyOffice 部署说明、备份恢复手册和观测指标均已落地。
