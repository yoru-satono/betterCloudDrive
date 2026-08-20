# ADR-007：OnlyOffice 保存采用异步持久任务

- 状态：已接受
- 日期：2026-08-20（对既有实现的追认记录）
- 适用范围：OnlyOffice 回调、团队任务、对象存储和文件版本

## 背景与约束

OnlyOffice 保存回调由外部 Document Server 发起，可能重复投递，回调中的内容 URL 具有时效性。下载编辑结果、写入对象存储和创建版本可能耗时或失败，不适合让外部回调连接一直等待全部工作完成。

Document Server 自身卷不是 Better Cloud Drive 的版本事实来源；成功保存必须最终体现为 S3 对象、`file_versions`、任务状态、审计和通知。

## 决策

回调 HTTP 请求只负责 JWT/状态校验、以复合唯一键预留 `onlyoffice_callbacks`，并创建或关联 `ONLYOFFICE_SAVE` 持久任务，然后及时响应接收结果。

后台 Worker 原子领取任务，从回调 URL 下载内容，通过统一版本服务写入对象存储和 PostgreSQL，再更新回调、任务、审计与通知。RabbitMQ 只负责唤醒；数据库扫描提供恢复路径。

同一团队、文件、callback key、状态和内容 URL 哈希的重复回调只能关联同一保存预留，不得重复创建版本。

## 考虑过的替代方案

- 在回调请求内同步完成保存：流程直接，但容易超时，外部重试会放大重复写入。
- 只依赖 RabbitMQ 消息保存回调内容：缺少可查询、可审计和可扫描恢复的持久事实。
- 把 OnlyOffice 本地卷视为历史版本：部署耦合强，不能满足应用版本、权限和恢复语义。
- 客户端主动上传编辑结果：浏览器会话和服务端回调状态难以形成可信一致边界。

## 后果

- 正面：回调响应时间与大文件保存解耦，失败可以从任务中心观察和重试。
- 正面：唯一约束和持久任务提供重复回调幂等与进程重启恢复。
- 负面：回调被接受不等于文件已经保存，客户端必须展示 pending/failed 状态。
- 负面：内容 URL 过期、对象成功但数据库失败等场景需要明确补偿和告警。

## 实现与验证

- 时序与失败边界：[OnlyOffice 保存链路](../architecture/flows/ONLYOFFICE_SAVE.md)
- 运行配置与排障：[OnlyOffice 部署](../operations/ONLYOFFICE_DEPLOYMENT.md)
- API：[OnlyOffice API](../reference/api/OFFICE.md)
- 当前实现：OnlyOffice callback Controller/Service、`onlyoffice_callbacks`、`team_tasks`、保存 Worker 与版本服务
- 验证：JWT、重复投递、同 key 不同 URL、下载超时、对象失败、版本递增、任务重试、审计和通知
