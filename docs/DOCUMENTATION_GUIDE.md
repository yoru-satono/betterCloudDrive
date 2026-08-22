# 文档维护指南

本文档定义 BetterCloudDrive 文档的信息架构、事实归属和维护约定。本轮目录重构已经完成；后续新增或移动文档仍采用渐进方式：新文档准备完成并通过事实核查后再替换旧入口，不保留两份同时声称权威的说明。

## 文档分类

| 分类 | 主要读者 | 回答的问题 | 不应包含 |
|------|----------|------------|----------|
| `getting-started` | 首次接触项目的人 | 如何准备环境并第一次成功运行？ | 完整变量表、内部实现细节、生产运维手册 |
| `architecture` | 开发者、评审者 | 系统由什么组成，组件如何协作，边界为何如此划分？ | 全量 API 参数、逐项环境变量、操作命令堆砌 |
| `development` | 功能开发者 | 如何修改、调试和验证各模块？ | 生产值班流程、历史发布快照 |
| `reference` | API 使用者、开发者、部署人员 | 某个接口、配置或协议的精确定义是什么？ | 设计动机、教程式长流程、历史状态 |
| `operations` | 部署和运维人员 | 如何部署、观察、备份、恢复和处置故障？ | 客户端开发教程、没有执行条件的设计设想 |
| `decisions` | 架构维护者 | 某项重要技术选择为什么做、替代方案是什么、后果是什么？ | 当前操作手册、会频繁变化的参数清单 |
| `archive` | 追溯历史的人 | 某个历史阶段发生过什么？ | 未标明时间的当前事实、当前文档入口 |

`ROADMAP.md` 暂时保留在 `docs` 根目录。只有当计划文档增长为多个文件时才新建 `roadmap` 目录，避免为单一短文档增加层级。

## 推荐结构

```text
docs/
├── README.md
├── DOCUMENTATION_GUIDE.md
├── DOCUMENTATION_RESTRUCTURE_CHECKLIST.md
├── ROADMAP.md
├── getting-started/
│   ├── README.md
│   ├── QUICKSTART.md
│   ├── DEVELOPMENT_SETUP.md
│   └── TROUBLESHOOTING.md
├── architecture/
│   ├── README.md
│   ├── OVERVIEW.md
│   ├── BACKEND.md
│   ├── DATA_MODEL.md
│   ├── CLIENTS.md
│   ├── PERMISSIONS.md
│   └── flows/
│       ├── UPLOAD_DOWNLOAD.md
│       ├── ASYNC_TASKS.md
│       └── ONLYOFFICE_SAVE.md
├── development/
│   ├── README.md
│   ├── WORKFLOW.md
│   ├── BACKEND.md
│   ├── WEB.md
│   ├── TAURI.md
│   ├── ANDROID.md
│   └── TESTING.md
├── reference/
│   ├── README.md
│   ├── API.md
│   ├── CONFIGURATION.md
│   ├── LOCALIZATION.md
│   ├── TESTING.md
│   └── api/
│       ├── README.md
│       ├── AUTH.md
│       ├── FILES.md
│       ├── TRANSFERS.md
│       ├── ORGANIZATION.md
│       ├── SHARING.md
│       ├── ADMIN.md
│       ├── TEAMS.md
│       ├── NOTIFICATIONS.md
│       └── OFFICE.md
├── operations/
│   ├── README.md
│   ├── PRODUCTION_DEPLOYMENT.md
│   ├── UPGRADE_ROLLBACK.md
│   ├── SECRET_ROTATION.md
│   ├── INCIDENT_RUNBOOK.md
│   ├── BACKUP_RESTORE.md
│   ├── OBSERVABILITY.md
│   ├── DATABASE_PERFORMANCE.md
│   └── ONLYOFFICE_DEPLOYMENT.md
├── decisions/
│   ├── README.md
│   ├── 001-android-admin-scope.md
│   ├── 002-message-queue-selection.md
│   ├── 003-modular-monolith.md
│   ├── 004-metadata-object-storage.md
│   ├── 005-team-file-permissions.md
│   ├── 006-client-boundaries.md
│   ├── 007-onlyoffice-async-save.md
│   └── 008-semantic-localization.md
└── archive/
    ├── README.md
    └── TEAM_RELEASE_NOTES.md
```

`reference/TESTING.md` 是标记为 deprecated 的旧链接兼容指针，不是第二份测试策略；权威正文位于 `development/TESTING.md`。除明确的迁移指针和历史归档外，推荐结构中的文件均为 current。

计划新增文件只应先写入分类 README 的计划清单；正文具备实际内容并通过核查后，才加入推荐结构和统一入口，不创建只有标题的空文件。

## 单一事实来源

