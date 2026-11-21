# 容量与对象一致性巡检

巡检是网盘后端自带的管理员能力，复用 PostgreSQL、Redis 和现有 S3 SDK，不需要业务数仓或新增部署服务。Web 和 Tauri 共享 `/admin/storage-inspections` 页面。所有 HTTP 接口均要求 `ROLE_ADMIN`，入口见[管理员 API](../reference/api/ADMIN.md)。

## 范围与口径

每次全量运行在 PostgreSQL 一致性事务中暂存文件、版本、缩略图、目录 ZIP 缓存、ZIP 任务结果、上传会话、后台任务和空间用量，再分页扫描配置的整个业务桶。只执行桶检查、LIST 和 HEAD，不下载正文、不计算内容哈希、不删除对象、不自动修复元数据。本地审计导出文件由原有本地任务结果存储管理，不属于 S3 桶对象；任务停滞检查仍覆盖所有后台任务类型。

对象数和字节数按唯一对象键计算，是业务桶的逻辑清单大小，不代表 SeaweedFS 卷、复制、副本、索引或宿主机磁盘占用。个人/团队用量按可达的有效文件及历史版本累计；相同对象被复制或被多个版本引用时，各逻辑引用分别计费。回收站文件及其后代不计空间用量，但保留对象引用，不能被认定为孤儿。团队版本归属文件所在团队，版本编辑者不是容量归属者。

检测类型包括：

| 类型 | 含义 |
|---|---|
| `MISSING_OBJECT` | 文件或版本引用的对象不存在 |
| `AUXILIARY_MISSING` | 缩略图、ZIP 缓存或任务结果对象不存在 |
| `OBJECT_SIZE_MISMATCH` | 引用期望大小与对象大小不一致；共享对象的每条冲突引用分别报告 |
| `ORPHAN_OBJECT` / `UNMANAGED_OBJECT` | 业务路径或未知路径下的无引用对象 |
| `QUOTA_DRIFT` | 空间计数与当前元数据重算值不一致 |
| `QUOTA_METADATA_INVALID` | 源元数据不完整或非法，无法安全重算容量 |
| `METADATA_INCONSISTENCY` | 目录环、不可达祖先、跨空间/缺失父项、非目录父项、非法类型/大小/状态、缺少对象键或版本属于目录 |
| `UPLOAD_STALLED` / `TASK_STALLED` | 上传超过过期及清理宽限，或后台任务超过等待/运行阈值 |

活动上传会话和流式 WebDAV 上传前缀保护临时对象；流式前缀由独立心跳发布短期 Redis 租约。Redis 不可用时孤儿检查失败，不将不确定的活动状态当作无引用。所有对象仍有至少 24 小时的保护期。

## 观测、运行与处置

默认首次启动且尚无运行记录时创建基线，每天 `Asia/Shanghai` 02:15 自动运行；自动调度关闭或计账尚未就绪时不创建启动基线。管理员也可手动触发、取消或复核单项。数据库只允许一项活动运行。单独工作线程通过持久租约、随机 fencing token 和分页游标恢复进度；取消在分页及候选项边界生效，进程退出保留租约，过期后由新进程接管。

首次差异为 `SUSPECTED`。证据保持一致、至少相隔 600 秒的第二次有效观测才能进入 `CONFIRMED`。更频繁的刷新不累计次数；证据变化重新开启观测窗口和 `OPEN` 处置状态。源数据恢复后为 `RESOLVED`。技术状态与 `OPEN`、`ACKNOWLEDGED`、`IGNORED` 处置状态分离，忽略不代表修复，也不永久屏蔽新证据。

`SUCCEEDED` 表示完成本次定义的覆盖范围，不表示没有差异。`PARTIAL`、`FAILED` 和 `CANCELED` 保留已有诊断，不能替代“最近完整巡检”。对象存储网络、授权、桶不存在和未知响应均作为运行错误，不能推断对象缺失。

SeaweedFS 4.40 的同名对象与目录碰撞可能使清单返回 `key/` 而引用仍为 `key`。巡检不改写或合并这些键：对于扫描开始前已存在、HEAD 可见但 LIST 漏掉的引用对象，标记 `inspection_inventory_gap`，本次为 `PARTIAL`，跳过孤儿发现与孤儿复核。解决存储清单问题后重新全量扫描。分页使用服务返回的 continuation token，不能用最后一个键替代。常规网盘生成的对象键避免叶子与目录同名。

