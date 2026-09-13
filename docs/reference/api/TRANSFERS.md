# 上传、下载与预览 API

本文定义个人文件的分片上传、秒传、下载 ticket、文件夹打包、预览和缩略图接口。通用约定见 [API 总入口](../API.md)，团队文件传输接口见 [团队协作 API](TEAMS.md)。

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
  "messageKey": "error.storageQuotaExceeded",
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

`messageKey` 是稳定语义键，`messageParams` 是客户端本地化插值参数。后端不返回旧 `message` 字段，也不会把异常诊断文本序列化给客户端。业务代码和自动化测试应优先使用 `code`、对象 `type/status` 或稳定 key 判断状态，不得按中文翻译分支。完整约定见 [客户端本地化与语义文案协议](../LOCALIZATION.md)。

未知或内部异常返回 `error.internal`；Web/Tauri 与 KMP 移动端使用各自本地资源显示通用提示，生产 UI 不直接展示未知 key。

`complete` 支持幂等重试：同一 `sessionId` 第一次成功后会记录产物文件 ID，后续重复调用返回同一个 `fileId`，不会重复合并对象、重复创建文件或重复累计存储配额。

**Response `419006`（分片不完整）:**
```json
{
  "code": 419006,
  "messageKey": "error.chunkUploadInvalid",
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
  "messageKey": "error.storageQuotaExceeded",
  "messageParams": {}
}
```

**Response `419010`（无匹配文件，需走分片上传）:**
```json
{
  "code": 419010,
  "messageKey": "error.instantUploadNotFound",
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
