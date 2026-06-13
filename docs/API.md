# Better Cloud Drive — API 文档

## 基础信息

- **Base URL**: `http://localhost:8080/api/v1`
- **Content-Type**: `application/json`（除文件上传外）
- **认证方式**: `Authorization: Bearer <accessToken>`
- **统一响应格式**:

```json
{
  "code": 200,
  "message": "success",
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
| 419005 | 下载次数已达上限 |
| 419006 | 分片上传状态无效 |
| 419007 | 分片 MD5 不匹配 |
| 419008 | 文件超出最大限制 |
| 419009 | 版本数量已达上限 |
| 419010 | 秒传未找到匹配文件 |
| 419011 | 邮箱验证失败 |
| 419012 | 验证码已过期 |
| 419013 | 验证码不匹配 |
| 419014 | 密码重置失败 |
| 429 | 请求过于频繁 |
| 500 | 服务器内部错误 |
| 500001 | 存储服务错误 |
| 500002 | 上传失败 |
| 500003 | 下载失败 |

---

## 一、认证 `/api/v1/auth`

### 1.1 注册

注册前必须先调用 `POST /api/v1/auth/register-code/send` 向邮箱发送 6 位验证码。

```
POST /api/v1/auth/register
```

**Request Body:**
```json
{
  "username": "zhangsan",       // 必填，3-64 字符
  "password": "Pass123456",     // 必填，8-128 字符，必须包含大写字母、小写字母和数字
  "email": "zhangsan@example.com", // 必填，邮箱格式
  "verificationCode": "123456"     // 必填，6 位邮箱验证码
}
```

> **密码复杂度要求**：至少 8 个字符，必须同时包含大写字母（A-Z）、小写字母（a-z）和数字（0-9）。

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": 1,
    "username": "zhangsan"
  }
}
```

**Response `409`:**
```json
{
  "code": 409,
  "message": "Username already exists"
}
```

---

### 1.2 发送注册验证码

```
POST /api/v1/auth/register-code/send
```

无需认证。向待注册邮箱发送 6 位数字验证码。验证码有效期 10 分钟。

**Request Body:**
```json
{
  "email": "zhangsan@example.com"
}
```

**错误码:** 409（邮箱已存在）、400（邮箱格式错误）

---

### 1.3 登录

```
POST /api/v1/auth/login
```

**Request Body:**
```json
{
  "username": "zhangsan",
  "password": "pass123456"
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "expiresIn": 1800              // access token 有效期（秒）
  }
}
```

- `accessToken` 有效期 30 分钟，用于所有 API 请求
- `refreshToken` 有效期 30 天，用于获取新的 access token

**Response `401`:**
```json
{
  "code": 401,
  "message": "Invalid username or password"
}
```

---

### 1.4 刷新令牌

```
POST /api/v1/auth/refresh
```

**Request Body:**
```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "expiresIn": 1800
  }
}
```

> 旧的 refresh token 在刷新后立即失效。

---

### 1.5 登出

