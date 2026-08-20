# 密钥与凭据轮换

本文说明当前实现中各类密钥的消费者、轮换影响和安全顺序。变量的精确定义见[配置参考](../reference/CONFIGURATION.md)。当前实现普遍不支持新旧密钥并行校验，因此轮换会使现有会话或密文失效，应在独立维护窗口执行。

## 清单和影响

| 凭据 | 主要消费者 | 直接影响 |
|------|------------|----------|
| `JWT_ACCESS_SECRET` | 后端 JWT 签发/校验 | 所有现有 access token 立即失效 |
| `JWT_REFRESH_SECRET` | 后端 refresh token 签发/校验 | 所有现有 refresh token 失效，用户必须重新登录 |
| `SHARE_PASSWORD_ENCRYPTION_KEY` | 后端 AES-GCM 分享密码加解密 | 旧 `password_ciphertext` 无法用新密钥解密 |
| `ONLYOFFICE_JWT_SECRET` | 后端、OnlyOffice Document Server | 已打开编辑会话、配置 token、内容 token 和回调可能失败 |
| `GRAFANA_AUTH_PROXY_SECRET` | 后端 Grafana session cookie | 现有 Grafana 会话 cookie 失效 |
| PostgreSQL 密码 | PostgreSQL、后端、备份任务 | 不同步会导致应用和备份连接失败 |
| RabbitMQ 密码 | RabbitMQ、后端 | 不同步会停止消息发布和消费；只改容器环境不会修改已有 RabbitMQ 用户 |
| S3 access/secret key | SeaweedFS/S3、后端 | 不同步会使上传、下载、缩略图、版本和任务结果失败 |
| SMTP 凭据 | 后端、邮件服务 | 注册验证码、邀请和密码重置邮件失败 |
| Grafana 管理员密码 | Grafana 初始化/运维 | 已初始化卷不会因为只改环境变量而可靠地更新账号密码 |

## 通用流程

1. 确认轮换原因、影响范围、维护窗口、负责人和回退密钥保管人。
2. 完成 PostgreSQL、对象存储和当前密钥的加密备份；验证备份校验和。
3. 停止前端、后端和 OnlyOffice，避免轮换期间产生使用混合密钥的新数据。
4. 在密钥管理系统或受控配置中生成新版本，不把明文写入 Git、聊天、工单或 shell 历史。
5. 按各凭据的服务端和客户端顺序更新，使用同一 Compose project name 重建消费者。
6. 验证登录、上传下载、分享、Office、Grafana、队列和邮件。
7. 只有在回退窗口结束且确认没有旧消费者后，才撤销旧凭据。

当前 Compose 从多个文件读取配置。轮换前先列出实际消费者，不要只修改 `.env.production`：

- `.env.production`：OnlyOffice 和公开地址插值。
- `config/env-files/security.env`：JWT 和分享密码密钥。
- `config/env-files/backend.env`：OnlyOffice 后端配置。
- `database.env`、`rabbitmq.env`、`storage.env`、`mail.env`、`observability.env`：对应基础设施和后端客户端配置。

## JWT access 与 refresh

后端只使用当前单个 access secret 和单个 refresh secret，没有 key ID 或双密钥校验窗口。两者应生成不同的 Base64 随机值：

```bash
openssl rand -base64 32
```

轮换顺序：

1. 停止前端和后端入口。
2. 同时更新 `JWT_ACCESS_SECRET` 与 `JWT_REFRESH_SECRET`，不要复用同一个值。
3. 重建后端并检查启动日志。
4. 确认旧 access/refresh token 均返回 401，新登录和刷新成功。
5. 通知用户重新登录。

数据库 `user_tokens` 中可能仍保留旧 token 的 JTI 记录，但更换签名密钥后旧 token 已无法通过签名校验。不要把清理数据库记录当作轮换成功的必要条件。

## 分享密码加密密钥

`SHARE_PASSWORD_ENCRYPTION_KEY` 必须解码为 32 字节，当前实现使用 AES-GCM，密文没有 key ID，也没有旧密钥回退。直接更换后，已有密码保护分享的密码无法读取或验证。

