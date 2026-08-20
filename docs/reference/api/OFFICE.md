# OnlyOffice API

本文定义个人和团队文档的 OnlyOffice 配置、内容读取及保存回调接口。通用约定见 [API 总入口](../API.md)，部署与运行注意事项见 [OnlyOffice 运维说明](../../operations/ONLYOFFICE_DEPLOYMENT.md)。

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
