# 认证与账号 API

本文定义注册、登录、令牌、账号偏好和密码重置接口。跨域通用的认证头、响应格式与错误码见 [API 总入口](../API.md)。

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
  "verificationCode": "123456",    // 必填，6 位邮箱验证码
  "preferredLocale": "zh-CN"       // 可选，支持 en-US、zh-Hans、zh-CN、zh-SG
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
  "email": "zhangsan@example.com",
  "preferredLocale": "zh-Hans"
}
```

`preferredLocale` 可选，用于选择验证码邮件语言；省略时使用服务端默认语言。

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
    "preferredLocale": "zh-Hans",
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
  "messageKey": "error.badRequest",
  "messageParams": {}
}
```

---

### 1.9 更新语言偏好

```
PUT /api/v1/auth/preferences/locale
Authorization: Bearer <accessToken>
```

**Request Body:**

```json
{
  "preferredLocale": "en-US"
}
```

当前支持 `en-US`、`zh-Hans`、`zh-CN` 和 `zh-SG`。成功后返回更新后的当前用户资料，结构同 `GET /api/v1/auth/me`。CN、SG 会作为精确账号偏好保存；Web/Tauri 与 Grafana 渲染时兼容映射到 `zh-Hans`。管理员修改语言时，后端还会尽力同步 Grafana；同步失败不影响主应用偏好保存。

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


## 十四、注册验证码与密码重置 `/api/v1/auth`

### 14.1 发送注册验证码

```
POST /api/v1/auth/register-code/send
```

无需认证。向待注册邮箱发送 6 位数字验证码。验证码有效期 10 分钟，存储在 Redis 中。

**Request Body:**
```json
{
  "email": "zhangsan@example.com",
  "preferredLocale": "zh-Hans"
}
```

`preferredLocale` 可选，用于选择验证码邮件语言。**错误码:** 409（邮箱已存在）、400（邮箱格式错误或语言值无效）

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