```
POST /api/v1/auth/logout
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

> 登出后将当前 token 加入黑名单，后续使用该 token 将返回 401002。

---

### 1.6 获取当前用户信息

```
GET /api/v1/auth/me
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "email": "zhangsan@example.com",
    "nickname": null,
    "avatarUrl": null,
    "storageQuota": 10737418240,
    "storageUsed": 5242880,
    "status": 1,
    "createdAt": "2026-06-01T10:00:00"
  }
}
```

---

## 二、文件管理 `/api/v1/files`

### 2.1 列出目录内容

```
GET /api/v1/files?parentId=&page=1&size=20&sortBy=fileName&order=asc
Authorization: Bearer <accessToken>
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| parentId | Long | 否 | null | 父文件夹 ID，null 表示根目录 |
| page | int | 否 | 1 | 页码，从 1 开始 |
| size | int | 否 | 20 | 每页条数 |
| sortBy | String | 否 | fileName | 排序字段 |
| order | String | 否 | asc | asc/desc |

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "id": 10,
        "userId": 1,
        "parentId": null,
        "fileName": "文档",
        "fileType": "folder",
        "mimeType": null,
        "fileSize": 0,
        "storagePath": null,
        "md5Hash": null,
        "isDeleted": false,
        "versionCount": 1,
        "createdAt": "2026-06-05T14:30:00",
        "updatedAt": "2026-06-05T14:30:00"
      },
      {
        "id": 11,
        "userId": 1,
        "parentId": null,
        "fileName": "photo.jpg",
        "fileType": "file",
        "mimeType": "image/jpeg",
        "fileSize": 204800,
        "storagePath": "1/2026/06/a1b2c3d4.jpg",
        "md5Hash": "d41d8cd98f00b204e9800998ecf8427e",
        "isDeleted": false,
        "versionCount": 1,
        "createdAt": "2026-06-06T09:15:00",
        "updatedAt": "2026-06-06T09:15:00"
      }
    ],
    "total": 2,
    "page": 1,
    "size": 20,
    "pages": 1
  }
}
```

> 文件夹始终排在文件前面，按 fileName 升序排列。

---

### 2.2 获取文件/文件夹元数据

```
GET /api/v1/files/{fileId}
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 11,
    "fileName": "photo.jpg",
    "fileType": "file",
    "mimeType": "image/jpeg",
    "fileSize": 204800,
    "storagePath": "1/2026/06/a1b2c3d4.jpg",
    "md5Hash": "d41d8cd98f00b204e9800998ecf8427e",
    "createdAt": "2026-06-06T09:15:00",
    "updatedAt": "2026-06-06T09:15:00"
  }
}
```

**Response `404001`:**
```json
{
  "code": 404001,
  "message": "file not found"
}
```

---

### 2.3 创建文件夹

```
POST /api/v1/files/folder
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "parentId": null,       // 可选，父文件夹 ID，null = 根目录
  "folderName": "新建文件夹"
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 12,
    "fileName": "新建文件夹",
    "fileType": "folder",
    "parentId": null,
    "createdAt": "2026-06-08T10:00:00"
  }
}
```

**Response `409001`:**
```json
{
  "code": 409001,
  "message": "file name already exists in this folder"
}
```

---

### 2.4 重命名

```
PUT /api/v1/files/{fileId}
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "newName": "新文件名.jpg"
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 11,
    "fileName": "新文件名.jpg",
    ...
  }
}
```

---

### 2.5 移动

```
POST /api/v1/files/{fileId}/move
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "targetParentId": 10
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

---

### 2.6 复制

```
POST /api/v1/files/{fileId}/copy
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "targetParentId": 20
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

> 复制后的文件名自动追加 ` (copy)` 后缀。

---

### 2.7 批量删除（移入回收站）

```
DELETE /api/v1/files
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "fileIds": [11, 12, 13]
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

> 删除的文件进入回收站，30 天后自动清理。文件夹及其所有子文件一并移入回收站。

---

## 三、文件上传 `/api/v1/upload`

### 3.1 初始化上传会话

```
POST /api/v1/upload/init
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "parentId": 10,                // 必填，目标文件夹 ID
  "fileName": "report.pdf",     // 必填
  "fileSize": 10485760,         // 必填，文件总大小（字节）
  "md5Hash": "d41d8cd...",      // 可选，完整文件 MD5
  "totalChunks": 3              // 必填，分片总数（每片 5MB）
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "a1b2c3d4-e5f6-...",
    "chunkSize": 5242880,
    "totalChunks": 3
  }
}
```

**Response `419001`（配额超限）:**
```json
{
  "code": 419001,
  "message": "storage quota exceeded"
}
```

---

### 3.2 上传分片

```
POST /api/v1/upload/{sessionId}/chunk
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
```

**Form Fields:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| file | File | 是 | 分片文件数据 |
| chunkNumber | int | 是 | 分片序号，从 0 开始 |
| chunkMd5 | String | 否 | 分片 MD5 校验值 |

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "chunkNumber": 2
  }
}
```

> 同一分片重复上传会被自动忽略（Redis bitmap 去重）。

---

### 3.3 查询上传进度

```
GET /api/v1/upload/{sessionId}/status
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "a1b2c3d4-e5f6-...",
    "totalChunks": 3,
    "uploadedChunks": 2,
    "missingChunks": [2]
  }
}
```

---

### 3.4 完成上传（合并分片）

```
POST /api/v1/upload/{sessionId}/complete
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "fileId": 42
  }
}
```

**Response `419006`（分片不完整）:**
```json
{
  "code": 419006,
  "message": "Chunk 2 is missing"
}
```

---

### 3.5 取消上传

```
POST /api/v1/upload/{sessionId}/cancel
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

