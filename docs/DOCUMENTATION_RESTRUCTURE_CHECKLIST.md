> 历史文档整理记录：以下版本和客户端名称描述当时状态；当前 Qt 桌面入口见 [开发说明](../desktop/README.md)。

# 文档重构核查记录

本文档从目录重构前的事实基线开始，持续记录各阶段范围、修正和完成标准。第一阶段只校正文档事实；后续阶段的完成记录保留在同一文件中，便于追溯重构依据。

核查日期：2026-08-19。

## 事实来源

| 范围 | 权威来源 |
|------|----------|
| HTTP API 和权限 | `betterclouddrive-web` Controller、`SecurityConfig`、请求/响应 DTO |
| 后端配置 | `application.yml`、`application-prod.yml` |
| Compose 服务、镜像、端口和卷 | `docker-compose*.yml` |
| 环境变量 | `.env*.example`、`config/env-files/*.env.example` 及变量实际消费者 |
| 数据库结构 | `V1__schema.sql`、JPA Entity 和 Repository |
| 客户端行为 | Web/Tauri/Android 实现及对应自动化测试 |
| 测试命令 | `scripts/verify-all.ps1`、`scripts/verify-all.sh` 和各模块构建文件 |

若文档与上述来源冲突，以实现和可执行配置为准；修正文档前仍需确认实现不是未完成或废弃代码。

## 现有文档分类

| 文档 | 当前状态 | 第一阶段结论 |
|------|----------|--------------|
| 根目录 `README.md` | 不完整、存在重复 | 功能、版本和启动入口可保留；架构、配置和测试细节后续迁出（第二至第五阶段已完成） |
| `docs/README.md` | 准确但导航层次不足 | 作为后续统一文档入口（第二阶段已完成） |
| `reference/API.md` | 不完整、部分过期、重复 | 本阶段修复已确认差异，后续按业务域拆分（第六阶段已完成） |
| `reference/CONFIGURATION.md` | 基本准确、部分遗漏 | 本阶段补齐确实生效的模板变量，并标记孤立变量 |
| `reference/LOCALIZATION.md` | 基本准确 | 后续保留在 reference |
| `reference/TESTING.md` | 基本准确 | 后续移动到 development，不在本阶段移动（第五阶段已完成，旧路径保留 deprecated 指针） |
| `operations/*.md` | 基本准确 | OnlyOffice 镜像版本有一处过期说明；其余后续按运维场景复核（第七阶段已完成） |
| `decisions/*.md` | 准确但数量不足 | 后续统一 ADR 格式并补关键决策（第八阶段已完成） |
| `archive/TEAM_RELEASE_NOTES.md` | 历史资料 | 已明确标记为非当前事实，继续隔离保存 |
| `ROADMAP.md` | 当前计划 | 保留单文件入口，只有增长为多个文件时才新建目录 |
| `frontend/README.md` | 不完整 | 保留最短启动和 Tauri E2E 兼容说明，结构与调试正文已并入客户端开发指南（第五阶段已完成） |

## 第一阶段已确认差异

### API

Controller 中识别到 175 个 HTTP 映射。初次核查时，API 文档明确提及其中 164 个路径，以下路径缺失或协议已经变化：

- 账号语言偏好：`PUT /api/v1/auth/preferences/locale`。
- 个人历史版本下载和恢复。
- 通知 SSE 已从 access token 查询参数改为一次性 `stream-ticket`。
- Grafana Auth Proxy 校验端点和管理员 Grafana 会话端点。
- 管理员系统日志和四个死信管理端点。
- 公开端点清单未覆盖团队邀请读取、内容下载/回调、Grafana Auth Proxy 和通知 SSE。

以上差异已在当时的 `API.md` 中修正；第六阶段已经按业务域完成拆分。

### 配置

环境模板中识别到 136 个变量。初次核查时，19 个变量未出现在配置参考中，其中：

