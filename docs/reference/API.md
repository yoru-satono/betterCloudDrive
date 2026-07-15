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
| 419010 | 秒传未找到匹配文件 |
| 419011 | 邮箱验证失败 |
| 419012 | 验证码已过期 |
| 419013 | 验证码不匹配 |
| 419014 | 密码重置失败 |
| 419015 | 文件夹打包下载超出限制 |
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "error.conflict",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "error.unauthorized",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "common.success",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "id": 1,
    "username": "zhangsan",
    "email": "zhangsan@example.com",
    "emailVerified": true,
    "nickname": null,
    "avatarUrl": null,
    "role": "ROLE_USER",
    "storageQuota": 10737418240,
    "storageUsed": 5242880,
    "webdavEnabled": false,
    "status": 1,
    "createdAt": "2026-06-01T10:00:00",
    "updatedAt": "2026-06-01T10:00:00",
    "deletedAt": null
  }
}
```

---

### 1.7 获取 WebDAV 设置

```
GET /api/v1/auth/webdav
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "enabled": false
  }
}
```

---

### 1.8 更新 WebDAV 设置

```
PUT /api/v1/auth/webdav
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "enabled": true,
  "password": "webdavPass123"
}
```

- WebDAV 默认关闭。
- 开启 WebDAV 时必须提供独立的 WebDAV 密码；服务端不要求该密码与网盘登录密码不同。
- 关闭 WebDAV 时仅需传 `{"enabled": false}`，`password` 可省略。
- 开启或重新开启时会写入新的 WebDAV 密码哈希。

**Response `200`:** 返回更新后的当前用户资料，结构同 `GET /api/v1/auth/me`。

**Response `400`:**
```json
{
  "code": 400,
  "messageKey": "error.bad_request",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "error.file_not_found",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "error.file_name_conflict",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
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

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| targetParentId | Long | 否 | 目标文件夹 ID；`null` 表示移动到根目录 |

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {}
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

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| targetParentId | Long | 否 | 目标文件夹 ID；`null` 表示复制到根目录 |

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {}
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
  "parentId": 10,                // 可选，目标文件夹 ID；null 表示根目录
  "fileName": "report.pdf",     // 必填
  "fileSize": 10485760,         // 必填，文件总大小（字节），可为 0
  "md5Hash": "d41d8cd...",      // 可选，完整文件 MD5
  "totalChunks": 3              // 必填，分片总数；0 字节文件传 0
}
```

> 当前服务端分片大小为 5MB。0 字节文件应使用 `fileSize: 0` 和 `totalChunks: 0` 初始化，会话创建后直接调用 `complete`，不上传分片。

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "error.storage_quota_exceeded",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "chunkNumber": 2
  }
}
```

> 同一分片重复上传会被自动忽略。服务端使用 Redis bitmap 记录已接收分片，bitmap 有效期为 24 小时。

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
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "sessionId": "a1b2c3d4-e5f6-...",
    "totalChunks": 3,
    "uploadedChunks": 2,
    "missingChunks": [2]
  }
}
```

---

### 3.3.1 断点续传约定

客户端如需暂停后继续上传，应本地持久化以下会话信息：

- `sessionId`
- `parentId`
- `fileName`
- `fileSize`
- `md5Hash`
- `totalChunks`
- `chunkSize`

恢复时先调用 `GET /api/v1/upload/{sessionId}/status`，只补传 `missingChunks` 中的分片，全部分片完成后再调用 `complete`。当前前端和 Tauri 客户端均按 24 小时窗口保存可续传上传记录。

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
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "fileId": 42
  }
}
```

`messageKey` 是稳定语义键，`messageParams` 是客户端本地化插值参数。后端不返回旧 `message` 字段，也不会把异常诊断文本序列化给客户端。业务代码和自动化测试应优先使用 `code`、对象 `type/status` 或稳定 key 判断状态，不得按中文翻译分支。完整约定见 [客户端本地化与语义文案协议](LOCALIZATION.md)。

未知或内部异常返回 `error.internal`；Web/Tauri 与 Android 使用各自本地资源显示通用中文提示，生产 UI 不直接展示未知 key。

