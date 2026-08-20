# 收藏、标签与版本 API

本文定义个人文件的收藏、标签关联和历史版本接口。通用约定见 [API 总入口](../API.md)。

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

> 如果文件仅剩一个版本，删除操作返回 409（不能删除唯一版本）。`419009` 表示创建新版本时达到服务端版本数量上限，不用于删除冲突。

---

### 13.3 下载版本

```
GET /api/v1/files/{fileId}/versions/{versionNumber}/download
Authorization: Bearer <accessToken>
```

仅文件所有者可下载。成功时返回二进制附件，文件名以 `v{versionNumber}_` 为前缀；响应不是统一 JSON 信封。

---

### 13.4 恢复版本

```
POST /api/v1/files/{fileId}/versions/{versionNumber}/restore
Authorization: Bearer <accessToken>
```

恢复指定历史版本为当前内容，并返回更新后的 `FileEntity`。恢复过程会保留恢复前的当前内容，使其仍可作为历史版本访问。

---
