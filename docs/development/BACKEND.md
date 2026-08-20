# 后端开发指南

本文面向修改 Spring Boot 后端的开发者，说明代码放置、启动、数据库迁移、调试和最低验证。模块依赖与事务边界先阅读[后端架构](../architecture/BACKEND.md)。

## 七个模块

| 模块 | 常见修改 |
|------|----------|
| `betterclouddrive-common` | API envelope、错误码、通用 DTO、消息 key |
| `betterclouddrive-dal` | Entity、Repository 和 PostgreSQL 查询 |
| `betterclouddrive-storage` | S3/SeaweedFS 对象操作与存储指标 |
| `betterclouddrive-service` | 业务接口/实现、事务、权限、队列、邮件和任务状态 |
| `betterclouddrive-web` | Controller、Security、JWT、WebDAV、SSE、异常响应 |
| `betterclouddrive-scheduler` | 定时清理、同步、Outbox 发布和后台 Worker |
| `betterclouddrive-start` | 应用入口、配置、Flyway 和最终制品 |

依赖只能沿 `common -> dal/storage -> service -> web/scheduler -> start` 的方向组合。可被 REST、WebDAV 和 Worker 共用的规则应放在 Service，不要复制到 Controller。

## 本机启动

先启动依赖并停止可能占用 8080 的 Compose backend：

```bash
docker compose stop backend
docker compose up -d postgres redis rabbitmq seaweedfs mailpit
```

把 `JWT_ACCESS_SECRET`、`JWT_REFRESH_SECRET` 和 `SHARE_PASSWORD_ENCRYPTION_KEY` 设置为当前进程环境变量，然后：

```bash
cd backend
mvn -pl betterclouddrive-start -am package -DskipTests
java -jar betterclouddrive-start/target/betterclouddrive-start.jar
```

IDE 运行入口是 `com.betterclouddrive.BetterCloudDriveApplication`。本机进程不会自动读取 Compose `env_file`；覆盖数据库、队列、对象存储或密钥时，要在 IDE Run Configuration 中设置变量。

## 请求修改路径

典型 REST 功能按以下顺序实现：

1. 在 `common` 或 `service/dto` 定义稳定输入输出类型。
2. 在 `service` 接口和实现中编写资源作用域、状态转换、权限与事务。
3. 必要时在 `dal` 增加 Entity/Repository 和 migration。
4. 在 `web/controller` 暴露 HTTP 协议，保持 Controller 薄。
5. 更新 API 参考、客户端契约和对应测试。

不要把 JPA Entity 直接当作新接口的长期协议，尤其是需要隐藏内部路径、密文或审计 payload 的场景。

## 数据库变更

当前 schema 由 `betterclouddrive-start/src/main/resources/db/migration` 下的 Flyway migration 管理，JPA 使用 `ddl-auto=validate`。

- 已进入共享历史的 migration 不应原地改写；新增版本文件演进 schema。
- migration、Entity、Repository 查询和 [数据模型](../architecture/DATA_MODEL.md)必须一起更新。
- 新增个人/团队数据时检查作用域隔离、唯一索引、删除状态和外键删除行为。
- 使用 PostgreSQL 特性的查询应进入 Testcontainers 集成测试，不要只用 mock 证明。

## 事务和外部资源

`@Transactional` 只覆盖 PostgreSQL。SeaweedFS、Redis、RabbitMQ 和邮件不参与同一事务。涉及对象写入、Outbox 或后台任务时，要明确幂等键、提交失败补偿、重试和清理者，参阅[异步任务链路](../architecture/flows/ASYNC_TASKS.md)。

权限检查应在产生副作用前完成。团队文件操作必须同时验证团队作用域与[有效权限](../architecture/PERMISSIONS.md)，不能只检查调用者是否登录。

## 调试入口

- HTTP 响应头 `X-Request-ID`、`X-Trace-ID` 可关联运行日志和审计。
- `application-dev.yml` 提高项目包、安全和 JDBC 日志级别；不要在生产 profile 打开敏感调试。
- Actuator 默认包含 health、info 和 prometheus；容器生产 profile 的管理端口是 8081。
- 队列问题先查 PostgreSQL Outbox/任务状态，再查 RabbitMQ 和死信。
- 对象问题同时核对 `storage_path` 元数据和 SeaweedFS 对象存在性。

## 最低验证

| 修改 | 最低命令 |
|------|----------|
| `common` 纯逻辑 | `cd backend && mvn test -pl betterclouddrive-common -am` |
| Service 业务逻辑 | `cd backend && mvn test -pl betterclouddrive-service -am` |
| Controller/Security | `cd backend && mvn test -pl betterclouddrive-web -am`，并运行相关黑盒/security marker |
| Entity/Repository/存储/队列 | `./scripts/verify-all.sh --module integration` |
| Scheduler/异步链路 | 对应单元测试，加 `integration` 或 `resilience` |
| API 契约变化 | 后端测试、Python 黑盒及受影响客户端契约测试 |

Windows 可以使用 `scripts/verify-all.ps1 -Module ...`。完整测试分层见[测试策略](TESTING.md)。