> 取消后会删除已上传的分片数据，释放存储空间。

---

### 3.6 秒传

如果文件 MD5 与服务端已有文件匹配，直接创建记录而不需要上传数据。

```
POST /api/v1/upload/instant
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "parentId": 10,
  "fileName": "report.pdf",
  "fileSize": 10485760,
  "md5Hash": "d41d8cd98f00b204e9800998ecf8427e"
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "fileId": 43,
    "instant": true
  }
}
```

**Response `419001`（配额超限）:**
```json
{
  "code": 419001,
  "message": "storage quota exceeded"
}
```

**Response `419010`（无匹配文件，需走分片上传）:**
```json
{
  "code": 419010,
  "message": "no matching file found for instant upload"
}
```

---

## 四、文件下载与预览

### 4.1 下载文件

```
GET /api/v1/download/{fileId}
Authorization: Bearer <accessToken>
```

以附件形式下载，浏览器自动触发保存。

**Response:** 二进制流，`Content-Type` 为文件实际 MIME 类型，`Content-Disposition: attachment`。

支持 `Range` 请求头实现断点续传。

---

### 4.2 在线预览

```
GET /api/v1/preview/{fileId}
Authorization: Bearer <accessToken>
```

以内联方式返回文件内容，浏览器直接渲染（图片、PDF、文本等）。

**Response:** 二进制流，`Content-Type` 为文件实际 MIME 类型，`Content-Disposition: inline`。

> 对文件夹请求会返回 `404001` 错误。

---

## 五、回收站 `/api/v1/recycle-bin`

### 5.1 回收站列表

```
GET /api/v1/recycle-bin?page=1&size=20
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "id": 11,
        "fileName": "photo.jpg",
        "fileType": "file",
        "fileSize": 204800,
        "isDeleted": true,
        "deletedAt": "2026-06-07T18:00:00"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20,
    "pages": 1
  }
}
```

---

### 5.2 恢复文件

```
POST /api/v1/recycle-bin/{fileId}/restore
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

---

### 5.3 彻底删除单个文件

```
DELETE /api/v1/recycle-bin/{fileId}
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

> 物理删除，不可恢复。同时删除存储中的文件对象和历史版本。

---

### 5.4 清空回收站

```
DELETE /api/v1/recycle-bin
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success"
}
```

> 物理删除回收站中当前用户的所有文件，不可恢复。

---

## 六、认证流程图

```
┌──────────┐          ┌──────────┐          ┌──────────┐
│  Client  │          │  Server  │          │  Redis   │
└────┬─────┘          └────┬─────┘          └────┬─────┘
     │                     │                     │
     │  POST /auth/login   │                     │
     │────────────────────>│                     │
     │                     │  Validate password  │
     │  {access, refresh}  │                     │
     │<────────────────────│                     │
     │                     │                     │
     │  GET /files         │                     │
     │  Authorization:     │                     │
     │  Bearer <access>    │                     │
     │────────────────────>│                     │
     │                     │  Check blacklist    │
     │                     │────────────────────>│
     │                     │     EXISTS?         │
     │                     │<────────────────────│
     │  200 OK             │                     │
     │<────────────────────│                     │
     │                     │                     │
     │  POST /auth/logout  │                     │
     │────────────────────>│                     │
     │                     │  SET blacklist      │
     │                     │────────────────────>│
     │  200 OK             │                     │
     │<────────────────────│                     │
```

---

## 七、通用说明

### 7.1 认证

除以下公开端点外，所有接口需要在请求头携带 `Authorization: Bearer <accessToken>`：

**公开端点（无需认证）：**
- `POST /api/v1/auth/register-code/send`、`POST /api/v1/auth/register`、`POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`
- `POST /api/v1/auth/forgot-password`、`POST /api/v1/auth/reset-password`
- `POST /api/v1/shares/access/{shareCode}`、`GET /api/v1/shares/access/{shareCode}/files`

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
- 存储用量通过 Redis 增量同步到 PostgreSQL（每 60 秒）
- 管理员可通过 `PATCH /api/v1/admin/users/{id}/quota` 修改任意用户的配额

### 7.6 缩略图

> `thumbnailPath` 字段已在数据库中预留，缩略图生成功能待后续版本实现。

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

## 八、文件搜索

### 8.1 搜索文件

