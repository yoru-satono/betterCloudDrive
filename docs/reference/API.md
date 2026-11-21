# Better Cloud Drive — API 文档

## 基础信息

- **Base URL**: `http://localhost:8080/api/v1`
- **Content-Type**: `application/json`（除文件上传外）
- **认证方式**: `Authorization: Bearer <accessToken>`
- **统一响应格式**:

```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": { ... },
  "timestamp": 1717000000000,
  "requestId": "a1b2c3d4"
}
```

### 错误码速查

| Code | 说明 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证/用户名或密码错误 |
| 401001 | Token 已过期 |
| 401002 | Token 已被吊销 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 404001 | 文件不存在 |
| 409 | 资源冲突 |
| 409001 | 同目录下文件名已存在 |
| 419001 | 存储配额已满 |
| 419002 | 无效的分享码 |
| 419003 | 分享需要密码 |
| 419004 | 分享链接已过期 |
| 419005 | 分享访问次数已达上限 |
| 419006 | 分片上传状态无效 |
| 419007 | 分片 MD5 不匹配 |
| 419008 | 文件超出最大限制 |
| 419009 | 版本数量已达上限 |
| 419010 | 已停用，保留编号，不复用；统一初始化以 MULTIPART 表示秒传未命中 |
| 419011 | 邮箱验证失败 |
| 419012 | 验证码已过期 |
| 419013 | 验证码不匹配 |
| 419014 | 密码重置失败 |
| 419015 | 文件夹打包下载超出限制 |
| 419016 | 整文件 MD5 不匹配 |
| 419017 | 上传字节数与声明长度不符 |
| 429 | 请求过于频繁 |
| 500 | 服务器内部错误 |
| 500001 | 存储服务错误 |
| 500002 | 上传失败 |
| 500003 | 下载失败 |

---


## 业务域索引

| 主题 | 内容 |
|------|------|
| [认证与账号](api/AUTH.md) | 注册、登录、令牌、账号偏好、密码重置 |
| [文件管理](api/FILES.md) | 文件夹、元数据、移动复制、回收站、搜索、WebDAV |
| [上传、下载与预览](api/TRANSFERS.md) | 分片上传、秒传、下载 ticket、ZIP、预览、缩略图 |
| [收藏、标签与版本](api/ORGANIZATION.md) | 收藏、标签关联、历史版本 |
| [分享](api/SHARING.md) | 分享创建、公开访问、保存与下载 |
| [管理员](api/ADMIN.md) | 用户、文件、团队、日志、Grafana、死信、统计 |
| [团队协作](api/TEAMS.md) | 成员、邀请、团队文件、权限、任务、活动流 |
| [通知](api/NOTIFICATIONS.md) | 通知状态、已读操作、SSE ticket 与事件流 |
| [OnlyOffice](api/OFFICE.md) | 编辑配置、内容读取、保存回调 |

需要按目录浏览时，从 [API 业务域目录](api/README.md) 进入。各业务域只定义自身端点；认证头、统一响应、错误码、分页和跨域结构以本页为准。

## 七、通用说明

### 7.1 认证

除以下公开端点外，所有接口需要在请求头携带 `Authorization: Bearer <accessToken>`：

