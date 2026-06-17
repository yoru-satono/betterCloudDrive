# 总体架构概要设计

## 目标

BetterCloudDrive 是一个自托管云盘系统，面向个人、小团队和内网场景，提供 Web、Tauri 桌面端和 Android 端访问能力。系统核心目标是可靠管理文件元数据和对象内容，并在不同客户端上提供一致的认证、文件管理、上传下载、分享、回收站、收藏、标签、版本、WebDAV、审计和系统日志能力。

## 架构风格

系统采用前后端分离 + 共享后端 API 的架构。后端提供 HTTP API、WebDAV 网关、后台任务和日志查询能力；Web 前端负责浏览器交互；Tauri 客户端复用 Web 页面并补充桌面本地文件系统能力；Android 客户端通过同一套 API 实现移动端体验。基础设施通过 Docker Compose 编排，也支持生产环境拆分部署。

## 子系统视图

| 子系统 | 主要职责 | 主要依赖 |
|------|------|------|
| 后端服务 | 认证、文件、上传下载、分享、管理、WebDAV、日志查询 | PostgreSQL、Redis、对象存储、SMTP、Loki、Grafana |
| Web 前端 | 浏览器 UI、文件操作、网页上传下载、管理后台 | 后端 API、浏览器 Web API |
| Tauri 客户端 | 桌面 UI、本地目录读写、传输队列、限速、代理 | Web 前端、Tauri、后端 API、本地文件系统 |
| Android 客户端 | 移动 UI、文件操作、预览、上传下载 | 后端 API、Android 系统能力 |
| 数据与存储 | 元数据、缓存状态、对象内容、日志数据 | PostgreSQL、Redis、SeaweedFS/S3、Loki、Tempo |
| 可观测性 | 运行日志、审计日志、Trace、管理员查询入口 | 后端、Promtail、Loki、Tempo、Grafana |
| 部署配置 | 服务编排、环境变量、反向代理、密钥管理 | Docker Compose、Nginx、env 文件 |
| 测试体系 | 单元、集成、黑盒、Web E2E、桌面 E2E、Android E2E | Maven、Vitest、Playwright、WebDriver、Gradle |

## 逻辑分层

```text
Client Layer
  Web Browser
  Tauri Desktop
  Android App

API Layer
  Spring Security
  REST Controllers
  WebDAV Controller
  Download Ticket / Grafana Auth

Domain Layer
  Auth / File / Upload / Download / Share / Admin / Logs

Persistence Layer
  JPA Repositories
  Redis State
  S3 Storage Adapter

Infrastructure Layer
  PostgreSQL / Redis / SeaweedFS / Mailpit
  Loki / Tempo / Grafana / OTel Collector / Promtail
```

## 核心端到端链路

### 登录与认证

1. 客户端提交用户名和密码。
2. 后端校验用户状态和密码哈希。
3. 后端签发 access token 和 refresh token，并记录 token 元数据。
4. 客户端保存 token，后续请求携带 `Authorization: Bearer`。
5. access token 过期后由 refresh token 换取新 token。
6. 登出或刷新后旧 token 进入吊销/黑名单逻辑。

### 文件浏览

1. 客户端请求当前目录文件列表。
2. 后端按当前用户、父目录、删除状态查询 `files`。
3. 返回分页结果，客户端按网格或列表展示。
4. 搜索、收藏、标签、回收站等页面都基于同一套文件元数据模型扩展。

### 上传

1. 客户端计算文件信息，尝试秒传或初始化上传会话。
2. 后端检查文件名冲突、配额和会话参数。
3. 客户端分片上传，后端记录分片进度。
4. 客户端可暂停，恢复时查询缺失分片。
5. 完成后后端写入对象存储、文件元数据、版本记录和配额变更。

### 下载

1. 网页端单文件下载先创建临时 ticket，再由浏览器下载。
2. 后端下载接口支持 Range，用于暂停后继续下载。
3. 网页端文件夹下载走服务端 ZIP 缓存。
4. Tauri 客户端文件夹下载递归列目录并直接写本地目录。
5. 下载行为进入审计日志。

### 分享

1. 认证用户为文件或文件夹创建分享链接。
2. 可选密码经加密后保存到数据库。
3. 公开访问时按分享码校验状态、密码、过期时间和访问次数。
4. 分享所有者可在登录态下按需读取分享密码原文。
5. 公开下载和保存到网盘都受分享范围限制。

### 管理与观测

1. 管理员访问 `/admin` 页面。
2. 前端通过后端 API 查询用户、文件、审计日志和系统日志概要。
3. 系统日志由后端代理查询 Loki。
4. Grafana 链接通过后端短期会话 + Nginx auth_request 鉴权。
5. 请求响应头携带 `requestId` 和 `traceId` 便于定位问题。

## 边界原则

| 边界 | 原则 |
|------|------|
| 前端与后端 | 前端只做交互和本地状态，业务一致性由后端保证 |
| 元数据与对象 | PostgreSQL 保存元数据，对象存储保存文件内容 |
| Web 与 Tauri | Web 端能力必须可在浏览器独立运行；桌面能力通过 Tauri command 增强 |
| Android 与后端 | Android 不绕过后端访问对象存储，所有业务权限由后端控制 |
| 日志与业务 | 审计日志可用于追责，运行日志用于排障，两者分开处理 |

## 主要非功能设计

- 安全：JWT、WebDAV 独立密码、分享密码加密、管理员鉴权、日志脱敏。
- 可恢复：上传分片续传、下载 Range、桌面传输队列持久化。
- 可部署：Docker Compose 单机运行，生产可拆分数据库、缓存、对象存储和日志平台。
- 可观测：requestId、traceId、结构化日志、Loki、Tempo、Grafana。
- 可测试：后端单测、前端单测、黑盒 API、Web E2E、Tauri E2E、Android E2E。

## 设计约束

- 后端是权限和一致性的唯一可信边界。
- 客户端本地状态只用于恢复和体验优化，不能作为业务真相。
- 多实例部署时必须共享 PostgreSQL、Redis、对象存储和日志平台。
- 生产环境必须固定并备份关键密钥，尤其 JWT 密钥和分享密码加密密钥。
