# 部署与运行概要设计

## 目标

部署与运行设计说明系统如何在开发、测试、单机和生产环境中启动、配置、升级和排障。详细变量说明见 [CONFIGURATION.md](../CONFIGURATION.md)。

## 部署形态

### Docker Compose

适合：

- 本地开发。
- 联调测试。
- 小规模单机部署。
- 快速重建环境。

特点：

- 所有组件在同一 Compose 项目内。
- 通过 `config/env-files/*.env` 按模块注入运行时变量。
- 前端 Nginx 代理 `/api/`、`/webdav/`、`/grafana/`。
- Grafana 不直接暴露给普通用户。

### 生产拆分部署

适合：

- 长期运行。
- 多实例后端。
- 独立数据库和对象存储。
- 独立日志平台。

推荐拆分：

| 层 | 组件 |
|------|------|
| 接入层 | Nginx、Ingress、负载均衡、TLS |
| 应用层 | 后端实例、前端静态服务 |
| 数据层 | PostgreSQL、Redis |
| 存储层 | S3 兼容对象存储 |
| 可观测层 | Loki、Tempo、Grafana、采集器 |
| 邮件层 | SMTP 服务 |

## Docker Compose 服务

| 服务 | 职责 |
|------|------|
| `postgres` | 主数据库 |
| `redis` | 缓存和短期状态 |
| `seaweedfs` | S3 兼容对象存储和开发 WebDAV |
| `mailpit` | 开发邮件 |
| `backend` | Spring Boot API |
| `frontend` | Nginx + Web 静态资源 |
| `loki` | 日志存储 |
| `promtail` | 日志采集 |
| `tempo` | trace 存储 |
| `otel-collector` | OpenTelemetry 转发 |
| `grafana` | 日志和 trace UI |

## 配置分层

| 文件 | 用途 |
|------|------|
| `.env` | Compose 变量替换和宿主机端口 |
| `database.env` | PostgreSQL、Redis 和后端数据库连接 |
| `storage.env` | SeaweedFS/S3 |
| `security.env` | JWT 和分享密码加密密钥 |
| `backend.env` | 后端运行变量 |
| `frontend.env` | 前端代理和客户端构建/测试地址 |
| `mail.env` | 邮件 |
| `observability.env` | Loki、Tempo、Grafana、审计 |

设计原则：

- 密钥类变量不写死在代码里。
- 各模块 env 文件只放本模块相关配置。
- 根 `.env` 不应无限膨胀。
- 生产环境可用平台 secret 管理替代 env 文件。

## 反向代理设计

前端容器内 Nginx 负责：

- `/`：静态文件和 SPA fallback。
- `/api/`：代理到后端 API。
- `/webdav/`：代理到后端 WebDAV。
- `/grafana-auth`：内部 auth_request 子请求。
- `/grafana/`：受保护的 Grafana 代理。

生产环境如果外层还有 Nginx/Ingress，需要保留：

- `Host`
- `X-Real-IP`
- `X-Forwarded-For`
- `X-Forwarded-Proto`
- `X-Forwarded-Host`

WebDAV 需要允许非标准 HTTP 方法。

## 数据卷

| Volume | 内容 | 重要性 |
|------|------|------|
| `pgdata` | PostgreSQL 数据 | 必须备份 |
| `redisdata` | Redis 数据 | 建议备份或可重建 |
| `seaweeddata` | 文件对象 | 必须备份 |
| `backendlogs` | 后端日志 | 按合规备份 |
| `lokidata` | Loki 数据 | 按保留策略 |
| `tempodata` | Tempo 数据 | 按保留策略 |
| `grafanadata` | Grafana 数据 | 建议备份 |

## 启动顺序

1. PostgreSQL。
2. Redis。
3. 对象存储。
4. Mail/可观测性组件。
5. 后端。
6. 前端。
7. Promtail 等依赖后端日志目录的采集器。

Compose 中通过 healthcheck 和 depends_on 保证部分顺序，生产部署也应做健康检查和就绪检查。

## 升级策略

后端升级：

1. 备份数据库。
2. 确认 Flyway 迁移可向前执行。
3. 部署新后端。
4. 验证健康检查和关键 API。
5. 验证上传、下载、分享、登录。

前端升级：

1. 构建静态资源。
2. 替换前端容器或静态目录。
3. 验证路由、API 地址和缓存策略。

数据库迁移：

- 只追加 Flyway 脚本。
- 避免直接修改已发布迁移。
- 破坏性变更需先兼容再清理。

## 安全运行要求

- 生产必须启用 HTTPS。
- 数据库、Redis、对象存储管理端口不暴露公网。
- 所有默认密码、示例密钥必须替换。
- 分享密码加密密钥必须长期稳定并备份。
- Grafana 应通过同域管理员鉴权进入。
- 审计日志访问仅限管理员。

## 验证清单

- `docker compose config --quiet` 通过。
- PostgreSQL healthcheck 通过。
- Redis `PING` 返回 `PONG`。
- 后端 `/actuator/health` 正常。
- 前端首页可访问。
- 未登录访问 `/grafana/` 返回 401。
- 管理员可从日志中心跳转 Grafana。
- 上传和下载各成功一次。
- 审计日志和 runtime 日志可查。

## 排障入口

| 问题 | 首查 |
|------|------|
| 前端空白 | 浏览器控制台、前端容器日志、静态资源路径 |
| API 失败 | 后端日志、requestId、数据库连接 |
| 上传失败 | 后端日志、S3、Redis、配额 |
| 下载失败 | Range、对象存储、ticket |
| Grafana 401 | 管理员身份、auth cookie、Nginx auth_request |
| 系统日志为空 | Promtail、Loki、backendlogs volume |

## 扩展建议

- 多实例后端前先确认 Redis 和对象存储共享。
- 生产推荐把 PostgreSQL、Redis、对象存储迁出 Compose。
- 配置中心或 secret 平台可替代 env 文件，但变量名应保持兼容。
- 反向代理变更后必须回归 WebDAV 和 Grafana。
