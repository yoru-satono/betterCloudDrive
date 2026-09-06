# 通知 API

本文定义通知查询、已读操作以及一次性 ticket 驱动的 SSE 事件流。通用认证和响应约定见 [API 总入口](../API.md)。

## 十七、通知 `/api/v1/notifications`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/notifications?unreadOnly=&type=&page=1&size=20` | 登录用户 | 分页列出站内通知 |
| GET | `/api/v1/notifications/unread-count` | 登录用户 | 返回未读数量 |
| GET | `/api/v1/notifications/preferences` | 登录用户 | 获取通知类型偏好 |
| PUT | `/api/v1/notifications/preferences` | 登录用户 | 更新通知类型偏好 |
| POST | `/api/v1/notifications/stream-ticket` | 登录用户 | 创建一次性 SSE ticket |
| GET | `/api/v1/notifications/stream?ticket=` | 有效 ticket | SSE 实时通知变更通道 |
| POST | `/api/v1/notifications/{notificationId}/read` | 通知所有者 | 标记单条已读 |
| POST | `/api/v1/notifications/read-all` | 登录用户 | 标记当前用户全部通知已读 |

**Notification 字段:** `id`、`userId`、`teamId`、`type`、`titleKey`、`contentKey`、`messageParams`、`link`、`readAt`、`createdAt`、`isRead`。

列表只返回当前用户的安全 DTO，固定按 `createdAt DESC, id DESC` 排序；同一创建时间下以 ID 保证分页稳定。`unreadOnly=true` 只返回未读项，`type` 会修剪并按大写通知类型精确匹配。客户端必须使用 `titleKey`、`contentKey` 和 `messageParams` 本地化显示，未知键不得直接暴露给用户。

示例：

```json
{
  "id": 53,
  "userId": 126,
  "teamId": 42,
  "type": "TEAM_INVITE_ACCEPT",
  "titleKey": "notification.teamInviteAccept.title",
  "contentKey": "notification.teamInviteAccept.content",
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

浏览器 `EventSource` 无法设置 `Authorization` header，因此客户端先携带 Bearer Token 调用 `POST /stream-ticket`。响应包含一次性 `ticket` 和 `expiresAt`；ticket 30 秒后过期，并在首次连接时消费，不能重复使用。随后用 `GET /stream?ticket=` 建立 `text/event-stream` 连接。连接建立后会发送 `connected` 事件，通知创建、标记已读或全部已读后会发送 `notifications-changed` 事件：

```text
event: notifications-changed
data: {"unreadCount":3}
```

SSE ticket 属于敏感查询参数，不得写入日志、持久状态或验收报告。移动端只在用户已登录且应用处于前台时保持连接；进入后台会立即断开，恢复前台时先刷新未读数，再为每次重连申请新 ticket。

**错误码:** 400（不支持的通知偏好类型）、403（读取他人通知）、404（通知不存在）。

---