- 15 个变量存在实际消费者，应补入配置文档，包括诊断端口、应用公开地址、邮件发件人、观测数据保留期、告警配置路径和生产管理端口等。
- `DATA_SOURCE_URI`、`DATA_SOURCE_USER`、`DATA_SOURCE_PASS`、`REDIS_ADDR` 仅存在于 `database.env.example`，当前 Compose、Spring 配置和脚本均不读取。它们不是当前有效配置，暂不写入有效变量表；后续配置清理时应删除或为其补充明确消费者。

### 已过期说明

- Compose 当前固定使用 `onlyoffice/documentserver:9.3.0.1`，不是 `latest`。
- 通知 SSE 当前要求先用 Bearer Token 创建一次性 ticket，再通过 `EventSource` 消费 ticket。

## 第一阶段遗留项

- 备份文档中的 RPO、RTO、密钥备份、异地保留和自动化边界。（第七阶段已完成）
- 生产升级、回滚和密钥轮换流程。（第七阶段已完成）

## 第一阶段完成标准

结果：已完成。

- 已确认的 API、公开端点、SSE 和 OnlyOffice 版本差异得到修正。
- 实际生效的环境模板变量均能在配置参考中查询。
- 孤立变量被明确记录，未被误写成当前有效配置。
- 所有 Markdown 相对链接可解析。
- Controller 路径和环境变量覆盖检查可重复执行，并把结果记录在阶段交付说明中。

完成时复核结果：175 个 Controller HTTP 映射的路径均能在 API 文档中检索到；136 个环境模板变量的名称均能在配置参考中检索到，其中 4 个孤立变量明确标记为非有效配置；Markdown 相对链接未发现断链。

## 第三阶段架构核查

核查日期：2026-08-20。

本阶段以父 POM、各模块 POM、Compose、Spring 配置、Flyway schema、JPA Entity/Repository、Service 实现、Web/Tauri/Android 客户端入口和自动化测试为事实来源，完成以下架构正文：

- 系统总览：确认默认部署的应用、基础设施与观测组件，以及 PostgreSQL、SeaweedFS、Redis 和 RabbitMQ 的数据职责。
- 后端模块：确认七个 Maven 模块的直接依赖方向；最终运行形态是一个 Spring Boot 模块化单体。
- 数据模型：确认个人/团队文件共表、邻接表文件树、软删除、版本、上传会话、团队任务和 OnlyOffice 回调关系。
- 客户端：确认 Web 与 Tauri 复用 Vue 应用，Tauri 只扩展原生传输/文件系统能力；Android 通过 Retrofit/Repository/ViewModel/Compose 使用同一 REST 协议。
- 权限：确认基础角色为 OWNER、ADMIN、EDITOR、VIEWER；覆盖按“目标到祖先、同节点 USER 优先 ROLE、首个匹配即停止”解析。
- 关键链路：补充分片上传与下载、Outbox/任务/死信、OnlyOffice 异步保存的状态与失败边界。

## 第三阶段完成标准

结果：已完成。

- `architecture` 不再只有计划入口，核心正文均有实现依据并进入统一导航。
- 模块依赖、数据归属、权限优先级和跨存储一致性边界有唯一说明位置。
- 架构文档不复制完整 API 字段、环境变量或运维命令，而是链接对应权威资料。
- 核心架构正文和相对链接通过自动检查。

## 第四阶段入门文档核查

核查日期：2026-08-20。

本阶段以 Compose、环境模板、服务健康检查和各客户端开发地址为事实来源，完成以下内容：

- 入门：补齐 Docker Compose Quickstart、按模块开发环境准备和本地常见排障。
- 去重：根 README 只保留最短启动入口，完整首次运行流程由 `getting-started` 统一维护。
- 配置纠错：Quickstart 纳入验证脚本实际要求的 `rabbitmq.env`，并明确宿主机、容器、Android 模拟器和真机地址差异。

## 第四阶段完成标准

结果：已完成。

- 首次使用者能够从配置复制到健康检查、注册和停止服务形成闭环。
- 本地开发者能够按修改端选择基础设施与客户端组合，并区分不同运行环境的地址。
- Quickstart 和排障中的命令均能追溯到 Compose、环境模板或健康检查。
- 所有相对链接和路径通过检查。

