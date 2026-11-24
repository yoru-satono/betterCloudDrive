# 本地开发常见排障

本文面向首次启动和本地联调场景。先定位失败发生在哪一层，再查看对应日志；生产故障请使用[运维手册](../operations/README.md)。

## 最短诊断路径

```bash
docker compose ps
docker compose logs --tail=150 backend
docker compose logs --tail=100 postgres redis rabbitmq seaweedfs
```

确认 Web、Mailpit 和后端容器是否可达：

```bash
curl -f http://localhost:3000/healthz
curl -f http://localhost:8025/api/v1/info
docker compose exec -T backend wget -q -O - http://127.0.0.1:8081/actuator/health
```

## 容器未启动或不健康

| 现象 | 优先检查 |
|------|----------|
| `env file ... not found` | 是否复制了八个 `config/env-files/*.env.example`，尤其是 `rabbitmq.env` |
| backend 反复重启 | PostgreSQL、Redis、RabbitMQ、SeaweedFS 是否 healthy；安全密钥是否仍为空或为错误格式 |
| OnlyOffice 长时间 starting | Docker 可用内存、`docker compose logs onlyoffice-documentserver`、8082 是否冲突 |
| frontend unhealthy | backend 是否先 healthy、Nginx 配置是否成功生成、3000 是否被占用 |
| 数据库 schema 校验失败 | Flyway 是否完成；不要用 Hibernate 自动改表代替 migration |

查看某一服务的最终 Compose 配置可使用 `docker compose config`。输出可能包含本地凭据，不要粘贴到公开 issue。

## 端口冲突

默认宿主机端口定义在根目录 `.env`。修改 `HOST_*_PORT` 后需要重新创建容器：

```bash
docker compose up -d --force-recreate
```

修改后端或前端宿主机端口时，同时更新客户端代理/服务器地址。只改变宿主机 PostgreSQL 端口不会改变容器间的 `postgres:5432`。

## 地址使用错误

| 调用方 | 访问宿主机后端的地址 |
|--------|----------------------|
| 宿主机浏览器、Qt | `http://127.0.0.1:8080` 或 `localhost` |
| Docker Desktop 容器 | `http://host.docker.internal:8080` |
| Compose 服务之间 | `http://backend:8080` 等服务名 |
| Android 官方模拟器 | `http://10.0.2.2:8080` |
| Android 真机 | 电脑的局域网 IP，且防火墙允许访问 |

Android 出现 cleartext HTTP 拒绝时，确认使用的是 debug 构建和项目提供的 network security 配置；生产地址应使用 HTTPS。

## Web 无法调用 API

- 确认 Vite 运行在 5173，后端运行在 8080。
- 查看 `frontend/vite.config.ts` 实际加载的 `VITE_DEV_BACKEND_PROXY_BASE`。
- 浏览器请求应使用 `/api/v1` 相对路径；不要在页面组件中硬编码另一个后端地址。
- 401 通常表示 token 过期或刷新失败；清理站点本地凭据后重新登录。
- 403 应先核对团队成员和有效权限，不要仅通过前端显示状态判断授权。

## Node、pnpm 或前端构建失败

确认 `node --version` 和 `pnpm --version` 与文档基线相符，然后在 `frontend` 运行 `pnpm install`。不要混用 npm/yarn 生成额外锁文件。类型错误先运行 `pnpm run build`，本地化硬编码错误单独运行 `pnpm run check:i18n`。

## Maven 或后端启动失败

- `java -version` 应为 JDK 25；仅安装 JRE 不够。
- 确认在 `backend` 目录运行 Maven，父 POM 才能找到七个模块。
- 本机 Java 进程不会自动读取 Compose 的 `env_file`，必须把安全密钥和覆盖变量传给进程或 IDE。
- 若 8080 被占用，先检查是否仍有 Compose backend 或旧 Java 进程。
- Repository 集成失败时确认 Docker 引擎可用；Testcontainers 不应被静默跳过。

## KMP 移动端构建或 Android 设备测试失败

- 使用项目 Gradle wrapper，不要用系统 Gradle 替代。
- 确认 SDK 37 已安装，并由 JDK 17 启动 Gradle。
- `adb devices` 应显示目标设备为 `device`，不是 `offline` 或 `unauthorized`。
- 多设备时设置 `ANDROID_SERIAL`，网络设备可先执行 `adb connect <serial>`。
- JVM 测试不要求设备；设备测试使用 `connectedDebugAndroidTest`。
- KMP 移动端验收不生成覆盖率报告，也不使用代码行数或行覆盖率作为质量指标。

## Qt 启动或验收失败

检查 uv、Node/pnpm、锁定依赖、网页资源、独立端口及 Profile 锁。普通启动默认关闭 CDP；真实后端专项需要已运行的服务。缺少 Qt 系统运行库时应明确失败，不跳过为通过。详见 [Qt 桌面开发](../../desktop/README.md)。

## OnlyOffice 无法加载或保存

编辑器空白通常是浏览器无法访问 public URL；保存失败通常是 Document Server 无法访问 app internal URL、JWT 密钥不一致或对象存储失败。按 [OnlyOffice 部署与排障](../operations/ONLYOFFICE_DEPLOYMENT.md)逐项检查双向地址、健康、JWT 和后台任务状态。

## RabbitMQ 或后台任务积压

确认 RabbitMQ healthy、backend 消费者已启动，并检查 Outbox、`team_tasks` 和死信记录。不要直接清空队列来掩盖数据库中的待处理事实；排障顺序见[异步任务链路](../architecture/flows/ASYNC_TASKS.md)。

## 仍无法定位

保留失败命令、首个错误、相关服务最近日志、`docker compose ps`、request ID/trace ID 和操作系统/工具版本。删除 token、Cookie、邮件验证码、密码和连接凭据后再共享诊断信息。
