# 后端服务概要设计

## 职责

后端是 BetterCloudDrive 的业务核心，负责认证授权、文件元数据、上传下载、分享、WebDAV、管理员能力、审计记录、系统日志查询和后台任务。后端同时承担安全边界职责：客户端不能直接决定用户身份、资源归属、配额、分享权限或管理员权限。

## 不负责的内容

- 不负责客户端页面布局和交互细节。
- 不直接管理桌面端本地文件路径。
- 不保存浏览器或 Android 的 UI 状态。
- 不直接提供 Grafana UI，只提供 Grafana 鉴权和链接生成。
- 不把对象存储作为业务数据库使用。

## 模块划分

| Maven 模块 | 主要职责 | 典型内容 |
|------|------|------|
| `betterclouddrive-common` | 通用基础结构 | `ApiResponse`、`PageResult`、`ApiCode`、异常、`RequestTraceContext` |
| `betterclouddrive-dal` | 持久化模型 | JPA 实体、Repository、查询方法 |
| `betterclouddrive-storage` | 对象存储抽象 | `StorageService`、S3 配置、S3 实现 |
| `betterclouddrive-service` | 业务服务 | 认证、文件、上传、分享、收藏、标签、版本、管理员、加密 |
| `betterclouddrive-web` | HTTP 层 | Controller、DTO、Security、WebDAV、下载 ticket、日志查询 |
| `betterclouddrive-scheduler` | 后台任务 | 清理、同步、分区维护 |
| `betterclouddrive-start` | 应用入口 | Spring Boot 启动、配置、Flyway、logback |

## 外部依赖

| 依赖 | 用途 | 失败影响 |
|------|------|------|
| PostgreSQL | 业务元数据和审计表 | 大多数 API 不可用 |
| Redis | token 黑名单、验证码、分片状态、配额同步、WebDAV 锁 | 上传续传、认证辅助、锁和限流受影响 |
| S3 兼容存储 | 文件内容、版本对象、ZIP 缓存 | 上传完成、下载、预览不可用 |
| SMTP | 注册验证码、密码重置、分享通知 | 邮件功能不可用 |
| Loki | 系统日志查询 | 管理后台系统日志降级 |
| Grafana | 日志和 trace 可视化 | Grafana 跳转不可用 |

## 入口层设计

| 入口 | 认证方式 | 用途 |
|------|------|------|
| `/api/v1/auth/**` | 部分公开，部分 JWT | 登录注册、刷新、用户资料、WebDAV 设置 |
| `/api/v1/files/**` | JWT | 文件浏览、创建、重命名、移动、复制、删除、搜索 |
| `/api/v1/upload/**` | JWT | 分片上传、状态查询、完成、取消、秒传 |
| `/api/v1/download/**` | JWT 或 ticket | 登录态下载和网页临时下载 |
| `/api/v1/shares/**` | JWT 或公开访问 | 分享管理和公开访问 |
| `/api/v1/admin/**` | JWT + `ROLE_ADMIN` | 用户、文件、日志、统计、Grafana session |
| `/webdav/**` | Basic Auth | WebDAV 协议访问 |
| `/api/v1/grafana/auth` | Grafana 短期 cookie | Nginx `auth_request` 子请求 |

## 请求处理链路

1. 请求进入 servlet filter。
2. `TraceAuditFilter` 建立 `requestId`，读取或生成 trace 关联信息。
3. JWT、Basic Auth 或公开端点规则完成认证。
4. `RateLimitFilter` 对高风险接口限流。
5. Controller 将请求转换为 DTO 并校验参数。
6. Service 执行业务规则和事务。
7. Repository、Redis、StorageService 完成数据访问。
8. `GlobalExceptionHandler` 将异常统一转换为 `ApiResponse`。
9. 响应头写入 `X-Request-ID`、`X-Trace-ID`。
10. 审计日志、运行日志和操作日志记录请求结果。

## 核心业务域

### 认证与用户

- 用户密码存储为 BCrypt 哈希。
- JWT 分 access token 和 refresh token。
- refresh token 刷新后旧 token 失效。
- 登出时当前 token 进入吊销记录或黑名单。
- 用户状态影响登录和后续访问。
- WebDAV 默认关闭，开启时必须设置独立 WebDAV 密码。

### 文件系统

- 文件和文件夹共用 `files` 表。
- `parent_id = null` 表示根目录。
- 文件夹没有对象存储内容，大小通常由子文件汇总或显示为 0。
- 删除是软删除，进入回收站。
- 恢复时需要处理目标目录同名冲突。
- 彻底删除需要删除文件元数据、版本对象和存储对象。

### 上传

上传会话由数据库和 Redis 共同管理：

