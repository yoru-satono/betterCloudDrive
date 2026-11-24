# 业务数仓

面向系统管理员的网盘使用与逻辑容量分析，不读取文件正文。包含业务事件采集、ClickHouse、dbt 模型、Airflow DAG 和 Web/Qt 共用管理页面，入口为 `/admin/analytics`。

## 本地运行

推荐为 Docker 分配约 12 GiB 内存。Windows 使用 Linux 容器运行 Airflow；其运行环境与 dbt/Python 加工环境隔离，依赖由 `uv.lock` 固定。

```powershell
python warehouse/init_local_env.py
docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml -f docker-compose.warehouse.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml -f docker-compose.warehouse.yml up -d --build
```

初始化只创建缺失文件，为新环境生成独立随机密钥，不覆盖现有配置。部分数仓配置已经存在时，需要核对成对密钥。新建根 `.env` 使用 `COMPOSE_PROJECT_NAME=bcd_warehouse_dev`，不复用或删除其他项目的数据卷。

数仓容器与主栈统一使用显式的 `bcd-` 名称，例如 `bcd-clickhouse`、`bcd-airflow-scheduler`，不带 Compose 自动生成的实例编号。命令和服务间连接仍使用 Compose 服务名；项目名继续决定默认网络与数据卷的归属。

`config/env-files/warehouse*.env.example` 分别提供加工任务、数据库、一次性初始化和后端只读配置。普通任务只读取白名单视图、领取事件；管理凭据仅提供给一次性初始化服务。

Airflow 本机地址默认为 `http://127.0.0.1:8083`，用户名为 `warehouse-admin`，密码位于本地 `warehouse.env`，不得复制到日志或报告。应用内从管理后台“业务分析”进入。

首次手工加工：

```powershell
docker compose -f docker-compose.yml -f docker-compose.warehouse.yml exec -T warehouse-airflow-scheduler /opt/warehouse/.venv/bin/python -m bcd_warehouse hourly
```

## 统计口径

- 原始事件存 UTC，报表采用 `Asia/Shanghai`。源库无时区字段按 `WAREHOUSE_SOURCE_ZONE` 解释，默认适用于 UTC 源环境。
- 使用统计每小时第 10 分钟更新；容量每日 00:05 采样。首次小时任务建立基线，同一业务日期复用原样本，显示实际采样时刻。
- 活跃账号是有合格业务活动的去重认证用户，与启用账号数区分。匿名分享访问、刷新 Token、轮询、SSE、内部回调和统计查询不计入 DAU。
- 上传成功以文件记录提交为准，秒传也计逻辑上传量；完成请求重试和分片不重复累计。
- 下载统计请求尝试，Range、重试分别计数。字节数是成功写入 Servlet 输出流的字节，不表示物理流量或用户保存完整文件。
- 下载分别持久化开始与终态。处理中、进程终止或终态无法保存的请求显示为未确认，超时不伪造成功。
- 当前文件、回收站当前内容、历史版本分别计算逻辑大小。物理去重、副本、文件系统开销、缩略图和临时对象不在首期范围。
- 删除目录按祖先链生效，不能把隐藏的子文件算入当前文件。
- 分享访问表示授权成功的公开访问请求，不表示独立访客，不与浏览分页和下载混加。

### 指标字典

所有指标可按全部空间、个人空间或团队空间查询，团队空间还可指定团队。区间指标相加，存量指标取所选区间内最后一次实际采样；两者不可混加。

