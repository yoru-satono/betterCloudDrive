# 团队协作 API

本文定义团队成员、邀请、文件、分享、权限、版本、审计、任务和活动流接口。通用约定见 [API 总入口](../API.md)。

## 十六、团队协作 `/api/v1/teams`

> 所有团队端点都需要登录。团队角色为 `OWNER`、`ADMIN`、`EDITOR`、`VIEWER`。文件权限会先校验团队成员身份，再应用文件级权限覆盖。

### 16.1 团队和成员

| 方法 | 路径 | 权限 | 请求 | 响应 |
|------|------|------|------|------|
| GET | `/api/v1/teams?page=1&size=20` | 登录用户 | 无 | 当前用户加入的团队分页列表，`size` 最大 100 |
| POST | `/api/v1/teams` | 登录用户 | `{ "name": "研发团队" }` | `TeamResponse` |
| GET | `/api/v1/teams/{teamId}` | 团队成员 | 无 | `TeamResponse` |
| PUT | `/api/v1/teams/{teamId}` | OWNER/ADMIN | `{ "name": "新名称" }` | `TeamResponse` |
| DELETE | `/api/v1/teams/{teamId}` | OWNER | 无 | 软删除团队 |
| POST | `/api/v1/teams/{teamId}/leave` | 非唯一 OWNER | 无 | 退出团队 |
| POST | `/api/v1/teams/{teamId}/owner` | OWNER | `{ "userId": 12 }` | 转让后的 `TeamResponse` |
| GET | `/api/v1/teams/{teamId}/members?page=1&size=20` | 团队成员 | 无 | `PageResult<TeamMemberResponse>`，`size` 最大 100 |
| POST | `/api/v1/teams/{teamId}/members` | OWNER/ADMIN | `{ "username": "lisi", "role": "VIEWER" }` | `TeamMemberResponse` |
| PUT | `/api/v1/teams/{teamId}/members/{userId}` | OWNER/ADMIN | `{ "role": "EDITOR" }` | `TeamMemberResponse` |
| DELETE | `/api/v1/teams/{teamId}/members/{userId}` | OWNER/ADMIN | 无 | 移除成员 |

**TeamResponse 字段:** `id`、`name`、`ownerId`、`storageQuota`、`storageUsed`、`role`、`createdAt`、`updatedAt`。

**错误码:** 403（非成员或角色不足）、404（团队/成员不存在）、409（重名或状态冲突）。

### 16.2 邀请链接

| 方法 | 路径 | 权限 | 请求 | 响应 |
|------|------|------|------|------|
| POST | `/api/v1/teams/{teamId}/invites` | OWNER/ADMIN | `{ "email": "user@example.com", "role": "VIEWER" }` | `TeamInviteResponse` |
| GET | `/api/v1/teams/{teamId}/invites?page=1&size=20` | OWNER/ADMIN | 无 | 邀请分页列表，`size` 最大 100 |
| POST | `/api/v1/teams/{teamId}/invites/{inviteId}/resend` | OWNER/ADMIN | 无 | 重新发送后的邀请 |
| DELETE | `/api/v1/teams/{teamId}/invites/{inviteId}` | OWNER/ADMIN | 无 | 取消邀请 |
| GET | `/api/v1/team-invites/{token}` | 登录用户 | 无 | 邀请详情 |
| POST | `/api/v1/team-invites/{token}/accept` | 登录用户 | 无 | 加入后的成员信息 |
| POST | `/api/v1/team-invites/{token}/reject` | 登录用户 | 无 | 拒绝邀请 |

**TeamInviteResponse 字段:** `id`、`teamId`、`teamName`、`email`、`role`、`status`、`invitedByUserId`、`invitedByUsername`、`expiresAt`、`acceptedAt`、`rejectedAt`、`createdAt`、`updatedAt`。

**错误码:** 403（无管理成员权限）、404（邀请不存在）、409（邀请已处理或已过期）。

