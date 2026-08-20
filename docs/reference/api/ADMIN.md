# 管理员 API

本文定义管理员用户、文件、团队、日志、Grafana、死信和系统统计接口。所有端点均受相应管理员权限约束；通用约定见 [API 总入口](../API.md)。

## 十五、管理员 `/api/v1/admin`

> 所有管理员端点需要 `ROLE_ADMIN` 角色，普通用户返回 403。

### 15.1 用户列表

```
GET /api/v1/admin/users?keyword=&status=&page=1&size=20
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| keyword | String | 否 | 搜索用户名/邮箱/昵称 |
| status | int | 否 | 按状态筛选（1=正常, 0=禁用） |
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |

返回分页用户列表（不含已删除用户）。

---

### 15.2 修改用户状态

```
PATCH /api/v1/admin/users/{userId}/status
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Request Body:**
```json
{
  "status": 0      // 1=启用, 0=禁用
}
```

---

### 15.3 修改用户配额

```
PATCH /api/v1/admin/users/{userId}/quota
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Request Body:**
```json
{
  "storageQuota": 21474836480   // 20GB，字节
}
```

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {}
}
```

> 配额修改即时生效。用户上传时系统检查 `storageUsed + 文件大小 ≤ storageQuota`，超限返回 419001。

---

### 15.4 浏览用户文件

```
GET /api/v1/admin/users/{userId}/files?parentId=&page=1&size=20&sortBy=fileName&order=asc
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

查看任意用户的文件列表，参数同 `GET /api/v1/files`。

---

### 15.5 查看任意文件

```
GET /api/v1/admin/files/{fileId}
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

跳过所有权检查，直接按 ID 获取文件元数据。

---

### 15.6 删除任意文件

```
DELETE /api/v1/admin/files/{fileId}
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

跳过所有权检查，软删除（移入回收站）。

---

### 15.7 团队管理

```
GET /api/v1/admin/teams?keyword=&page=1&size=20
GET /api/v1/admin/teams/{teamId}
GET /api/v1/admin/teams/{teamId}/members
GET /api/v1/admin/teams/{teamId}/files?parentId=&deleted=&keyword=&page=1&size=20&sortBy=fileName&order=asc
GET /api/v1/admin/teams/{teamId}/audit-logs?action=&targetType=&actorUserId=&result=&startAt=&endAt=&page=1&size=20
GET /api/v1/admin/teams/{teamId}/audit-logs/export?action=&targetType=&actorUserId=&result=&startAt=&endAt=
GET /api/v1/admin/teams/{teamId}/recovery-details
PATCH /api/v1/admin/teams/{teamId}/quota
POST /api/v1/admin/teams/{teamId}/archive
POST /api/v1/admin/teams/{teamId}/unarchive
POST /api/v1/admin/teams/{teamId}/restore
DELETE /api/v1/admin/teams/{teamId}/purge
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

团队管理端点用于运维排障、配额调整和删除后恢复/清理，不要求管理员加入团队：

| 端点 | 说明 |
|------|------|
| `GET /admin/teams` | 分页搜索团队，`status=active|archived|deleted|all` |
| `GET /admin/teams/{teamId}` | 查看团队元数据 |
| `GET /admin/teams/{teamId}/members` | 查看团队成员和角色 |
| `GET /admin/teams/{teamId}/files` | 查看团队文件或回收站文件，`deleted=true` 表示回收站 |
| `GET /admin/teams/{teamId}/audit-logs` | 查看团队审计日志，支持动作、目标、用户、结果和 ISO DateTime 时间范围过滤 |
| `GET /admin/teams/{teamId}/audit-logs/export` | 导出团队审计日志 CSV，最多 10000 行 |
| `GET /admin/teams/{teamId}/recovery-details` | 查看团队删除恢复决策信息，包括删除原因、删除人、计划清理时间、文件数、成员数和最近审计 |
| `PATCH /admin/teams/{teamId}/quota` | 修改团队配额，单位为字节，并写入团队审计日志 |
| `POST /admin/teams/{teamId}/archive` | 归档团队，归档后普通团队列表不展示 |
| `POST /admin/teams/{teamId}/unarchive` | 取消归档 |
| `POST /admin/teams/{teamId}/restore` | 恢复已删除团队 |
| `DELETE /admin/teams/{teamId}/purge` | 永久清理已删除团队及关联数据 |

**修改团队配额 Request Body:**
```json
{
  "storageQuota": 32212254720
}
```

