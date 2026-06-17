# 数据与存储概要设计

## 目标

数据与存储层负责保存系统事实数据、短期状态、文件内容和日志数据。设计目标是让业务元数据强一致、文件内容可扩展、短期状态高效、日志可检索，并支持 Docker Compose 与生产拆分部署。

## 组件边界

| 组件 | 保存内容 | 生命周期 | 是否业务真相 |
|------|------|------|------|
| PostgreSQL | 用户、文件、分享、版本、上传会话、审计记录 | 长期 | 是 |
| Redis | 验证码、token 黑名单、上传分片、锁、计数、限流 | 短期或可重建 | 否 |
| S3 兼容存储 | 文件对象、版本对象、ZIP 缓存、缩略图 | 长期或缓存 | 文件内容真相 |
| 后端日志目录 | runtime/audit JSON 日志 | 按策略保留 | 排障/审计副本 |
| Loki | 日志索引和查询 | 按策略保留 | 否 |
| Tempo | Trace 数据 | 按策略保留 | 否 |

## PostgreSQL 模型

### `users`

保存用户认证资料、角色、状态、邮箱、配额和 WebDAV 设置。

关键字段：

- `username`：唯一用户名。
- `password_hash`：登录密码 BCrypt 哈希。
- `role`：`ROLE_USER` 或 `ROLE_ADMIN`。
- `status`：用户启用、禁用或冻结状态。
- `storage_quota` / `storage_used`：配额和已用空间。
- `webdav_enabled` / `webdav_password_hash`：WebDAV 独立开关和密码。

设计约束：

- 登录密码和 WebDAV 密码是两套凭据。
- 管理员调整配额只改 `storage_quota`，上传时按最新配额校验。

### `files`

保存文件树。文件和文件夹共表，使用邻接表模型。

关键字段：

- `user_id`：资源所有者。
- `parent_id`：父文件夹，`null` 表示根目录。
- `file_type`：`file` 或 `folder`。
- `storage_path`：对象存储 key，仅文件有值。
- `md5_hash`：秒传和去重辅助。
- `is_deleted` / `deleted_at`：回收站状态。
- `version_count`：版本数量。

设计约束：

- 任何文件查询都必须带用户上下文或管理员上下文。
- 普通用户不能跨用户读取文件树。
- 文件夹递归操作应避免形成循环。

### `file_versions`

保存历史版本，每个版本指向一个对象存储 key。

设计约束：

- 版本号在同一文件内递增。
- 删除文件时需要同步处理版本对象。
- 不能删除唯一版本。

### `share_links`

保存分享入口。

关键字段：

- `share_code`：公开访问短码。
- `password_ciphertext`：分享密码密文。
- `expire_at`：过期时间。
- `max_visits` / `visit_count`：访问次数限制。
- `download_count`：下载统计。
- `is_canceled`：取消状态。

设计约束：

- 密码字段语义是密文，不是哈希。
- 密文必须包含或关联初始化向量。
- 公开访问必须始终重新校验分享状态。

### `upload_sessions`

保存分片上传会话。

关键字段：

- `id`：上传会话 ID。
- `user_id`、`parent_id`、`file_name`：目标位置。
- `file_size`、`md5_hash`、`chunk_size`、`total_chunks`：文件和分片信息。
- `received_chunks`：已收分片统计。
- `status`：上传中、完成或过期。

设计约束：

- Redis 中的分片 bitmap 是高频状态，数据库保存会话事实。
- 会话过期后客户端不能继续上传。

### `folder_zip_download_cache`

保存网页端文件夹 ZIP 缓存对象。

设计约束：

- 缓存以用户、文件夹和内容签名识别。
- 每次下载刷新 `last_downloaded_at`。
- 清理任务按最后下载时间删除缓存。

### `operation_logs`

保存审计日志，按月分区。

关键字段：

- `user_id`、`action_type`、`target_type`、`target_id`。
- `detail`：脱敏 JSON。
- `request_id`、`trace_id`。
- `status_code`、`error_code`、`duration_ms`。
- `created_at`：分区键。

设计约束：

- 审计日志应可用于管理后台查询。
- 分区创建和清理由定时任务维护。
- 敏感字段必须脱敏。

### `user_tokens`

保存 JWT 元数据和吊销状态。

设计约束：

- `jti` 唯一。
- refresh token 轮换后旧 token 失效。
- 过期 token 可定期清理。

## Redis 数据职责

Redis 不作为长期业务事实。它适合保存：

- 邮箱验证码和密码重置验证码。
- token 黑名单或吊销快速判断。
- 上传分片接收状态。
- 配额增量计数。
- 分享访问计数临时值。
- WebDAV lock token。
- 限流计数。

设计要求：

- 每类 key 应有明确 TTL。
- Redis 丢失后系统应能降级或从 PostgreSQL 重建关键状态。
- 不能只把唯一业务状态放在 Redis。

## 对象存储

对象存储保存真实文件内容。后端通过 `StorageService` 访问，不让客户端直接绕过业务权限访问。

对象类型：

- 普通文件对象。
- 文件历史版本对象。
- 文件夹 ZIP 缓存对象。
- 未来缩略图对象。
- 上传过程中的临时分片或中间对象。

设计要求：

- 对象 key 应避免用户可控路径直接拼接。
- 文件元数据删除和对象删除之间需要补偿清理。
- 生产环境应开启对象存储备份或高可靠策略。

## 一致性与恢复

| 场景 | 一致性风险 | 恢复策略 |
|------|------|------|
| 上传完成后数据库写失败 | 对象存储出现孤儿对象 | 清理任务按临时对象或无引用对象清理 |
| 数据库文件删除成功但对象删除失败 | 对象存储残留 | 后台重试或定期扫描 |
| Redis 分片状态丢失 | 客户端无法准确续传 | 查询数据库会话，必要时重新上传 |
| 配额 Redis 同步失败 | `storage_used` 短期不准 | 定时全量或增量修复 |
| ZIP 缓存对象丢失 | 下载缓存记录失效 | 重新生成 ZIP |

## 备份策略

优先级：

1. PostgreSQL。
2. 对象存储。
3. 密钥文件，尤其 `SHARE_PASSWORD_ENCRYPTION_KEY`。
4. Redis 持久化数据。
5. 审计和运行日志。

恢复顺序：

1. 恢复密钥。
2. 恢复 PostgreSQL。
3. 恢复对象存储。
4. 恢复 Redis 或允许缓存重建。
5. 恢复日志平台数据。

## 扩展建议

- 新表应通过 Flyway 管理，不依赖 Hibernate 自动建表。
- 新缓存 key 应记录用途、TTL 和可重建方式。
- 新对象类型应明确对象 key 规则和清理策略。
- 多租户或团队空间后续应优先扩展 `files` 的归属模型，而不是复制文件表。
