# 可观测性概要设计

## 职责

可观测性子系统负责记录系统运行状态、审计用户行为、关联请求链路，并为系统管理员提供查询和跳转入口。它不替代业务数据库，也不直接决定访问权限；权限仍由后端和反向代理控制。

## 组件视图

| 组件 | 职责 |
|------|------|
| 后端 MDC / logback | 输出结构化 runtime 和 audit 日志 |
| `TraceAuditFilter` | 建立 requestId、traceId、审计上下文 |
| `operation_logs` | 保存可查询的结构化审计记录 |
| Promtail | 采集后端日志文件 |
| Loki | 存储和查询日志 |
| OpenTelemetry | 生成和传播 trace |
| OTel Collector | 接收后端 OTLP 并转发 |
| Tempo | 存储 trace |
| Grafana | 日志和 trace 可视化 |
| 管理后台日志中心 | 受控入口，展示审计日志和系统日志摘要 |

## 日志分类

| 类型 | 内容 | 入口 | 用途 |
|------|------|------|------|
| 运行日志 | 应用启动、异常、依赖调用、内部状态 | `runtime.log` | 排障 |
| 审计文件日志 | 用户、动作、路径、状态、脱敏详情 | `audit.log` | 审计副本和外部采集 |
| 审计数据库日志 | `operation_logs` 结构化记录 | PostgreSQL | 管理后台查询 |
| Trace | 请求链路和 span | Tempo | 性能分析和跨组件定位 |

## ID 设计

| ID | 粒度 | 来源 | 用途 |
|------|------|------|------|
| `requestId` | 单次 HTTP 请求 | 后端生成或继承请求头 | 用户反馈、API 响应、审计和运行日志关联 |
| `traceId` | 一条分布式链路 | OpenTelemetry | 跨服务日志和 trace 关联 |
| `spanId` | trace 内一次操作 | OpenTelemetry | 定位局部耗时 |

说明：

- `requestId` 更贴近 HTTP 请求。
- `traceId` 更贴近分布式调用链。
- 没有启用 tracing 或采样未命中时，可能只有 `requestId`。
- 后端响应头尽量返回两者，便于用户报告问题。

## 后端采集流程

1. 请求进入后端。
2. `TraceAuditFilter` 创建或读取 requestId。
3. OpenTelemetry 生成 trace/span。
4. 认证成功后将 userId 写入上下文。
5. Controller 和 Service 正常处理请求。
6. 响应完成时记录状态码、耗时、结果。
7. 写入 runtime 日志、audit 日志和 `operation_logs`。
8. Promtail 采集日志到 Loki。
9. OTel Collector 将 trace 转发到 Tempo。

## 审计设计

审计关注“谁在什么时候对什么资源做了什么，结果如何”。典型字段：

- `userId`
- `actionType`
- `targetType`
- `targetId`
- `path`
- `method`
- `statusCode`
- `errorCode`
- `requestId`
- `traceId`
- `durationMs`
- `ipAddress`
- `userAgent`
- `detail`

脱敏规则：

- `Authorization`、Cookie、token 不落明文。
- 密码、验证码、密钥、分享密码等字段脱敏。
- 请求体长度受配置限制。
- 对二进制上传不记录正文。

## 系统日志查询

管理后台系统日志不直接连接 Loki，而是通过后端代理。原因：

- 统一管理员权限校验。
- 避免把 Loki 查询能力暴露给浏览器。
- 后端可限制查询范围、条数和返回字段。
- 后端可生成 Grafana Explore 链接。

返回给前端的是概要：

- 日志 ID。
- 时间。
- 等级。
- message 摘要。
- requestId。
- traceId。
- Grafana 链接。

## Grafana 鉴权

Grafana 通过同域反向代理和后端管理员身份复用登录体系。

流程：

1. 管理员在日志中心点击 Grafana。
2. 前端调用 `POST /api/v1/admin/grafana/session`。
3. 后端确认当前用户仍是 `ROLE_ADMIN`。
4. 后端写入短期 HttpOnly cookie。
5. 浏览器访问 `/grafana/`。
6. Nginx 对该请求执行 `auth_request /grafana-auth`。
7. `/grafana-auth` 转发到后端 `/api/v1/grafana/auth`。
8. 后端验证 cookie 和管理员身份。
9. Nginx 将 `X-WEBAUTH-*` 头传给 Grafana Auth Proxy。

设计约束：

- Grafana 不暴露公网直连端口。
- cookie 有短 TTL。
- 普通用户即使知道 `/grafana/` 也不能进入。

## 失败和降级

| 失败场景 | 影响 | 降级 |
|------|------|------|
| Loki 不可用 | 系统日志查询失败 | 审计日志仍可查 |
| Tempo 不可用 | Trace 不可查 | requestId 仍可查日志 |
| Promtail 不可用 | 新日志不进入 Loki | 后端本地日志仍存在 |
| Grafana 不可用 | 图形化跳转失败 | 管理后台摘要仍可显示 |
| OTel Collector 不可用 | trace 上报失败 | 业务请求不应失败 |

## 保留与清理

- `operation_logs` 使用数据库分区和定时任务清理。
- Loki、Tempo 按各自配置保留。
- 后端本地日志应通过 logback 滚动策略控制。
- 生产环境需根据合规要求设置审计保留期。

## 测试关注点

- 响应头包含 requestId。
- 未登录访问 Grafana auth 返回 401。
- 普通用户不能创建 Grafana session。
- 管理员可创建 Grafana session。
- 审计日志字段脱敏。
- Loki 查询失败时 API 返回可理解错误。
- Grafana 链接包含正确 datasource、traceId 或 requestId。

## 扩展建议

- 新服务接入时统一传播 trace context。
- 新审计字段需同时考虑数据库查询和 JSON 日志。
- 日志平台替换时保持管理后台 API 契约不变。
- 如果未来引入告警，应由日志/指标平台触发，不放在业务请求路径中。