单项复核只证明该项当前状态，不刷新全桶统计；孤儿复核还要求最近一次全量清单完整。对象键仅在管理员详情中返回，列表只提供指纹。备注和修复原因最长 500 字符，应只写处置说明，不填写凭据或文件内容；通用 HTTP 审计不采集这些接口的请求正文。

## 容量修复

新代码将个人/团队容量变化与文件、版本元数据放在同一个 PostgreSQL 业务事务中，先锁空间 owner，再锁文件；每次容量变更增加 `quota_revision`。Redis 不再承担用量增量计账。上传、复制、分享保存、回收/恢复、版本和清理沿用统一容量入口。

只允许修复已确认的 `QUOTA_DRIFT`。管理员先生成 10 分钟有效的预览，查看空间、当前值、重算值、差额，再填写理由并确认。提交在 owner 锁内重新检查操作者、预览期限、当前容量、revision 和即时元数据重算；期间任何变化均返回 409，需要刷新预览。非法层级、大小溢出、已删除空间和不完整元数据不能修复。

修复只改 `storage_used` 和 revision，不改配额上限，不改文件或对象。计数更新、修复审计与差异解决在同一事务提交；审计失败则全部回滚。每次提交带 UUID `idempotencyKey`：网络结果不确定时用相同预览、相同原因和相同 key 重试，可返回原结果；同 key 对应不同请求返回 409。已成功请求可在预览过期后重放，在审计保留期内保持幂等。

## 从旧 Redis 增量计账升级

这是明确的离线操作。验证脚本、定时任务、HTTP 请求均不会自动执行迁移。

1. 确认目标 Compose project、PostgreSQL 数据库、Redis 实例和旧镜像摘要。停止所有旧版本后端、副本、WebDAV/Office 写入口和后台清理任务，保持它们停止；仅切断外部上传不足以证明后台写入已停止。
2. 在受限目录中保存 PostgreSQL 完整备份、Redis RDB/AOF 一致快照及旧程序的可加载镜像归档（或已验证可拉取的不可变引用），记录摘要并在隔离实例中验证恢复。仅记录本地镜像 ID 或可变 tag 不能保证旧程序仍然可用。备份含敏感数据，不放入仓库、测试报告或共享目录。不要删除数据卷。
3. 使用新镜像及同一套数据库/Redis 环境运行专用 CLI。它启动 Flyway/JDBC/Redis，不启动 HTTP、定时任务或 Rabbit 消费者。现有数据库应用 V3 后默认 `ready=false`；全新空库可直接使用事务计账。

   ```sh
   docker compose run --rm --no-deps backend --quota-cutover --writers-stopped
   ```

   运行项目使用诊断/数仓 overlay 时，命令的 `-f` 参数必须与实际项目一致。宿主机启动可使用 `java -jar betterclouddrive-start.jar --quota-cutover --writers-stopped`，通过原有环境配置提供连接信息；不要把密码写到命令行。

4. CLI 取得 PostgreSQL advisory lock，将 `storage:incr:*`、`team:storage:incr:*` 的旧增量逐项应用并记录到 `quota_legacy_transfers`。不存在 owner 的旧键也留下记录。只删除与记录值完全相等的 Redis 键；数值改变或已确认键重新出现会中止并保持 gate 关闭。不会按文件元数据覆盖旧计数。
5. 只有所有已记录增量已确认、两类旧键均为空时才打开 `quota_accounting_state.ready`，输出 `quota_cutover_complete`。检查该状态和 journal 的未确认数后启动新后端，等待基线；历史已有漂移通过巡检和人工预览处理。

新后端在 gate 关闭时拒绝需要容量写入的操作并返回 503。切换中断后，保持旧写入者停止，用相同 CLI 重跑：已应用但未确认的增量从 journal 恢复，不重复累加。若有旧写入者继续写或值改变，应定位并停止写入者，核对 journal、源 Redis 值和备份后决定恢复；不要直接设置 `ready=true`、清空 Redis 或自动重算全部空间。

回滚必须停掉新旧全部写入者，并成对恢复同一恢复点的 PostgreSQL 与 Redis，再启动归档的旧程序。不要仅回退镜像或仅还原其中一个存储，这会丢失或重复计算迁移增量。业务恢复写入后，旧备份不能直接覆盖新数据；应按[备份与恢复](BACKUP_RESTORE.md)协调元数据和对象的恢复点，并处理恢复点之后的写入。

## 配置与资源边界