1. `init` 创建会话并检查配额。
2. 客户端上传分片。
3. Redis 记录已上传分片状态。
4. `status` 返回缺失分片。
5. `complete` 校验分片完整性并创建文件。
6. `cancel` 清理已上传临时内容。

关键设计点：

- 同一分片重复上传应幂等。
- 完成上传前不能创建最终文件记录。
- 文件名冲突、配额不足、分片缺失必须以业务错误返回。
- 客户端断点续传依赖 `sessionId`、`totalChunks`、`chunkSize` 和缺失分片列表。

### 下载

下载分三类：

- 普通文件下载：支持 JWT 或 ticket，支持 Range。
- 网页文件夹下载：服务端生成 ZIP 缓存，并支持 Range。
- 分享下载：公开接口按分享权限校验，部分接口不提供完整 Range 语义。

Range 处理要求：

- 合法 Range 返回 `206 Partial Content`。
- 无 Range 返回完整文件。
- 非法或不可满足 Range 返回 `416`。
- 多段 Range 不作为当前目标。

### 分享

- 分享码是公开入口的主键。
- 分享密码使用加密密文保存，不再使用哈希字段语义。
- 每个密码密文应携带初始化向量。
- 分享访问需要校验取消状态、过期时间、访问次数和密码。
- 只有分享所有者能在登录态读取分享密码原文。

### 管理员

管理员接口负责：

- 用户列表、禁用启用、配额调整。
- 浏览用户文件和删除任意文件。
- 查询审计日志。
- 查询系统日志摘要。
- 创建 Grafana 短期会话。

管理员接口必须始终校验 `ROLE_ADMIN`，不能只依赖前端隐藏菜单。

### WebDAV

WebDAV 入口独立于 `/api/v1`，使用 HTTP Basic Auth。认证成功后将 WebDAV 方法映射到文件服务：

- `PROPFIND`：目录列举或属性读取。
- `GET` / `HEAD`：文件下载或元信息。
- `PUT`：上传或覆盖。
- `MKCOL`：创建文件夹。
- `DELETE`：移入回收站。
- `MOVE` / `COPY`：移动、重命名、复制。
- `LOCK` / `UNLOCK`：基于 Redis 的锁。

WebDAV 上传为流式 PUT，不等同于分片上传接口，因此断点续传语义较弱。

## 状态与错误

| 状态对象 | 主要状态 |
|------|------|
| 上传会话 | uploading、completed、expired |
| 文件 | normal、deleted |
| 分享 | active、expired、canceled、visit-limit-reached |
| 用户 | active、disabled、frozen |
| token | active、revoked、expired |

错误响应统一使用 `ApiResponse`，业务错误码由 `ApiCode` 维护。新增错误码时应同时更新 API 文档和客户端处理逻辑。

## 事务与一致性

- 元数据变更优先放在数据库事务中。
- 对象存储操作与数据库事务无法天然原子，需要补偿或清理任务。
- 上传完成时应先确保对象可用，再提交文件记录。
- 删除文件时需要考虑数据库标记和对象物理删除之间的失败窗口。
- 配额更新如果通过 Redis 增量同步，必须有定时回写和异常修复策略。

## 安全设计

- 所有非公开 API 默认需要 JWT。
- WebDAV 需要独立密码，不能默认开启。
- 管理接口需要服务端角色校验。
- 分享密码、JWT、Cookie、Authorization 不进入明文日志。
- Grafana 使用短期 HttpOnly cookie 和 Nginx `auth_request`。
- CORS 和反向代理配置必须与部署域名一致。

## 可观测性

后端为每个请求维护：

- `requestId`：用于 API 响应、日志检索和用户反馈。
- `traceId`：用于分布式追踪。
- `userId`：认证后写入日志上下文。
- HTTP 方法、路径、状态码、耗时。

写操作进入 `operation_logs`，运行日志进入 `runtime.log`，审计详情进入 `audit.log`。

## 测试关注点

- Security：公开端点、登录态端点、管理员端点、WebDAV Basic Auth。
- 文件：同名冲突、移动到子目录、软删除、恢复、彻底删除。
- 上传：秒传、分片缺失、重复分片、取消、过期、配额不足。
- 下载：Range、ticket、ZIP 缓存、无权限访问。
- 分享：密码、过期、访问次数、取消、所有者读取密码。
- 日志：requestId、traceId、脱敏、Grafana auth。
- 定时任务：过期清理、分区维护、配额同步。

## 扩展建议

- 新业务先定义 Service 层契约，再补 Controller。
- 新客户端能力优先复用已有 API，除非后端缺少必要原子能力。
- 新存储平台通过 `StorageService` 适配。
- 新日志平台应保持管理后台 API 返回结构稳定。