### 16.3 团队文件、回收站、上传下载

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/teams/{teamId}/files?parentId=&page=&size=&sortBy=&order=` | 可读 | 列出团队文件 |
| GET | `/api/v1/teams/{teamId}/files/search?q=&page=&size=` | 团队成员 | 搜索可读文件 |
| GET | `/api/v1/teams/{teamId}/files/{fileId}` | 可读 | 获取文件元数据 |
| GET | `/api/v1/teams/{teamId}/files/{fileId}/thumbnail` | 可读 | 获取图片缩略图，未生成时返回 `404001` |
| POST | `/api/v1/teams/{teamId}/files/folder` | 父目录可写 | 创建文件夹，Body 同个人文件夹创建 |
| PUT | `/api/v1/teams/{teamId}/files/{fileId}` | 可写 | 重命名文件 |
| POST | `/api/v1/teams/{teamId}/files/{fileId}/move` | 源可写且目标父目录可写 | 移动文件 |
| DELETE | `/api/v1/teams/{teamId}/files` | 可删除 | Body `{ "fileIds": [1, 2] }`，移入团队回收站 |
| GET | `/api/v1/teams/{teamId}/recycle-bin?q=&page=&size=` | 团队成员 | 团队回收站 |
| POST | `/api/v1/teams/{teamId}/recycle-bin/{fileId}/restore` | 可写 | 从团队回收站恢复 |
| DELETE | `/api/v1/teams/{teamId}/recycle-bin/{fileId}` | OWNER/ADMIN | 永久删除单个回收站文件 |
| DELETE | `/api/v1/teams/{teamId}/recycle-bin` | OWNER/ADMIN | 清空团队回收站 |
| POST | `/api/v1/teams/{teamId}/upload/init` | 父目录可写 | 初始化分片上传，Body 同个人上传 |
| POST | `/api/v1/teams/{teamId}/upload/{sessionId}/chunk` | 上传会话所有者 | 上传分片 |
| GET | `/api/v1/teams/{teamId}/upload/{sessionId}/status` | 上传会话所有者 | 查询上传状态 |
| POST | `/api/v1/teams/{teamId}/upload/{sessionId}/complete` | 上传会话所有者 | 完成上传；支持幂等重试，重复调用返回同一 `fileId` |
| POST | `/api/v1/teams/{teamId}/upload/{sessionId}/cancel` | 上传会话所有者 | 取消上传 |
| POST | `/api/v1/teams/{teamId}/upload/instant` | 父目录可写 | 秒传 |
| POST | `/api/v1/teams/{teamId}/download/{fileId}/ticket` | 可读 | 创建团队文件下载 ticket |
| POST | `/api/v1/teams/{teamId}/download/folders/{fileId}/zip/ticket` | 可读 | 创建团队文件夹 ZIP ticket |
| POST | `/api/v1/teams/{teamId}/download/folders/{fileId}/zip/tasks` | 可读 | 创建团队文件夹 ZIP 后台任务，返回 `TeamTask` |
| GET | `/api/v1/teams/{teamId}/download/{fileId}?ticket=` | 可读或有效 ticket | 下载文件，支持 Range |
| GET | `/api/v1/teams/{teamId}/download/folders/{fileId}/zip?ticket=` | 可读或有效 ticket | 兼容旧客户端的同步 ZIP 下载，支持 Range |
| GET | `/api/v1/teams/{teamId}/preview/{fileId}` | 可读 | 内联预览文件内容 |

**错误码:** 403（权限不足）、404001（文件不存在）、409001（同名冲突）、419001（团队配额不足）、419006/419007（上传状态或分片 MD5 错误）、419015（ZIP 超限）。

团队列表固定按 `name ASC, id ASC` 排序。团队目录的 `sortBy` 支持 `fileName`、`updatedAt`、`fileSize`，`order` 支持 `asc`、`desc`；非法值分别回退 `fileName` 和 `asc`。目录始终文件夹优先，选定字段同值时按名称、ID 升序，`updatedAt` 空值位于末尾。目录和搜索都先过滤当前用户不可读项目，再计算分页；搜索词会去除首尾空白，空查询返回空页，搜索结果按文件夹、名称、ID 稳定排序。

### 16.4 团队分享、收藏和标签

| 方法 | 路径 | 权限 | 请求/响应 |
|------|------|------|------|
| POST | `/api/v1/teams/{teamId}/shares` | 可分享 | Body 同个人分享；响应 `ShareLinkResponse`，含 `teamId` |
| GET | `/api/v1/teams/{teamId}/shares?q=&passwordProtected=&expirationStatus=&page=&size=` | 团队成员 | 返回当前用户可读文件对应的分享；状态过滤参数与个人分享一致 |
| GET | `/api/v1/teams/{teamId}/shares/{shareId}` | 分享文件可读 | 分享详情 |
| GET | `/api/v1/teams/{teamId}/shares/{shareId}/password` | 可分享 | 读取分享密码 |
| PUT | `/api/v1/teams/{teamId}/shares/{shareId}` | 可分享 | Body 同个人更新分享；可更新或清空密码、过期时间和访问次数 |
| DELETE | `/api/v1/teams/{teamId}/shares/{shareId}` | 可分享 | 取消分享 |

团队分享响应与个人分享使用同一兼容 DTO：`shareUrl` 由后端按 `APP_PUBLIC_URL` 生成，`expireAtEpochMs` 提供明确时区的 epoch 毫秒值，并可携带分享根项目的 `fileName`、`fileType`、`fileSize`；原有 `expireAt` 保留兼容。列表文件元数据批量读取，项目已删除时元数据字段为 `null`。
| POST | `/api/v1/teams/{teamId}/favorites/{fileId}` | 可读 | 添加团队收藏 |
| DELETE | `/api/v1/teams/{teamId}/favorites/{fileId}` | 登录用户 | 取消团队收藏 |
| GET | `/api/v1/teams/{teamId}/favorites/{fileId}/status` | 可读 | 返回 boolean |
| GET | `/api/v1/teams/{teamId}/favorites?q=&page=&size=` | 团队成员 | 返回可读收藏文件 |
| POST | `/api/v1/teams/{teamId}/tags` | OWNER/ADMIN/EDITOR | `{ "tagName": "重要", "color": "#00d0b8" }` |
| GET | `/api/v1/teams/{teamId}/tags?q=` | 团队成员 | 团队标签列表 |
| PUT | `/api/v1/teams/{teamId}/tags/{tagId}` | OWNER/ADMIN/EDITOR | 更新标签 |
| DELETE | `/api/v1/teams/{teamId}/tags/{tagId}` | OWNER/ADMIN/EDITOR | 删除标签 |
| POST | `/api/v1/teams/{teamId}/tags/{tagId}/files` | 文件可写 | `{ "fileIds": [10, 11] }` |
| DELETE | `/api/v1/teams/{teamId}/tags/{tagId}/files/{fileId}` | 文件可写 | 取消标签 |
| GET | `/api/v1/teams/{teamId}/tags/{tagId}/files?q=&page=&size=` | 团队成员 | 返回可读标签文件 |

**错误码:** 403（文件权限不足）、404（标签/分享不存在）、409（标签名重复）、419002-419005（公开分享访问限制）。

### 16.5 文件权限覆盖

```
GET /api/v1/teams/{teamId}/files/{fileId}/effective-permission
GET /api/v1/teams/{teamId}/files/{fileId}/permissions
PUT /api/v1/teams/{teamId}/files/{fileId}/permissions
DELETE /api/v1/teams/{teamId}/files/{fileId}/permissions/{permissionId}
Authorization: Bearer <accessToken>
```

| 端点 | 权限 | 响应 |
|------|------|------|
| `GET effective-permission` | 可读 | 当前用户的 `canRead/canWrite/canShare/canDelete/canManage`，以及权限来源链 |
| `GET permissions` | 可管理 | 文件权限覆盖列表 |
| `PUT permissions` | 可管理 | 新增或更新覆盖规则 |
| `DELETE permissions/{permissionId}` | 可管理 | 删除覆盖规则 |

**GET effective-permission Response:**
```json
{
  "canRead": true,
  "canWrite": false,
  "canShare": false,
  "canDelete": false,
  "canManage": false,
  "sourceType": "ROLE_OVERRIDE",
  "sourceChain": [
    {
      "type": "BASE_ROLE",
      "fileId": null,
      "fileName": null,
      "subjectType": "ROLE",
      "subjectUserId": null,
      "subjectRole": "EDITOR",
      "inherited": false,
      "canRead": true,
      "canWrite": true,
      "canShare": true,
      "canDelete": true,
      "canManage": false,
      "descriptionKey": "permission.baseRole",
      "descriptionParams": { "role": "EDITOR" }
    },
    {
      "type": "ROLE_OVERRIDE",
      "fileId": 20,
      "fileName": "项目资料",
      "subjectType": "ROLE",
      "subjectUserId": null,
      "subjectRole": "EDITOR",
      "inherited": true,
      "canRead": true,
      "canWrite": false,
      "canShare": false,
      "canDelete": false,
      "canManage": false,
      "descriptionKey": "permission.inheritedOverride",
      "descriptionParams": { "fileName": "项目资料" }
    }
  ]
}
```

`sourceType` 表示最终生效来源：`BASE_ROLE`、`ROLE_OVERRIDE` 或 `USER_OVERRIDE`。`sourceChain` 按解析顺序返回来源链，第一项通常是基础角色权限；后续项是最终命中的用户或角色覆盖。`inherited=true` 表示该覆盖来自父文件夹。

Web/Tauri 与 Android 均展示该来源链；Android 团队文件页会标明基础角色、用户/角色覆盖、命中文件夹及是否继承。

**PUT Request Body:**
```json
{
  "subjectType": "USER",
  "subjectUserId": 12,
  "subjectRole": null,
  "canRead": true,
  "canWrite": true,
  "canShare": false,
  "canDelete": false,
  "canManage": false
}
```

`subjectType` 支持 `USER` 或 `ROLE`；用户维度使用 `subjectUserId`，角色维度使用 `subjectRole=ADMIN|EDITOR|VIEWER`。缺省布尔值按 `false` 处理。

**错误码:** 400（subject 参数非法、自锁或 OWNER 覆盖被拒绝）、403（不可管理文件）、404（文件或权限规则不存在）。

### 16.6 团队版本

```
GET /api/v1/teams/{teamId}/files/{fileId}/versions
GET /api/v1/teams/{teamId}/files/{fileId}/versions/{versionNumber}/download
POST /api/v1/teams/{teamId}/files/{fileId}/versions/{versionNumber}/restore
DELETE /api/v1/teams/{teamId}/files/{fileId}/versions/{versionNumber}
Authorization: Bearer <accessToken>
```

| 端点 | 权限 | 响应 |
|------|------|------|
| `GET versions` | 可读 | `FileVersionEntity[]`，按版本号降序 |
| `GET download` | 可读 | 二进制流，`Content-Disposition: attachment`，支持 Range |
| `POST restore` | 可写 | 恢复后的 `FileEntity`，并保留当前内容为新版本 |
| `DELETE` | 可删除 | 删除指定历史版本 |

**错误码:** 403（权限不足）、404（版本不存在）、419009（不能删除唯一版本或版本限制冲突）。

### 16.7 团队审计

```
GET /api/v1/teams/{teamId}/audit-logs?action=&targetType=&actorUserId=&result=&startAt=&endAt=&page=1&size=20
GET /api/v1/teams/{teamId}/audit-logs/export?action=&targetType=&actorUserId=&result=&startAt=&endAt=
POST /api/v1/teams/{teamId}/audit-logs/export-tasks?action=&targetType=&actorUserId=&result=&startAt=&endAt=
Authorization: Bearer <accessToken>
```

OWNER/ADMIN 可查看和导出团队审计日志。`startAt`、`endAt` 均使用 ISO DateTime，例如 `2026-06-27T12:59:14`。列表、同步导出和异步导出任务使用相同过滤语义。`GET /audit-logs/export` 保留同步 CSV 导出兼容旧客户端，响应为 `text/csv`，文件名格式 `team-{teamId}-audit-yyyyMMddHHmmss.csv`，最多 10000 行。`POST /audit-logs/export-tasks` 返回 `TeamTask`；异步任务以创建时刻作为默认结束时间，按 `createdAt DESC, id DESC` 稳定分批读取并流式写入临时 CSV，成功后原子替换结果文件，因此不受 10000 行上限约束且重试不会暴露不完整结果。

**错误码:** 400（参数格式错误）、403（非 OWNER/ADMIN）、404（团队不存在）。

### 16.8 团队任务中心

```
GET /api/v1/teams/{teamId}/tasks?status=&type=&page=1&size=20
POST /api/v1/teams/{teamId}/tasks/{taskId}/retry
GET /api/v1/teams/{teamId}/tasks/{taskId}/download
Authorization: Bearer <accessToken>
```

任务中心记录团队审计导出、团队文件夹 ZIP、团队图片缩略图生成、OnlyOffice 团队文档保存和团队回收站清理。OWNER/ADMIN 可查看和重试全部团队任务；普通成员仅能看到自己发起且当前仍有目标文件读取权限的文件夹 ZIP 和 OnlyOffice 保存任务。OnlyOffice 保存、缩略图生成和团队文件夹 ZIP 任务由后台 worker 消费 `PENDING` 任务，失败后可通过重试重新进入队列。审计导出任务由后台 worker 生成 CSV 结果文件，结果文件会按服务端保留时间过期清理。

| 端点 | 权限 | 说明 |
|------|------|------|
| `GET /tasks` | 按任务可见性 | OWNER/ADMIN 返回全部任务；普通成员只返回本人任务，并按当前目标文件读取权限过滤 |
| `POST /tasks/{taskId}/retry` | 按任务类型 | OWNER/ADMIN 可重试全部失败任务；文件夹 ZIP 发起人需仍可读；OnlyOffice 发起人需仍可写；系统任务不允许普通成员重试 |
| `GET /tasks/{taskId}/download` | 按任务类型 | 审计导出要求 OWNER/ADMIN；文件夹 ZIP 要求发起人身份及当前目标文件夹读取权限；响应为任务结果文件二进制流 |

**TeamTask 字段:** `id`、`teamId`、`actorUserId`、`taskType`、`status`、`targetType`、`targetId`、`titleKey`、`titleParams`、`progress`、`resultMessageKey`、`resultMessageParams`、`errorMessageKey`、`errorMessageParams`、`retryCount`、`payload`、`resultPath`、`resultFileName`、`resultContentType`、`resultSizeBytes`、`resultExpiresAt`、`createdAt`、`updatedAt`、`startedAt`、`completedAt`。文案 key 必须由客户端解析，任务状态仍以 `status` 为准。

**状态:** `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。