| 字段 | 口径 |
| --- | --- |
| `total_users` / `enabled_users` | 采样时未删除账号数 / 其中状态为启用的账号数；团队筛选不将成员数当作账号数 |
| `registered_users` | 区间成功注册事件数，归入个人空间 |
| `active_users` / `active_day` | 最近已采集统计日的去重认证活跃用户及该日期；30 天查询不把每日 DAU 相加 |
| `total_teams` / `member_count` | 未删除且未归档的团队数 / 状态有效的成员关系数；跨团队同一用户可有多条关系 |
| `active_teams` | 最近统计日内发生认证用户业务活动的去重团队数，匿名访问单独计入分享使用 |
| `file_count` / `folder_count` | 未处于回收站（含祖先删除状态）的文件数 / 目录数；复制出的记录独立计数 |
| `current_bytes` / `trash_bytes` / `version_bytes` | 当前文件、回收站当前内容、历史版本的互斥逻辑容量 |
| `quota_bytes` | 作用域内配置配额之和，不代表底层可用磁盘或物理容量 |
| `uploads` / `uploaded_logical_bytes` / `instant_uploads` | 区间上传提交数 / 逻辑字节 / 其中秒传数；WebDAV 流式提交也进入统计 |
| `download_attempts` / `download_succeeded` / `download_failed` / `download_unknown` | 区间下载请求数及三类结果；三类结果之和等于请求数 |
| `download_written_bytes` | 已持久化终态中，成功完成的 Servlet 写入调用所计字节；未确认请求及异常调用内部可能已部分写出的字节不估算 |
| `shares_created` / `shares_canceled` / `share_accesses` | 区间创建、首次取消、授权成功的公开访问请求数 |
| `active_shares` | 采样时未取消且未过期的分享配置数，不替代访问时对目标、口令和次数限制的验证 |
| `operations` | 合格业务操作响应及预览的活动记录数，用于观察功能使用；不包含管理报表、轮询、Token 刷新和内部取文件 |

`sampled_at` 为实际容量采样时刻。新增团队有使用事件但还没有容量样本时，使用明细可见，容量、文件数、成员数及采样日期保持无数据。

所选区间完全位于采集覆盖范围之外时，概览的使用和容量对象为空，趋势中对应日期为 null，不把未采集历史显示为零。覆盖范围内没有合格事件的日期，使用计数可以为零。

## 数据层与发布

`ods_*` 保存原始事件及完整快照，`dwd_*` 去重并解析传输状态，`dws_*` 形成日口径，`ads_*` 提供报表。

业务事件随相应事务提交；导出使用短事务、行锁和 15 分钟租约，ClickHouse 成功写入后确认。崩溃后可重新领取，查询层按事件 ID 显式去重，不依赖后台合并。

六类元数据从同一 PostgreSQL 一致性事务读取。投影不包含密码、邮箱、文件名、对象路径、分享码和审计正文。目录循环、跨作用域父节点等错误阻止快照发布。

每次加工创建独立 generation ID。dbt 及质量检查成功后才切换发布标记，失败保留上一版本；前端固定概览、趋势与团队查询的发布批次。

进程被强制终止时可能来不及补记终态。下一次成功取得源端全局锁的任务会恢复遗留运行记录：已有发布标记的记为成功，尚未发布的记为 `pipeline_interrupted` 失败。持锁失败的并发任务不会修改仍在运行的任务状态。

原始数据保留 90 天，汇总保留 365 天。最近 90 个业务日期从明细重算，更早汇总从上一发布版本保留。源端仅清理 7 天前已经确认导出的事件；未导出事件不自动删除。

源事件采集默认启用，数仓短暂停机时继续保留待导出事实；未部署加工栈的环境应关注源端积压。可用 `WAREHOUSE_CAPTURE_ENABLED=false` 明确关闭采集，但关闭期间的历史行为不会从审计日志回填。

## 重跑与补数

小时任务可以重跑；同日容量快照重跑复用原样本。

单次任务最多运行 20 分钟，DAG 运行最长 45 分钟，避免异常退出留下永久占用并发名额的运行记录。小时和每日任务由 Airflow 重试一次；手工验收使用正常触发和状态轮询，不在运行中的调度器旁执行 `dags test`。

```powershell
docker compose -f docker-compose.yml -f docker-compose.warehouse.yml exec -T warehouse-airflow-scheduler /opt/warehouse/.venv/bin/python -m bcd_warehouse backfill --start 2026-11-20 --end 2026-11-20
```