**公开端点（无需认证）：**
- `POST /api/v1/auth/register-code/send`、`POST /api/v1/auth/register`、`POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`
- `POST /api/v1/auth/forgot-password`、`POST /api/v1/auth/reset-password`
- `GET /api/v1/team-invites/{token}`
- `GET /api/v1/download/{fileId}`、`GET /api/v1/download/folders/{fileId}/zip`（未携带 Bearer Token 时必须提供有效 `ticket`）
- `GET /api/v1/teams/{teamId}/download/{fileId}`、`GET /api/v1/teams/{teamId}/download/folders/{fileId}/zip`（必须提供有效 `ticket`）
- `GET /api/v1/office/files/{fileId}/content`、`GET /api/v1/teams/{teamId}/office/files/{fileId}/content`（必须提供有效内容 token）
- `POST /api/v1/teams/{teamId}/office/files/{fileId}/callback`（由 OnlyOffice 携带有效 callback token 调用）
- `POST /api/v1/shares/access/{shareCode}`、`GET /api/v1/shares/access/{shareCode}/files`
- `POST /api/v1/shares/access/{shareCode}/download/{fileId}`、`POST /api/v1/shares/access/{shareCode}/download/{fileId}/zip`
- `GET /api/v1/grafana/auth`（仅供 Nginx `auth_request` 使用，必须携带有效的短期 Grafana session cookie）
- `GET /api/v1/notifications/stream`（必须提供有效的一次性 SSE `ticket`）

```
Authorization: Bearer <accessToken>
```

Access Token 过期后，使用 Refresh Token 获取新的 Token 对：

```
POST /api/v1/auth/refresh
{"refreshToken": "<refreshToken>"}
```

### 7.2 分页

支持分页的接口统一使用以下参数和响应格式：

**请求参数:** `page`（页码，从 1 开始）、`size`（每页条数，默认 20，**最大 100**）

**响应字段:** `records`（数据列表）、`total`（总记录数）、`page`（当前页）、`size`（每页条数）、`pages`（总页数）

### 7.3 文件树结构

文件和文件夹存储在同一张表中，使用邻接表模型（`parentId` 指向父文件夹）：

```
根目录 (parentId = null)
├── 文档 (folder, id=10)
│   ├── report.pdf (file, parentId=10)
│   └── 图片 (folder, id=15, parentId=10)
└── photo.jpg (file, parentId=null)
```

### 7.4 回收站

- 删除的文件进入回收站，`isDeleted` 标记为 `true`
- 30 天后定时任务自动物理删除（每天 3:00 AM 执行）
- 支持手动恢复或彻底删除

### 7.5 存储配额

- 每个用户注册时分配默认存储配额 **10GB**（`storageQuota` 字段）
- 上传文件前系统检查 `storageUsed + 文件大小 ≤ storageQuota`，超限返回 **419001**
- 文件删除时自动扣减已用空间
- 个人和团队的用量与文件、版本元数据在 PostgreSQL 同一业务事务中更新，不再通过 Redis 周期同步；已有数据库需先完成[旧增量计账离线切换](../operations/STORAGE_INSPECTIONS.md#从旧-redis-增量计账升级)，切换未完成时相关容量写入返回 HTTP 503。
- 管理员可通过 `PATCH /api/v1/admin/users/{userId}/quota` 修改任意用户的配额

### 7.6 缩略图

- 图片文件上传完成后会创建 `THUMBNAIL` 后台任务，worker 生成 JPEG 缩略图，并把对象存储路径写入 `thumbnailPath`。
- 当前先支持可由后端 ImageIO 解码的图片格式；解码失败会保留 `thumbnailPath = null`，客户端按无缩略图处理，团队缩略图失败任务可在团队任务中心重试。
- 个人文件通过 `GET /api/v1/files/{fileId}/thumbnail` 获取缩略图；团队文件通过 `GET /api/v1/teams/{teamId}/files/{fileId}/thumbnail` 获取。
- Web/Tauri 列表在 `thumbnailPath` 为空时显示默认文件图标，刷新后若 `thumbnailPath` 已生成则显示缩略图。
- 文件从回收站永久删除或过期清理时，会同步清理缩略图对象。

### 7.7 操作日志

- 所有写操作（POST/PUT/DELETE）自动记录到 `operation_logs` 表
- 日志表按月分区存储，每月 1 日自动创建下月分区
- 超过 6 个月的分区每周日自动清理

### 7.8 管理员

管理员端点（`/api/v1/admin/**`）需要 `ROLE_ADMIN` 角色。普通用户访问返回 403。首个管理员通过数据库手动创建：

```sql
UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'admin';
```

---