**当前任务类型:** `AUDIT_EXPORT`、`FOLDER_ZIP`、`THUMBNAIL`、`ONLYOFFICE_SAVE`、`TEAM_CLEANUP`。

后台 worker 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 原子领取任务，并在同一事务内将状态更新为 `RUNNING`。超过 `TEAM_TASK_RUNNING_TIMEOUT_SECONDS`（默认 1800 秒）的 `RUNNING` 任务会在下一次领取前恢复为 `PENDING` 并增加重试计数。

**错误码:** 400（非失败任务重试、任务未成功或参数错误）、403（非团队成员或缺少任务结果下载权限）、404（团队、任务或结果文件不存在）。

### 16.9 团队活动流

```
GET /api/v1/teams/{teamId}/activities?page=1&size=20
Authorization: Bearer <accessToken>
```

团队成员可查看按时间倒序排列的精简活动流。活动流复用团队审计数据，但不会开放完整审计字段；`FILE` 目标和可解析出文件 ID 的团队分享活动会按当前用户对文件的有效 `read` 权限过滤，普通成员不会看到无权读取文件的活动摘要。OWNER/ADMIN 的完整审计仍使用 `/audit-logs`。

**TeamActivity 字段:** `id`、`teamId`、`actorUserId`、`action`、`targetType`、`targetId`、`summaryKey`、`summaryParams`、`createdAt`。客户端按 key/params 生成摘要，权限过滤和程序判断使用结构化字段。

**错误码:** 403（非团队成员）、404（团队不存在）。

---