```
GET /api/v1/files/search?q=keyword&page=1&size=20
Authorization: Bearer <accessToken>
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| q | String | 是 | — | 搜索关键词（文件名模糊匹配） |
| page | int | 否 | 1 | 页码 |
| size | int | 否 | 20 | 每页条数 |

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "id": 42,
        "fileName": "quarterly_report_2026.pdf",
        "fileType": "file",
        "fileSize": 204800,
        ...
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20,
    "pages": 1
  }
}
```

> 搜索仅返回当前用户的文件，不跨用户。

---

## 九、分享管理 `/api/v1/shares`

### 9.1 创建分享

```
POST /api/v1/shares
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "fileId": 42,              // 必填
  "password": "1234",        // 可选，访问密码
  "expireAt": 1718000000000, // 可选，过期时间（epoch ms），null 永不过期
  "maxDownloads": 10,        // 可选，最大下载次数，null 无限制
  "notifyEmail": "friend@example.com"  // 可选，发送分享通知邮件
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "fileId": 42,
    "shareCode": "aB3xK9mW",
    "passwordHash": "$2a$12$...",
    "expireAt": "2026-07-09T10:00:00",
    "maxDownloads": 10,
    "downloadCount": 0,
    "visitCount": 0,
    "isCanceled": false,
    "createdAt": "2026-06-09T12:00:00"
  }
}
```

---

### 9.2 列出我的分享

```
GET /api/v1/shares?page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回当前用户创建的所有分享。

---

### 9.3 获取分享详情

```
GET /api/v1/shares/{shareId}
Authorization: Bearer <accessToken>
```

返回单个分享的完整信息。仅分享所有者可查看。

---

### 9.4 更新分享

```
PUT /api/v1/shares/{shareId}
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "password": "",           // 设置为 "" 移除密码保护
  "expireAt": 0,            // 设置为 0 移除过期时间
  "maxDownloads": null      // 设置为 null 移除下载限制
}
```

---

### 9.5 取消分享

```
DELETE /api/v1/shares/{shareId}
Authorization: Bearer <accessToken>
```

取消后分享码立即失效，已获取链接的用户无法继续访问。

---

### 9.6 访问分享（公开）

```
POST /api/v1/shares/access/{shareCode}
```

无需认证。如果分享设置了密码，需要在 Body 中提供：

**Request Body（无密码时可选）:**
```json
{
  "password": "1234"
}
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "fileId": 42,
    "fileName": "report.pdf",
    "fileType": "file",
    "fileSize": 1048576
  }
}
```

**错误码:** 419002（无效分享码）、419003（需要密码/密码错误）、419004（已过期）、419005（下载次数达上限）

---

### 9.7 浏览分享内容（公开）

```
GET /api/v1/shares/access/{shareCode}/files?parentId=&page=1&size=20
```

无需认证。浏览分享的文件/文件夹内容，支持分页。

---

## 十、收藏 `/api/v1/favorites`

### 10.1 添加收藏

```
POST /api/v1/favorites/{fileId}
Authorization: Bearer <accessToken>
```

重复收藏同一文件不报错（幂等）。

---

### 10.2 取消收藏

```
DELETE /api/v1/favorites/{fileId}
Authorization: Bearer <accessToken>
```

对未收藏的文件执行不报错（幂等）。

---

### 10.3 收藏列表

```
GET /api/v1/favorites?page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回当前用户收藏的文件列表，包含完整 FileEntity。

---

### 10.4 查询收藏状态

```
GET /api/v1/favorites/{fileId}/status
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": true
}
```

---

## 十一、标签 `/api/v1/tags`

### 11.1 创建标签

```
POST /api/v1/tags
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "tagName": "重要",       // 必填
  "color": "#ff0000"       // 可选，默认 "#1890ff"
}
```

同用户下标签名不可重复，返回 409。

---

### 11.2 标签列表

```
GET /api/v1/tags
Authorization: Bearer <accessToken>
```

返回当前用户所有标签（非分页）。

---

### 11.3 更新标签

```
PUT /api/v1/tags/{tagId}
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "tagName": "紧急",
  "color": "#ff4444"
}
```

---

### 11.4 删除标签

```
DELETE /api/v1/tags/{tagId}
Authorization: Bearer <accessToken>
```

删除标签时自动清除所有文件关联。

---

### 11.5 批量关联文件