| 事实 | 文档权威位置 | 实现权威来源 |
|------|--------------|--------------|
| 项目定位、核心能力 | 根目录 `README.md` | 当前产品实现和验收测试 |
| 组件关系和模块边界 | `architecture` | Maven 模块、客户端入口、Compose 拓扑 |
| HTTP 路径、参数和响应 | `reference/API.md` 通用约定、`reference/api/*` 业务域正文 | Controller、SecurityConfig、DTO |
| 环境变量和运行配置 | `reference/CONFIGURATION.md` | 环境模板、Compose、Spring 配置 |
| 本地开发和调试命令 | `getting-started`、`development` | 构建文件和验证脚本 |
| 测试层次和最低验证 | `development/TESTING.md` | 构建配置、测试代码、验证脚本 |
| 生产操作步骤 | `operations` | Compose overlay、观测配置和运维脚本 |
| 技术选择及原因 | `decisions` | 已接受的 ADR 和对应实现 |
| 后续计划 | `ROADMAP.md` | 当前维护计划 |
| 历史状态 | `archive` | Git 历史和带日期的验证记录 |

其他文档需要使用某项事实时，应链接到权威位置并只保留必要摘要。例如 Quickstart 可以列出必填密钥，但不复制完整环境变量表；根 README 可以展示技术栈摘要，但具体兼容性和部署参数应链接到参考文档。

## 文档状态

文档按以下状态理解：

- `current`：当前事实，可作为操作或实现依据。
- `planned`：已确定需要编写，但内容尚不存在；只能出现在分类 README 的计划清单中。
- `deprecated`：仍为兼容或迁移保留，必须指向替代文档和删除条件。
- `archived`：历史快照，必须标明日期，不得作为当前依据。

除 `archive` 外，正式文档默认是 `current`。不要创建没有内容的 `planned` 文件，也不要让旧文档在新文档完成后继续保留未标记的重复说明。

## 写作规则

1. 文档正文默认使用简体中文；代码标识、命令、协议字段和没有稳定中文译名的专有名词保留原文。只有明确面向英文读者时才编写英文文档。
2. 文档开头先说明用途、读者和适用范围，避免直接进入参数堆砌。
3. 操作步骤写明前置条件、预期结果和失败时的检查入口。
4. 架构说明优先解释职责、数据流、事实来源、事务和失败边界。
5. Reference 使用稳定名称和精确类型；示例不能代替字段定义。
6. 当前事实使用现在时；带数量、版本或验证结果的快照必须标注日期。
7. 内部链接使用相对路径；同一概念使用固定术语，不混用“任务队列”“消息流”等历史称呼。
8. 文档中的密钥只使用明显的示例值，禁止提交真实凭据。
9. 删除或重命名文档时同步修正所有入口和反向链接。

## 命名规则

- 分类入口统一命名为 `README.md`。
- 稳定主题文档使用大写 snake case，例如 `DATA_MODEL.md`、`BACKUP_RESTORE.md`。
- 业务链路目录使用小写 `flows`，内部文件仍使用大写主题名。
- ADR 采用 `NNN-short-title.md`；编号只表示接受顺序，不表示优先级。
- 历史快照的标题或正文必须包含明确日期，必要时文件名也包含日期。

## 变更同步规则

| 实现变更 | 必须检查的文档 |
|----------|----------------|
| 新增或修改 Controller、Security matcher、DTO | API 参考、公开端点和客户端协议说明 |
| 新增或修改环境变量、Compose 服务、端口、卷 | 配置参考、Quickstart、相关运维文档 |
| 修改 Maven 模块依赖或关键包职责 | 后端架构和后端开发指南 |
| 修改数据表、实体关系或生命周期 | 数据模型、备份恢复、相关 API |
| 修改团队角色或文件权限计算 | 权限架构、API、客户端开发指南和测试策略 |
| 修改 Outbox、RabbitMQ、后台任务或 DLQ | 异步链路、队列 ADR、观测和运维 Runbook |
| 修改 Web/Tauri/Android 共用协议 | 客户端架构、本地化协议、各端开发指南 |
| 增删验证阶段或测试验收条件 | 测试策略和受影响的专项开发指南；若改变首次启动验收，再同步 Quickstart |

## 迁移规则

1. 先对照实现核实旧文档，不直接复制未经确认的内容。
2. 在目标目录完成新文档，并补齐来自其他入口的链接。
3. 对比新旧内容，确认没有丢失仍然有效的事实。
4. 同一变更中删除旧正文或把它改成明确的迁移指针。
5. 运行链接、API 路径和环境变量覆盖检查。
6. 最后更新 `docs/README.md`；不得提前把未完成文档标为推荐入口。

## 完成定义

一份文档只有同时满足以下条件才算完成：

- 范围和读者明确。
- 关键陈述能追溯到实现、配置、测试或已接受决策。
- 与其他文档没有互相冲突的权威事实。
- 示例命令和路径存在，危险操作带有边界与恢复提示。
- 所有相对链接有效。
- 已被 `docs/README.md` 或所属分类 README 正确收录。