`complete` 支持幂等重试：同一 `sessionId` 第一次成功后会记录产物文件 ID，后续重复调用返回同一个 `fileId`，不会重复合并对象、重复创建文件或重复累计存储配额。

**Response `419006`（分片不完整）:**
```json
{
  "code": 419006,
  "messageKey": "error.chunk_upload_invalid",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "error.storage_quota_exceeded",
  "messageParams": {}
}
```

**Response `419010`（无匹配文件，需走分片上传）:**
```json
{
  "code": 419010,
  "messageKey": "error.instant_upload_not_found",
  "messageParams": {}
}
```

---

## 四、文件下载与预览

### 4.1 创建文件下载 Ticket

```
POST /api/v1/download/{fileId}/ticket
Authorization: Bearer <accessToken>
```

为网页端下载生成临时 URL。Ticket 有效期 24 小时；每次使用时会续期 24 小时。

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "url": "/api/v1/download/42?ticket=F6imJ4..."
  }
}
```

> 该接口仅支持普通文件。对文件夹调用会返回 `404001`，文件夹应使用 ZIP ticket 或由 Tauri 客户端直接递归下载。

---

### 4.2 下载文件

```
GET /api/v1/download/{fileId}
Authorization: Bearer <accessToken>   // 可选：使用 Bearer 或 ticket 二选一
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| ticket | String | 否 | 由 `POST /api/v1/download/{fileId}/ticket` 生成的临时下载凭证 |

以附件形式下载。浏览器端通常先创建 ticket，再使用返回的 URL 触发下载；Tauri 客户端可直接携带 `Authorization: Bearer <accessToken>` 下载。

**Response:** 二进制流，`Content-Type` 为文件实际 MIME 类型，`Content-Disposition: attachment`。

支持 `Range` 请求头实现断点续传：

```
Range: bytes=1048576-
```

- 无 `Range`：返回 `200 OK`，包含 `Accept-Ranges: bytes`。
- 有合法 `Range`：返回 `206 Partial Content`，包含 `Content-Range` 和该片段的 `Content-Length`。
- 多段 Range 不支持；非法或不可满足的 Range 返回 `416 Requested Range Not Satisfiable`。

---

### 4.3 创建文件夹 ZIP 下载 Ticket

```
POST /api/v1/download/folders/{fileId}/zip/ticket
Authorization: Bearer <accessToken>
```

为网页端文件夹打包下载生成临时 URL。Ticket 有效期 24 小时；每次使用时会续期 24 小时。

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "url": "/api/v1/download/folders/10/zip?ticket=F6imJ4..."
  }
}
```

**错误码:** 400（目标不是文件夹）、419015（文件数或总大小超过网页打包限制）

---

### 4.4 下载文件夹 ZIP

```
GET /api/v1/download/folders/{fileId}/zip
Authorization: Bearer <accessToken>   // 可选：使用 Bearer 或 ticket 二选一
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| ticket | String | 否 | 由 `POST /api/v1/download/folders/{fileId}/zip/ticket` 生成的临时下载凭证 |

网页端文件夹下载会在服务端生成 ZIP 缓存对象并支持 `Range`。缓存按文件夹内容签名复用，默认从最后一次下载时间起保留 24 小时，清理任务默认每 30 分钟执行一次。

默认网页端打包限制：

| 限制项 | 默认值 |
|--------|--------|
| 最大文件数 | 1000 |
| 最大总大小 | 1GB |

Tauri 客户端的文件夹下载不走 ZIP 打包接口，而是递归列出文件夹内容并逐个文件下载到用户选择的本地目录。

---

### 4.5 在线预览

```
GET /api/v1/preview/{fileId}
Authorization: Bearer <accessToken>
```

以内联方式返回文件内容，浏览器直接渲染（图片、PDF、文本等）。

**Response:** 二进制流，`Content-Type` 为文件实际 MIME 类型，`Content-Disposition: inline`。

> 对文件夹请求会返回 `404001` 错误。

---

### 4.6 获取文件缩略图

```
GET /api/v1/files/{fileId}/thumbnail
Authorization: Bearer <accessToken>
```