## 第五阶段开发者文档核查

核查日期：2026-08-20。

本阶段以父 POM、模块目录、Controller/Service、前端 package scripts、Tauri toolchain/command、Android Gradle/Repository/ViewModel 和两套完整验证脚本为事实来源，完成以下内容：

- 工作流：补齐事实来源、作用域与失败边界、跨模块实现顺序、文档同步和完成定义。
- 专项开发：补齐后端、Web、Tauri 和 Android 的目录、调用边界、调试入口与最低验证。
- 测试：将增强后的测试策略迁入 `development/TESTING.md`，旧 reference 路径只保留兼容指针。
- 去重：根 README 和配置参考只保留验证入口，模块选择、清理和报告由测试策略统一维护。
- 工具修正：PowerShell `all` 与 Linux `all` 对齐，均单独执行 Playwright `office` project，确保完整验证确实包含 OnlyOffice E2E。

## 第五阶段完成标准

结果：已完成。

- 开发者能从修改类型定位事实来源、实现层和对应专项指南。
- 各端指南中的目录、命令和工具版本均能在仓库实现中对应。
- 修改范围能够映射到最低验证，跨端协议和异步边界有额外检查要求。
- 测试策略只有一个权威正文；Android 测试继续明确不使用代码行指标。
- 开发文档链接、构建路径、脚本参数和 PowerShell/Shell 语法通过检查。

## 第六阶段参考文档核查

核查日期：2026-08-20。

本阶段以第一阶段已经对照 Controller、SecurityConfig 和 DTO 修正的 `reference/API.md` 为迁移基线，完成以下内容：

- 总入口：保留基础地址、统一响应、错误码、认证、分页、文件树、回收站、配额、缩略图、操作日志和管理员通用约定。
- 业务域：将具体端点拆分为认证、文件管理、传输、文件组织、分享、管理员、团队、通知和 OnlyOffice 九个主题。
- 去重：同一端点只在一个业务域正文中定义，总入口和目录只提供导航及跨域约定。
- 兼容：原 `reference/API.md` 路径继续作为 API 总入口，因此既有文件级链接无需迁移；内部入口统一指向业务域正文。
- 配置边界：`CONFIGURATION.md` 暂不拆分，继续作为环境变量和运行配置的单一权威文件。

## 第六阶段完成标准

结果：已完成。

- 原 API 正文中的全部业务章节均迁入对应业务域，没有丢失接口说明和示例。
- 通用约定与具体端点的维护位置唯一，业务域目录能够从统一文档入口到达。
- Controller 路径能够在 API 总入口与业务域文档集合中检索到。
- 所有 Markdown 相对链接、代码围栏和文档引用通过检查。

## 第七阶段生产运维核查

核查日期：2026-08-20。

本阶段以生产 Compose overlay、生产环境模板、Caddy 示例、健康检查、Flyway、持久化卷、观测告警、密钥消费者和现有备份手册为事实来源，完成以下内容：

- 生产部署：明确单机单实例边界、固定 Compose project name、生产 overlay、HTTPS 接入、上线阻断项和验收。
- 升级回滚：补齐发布记录、变更分级、维护窗口、Flyway 不可逆边界、应用级与数据级回滚。
- 备份恢复：补齐 RPO/RTO 初始演练基线、保留策略、异地加密副本、自动化责任和隔离恢复限制。
- 密钥轮换：区分 JWT、分享密码、OnlyOffice、Grafana、数据库、队列、对象存储和 SMTP 的消费者与失效影响。
- 故障处置：按应用、数据库、对象存储、RabbitMQ/死信、OnlyOffice、观测、资源和安全事件形成 Runbook。
- 风险披露：明确当前构建仍包含开发种子迁移，且生产 Compose 不提供零停机、自动备份/恢复或密钥双版本能力。

## 第七阶段完成标准

结果：已完成。

