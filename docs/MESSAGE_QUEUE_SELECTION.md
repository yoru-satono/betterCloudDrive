# 消息队列选型

## 结论

当前阶段引入 **Redis Streams**，不引入 RabbitMQ 或 Kafka。

Redis 已经是 BetterCloudDrive 的必需组件，用于验证码、上传分片状态、下载 ticket、WebDAV 锁、统计同步等能力。团队模块已经有 `team_event_outbox` 表，团队任务中心也已经有 `team_tasks` 表。下一阶段应把 Redis Streams 作为运行时投递和消费通道，把 PostgreSQL outbox/task 表作为事实来源和幂等边界。

## 为什么不是 RabbitMQ 或 Kafka

| 方案 | 优点 | 当前阶段问题 | 结论 |
| --- | --- | --- | --- |
| Redis Streams | 复用现有 Redis；支持持久化消息、consumer group、pending entries、ack、重试；Spring Data Redis 可直接接入 | 路由能力弱于 RabbitMQ；长期大规模日志流能力弱于 Kafka | 采用 |
| RabbitMQ | 路由、延迟、死信和消费确认模型成熟 | 需要新增基础设施和运维面；当前事件规模和路由复杂度还不需要 | 暂不引入 |
| Kafka | 高吞吐、长留存、流处理能力强 | 运维成本最高；当前不是跨系统事件总线，也没有大规模日志流处理需求 | 暂不引入 |

## 工作负载匹配

| 工作负载 | 当前状态 | 队列化收益 | 第一阶段策略 |
| --- | --- | --- | --- |
| 团队通知 | 成员、邀请、权限、配额、删除等主流程已写 `NOTIFICATION` dispatch outbox，并由 `bcd:stream:notifications` worker 异步生成通知 | 降低团队成员、邀请、权限、配额、删除等主请求延迟；通知失败可重试 | 已队列化 |
| 审计导出 | 已通过 `team_tasks` 和 Spring scheduler worker 后台生成 CSV 结果 | 大导出不阻塞 HTTP 请求；结果可下载、可过期清理 | 已任务化；后续可接入 `bcd:stream:team-tasks` 统一唤醒 |
| 缩略图 | 已有生成逻辑和任务状态 | 上传后先返回列表，后台生成缩略图 | 第二批队列化 |
| OnlyOffice 保存 | callback 已入任务，后台 worker 保存 | Redis Stream 可替代轮询，提高失败重试和积压观测 | 第二批队列化 |
| 文件夹 ZIP | 已有任务化基础 | 大目录打包不占用请求线程 | 第二批队列化 |
| 团队清理 | 已有删除、归档、purge 和定时清理 | 避免大团队清理阻塞定时任务或 admin 请求 | 第三批队列化 |

## 设计边界

1. PostgreSQL 是事实来源。
   - 业务事务先写 `team_event_outbox` 或 `team_tasks`。
   - Redis Stream 只负责唤醒和分发 worker。
   - Redis 消息丢失时，可以用 outbox/task 表扫描补投。

2. 消费必须幂等。
   - 消息必须包含 `outboxId` 或 `taskId`。
   - worker 处理前按数据库状态做幂等判断。
   - 成功后更新数据库状态并 `XACK`。

3. 失败可重试且可定位。
   - 使用 consumer group pending entries 记录未确认消息。
   - 超过空闲时间后由 worker `XAUTOCLAIM` 接管。
   - 超过最大投递次数后写入 dead-letter stream，并把 outbox/task 标记失败。

4. 队列不是用户可见状态源。
   - 用户看到的任务状态仍来自 `team_tasks`。
   - 管理员排查仍以数据库审计、任务中心和日志为准。

## Redis Stream 命名

| Stream | Consumer group | 用途 |
| --- | --- | --- |
| `bcd:stream:team-events` | `bcd-team-event-workers` | 团队成员、邀请、权限、分享、配额、删除等领域事件 |
| `bcd:stream:notifications` | `bcd-notification-workers` | 通知发送、邮件发送、实时推送补偿 |
| `bcd:stream:team-tasks` | `bcd-team-task-workers` | 审计导出、缩略图、OnlyOffice 保存、ZIP、团队清理 |
| `bcd:stream:dead-letter` | 无固定 group | 超过重试上限的异常消息 |

## 第一阶段落地范围

第一阶段只做队列基础设施和团队通知异步化，对应 ROADMAP 的 `PERF-02` 和 `PERF-03`。

### PERF-02 队列基础设施

- 增加队列配置：
  - `queue.enabled`
  - `queue.redis.consumer-name`
  - `queue.redis.max-deliveries`
  - `queue.redis.claim-idle-ms`
  - `queue.redis.trim-maxlen`
- 增加 Redis Stream publisher 和 consumer 基础封装。
- 启动时确保 consumer group 存在。
- 增加 outbox 补投任务：扫描可投递的 `team_event_outbox`，写入对应 stream。
- 增加最小消费者测试：发布消息、消费、ack、失败重试、dead-letter。

### PERF-03 通知异步化

- 团队成员、邀请、权限、配额、删除等主流程写入 `NOTIFICATION` dispatch outbox，不直接同步生成通知。
- outbox 补投任务把 dispatch 事件投递到 `bcd:stream:notifications`。
- 通知 worker 消费 `bcd:stream:notifications`，调用现有通知服务生成站内通知、邮件和实时刷新事件。
- 通知偏好仍在现有通知服务内判断。
- `NotificationService.notifyUser` 创建通知后仍写 `NOTIFICATION` outbox 作为下游事件记录；通知 worker 只处理 dispatch target，避免重复通知。
- 主接口只依赖领域事件写入成功，不依赖通知写入、邮件发送或 SSE 推送成功。
- 消费失败会回写 outbox 为 `PENDING` 并延迟重投；超过最大投递次数后标记 `FAILED` 并写入 dead-letter stream。

## 后续批次

1. `PERF-04` 审计导出异步化已完成：导出请求创建为 `team_tasks`，当前由 Spring scheduler 扫描 `PENDING` 任务生成 CSV 并提供下载结果；后续可把任务唤醒接入 `bcd:stream:team-tasks`。
2. `PERF-05` 缩略图异步化：上传后创建缩略图任务，worker 生成缩略图并更新文件记录。
3. `PERF-06` ZIP 任务化：大文件夹下载先创建 ZIP 任务，再异步生成临时包。
4. `OBS-03` 队列积压指标：补 pending、retry、dead-letter、处理耗时和 stream 长度指标。

## 暂不做

- 不把审计日志本身迁移到 Kafka。
- 不把 Redis Stream 当作长期事件仓库。
- 不引入跨语言独立 worker 进程；第一阶段仍由 Spring Boot 内部 worker 消费。
- 不改变现有用户可见 API，队列化应保持接口响应结构兼容。