返回上传图片后台任务生成的 JPEG 缩略图。缩略图尚未生成、源文件不是普通文件或文件没有缩略图时返回 `404001`，客户端应回退到默认占位图或原始预览。

**Response:** 二进制流，`Content-Type: image/jpeg`，`Content-Disposition: inline`，`Cache-Control: private, max-age=86400`。

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
  "messageKey": "common.success",
  "messageParams": {},
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
  "messageKey": "common.success",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {}
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
  "messageKey": "common.success",
  "messageParams": {}
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
- `GET /api/v1/download/{fileId}`、`GET /api/v1/download/folders/{fileId}/zip`（未携带 Bearer Token 时必须提供有效 `ticket`）
- `POST /api/v1/shares/access/{shareCode}`、`GET /api/v1/shares/access/{shareCode}/files`
- `POST /api/v1/shares/access/{shareCode}/download/{fileId}`、`POST /api/v1/shares/access/{shareCode}/download/{fileId}/zip`

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
  "messageKey": "common.success",
  "messageParams": {},
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

## 九、WebDAV `/webdav`

WebDAV 协议入口不使用 `/api/v1` 前缀，Base URL 为：

```
http://localhost:8080/webdav/
```

**认证方式:** HTTP Basic Auth

- 用户名：网盘用户名
- 密码：用户在 `PUT /api/v1/auth/webdav` 中设置的独立 WebDAV 密码
- 只有 `webdavEnabled = true` 且已设置 WebDAV 密码的用户可以访问

**支持的方法:**

| 方法 | 说明 |
|------|------|
| OPTIONS | 返回支持的方法和 DAV 级别 |
| PROPFIND | 列出目录或读取资源属性，支持 `Depth: 0` 和默认一层目录 |
| GET / HEAD | 下载文件或读取文件元信息 |
| PUT | 上传或覆盖文件 |
| MKCOL | 创建文件夹 |
| DELETE | 删除文件或文件夹（移入回收站） |
| MOVE | 移动或重命名 |
| COPY | 复制文件或文件夹；文件夹会递归复制子项 |
| LOCK / UNLOCK | 写锁令牌，锁信息保存在 Redis，默认 1 小时 |

> WebDAV 的 PUT 为流式上传，不使用 `/api/v1/upload` 的分片会话接口，因此不提供同等的上传断点续传语义。

`COPY` 不覆盖已有目标；目标同名、目标父目录不存在、或将文件夹复制到自身及其子目录时返回失败。复制会按源文件树总大小检查用户配额，成功后计入用户逻辑存储用量。

---

## 十、分享管理 `/api/v1/shares`

### 10.1 创建分享

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
  "maxVisits": 10,           // 可选，最大访问次数，null 无限制
  "notifyEmail": "friend@example.com"  // 可选，发送分享通知邮件
}
```

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "id": 1,
    "fileId": 42,
    "shareCode": "aB3xK9mW",
    "hasPassword": true,
    "expireAt": "2026-07-09T10:00:00",
    "maxVisits": 10,
    "downloadCount": 0,
    "visitCount": 0,
    "isCanceled": false,
    "createdAt": "2026-06-09T12:00:00",
    "updatedAt": "2026-06-09T12:00:00"
  }
}
```

> 分享密码在数据库中以加密密文保存，API 响应只返回 `hasPassword`，不会返回密文或原始密码。`downloadCount` 当前会在公开下载或保存分享内容时递增，`maxVisits` 会限制公开访问详情、浏览内容、下载和保存分享内容。
> 分享密码如果提供，长度必须为 4-16 个字符。

---

### 10.2 列出我的分享

```
GET /api/v1/shares?q=&passwordProtected=&expirationStatus=&page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回当前用户创建的所有分享。`q` 只匹配用户数据，不解析“有密码”“已过期”等中文状态词；密码状态使用独立布尔参数 `passwordProtected`，过期状态使用 `expirationStatus=ACTIVE|EXPIRED|NEVER`。

---

### 10.3 获取分享详情

```
GET /api/v1/shares/{shareId}
Authorization: Bearer <accessToken>
```

返回单个分享的完整信息。仅分享所有者可查看。

---

### 10.4 读取分享密码

```
GET /api/v1/shares/{shareId}/password
Authorization: Bearer <accessToken>
```

仅分享所有者可调用。用于在“我的分享”中按需查看自己创建的分享密码。

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "password": "1234"
  }
}
```