```
POST /api/v1/tags/{tagId}/files
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "fileIds": [10, 20, 30]
}
```

---

### 11.6 取消文件关联

```
DELETE /api/v1/tags/{tagId}/files/{fileId}
Authorization: Bearer <accessToken>
```

---

### 11.7 按标签列出文件

```
GET /api/v1/tags/{tagId}/files?page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回该标签关联的所有文件。

---

## 十二、文件版本 `/api/v1/files/{fileId}/versions`

### 12.1 版本列表

```
GET /api/v1/files/{fileId}/versions
Authorization: Bearer <accessToken>
```

返回该文件的所有历史版本，按 `versionNumber` 降序排列。

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 5,
      "fileId": 42,
      "versionNumber": 2,
      "fileSize": 204800,
      "md5Hash": "d41d8cd9...",
      "storagePath": "1/2026/06/v2_report.pdf",
      "createdAt": "2026-06-09T14:00:00"
    },
    {
      "id": 3,
      "fileId": 42,
      "versionNumber": 1,
      "fileSize": 102400,
      "md5Hash": "a1b2c3d4...",
      "storagePath": "1/2026/06/v1_report.pdf",
      "createdAt": "2026-06-08T10:00:00"
    }
  ]
}
```

---

### 12.2 删除版本

```
DELETE /api/v1/files/{fileId}/versions/{versionNumber}
Authorization: Bearer <accessToken>
```

> 如果文件仅剩一个版本，删除操作返回 419009（不能删除唯一版本）。

---

## 十三、注册验证码与密码重置 `/api/v1/auth`

### 13.1 发送注册验证码

```
POST /api/v1/auth/register-code/send
```

无需认证。向待注册邮箱发送 6 位数字验证码。验证码有效期 10 分钟，存储在 Redis 中。

**Request Body:**
```json
{
  "email": "zhangsan@example.com"
}
```

**错误码:** 409（邮箱已存在）、400（邮箱格式错误）

---

### 13.2 忘记密码（公开）

```
POST /api/v1/auth/forgot-password
```

无需认证。向指定邮箱发送密码重置验证码。

**Request Body:**
```json
{
  "email": "user@example.com"
}
```

> 即使用户不存在也返回 200（防止邮箱枚举）。

---

### 13.4 重置密码（公开）

```
POST /api/v1/auth/reset-password
```

无需认证。验证码通过后重置密码。

**Request Body:**
```json
{
  "email": "user@example.com",
  "code": "123456",
  "newPassword": "newPass123"
}
```

**错误码:** 419012（验证码过期）、419013（验证码不匹配）、419014（用户不存在）

---

## 十四、管理员 `/api/v1/admin`

> 所有管理员端点需要 `ROLE_ADMIN` 角色，普通用户返回 403。

### 14.1 用户列表

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

### 14.2 修改用户状态

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

### 14.3 修改用户配额

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
  "message": "success"
}
```

> 配额修改即时生效。用户上传时系统检查 `storageUsed + 文件大小 ≤ storageQuota`，超限返回 419001。

---

### 14.4 浏览用户文件

```
GET /api/v1/admin/users/{userId}/files?parentId=&page=1&size=20&sortBy=fileName&order=asc
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

查看任意用户的文件列表，参数同 `GET /api/v1/files`。

---

### 14.5 查看任意文件

```
GET /api/v1/admin/files/{fileId}
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

跳过所有权检查，直接按 ID 获取文件元数据。

---

### 14.6 删除任意文件

```
DELETE /api/v1/admin/files/{fileId}
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

跳过所有权检查，软删除（移入回收站）。

---

### 14.7 操作日志

```
GET /api/v1/admin/logs?userId=&actionType=&startDate=&endDate=&page=1&size=20
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| userId | Long | 否 | 按用户筛选 |
| actionType | String | 否 | 按动作筛选（LOGIN/LOGOUT/UPLOAD/DOWNLOAD/DELETE 等） |
| startDate | ISO DateTime | 否 | 起始时间 |
| endDate | ISO DateTime | 否 | 结束时间 |
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |

---

### 14.8 系统统计

```
GET /api/v1/admin/stats
Authorization: Bearer <accessToken>  (需要 ROLE_ADMIN)
```

**Response `200`:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalUsers": 150,
    "activeUsers": 120,
    "totalStorageUsed": 10737418240
  }
}
```