- 运维人员能够从首次上线进入升级、回滚、轮换、备份、恢复和故障关闭的完整流程。
- 所有示例命令使用明确的 Compose project name 和生产 overlay，不使用 `down -v` 或无边界清理。
- 数据库、对象存储和密钥被定义为一致恢复集，RPO/RTO 不被误写成未经演练的 SLA。
- 破坏性操作、当前能力缺口和需要外部系统承担的自动化边界有明确提示。
- 运维文档中的服务名、变量、路径、告警和相对链接通过实现核查。

## 第八阶段架构决策核查

核查日期：2026-08-20。

本阶段以现有两份决策、Maven 模块、数据归属、权限算法、三端实现、OnlyOffice 保存链路和本地化协议为事实来源，完成以下内容：

- 格式统一：ADR 使用编号文件名，并包含状态、日期、背景约束、决策、替代方案、后果及实现验证位置。
- 历史保留：Android 管理范围和 RabbitMQ 选型保留首次提交日期；新增 ADR 明确标记为对既有实现的追认记录。
- 关键决策：补齐模块化单体、元数据/对象内容分离、团队文件权限、客户端复用边界、OnlyOffice 异步保存和语义本地化协议。
- 职责分离：ADR 解释选择原因，架构文档描述当前实现，Reference 保存精确协议，运维手册保存操作步骤。
- 反向链接：相关架构、流程、Reference 和历史归档均链接到新的编号 ADR。

## 第八阶段完成标准

结果：已完成。

- 当前跨模块、跨存储和跨客户端的关键稳定选择均有可追溯决策记录。
- 旧的未编号 ADR 文件和内部链接已经迁移，不保留两份权威正文。
- 每份 ADR 至少列出一个被拒绝的替代方案，并同时说明正面和负面后果。
- ADR 引用的实现、架构、协议和运维路径存在，所有 Markdown 相对链接通过检查。

## 最终一致性与完整性核查

核查日期：2026-08-20。

- 导航闭包：仓库 61 份 Markdown 中有 59 份位于 `docs`；59 份均可从 `docs/README.md` 到达，本地链接和锚点无失效项。
- API 闭包：16 个 Controller 的 175 个方法/路径组合均有精确文档，文档没有多出的旧端点；SecurityConfig 的 20 个公开 matcher 路径均在认证约定中说明。
- 配置闭包：10 份环境模板中的 136 个变量，以及 Compose/Spring 配置直接读取的 122 个变量，均可在 `reference/CONFIGURATION.md` 查到。16 个“有默认消费者但未显式列入模板”的变量已补充默认值和覆盖位置。
- 版本闭包：后端、Web、Tauri、Android 和 Compose 镜像版本均与构建文件一致；Tauri core 与 CLI 已统一锁定为 2.11.4。
- 示例格式：291 个代码围栏闭合；其中 57 个 PowerShell 和 47 个 Bash/Shell 代码块通过语法解析。`verify-all.ps1`、`verify-all.sh` 和三组 Compose 模板组合通过静态解析或渲染。
- Android 测试：Gradle 保留 `connectedDebugAndroidTest` 行为测试任务，不再生成 JaCoCo 或 instrumentation 覆盖率任务；构建配置、验证脚本和当前文档中没有 Android 覆盖率门禁残留。
- 变更卫生：`git diff --check` 通过，推荐结构中没有空白正文、孤立文档或两份同时声明权威的内容。

本轮有意保留三类非 current 内容：`reference/TESTING.md` 是带删除条件的 deprecated 兼容指针；`archive` 是带日期的历史快照；`ROADMAP.md` 只记录未完成事项，其中开发种子迁移仍是生产公网开放前的 P0 阻断项。它们均已从当前事实正文中隔离并明确标记。

本次最终核查属于文档、配置和构建任务的静态一致性验证，没有重新执行完整后端、Web、Tauri 或 Android 测试套件；运行时质量结论仍以按修改范围执行[测试策略](development/TESTING.md)后的结果为准。