无密码分享返回：

```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "password": null
  }
}
```

---

### 10.5 更新分享

```
PUT /api/v1/shares/{shareId}
Authorization: Bearer <accessToken>
```

**Request Body:**
```json
{
  "password": "1234",       // 可选；设置新密码
  "expireAt": 1718000000000,// 可选；设置过期时间（epoch ms）
  "maxVisits": 10,          // 可选；设置最大访问次数
  "clearPassword": false,   // 可选；true 时移除密码保护
  "clearExpireAt": false,   // 可选；true 时移除过期时间
  "clearMaxVisits": false   // 可选；true 时移除访问次数限制
}
```

未传字段保持原值不变。兼容旧请求：`password=""` 仍会移除密码保护，`expireAt=0` 仍会移除过期时间；`maxVisits=null` 仍表示不修改，清空访问次数限制请使用 `clearMaxVisits=true`。

---

### 10.6 取消分享

```
DELETE /api/v1/shares/{shareId}
Authorization: Bearer <accessToken>
```

取消后分享码立即失效，已获取链接的用户无法继续访问。

---

### 10.7 访问分享（公开）

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
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "fileId": 42,
    "fileName": "report.pdf",
    "fileType": "file",
    "fileSize": 1048576
  }
}
```

**错误码:** 419002（无效分享码）、419003（需要密码/密码错误）、419004（已过期）、419005（访问次数达上限）

---

### 10.8 浏览分享内容（公开）

```
GET /api/v1/shares/access/{shareCode}/files?parentId=&page=1&size=20&password=1234
```

无需认证。浏览分享的文件/文件夹内容，支持分页。分享设置了密码时必须通过 `password` 查询参数提供密码。

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| parentId | Long | 否 | 要浏览的分享内文件夹 ID；省略或空值表示分享根节点 |
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页数量，默认 20 |
| password | String | 否 | 分享密码 |

**错误码:** 419002（无效分享码）、419003（需要密码/密码错误）、419004（已过期）、419005（访问次数达上限）

---

### 10.9 保存分享内容到我的网盘

```
POST /api/v1/shares/access/{shareCode}/save
Authorization: Bearer <accessToken>
```

将公开分享中的文件或文件夹复制到当前登录用户的网盘。分享设置了密码时必须提供密码。

**Request Body（可选）:**
```json
{
  "fileId": 42,
  "targetParentId": null,
  "password": "1234"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| fileId | Long | 否 | 要保存的分享内文件/文件夹 ID；省略时保存分享根节点 |
| targetParentId | Long | 否 | 目标文件夹 ID；`null` 表示保存到根目录 |
| password | String | 否 | 分享密码 |

**Response `200`:** 返回保存后的根文件或文件夹实体。

**错误码:** 409001（目标目录同名冲突）、419001（配额不足）、419002/419003/419004/419005（分享无效、密码错误、过期或访问次数达上限）

---

### 10.10 下载分享文件（公开）

```
POST /api/v1/shares/access/{shareCode}/download/{fileId}
```

无需认证。分享设置了密码时必须在 Body 中提供密码。

**Request Body（无密码时可选）:**
```json
{
  "password": "1234"
}
```

支持 `Range` 请求头实现断点续传：

```http
Range: bytes=1048576-
```

**Response:** 二进制流，`Content-Disposition: attachment`，包含 `Accept-Ranges: bytes` 响应头。

- 无 `Range`：返回 `200 OK`，包含完整文件和 `Content-Length`。
- 有合法 `Range`：返回 `206 Partial Content`，包含 `Content-Range` 和片段 `Content-Length`。
- 多段 Range 不支持；非法或不可满足的 Range 返回 `416 Requested Range Not Satisfiable`。

**错误码:** 419002（无效分享码）、419003（需要密码/密码错误）、419004（已过期）、419005（访问次数达上限）

---

### 10.11 下载分享文件夹 ZIP（公开）

```
POST /api/v1/shares/access/{shareCode}/download/{fileId}/zip
```

无需认证。将分享范围内的文件夹打包为 ZIP 流返回；分享设置了密码时必须在 Body 中提供密码。

**Request Body（无密码时可选）:**
```json
{
  "password": "1234"
}
```

**Response:** 二进制 ZIP 流，`Content-Disposition: attachment`。

> 该公开 ZIP 接口为即时打包流，不使用 4.4 的登录态 ZIP 缓存，也不支持 Range 续传。

**错误码:** 419002（无效分享码）、419003（需要密码/密码错误）、419004（已过期）、419005（访问次数达上限）

---

## 十一、收藏 `/api/v1/favorites`

### 11.1 添加收藏

```
POST /api/v1/favorites/{fileId}
Authorization: Bearer <accessToken>
```

重复收藏同一文件不报错（幂等）。

---

### 11.2 取消收藏

```
DELETE /api/v1/favorites/{fileId}
Authorization: Bearer <accessToken>
```

对未收藏的文件执行不报错（幂等）。

---

### 11.3 收藏列表

```
GET /api/v1/favorites?page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回当前用户收藏的文件列表，包含完整 FileEntity。

---

### 11.4 查询收藏状态

```
GET /api/v1/favorites/{fileId}/status
Authorization: Bearer <accessToken>
```

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": true
}
```

---

## 十二、标签 `/api/v1/tags`

### 12.1 创建标签

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

### 12.2 标签列表

```
GET /api/v1/tags
Authorization: Bearer <accessToken>
```

返回当前用户所有标签（非分页）。

---

### 12.3 更新标签

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

### 12.4 删除标签

```
DELETE /api/v1/tags/{tagId}
Authorization: Bearer <accessToken>
```

删除标签时自动清除所有文件关联。

---

### 12.5 批量关联文件

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

### 12.6 取消文件关联

```
DELETE /api/v1/tags/{tagId}/files/{fileId}
Authorization: Bearer <accessToken>
```

---

### 12.7 按标签列出文件

```
GET /api/v1/tags/{tagId}/files?page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回该标签关联的所有文件。

---

## 十三、文件版本 `/api/v1/files/{fileId}/versions`

### 13.1 版本列表

```
GET /api/v1/files/{fileId}/versions
Authorization: Bearer <accessToken>
```

返回该文件的所有历史版本，按 `versionNumber` 降序排列。

**Response `200`:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
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

### 13.2 删除版本

```
DELETE /api/v1/files/{fileId}/versions/{versionNumber}
Authorization: Bearer <accessToken>
```

> 如果文件仅剩一个版本，删除操作返回 419009（不能删除唯一版本）。

---

## 十四、注册验证码与密码重置 `/api/v1/auth`

### 14.1 发送注册验证码

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

### 14.2 忘记密码（公开）

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

### 14.3 重置密码（公开）

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

### 15.9 系统统计

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

## 十六、团队协作 `/api/v1/teams`

> 所有团队端点都需要登录。团队角色为 `OWNER`、`ADMIN`、`EDITOR`、`VIEWER`。文件权限会先校验团队成员身份，再应用文件级权限覆盖。

### 16.1 团队和成员

| 方法 | 路径 | 权限 | 请求 | 响应 |
|------|------|------|------|------|
| GET | `/api/v1/teams` | 登录用户 | 无 | 当前用户加入的团队列表 |
| POST | `/api/v1/teams` | 登录用户 | `{ "name": "研发团队" }` | `TeamResponse` |
| GET | `/api/v1/teams/{teamId}` | 团队成员 | 无 | `TeamResponse` |
| PUT | `/api/v1/teams/{teamId}` | OWNER/ADMIN | `{ "name": "新名称" }` | `TeamResponse` |
| DELETE | `/api/v1/teams/{teamId}` | OWNER | 无 | 软删除团队 |
| POST | `/api/v1/teams/{teamId}/leave` | 非唯一 OWNER | 无 | 退出团队 |
| POST | `/api/v1/teams/{teamId}/owner` | OWNER | `{ "userId": 12 }` | 转让后的 `TeamResponse` |
| GET | `/api/v1/teams/{teamId}/members` | 团队成员 | 无 | `TeamMemberResponse[]` |
| POST | `/api/v1/teams/{teamId}/members` | OWNER/ADMIN | `{ "username": "lisi", "role": "VIEWER" }` | `TeamMemberResponse` |
| PUT | `/api/v1/teams/{teamId}/members/{userId}` | OWNER/ADMIN | `{ "role": "EDITOR" }` | `TeamMemberResponse` |
| DELETE | `/api/v1/teams/{teamId}/members/{userId}` | OWNER/ADMIN | 无 | 移除成员 |

**TeamResponse 字段:** `id`、`name`、`ownerId`、`storageQuota`、`storageUsed`、`role`、`createdAt`、`updatedAt`。

**错误码:** 403（非成员或角色不足）、404（团队/成员不存在）、409（重名或状态冲突）。

### 16.2 邀请链接

| 方法 | 路径 | 权限 | 请求 | 响应 |
|------|------|------|------|------|
| POST | `/api/v1/teams/{teamId}/invites` | OWNER/ADMIN | `{ "email": "user@example.com", "role": "VIEWER" }` | `TeamInviteResponse` |
| GET | `/api/v1/teams/{teamId}/invites` | OWNER/ADMIN | 无 | 邀请列表 |
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

### 16.4 团队分享、收藏和标签

| 方法 | 路径 | 权限 | 请求/响应 |
|------|------|------|------|
| POST | `/api/v1/teams/{teamId}/shares` | 可分享 | Body 同个人分享；响应 `ShareLinkResponse`，含 `teamId` |
| GET | `/api/v1/teams/{teamId}/shares?q=&passwordProtected=&expirationStatus=&page=&size=` | 团队成员 | 返回当前用户可读文件对应的分享；状态过滤参数与个人分享一致 |
| GET | `/api/v1/teams/{teamId}/shares/{shareId}` | 分享文件可读 | 分享详情 |
| GET | `/api/v1/teams/{teamId}/shares/{shareId}/password` | 可分享 | 读取分享密码 |
| PUT | `/api/v1/teams/{teamId}/shares/{shareId}` | 可分享 | Body 同个人更新分享；可更新或清空密码、过期时间和访问次数 |
| DELETE | `/api/v1/teams/{teamId}/shares/{shareId}` | 可分享 | 取消分享 |
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
      "descriptionKey": "permission.base_role",
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
      "descriptionKey": "permission.inherited_override",
      "descriptionParams": { "fileName": "项目资料" }
    }
  ]
}
```

`sourceType` 表示最终生效来源：`BASE_ROLE`、`ROLE_OVERRIDE` 或 `USER_OVERRIDE`。`sourceChain` 按解析顺序返回来源链，第一项通常是基础角色权限；后续项是最终命中的用户或角色覆盖。`inherited=true` 表示该覆盖来自父文件夹。

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

OWNER/ADMIN 可查看和导出团队审计日志。`startAt`、`endAt` 均使用 ISO DateTime，例如 `2026-06-27T12:59:14`。列表、同步导出和异步导出任务使用相同过滤语义。`GET /audit-logs/export` 保留同步 CSV 导出兼容旧客户端，响应为 `text/csv`，文件名格式 `team-{teamId}-audit-yyyyMMddHHmmss.csv`，最多 10000 行。`POST /audit-logs/export-tasks` 返回 `TeamTask`，后台生成 CSV 后可在任务中心下载结果。

**错误码:** 400（参数格式错误）、403（非 OWNER/ADMIN）、404（团队不存在）。

### 16.8 团队任务中心

```
GET /api/v1/teams/{teamId}/tasks?status=&type=&page=1&size=20
POST /api/v1/teams/{teamId}/tasks/{taskId}/retry
GET /api/v1/teams/{teamId}/tasks/{taskId}/download
Authorization: Bearer <accessToken>
```

团队成员可查看团队长任务状态。任务中心记录团队审计导出、团队文件夹 ZIP、团队图片缩略图生成、OnlyOffice 团队文档保存和团队回收站清理；OnlyOffice 保存、缩略图生成和团队文件夹 ZIP 任务由后台 worker 消费 `PENDING` 任务，失败后可通过重试重新进入队列。审计导出任务由后台 worker 生成 CSV 结果文件，结果文件会按服务端保留时间过期清理。团队文件夹 ZIP 任务成功后会把 ZIP 对象 key 写入任务结果，任务中心通过 `GET /tasks/{taskId}/download` 下载。

| 端点 | 权限 | 说明 |
|------|------|------|
| `GET /tasks` | 团队成员 | 分页返回团队任务，可按 `status` 和 `type` 过滤 |
| `POST /tasks/{taskId}/retry` | 团队成员 | 仅允许重试 `FAILED` 任务，状态重置为 `PENDING`，清空失败原因并递增 `retryCount` |
| `GET /tasks/{taskId}/download` | 按任务类型 | 审计导出要求 OWNER/ADMIN；文件夹 ZIP 要求对目标文件夹可读；响应为任务结果文件二进制流 |

**TeamTask 字段:** `id`、`teamId`、`actorUserId`、`taskType`、`status`、`targetType`、`targetId`、`titleKey`、`titleParams`、`progress`、`resultMessageKey`、`resultMessageParams`、`errorMessageKey`、`errorMessageParams`、`retryCount`、`payload`、`resultPath`、`resultFileName`、`resultContentType`、`resultSizeBytes`、`resultExpiresAt`、`createdAt`、`updatedAt`、`startedAt`、`completedAt`。文案 key 必须由客户端解析，任务状态仍以 `status` 为准。

**状态:** `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。

**当前任务类型:** `AUDIT_EXPORT`、`FOLDER_ZIP`、`THUMBNAIL`、`ONLYOFFICE_SAVE`、`TEAM_CLEANUP`。

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

## 十七、通知 `/api/v1/notifications`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/notifications?unreadOnly=&type=&page=1&size=20` | 登录用户 | 分页列出站内通知 |
| GET | `/api/v1/notifications/unread-count` | 登录用户 | 返回未读数量 |
| GET | `/api/v1/notifications/preferences` | 登录用户 | 获取通知类型偏好 |
| PUT | `/api/v1/notifications/preferences` | 登录用户 | 更新通知类型偏好 |
| GET | `/api/v1/notifications/stream?accessToken=` | 登录用户 | SSE 实时通知变更通道 |
| POST | `/api/v1/notifications/{notificationId}/read` | 通知所有者 | 标记单条已读 |
| POST | `/api/v1/notifications/read-all` | 登录用户 | 标记当前用户全部通知已读 |

**Notification 字段:** `id`、`userId`、`teamId`、`type`、`titleKey`、`contentKey`、`messageParams`、`link`、`readAt`、`createdAt`、`isRead`。

示例：

```json
{
  "id": 53,
  "userId": 126,
  "teamId": 42,
  "type": "TEAM_INVITE_ACCEPT",
  "titleKey": "notification.team_invite_accept.title",
  "contentKey": "notification.team_invite_accept.content",
  "messageParams": {
    "teamName": "产品组",
    "userName": "zhangsan"
  },
  "link": "/teams/42/settings",
  "readAt": null,
  "createdAt": "2026-07-13T18:34:59",
  "isRead": false
}
```

通知偏好为用户级配置，未设置的类型默认开启。关闭某个类型后，后续该类型不再生成站内通知或邮件通知；团队审计不受影响。

**PUT /preferences Request Body:**
```json
{
  "preferences": {
    "TEAM_INVITE": false,
    "TEAM_QUOTA_ALERT": true,
    "TEAM_OFFICE_SAVE_FAILED": true
  }
}
```

**Preferences Response:**
```json
{
  "TEAM_INVITE": false,
  "TEAM_INVITE_ACCEPT": true,
  "TEAM_INVITE_REJECT": true,
  "TEAM_QUOTA_ALERT": true,
  "TEAM_ARCHIVE": true,
  "TEAM_UNARCHIVE": true,
  "TEAM_RESTORE": true,
  "TEAM_PERMISSION_UPDATE": true,
  "TEAM_DELETE": true,
  "TEAM_MEMBER_ADD": true,
  "TEAM_ROLE_UPDATE": true,
  "TEAM_MEMBER_REMOVE": true,
  "TEAM_OWNER_TRANSFER": true,
  "TEAM_OFFICE_SAVE_FAILED": true
}
```

`GET /stream` 返回 `text/event-stream`。浏览器 `EventSource` 无法设置 `Authorization` header，因此该端点接受当前 access token 的 `accessToken` 查询参数；服务端仍按登录用户鉴权。连接建立后会发送 `connected` 事件，通知创建、标记已读或全部已读后会发送 `notifications-changed` 事件：

```text
event: notifications-changed
data: {"unreadCount":3}
```

**错误码:** 400（不支持的通知偏好类型）、403（读取他人通知）、404（通知不存在）。

---

## 十八、OnlyOffice 文档 `/api/v1/office` 和 `/api/v1/teams/{teamId}/office`

OnlyOffice 支持 `docx`、`pptx`、`xlsx` 等 Office 文件。个人文件只读预览；团队文件根据团队角色和文件级有效权限返回只读或可编辑配置，保存通过 Document Server callback 入队，再由后台 worker 下载编辑结果并写入新版本。

### 18.1 配置接口

| 方法 | 路径 | 权限 | 响应 |
|------|------|------|------|
| GET | `/api/v1/office/files/{fileId}/config` | 文件所有者 | 个人只读 `OfficeConfigResponse` |
| GET | `/api/v1/teams/{teamId}/office/files/{fileId}/config` | 团队成员 | 团队 `OfficeConfigResponse`，OWNER/ADMIN/EDITOR 为可编辑 |

**OfficeConfigResponse:**
```json
{
  "documentServerUrl": "http://localhost:8082",
  "editable": false,
  "config": {
    "documentType": "word",
    "document": {
      "fileType": "docx",
      "key": "stable-document-key",
      "title": "test.docx",
      "url": "http://backend:8080/api/v1/office/files/7/content?token=..."
    },
    "editorConfig": {
      "mode": "view",
      "callbackUrl": null
    }
  }
}
```

### 18.2 内容和回调接口

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/office/files/{fileId}/content?token=` | 有效内容 token | 个人文件内容流 |
| GET | `/api/v1/teams/{teamId}/office/files/{fileId}/content?token=` | 有效内容 token + 团队可读 | 团队文件内容流 |
| POST | `/api/v1/teams/{teamId}/office/files/{fileId}/callback?token=` | 有效回调 token + 团队可写 | OnlyOffice 保存回调 |

**Callback Request Body:**
```json
{
  "status": 2,
  "url": "http://onlyoffice-documentserver/cache/files/data/edit/output.docx/output.docx",
  "key": "document-key",
  "error": 0,
  "users": ["2"],
  "token": "<document-server-jwt>"
}
```

`status` 为 `2` 或 `6` 时，后端验证 callback token、团队写权限和幂等记录后立即创建 `ONLYOFFICE_SAVE` 团队任务并返回 `{ "error": 0 }`；后台 worker 再从 `url` 下载编辑结果并调用团队版本服务保存为新版本。其他状态返回 `{ "error": 0 }` 但不写版本。个人文件没有 callback，始终只读。

保存回调具有幂等保护：

- callback token 内包含配置生成时的 `document.key`，回调请求体中的 `key` 必须一致。
- 后端按 `teamId + fileId + key + status + urlHash` 记录保存回调；重复投递同一个回调时直接返回 `{ "error": 0 }`，不会重复入队或生成版本。
- 保存版本时仍会锁定文件行，保证并发保存的版本号顺序可预测。
- 若后台下载或保存失败，callback 记录进入失败状态，关联团队任务进入 `FAILED`，写入 `OFFICE_SAVE_FAILED` 团队失败审计，并向编辑用户发送 `TEAM_OFFICE_SAVE_FAILED` 通知；用户在团队任务中心重试后会重新置为 `PENDING` 并由 worker 再次处理。

**错误码:** 400（非 Office 文件或 callback 参数无效）、403（token 无效或团队不可写）、404001（文件不存在）、500003（下载编辑结果失败）。
