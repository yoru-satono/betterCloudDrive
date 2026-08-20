# 开发工作流

本文面向跨模块功能开发和代码评审，给出从确认事实、选择修改位置到验证与文档同步的统一流程。各端具体命令分别见[后端](BACKEND.md)、[Web](WEB.md)、[Tauri](TAURI.md)、[Android](ANDROID.md)和[测试策略](TESTING.md)。

## 1. 先确定事实来源

不要从旧示例或客户端现状反推服务端协议。按变更类型先找到实现事实：

| 变更 | 首先检查 |
|------|----------|
| HTTP 路径、认证、DTO | Controller、SecurityConfig、请求/响应类型 |
| 业务状态与事务 | Service 接口/实现、相关单元测试 |
| 数据表和查询 | Flyway、Entity、Repository、集成测试 |
| 对象内容和生命周期 | StorageService、文件/版本/上传 Service |
| 异步任务 | Outbox writer/publisher、`team_tasks`、Worker、DLQ |
| Web/Tauri 协议 | `frontend/src/api`、共享 client、Rust envelope/command |
| Android 协议 | Retrofit `ApiService`、Repository、interceptor |
| 运行配置 | 环境模板、Compose、Spring/Vite/Gradle 消费者 |

文档与实现冲突时先判断实现是否仍有效，再修正文档；不要把未完成或废弃代码直接写成当前能力。

## 2. 明确作用域和失败边界

编码前列出：

- 操作属于个人空间、团队空间、公开分享还是系统管理。
- 需要的账号状态、全局角色、团队角色和文件有效权限。
- 权威状态保存在哪里，是否跨 PostgreSQL、SeaweedFS、Redis 或 RabbitMQ。
- 成功、重复提交、并发、超时、权限变化和依赖失败时的预期结果。
- 用户从哪个客户端进入，以及不支持该功能的平台应如何表现。

跨存储或异步功能必须提前定义幂等键、重试、补偿、最终清理者和可观察状态。

## 3. 按依赖方向实现

后端通常按以下顺序：

1. 通用错误码或 DTO。
2. migration、Entity 和 Repository（如需要）。
3. Service 业务规则、事务和权限。
4. Controller/WebDAV/Worker 入口。
5. Web/Tauri/Android 协议适配和 UI 状态。

只改客户端的功能也应先确认服务端已经支持所需协议。多个客户端共用的业务规则留在后端；Web 与 Tauri 共用的界面/协议逻辑留在 TypeScript；平台文件系统能力才进入 Rust 或 Android 平台层。

## 4. 先跑最小反馈，再跑边界验收

开发中先执行离修改最近的测试，提交前再按[最低验证矩阵](TESTING.md#修改范围到最低验证)合并所需阶段。例如：

- Service + Repository：模块单元测试，再运行 `integration`。
- Controller + Web 页面：后端 Web 模块、blackbox、Web 单元和相关 Playwright。
- 共同 API：后端契约、Web/Tauri、Android 相关测试。
- 队列恢复：Service/Scheduler 单元、integration、resilience。
- Android UI：JVM 状态测试和 instrumentation；不生成或检查代码行覆盖率。

完整 `all` 只用于准备发布或确认跨系统变更，不应代替开发中的快速反馈。

## 5. 同步文档

| 实现变化 | 同步位置 |
|----------|----------|
| Controller/DTO/Security | `reference/API.md` 通用约定及对应的 `reference/api/*` 业务域正文 |
| 环境变量、端口、Compose 服务 | 配置参考、Quickstart、相关运维手册 |
| 模块依赖、实体、权限算法 | `architecture` 对应正文 |
| 启动、调试、验证方式 | 本目录对应指南和测试策略 |
| 生产操作或恢复步骤 | `operations` |
| 重要且稳定的技术选择 | ADR |

不要在多个位置复制全量字段或参数。非权威文档只保留必要摘要和链接。

## 6. 完成定义

一次开发变更至少满足：

- 正常、空、失败、权限拒绝、重试/重复提交等适用场景有明确行为。
- 个人/团队/管理员作用域没有串用，敏感值不进入日志或响应。
- 迁移和外部副作用有回滚、补偿或清理路径。
- 最低验证通过；未运行的高成本阶段有明确原因和后续入口。
- API、配置、架构、开发或运维文档按事实归属同步。
- 不提交本地 env、构建产物、测试报告、真实凭据或临时诊断数据。
