# 文件管理 API

本文定义个人文件和文件夹的浏览、元数据、创建、重命名、移动、复制、删除、回收站、搜索与 WebDAV 接口。上传下载见 [传输 API](TRANSFERS.md)，收藏、标签和版本见 [文件组织 API](ORGANIZATION.md)，跨域通用约定见 [API 总入口](../API.md)。

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
| sortBy | String | 否 | fileName | `fileName`、`updatedAt` 或 `fileSize`；未知值回退 `fileName` |
| order | String | 否 | asc | `asc` 或 `desc`；未知值回退 `asc` |

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

> 文件夹始终排在文件前面，随后按请求字段和方向排序。排序值相同时再按 `fileName` 升序、ID 升序，因此连续分页不会因同值记录而重复或遗漏；`updatedAt` 为空的记录始终位于该组末尾。

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
  "messageKey": "error.fileNotFound",
  "messageParams": {}
}
```

---

### 2.3 创建文件夹

`folderName` 会先去除首尾空白，规范化后的长度必须为 1～255。`parentId` 必须指向当前用户拥有、未删除的个人文件夹；文件和文件夹在同一父目录共用名称空间。

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
  "messageKey": "error.fileNameConflict",
  "messageParams": {}
}
```

---

### 2.4 重命名

`newName` 使用与创建文件夹相同的修剪、长度和同目录冲突规则。规范化后与当前名称相同时按幂等成功处理，不修改更新时间。

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

目标必须是当前用户拥有、未删除的个人文件夹。移动到当前父目录按幂等成功处理；文件夹不能移动到自身或其后代，否则返回 `400 / error.invalidFileDestination`。目标目录存在同名文件或文件夹时返回 `409001 / error.fileNameConflict`。

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

> 普通文件和文件夹均可复制；文件夹会递归复制完整的未删除子树，并按子树总大小执行现有配额检查。目标名称在源名称后追加 ` (copy)`；超过 255 字符时先安全截断源名称再追加后缀。目标已有该名称时不自动编号，返回 `409001 / error.fileNameConflict`。文件夹不能复制到自身或其后代，否则返回 `400 / error.invalidFileDestination`。

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

> 服务端先对 `fileIds` 去重，再在同一事务中执行软删除；任一项目无权访问或不存在时整批失败。删除的文件进入回收站，30 天后自动清理。文件夹及其所有子文件沿用现有回收站链路。

个人文件创建、重命名、移动和复制在同一用户内串行执行，避免并发请求绕过名称冲突检查；URL、请求体与 Web/Tauri 调用方式保持不变。

---


## 五、回收站 `/api/v1/recycle-bin`

### 5.1 回收站列表

```
GET /api/v1/recycle-bin?q=photo&page=1&size=20
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

`q` 会先去除首尾空白并按名称匹配。列表只返回当前用户个人空间的“删除根”，固定按 `deletedAt DESC, id DESC` 排序；删除文件夹时，其后代不会作为独立回收站记录出现。响应使用客户端 DTO，不包含 `storagePath`、`userId` 等内部字段。

文件夹软删除会让整棵活动子树从直接访问、搜索、收藏、标签、分享、预览和下载中隐藏。回收站内容不计入已用容量，恢复时按当前子树大小重新校验配额。

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

原父目录仍存在且可用时恢复到原位置，否则恢复到个人根目录。目标位置已有同名项目时返回 `409001 / error.fileNameConflict`，配额不足返回 `419001`，失败时项目继续留在回收站。

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

> 物理删除，不可恢复。文件夹会递归删除整棵子树、缩略图、当前对象和历史版本对象。

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

> 物理删除回收站中当前用户的所有删除根，不可恢复。手动清空和定时过期清理复用同一递归删除语义。

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

协议方法发送到 `/webdav/` 或 `/webdav/{path}`；例如可用 `OPTIONS /webdav/` 查询服务器支持的方法和 DAV 级别。

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
