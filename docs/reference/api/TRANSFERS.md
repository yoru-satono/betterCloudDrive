# 上传、下载与预览 API

本文定义个人文件的分片上传、秒传、下载 ticket、文件夹打包、预览和缩略图接口。通用约定见 [API 总入口](../API.md)，团队文件传输接口见 [团队协作 API](TEAMS.md)。

## 三、文件上传 `/api/v1/upload`

### 3.1 统一初始化上传

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
  "md5Hash": "d41d8cd98f00b204e9800998ecf8427e" // 必填，32 位十六进制完整文件 MD5
}
```

服务端先完成身份、目录、权限、名称及配额检查，再核验秒传候选。有效候选直接创建文件并返回 `INSTANT`；未命中则创建会话并返回 `MULTIPART`。两种结果仅包含各自字段。请求不再包含 `totalChunks`，由服务端按 5 MiB（5242880 字节）向上取整计算；客户端按响应的 `chunkSize`、`totalChunks` 分片。摘要接受大小写，内部统一保存小写。

零字节文件也使用此接口。返回 `MULTIPART` 时 `totalChunks` 为 0，直接调用 `complete`，不上传分片；返回 `INSTANT` 时已经完成。

**Response `200`（分片上传）:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {
    "mode": "MULTIPART",
    "sessionId": "a1b2c3d4-e5f6-...",
    "chunkSize": 5242880,
    "totalChunks": 2
  }
}
```

**Response `200`（秒传完成）:**
```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {"mode": "INSTANT", "fileId": 43}
}
```

客户端必须拒绝未知 `mode`、缺少必要字段、分支字段混用或不合法分片形状的响应，不能将其视为完成。

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
| chunkMd5 | String | 是 | 当前分片内容的 32 位十六进制 MD5 |

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

> 服务端检查分片的预期长度及实际 MD5，验证后才更新进度。同一编号重复上传会核验已存分片，相同内容幂等返回，不同内容返回 `419007` 且保留原分片。服务端使用 Redis bitmap 记录已接收分片，bitmap 有效期为 24 小时。

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

恢复时先调用 `GET /api/v1/upload/{sessionId}/status`，只补传 `missingChunks` 中的分片，全部分片完成后再调用 `complete`。合法会话恢复时不再次初始化；`complete` 响应丢失时仍用原会话幂等恢复。会话失效后，Web 可重新调用统一 `/init`，并处理 `INSTANT` 和 `MULTIPART` 两种结果，Web 可续传记录保留 24 小时。Qt 持久保存任务，恢复时核对原会话；会话失效明确报错，不静默创建新会话，需用户重新发起。

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

未知或内部异常返回 `error.internal`；Web/Qt 与 KMP 移动端使用各自本地资源显示通用提示，生产 UI 不直接展示未知 key。

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

### 3.6 秒传核验与升级

秒传由 `/init` 自动尝试。按 MD5 和文件大小查找候选，再流式读取源对象核验实际大小与摘要；候选不存在或内容不符属于正常未命中，返回 `MULTIPART`，存储服务故障仍返回存储错误。秒传不创建上传会话；文件提交后才累计容量，并沿用缩略图及团队 `UPLOAD_INSTANT` 审计。会话初始化不累计容量。

个人及团队的 `/upload/instant` 已删除，已认证请求返回 HTTP `404`、API `404`。`419010` 已停用并保留编号，不得复用。旧客户端须与后端协调升级：只调用一次 `/init`，移除请求 `totalChunks` 和秒传未命中回退逻辑。秒传资源复用范围不变，MD5 不作为内容所有权证明。

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

> 该接口仅支持普通文件。对文件夹调用会返回 `404001`，文件夹应使用 ZIP ticket 或由 Qt 客户端直接递归下载。

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

以附件形式下载。浏览器端通常先创建 ticket，再使用返回的 URL 触发下载；Qt 客户端可直接携带 `Authorization: Bearer <accessToken>` 下载。

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

Qt 客户端的文件夹下载不走 ZIP 打包接口，而是递归列出文件夹内容并逐个文件下载到用户选择的本地目录。

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

### 上传完整性与恢复约定

`init.md5Hash` 与 `chunkMd5` 必须是 32 位十六进制摘要；缺失或格式非法返回 `400`。不满足摘要和分片形状约定的旧会话，在状态、分片和完成接口返回 `419006`，客户端必须重新初始化。旧客户端需要与后端协调升级。

合并时服务端流式计算实际字节数和整文件 MD5，通过后才创建文件记录；零字节文件仍核验空内容 MD5。分片与会话完成使用数据库锁串行协调。临时分片和位图保留到数据库提交成功，校验失败保留续传数据，回滚清理本次最终对象。

| 错误码 | 语义 key | 客户端恢复 |
|--------|----------|------------|
| 419007 | `error.chunkMd5Mismatch` | 停止当前上传，保留会话，允许重试分片 |
| 419016 | `error.fileMd5Mismatch` | 停止自动重试，取消旧会话，用户重试时重新计算摘要和初始化 |
| 419017 | `error.uploadSizeMismatch` | 与整文件摘要不匹配采用相同恢复流程 |

流式上传始终计算实际大小与 MD5；调用方提供摘要时必须匹配。网络和存储暂时故障保留分片上传的续传状态。