因此它不是普通的定期无损轮换项：

- 常规维护应稳定保存该密钥，并把它与数据库备份绑定为同一个恢复集。
- 泄露但数据库未泄露时，评估是否先取消所有密码保护分享，再更换密钥并由所有者重新创建密码。
- 必须保留既有分享时，需要先实现受审计的离线重加密工具，在同一事务/维护窗口用旧密钥解密并用新密钥加密；仓库当前没有该工具。
- 误换密钥后应立即停止写入并恢复旧密钥，不要让部分分享以新密钥继续写入形成混合密文。

## OnlyOffice JWT

`ONLYOFFICE_JWT_SECRET` 同时由 Document Server 和后端使用，且后端要求至少 32 字节。根 `.env.production` 与 `backend.env` 中的值应保持一致。

轮换前要求用户保存并关闭正在编辑的文档，然后：

1. 停止新的编辑入口和后端回调处理。
2. 同时更新生产 Compose 插值和后端 env 文件。
3. 重建 `onlyoffice-documentserver` 与 `backend`。
4. 检查 Document Server `/healthcheck`、编辑配置、内容读取和保存回调。
5. 新建一次真实编辑会话并确认形成新版本。

旧配置 token 和仍在浏览器中的编辑会话会失效，这是当前设计的预期影响。

## Grafana Auth Proxy

`GRAFANA_AUTH_PROXY_SECRET` 只由后端签发和校验短期 Grafana session cookie。更换后重建后端，现有 cookie 失效，管理员需要从 Better Cloud Drive 管理入口重新进入 Grafana。轮换后验证：

- 未登录访问 `/grafana/` 被拒绝。
- 普通用户不能创建管理员 Grafana 会话。
- 管理员重新进入后身份、角色和语言同步正常。

## PostgreSQL、RabbitMQ 和对象存储

这些凭据同时存在于服务端和后端客户端，必须分阶段切换：

### PostgreSQL

1. 停止应用写入并确认备份任务暂停。
2. 在 PostgreSQL 内修改应用用户密码。
3. 立即更新 `POSTGRES_PASSWORD`、`DB_PASSWORD` 以及备份系统凭据。
4. 重建后端，验证连接池、Flyway 历史和备份任务。

首次初始化变量只在创建数据库卷时生效；对已有 `pgdata` 仅修改 env 文件不会改变数据库内部密码。

### RabbitMQ

1. 停止后端发布和消费。
2. 使用 RabbitMQ 管理接口或 `rabbitmqctl change_password` 修改已有用户。
3. 更新 `RABBITMQ_DEFAULT_PASS` 和 `RABBITMQ_PASSWORD`。
4. 重建后端并确认 ready、in-flight、数据库 pending 和死信恢复正常。

`RABBITMQ_DEFAULT_PASS` 主要用于首次初始化；已有 `rabbitmqdata` 卷不会因 env 变化自动修改用户。

### S3/SeaweedFS

优先使用存储服务支持的双 access key 方式：先创建新 key，更新后端并验证，再撤销旧 key。若当前 SeaweedFS 配置不支持双 key，应停止写入后同时更新服务端与 `STORAGE_S3_ACCESS_KEY`、`STORAGE_S3_SECRET_KEY`。至少验证上传、Range 下载、缩略图、历史版本和团队 ZIP 任务。

## 轮换验收与回退

轮换记录至少包括变量名、旧/新版本标识、消费者、开始/结束时间、影响用户、验证结果和旧凭据撤销时间；记录中不得出现明文。

出现以下情况应停止并回退：

- 后端无法启动或数据库/队列/存储客户端持续认证失败。
- 只有部分实例或任务使用新凭据。
- 分享密码出现新旧密文混用。
- OnlyOffice 能打开但不能保存，或回调持续 401。

回退使用受控保存的上一密钥版本，并重新验证所有消费者。若凭据因泄露而轮换，旧值只能用于短时恢复服务，必须立即制定新的完整轮换窗口。