**恢复决策信息 Response `200`:**
```json
{
  "teamId": 1,
  "teamName": "Design",
  "deletedAt": "2026-07-07T10:00:00",
  "deletedBy": 2,
  "deletedByUsername": "chain",
  "deleteReason": "project closed",
  "purgeAfter": "2026-08-06T10:00:00",
  "archivedAt": null,
  "archivedBy": null,
  "archivedByUsername": null,
  "activeFileCount": 120,
  "deletedFileCount": 8,
  "memberCount": 6,
  "restoreAvailable": true,
  "purgeAvailable": true,
  "recentAuditLogs": []
}
```

**Response `200`:** 其他 `GET/PATCH/POST` 返回团队实体或分页结果；`DELETE /purge` 返回统一成功响应。

生命周期操作幂等语义：

- `POST /archive`、`POST /unarchive`、`POST /restore` 对已经处于目标状态的团队返回当前团队实体，不重复写审计或通知。
- `DELETE /purge` 对已永久清理的团队返回成功；对仍未删除的团队返回 400。
- 团队所有者调用 `DELETE /api/v1/teams/{teamId}` 删除团队时，重复调用返回成功，不重复审计或通知。
- 定时清理和 admin purge 使用同一底层清理服务；重复执行不会因团队记录已不存在而失败。

`startAt`、`endAt` 均使用 ISO DateTime，例如 `2026-06-27T12:59:14`。列表和导出接口使用相同过滤语义；时间格式非法返回 400。

**错误码:** 400（参数格式错误）、403（非管理员）、404（团队不存在）、409（状态不允许，如未删除团队执行 purge）、419001（配额小于已用空间或写入超限）。

---

### 15.8 操作日志

```
GET /api/v1/admin/logs?userId=&actionType=&requestId=&traceId=&statusCode=&result=&startDate=&endDate=&page=1&size=20
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| userId | Long | 否 | 按用户筛选 |
| actionType | String | 否 | 按动作筛选（LOGIN/LOGOUT/UPLOAD/DOWNLOAD/DELETE 等） |
| requestId | String | 否 | 按请求 ID 精确筛选 |
| traceId | String | 否 | 按 Trace ID 精确筛选 |
| statusCode | int | 否 | 按 HTTP 状态码筛选 |
| result | int | 否 | 按操作结果筛选 |
| startDate | ISO DateTime | 否 | 起始时间 |
| endDate | ISO DateTime | 否 | 结束时间 |
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |

---

### 15.9 系统日志

```
GET /api/v1/admin/system-logs?traceId=&requestId=&level=&logType=&keyword=&startTime=&endTime=&limit=
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

后端代理查询 Loki，并返回限量的系统日志概要。`startTime`、`endTime` 使用 ISO Instant；每项包含 `traceId`、`requestId`、时间、级别、logger、消息、请求路径、日志类型和 Grafana Explore 链接。返回结果不暴露任意 Loki 查询能力。

---

### 15.10 Grafana 管理员会话与代理鉴权

```
POST /api/v1/admin/grafana/session
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

创建短期、HttpOnly 的 Grafana session cookie，供同域 `/grafana/` 入口使用。服务端会重新确认用户仍是启用状态的管理员，并尽力同步其 Grafana 语言偏好。

```
GET /api/v1/grafana/auth
Cookie: <Grafana session cookie>
```

该端点仅供 Nginx `auth_request` 子请求使用，不是普通客户端 API。有效 cookie 返回 `204` 并附加受信任的 Grafana Auth Proxy Header；无效、过期或非管理员会话返回 `401`。

---

### 15.11 死信管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/dead-letters?queue=&category=&status=OPEN&page=1&size=20` | 分页查询已持久化死信，`size` 最大 100 |
| GET | `/api/v1/admin/dead-letters/{id}` | 获取单条死信概要 |
| POST | `/api/v1/admin/dead-letters/replay` | 批量重放 1–20 个状态为 `OPEN` 的死信 |
| POST | `/api/v1/admin/dead-letters/{id}/discard` | 填写原因并丢弃单条 `OPEN` 死信 |

批量重放请求：

```json
{
  "ids": ["0195d3b2-9dc3-7f63-9f1e-112233445566"]
}
```

丢弃请求中的 `reason` 长度必须为 3–500 个字符：

```json
{
  "reason": "对应业务事实已失效，无需重放"
}
```

列表和详情只返回受控概要，不返回原始 payload。通知死信重放前会验证对应 Outbox 事实仍然存在且可重放；状态冲突返回 `409`。

---

### 15.12 系统统计

```
GET /api/v1/admin/stats
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "totalUsers": 150,
    "activeUsers": 120,
    "totalStorageUsed": 10737418240
  }
}
```

---