配置前缀为 `drive.inspection`，可按 Spring Boot 环境变量规则映射，例如 `DRIVE_INSPECTION_ENABLED=false`。调度时区与源数据时区不同：`zone` 控制每日任务时间；`source-zone` 解释旧表中不带时区的上传和任务时间，必须匹配原写入环境，容器默认 UTC。本机或历史部署使用其他 JVM 时区时显式配置；不要改变已有源时间来迁就巡检。

| 配置 | 默认值 | 约束/含义 |
|---|---|---|
| `enabled` / `scheduled` | `true` / `true` | 前者同时停手动和自动，后者停每日调度和启动基线；派生数据保留清理仍运行 |
| `cron` / `zone` | `0 15 2 * * *` / `Asia/Shanghai` | Spring 六字段 cron |
| `source-zone` | `UTC` | 无时区源列的解释时区 |
| `page-size` | `1000` | 1～1000 |
| `observation-gap-seconds` | `600` | 不得低于 600 |
| `orphan-grace-seconds` | `86400` | 不得低于 86400 |
| `run-timeout-seconds` | `7200` | 至少 60；超时发布 PARTIAL |
| `lease-seconds` | `180` | 150～3600 |
| `pending-task-seconds` | `7200` | PENDING 阈值 |
| `running-task-seconds` | 继承 `drive.tasks.running-timeout-seconds`，默认 1800 | RUNNING 阈值 |
| `upload-expire-seconds` | 继承 `drive.upload.session-expire-hours`，默认 24 小时 | 上传过期阈值 |
| `upload-cleanup-grace-seconds` | `1800` | 过期后的清理宽限 |
| `retention-batch-size` | `5000` | 每张派生表每次最多删除数，1～10000 |

单 worker，LIST 至多 1 次/秒，HEAD 至多 10 次/秒；当前 HEAD 串行执行。SDK 边界限速覆盖额外桶检查。元数据一致性事务最多 120 秒，小事务最多 30 秒，容量修复锁等待最多 5 秒。大桶可能在 2 小时上限内无法完成，需要依据实际规模调整上限并观察数据库开销；不会把部分清单当完整报告。

每分钟分批清理派生数据：原始暂存 7 天，已解决报告 90 天，审计 365 天；未解决报告保留。报告若仍被审计/预览引用会继续保留，最新完整运行不被清理。保留策略不触碰业务文件、队列和对象。

## 监控与验收

指标使用固定类型/状态标签，不包含用户 ID、对象键或文件名：`bcd_storage_inspection_runs_total`、`bcd_storage_inspection_duration_seconds_*`、`bcd_storage_inspection_last_complete_timestamp`、`bcd_storage_inspection_findings`、`bcd_storage_inspection_actionable_findings`、`bcd_storage_quota_accounting_ready`、`bcd_storage_quota_repairs_total`。LIST/HEAD 请求也计入现有对象存储操作指标。

Prometheus 规则覆盖长期无完整结果、近期失败/部分运行、未忽略的已确认 ERROR 差异和计账 gate 长期关闭，详见[观测运维](OBSERVABILITY.md)。先查看当前运行的 `errorKey` 与覆盖标志，再检查对象存储和数据库健康；不能通过忽略差异消除基础设施故障。

验收入口和真实测试的数据清理约束见[测试策略](../development/TESTING.md)。后端隔离测试覆盖事务回滚、并发容量、迁移中断/变值、租约 fencing/恢复、对象分页、缺失/大小/层级/任务差异、稳定观测、取消、超时和原子修复；真实 API 与 Web/Tauri 验收补足权限和用户操作链路。没有读取正文的巡检不能证明内容哈希、文件可打开或副本物理健康。

### 本机验收样本（2026-11-21）

回归遗留对象桶的全量巡检扫描 228 个对象、20,993,177 字节和 43 个空间；该样本的文件引用已清理为空。运行耗时 28.56 秒，记录 LIST 1 次、HEAD 285 次（包含桶检查和已有差异复核），GET、PUT、COPY、DELETE 均为 0。源容量投影查询返回 43 行，执行 0.82 毫秒、规划 5.85 毫秒，命中共享缓存 89 块、物理读取 0 块；此查询测量不含暂存 INSERT 成本。

窗口前后 JVM 已用堆约 132→104 MiB，容器内存约 558→605 MiB，本次容器启动以来峰值约 608 MiB。前后值受 GC、启动和管理页面请求影响，不能当作巡检独占内存或生产性能承诺。原始数字由实际指标与 PostgreSQL EXPLAIN 生成，保存在本机忽略的 `test-results/inspection/measurements.json`；生产规模、高并发写入及更大桶仍需按部署资源压测。
