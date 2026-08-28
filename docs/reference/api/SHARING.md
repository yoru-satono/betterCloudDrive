# 分享 API

本文定义个人分享的创建、管理、公开访问、保存和下载接口。跨域通用约定见 [API 总入口](../API.md)。

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
    "shareUrl": "https://drive.example.com/s/aB3xK9mW",
    "hasPassword": true,
    "expireAt": "2026-07-09T10:00:00",
    "expireAtEpochMs": 1783562400000,
    "maxVisits": 10,
    "downloadCount": 0,
    "visitCount": 0,
    "isCanceled": false,
    "createdAt": "2026-06-09T12:00:00",
    "updatedAt": "2026-06-09T12:00:00",
    "fileName": "report.pdf",
    "fileType": "file",
    "fileSize": 1048576
  }
}
```

> 分享密码在数据库中以加密密文保存，API 响应只返回 `hasPassword`，不会返回密文或原始密码。`downloadCount` 当前会在公开下载或保存分享内容时递增，`maxVisits` 会限制公开访问详情、浏览内容、下载和保存分享内容。
> `shareUrl` 是由后端根据 `APP_PUBLIC_URL` 生成的 canonical 链接，客户端不应推断 Web 端口或域名。`expireAtEpochMs` 是明确时区的 epoch 毫秒值；`expireAt` 保留给旧客户端。根项目已删除时 `fileName`、`fileType`、`fileSize` 为 `null`。
> 分享密码会先去除首尾空白，提供时长度必须为 4-16 个字符；`maxVisits` 必须是正整数。

---

### 10.2 列出我的分享

```
GET /api/v1/shares?q=&passwordProtected=&expirationStatus=&page=1&size=20
Authorization: Bearer <accessToken>
```

分页返回当前用户创建、尚未取消且不属于团队的分享。`q` 去除首尾空白，只匹配分享码和数字文件 ID，不解析“有密码”“已过期”等状态词；密码状态使用独立布尔参数 `passwordProtected`，过期状态使用 `expirationStatus=ACTIVE|EXPIRED|NEVER`。结果固定按 `createdAt DESC, id DESC` 排序，跨页稳定；列表中的文件元数据批量读取。

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

取消操作幂等。取消后分享码立即失效，已获取链接的用户无法继续访问。

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