补数最多 31 天，必须处于明细保留期并有真实快照。不会用当前状态伪造历史；首次基线前没有历史报表。也可通过 `bcd_warehouse_backfill` DAG 提交日期参数。

失败排查先查看 Airflow 与管理后台运行记录。模型问题查看本机 dbt artifacts，结果 API 不返回 SQL、连接串或远端错误正文。

## 验证

```powershell
.\scripts\verify-all.ps1 -Module warehouse
```

```bash
bash scripts/verify-all.sh --module warehouse
```

该模块包含 Python 单元、隔离 PostgreSQL/ClickHouse 集成和真实 Airflow/网盘 API 验收。仅运行独立数据层检查：`cd warehouse` 后执行 `uv run --locked --extra test pytest`。

客户端验收组合为 PowerShell `-Module warehouse,web` 和 `-Module warehouse,qt`，Bash 使用重复的 `--module` 参数。入口自动启用业务分析页面测试；单独运行页面测试时需设置 `E2E_WAREHOUSE=1`，并确保已有成功发布的报表。

隔离测试使用专有运行 ID、随机凭据和临时容器，只移除该测试创建的容器，不连接开发数据库。完整应用验收沿用统一入口的运行 ID 和范围受限清理。

### 本机验收记录（2026-11-20）

| 检查 | 结果 |
| --- | --- |
| 后端单元 / 集成 | 599 / 15 项通过 |
| API 黑盒 / 安全 / WebDAV | 156 / 21 / 4 项通过 |
| Web 单元 | 201 项通过 |
| Web 浏览器 | 31 项通用流程通过；业务分析专项通过，覆盖中英文、明暗主题及 1440/768/390 像素宽度 |
| Qt | 复用共享管理页面；本轮未重跑原生业务分析专项 |
| 数仓 | 25 项单元与隔离集成、12 项真实 API/Airflow 验收通过 |
| 验证入口与配置 | PowerShell 与 Bash 实际模块运行通过；语法、帮助、错误参数及 Compose 渲染检查通过 |

本次未运行 KMP 移动端、OnlyOffice 和韧性专项；没有修改这些客户端或功能。Prometheus 规则检查和规则测试通过，不等同于完整观测专项验收。

资源基线使用 Windows Docker Desktop Linux 容器，Docker 可用内存约 7.655 GiB，低于推荐的 12 GiB；未修改宿主机内存配置。构建与宿主机测试限制并发。容器内 Maven Central 请求返回 HTTP 403，本机通过宿主机缓存完成同版本 JAR 打包，再使用运行阶段镜像联调；这属于本机依赖获取限制。

| 小数据集实测 | 结果 |
| --- | --- |
| 原始事件 / 去重事件 | 2,837 / 2,837 条 |
| 已完成容量样本 | 1 批、2 条记录；同日重跑保持首次真实样本 |
| 测量时源库 | 16 个用户、0 个团队、0 个文件（验收夹具已清理） |
| 一次小时加工 / 调度触发至完成 | 4.691 秒 / 10.23 秒 |
| 19 个运行中项目容器内存合计 | 5 次采样观测峰值约 4.529 GiB |

以下为本机 HTTP API 连续串行各 5 次查询，默认最近 30 天，趋势和团队固定概览的发布版本；包含应用鉴权与查询耗时。

| 报表 | 中位延迟 | 最大延迟 |
| --- | --- | --- |
| overview | 25.64 ms | 65.33 ms |
| trends | 22.99 ms | 26.19 ms |
| teams | 28.94 ms | 74.23 ms |
| runs | 12.85 ms | 24.86 ms |

这些结果仅描述受控小数据集，不是生产规模压力测试。内存为 Docker stats 离散采样的容器合计，不包含 Docker 虚拟机和宿主机开销，也不保证捕获瞬时绝对峰值。首次基线前的历史容量不能回填。

相关约定：[数据模型](../docs/architecture/DATA_MODEL.md)、[测试策略](../docs/development/TESTING.md)、[管理员接口](../docs/reference/api/ADMIN.md)。
