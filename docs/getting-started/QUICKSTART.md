# Docker Compose 快速启动

本文面向第一次运行 BetterCloudDrive 的使用者，目标是在本机启动完整栈、完成健康检查并注册第一个账号。只开发单个模块时，请改用[开发环境准备](DEVELOPMENT_SETUP.md)。

## 前置条件

- Docker Engine 或 Docker Desktop 正常运行。
- `docker compose version` 能输出 Compose v2 版本。
- 默认端口 1025、3000、5432、5672、6379、8025、8080、8082、8333、8888、9333、15672 未被占用；也可以在根目录 `.env` 中修改宿主机端口。

首次构建需要下载镜像和项目依赖。完整栈包含 PostgreSQL、Redis、RabbitMQ、SeaweedFS、Mailpit、OnlyOffice、前后端及观测组件。

## 1. 创建本地配置

在仓库根目录执行：

```bash
test -e .env || cp .env.example .env
for name in database rabbitmq storage security backend frontend observability mail; do
  target="config/env-files/${name}.env"
  test -e "$target" || cp "${target}.example" "$target"
done
```

PowerShell：

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item .env.example .env
}
@('database', 'rabbitmq', 'storage', 'security', 'backend', 'frontend', 'observability', 'mail') |
    ForEach-Object {
        $target = "config/env-files/$_.env"
        if (-not (Test-Path -LiteralPath $target)) {
            Copy-Item "$target.example" $target
        }
    }
```

这些目标文件用于本地运行且不应提交。若文件已经存在，请先确认内容，不要覆盖。

## 2. 设置本地密钥

编辑 `config/env-files/security.env`，替换以下三个占位值：

- `JWT_ACCESS_SECRET`
- `JWT_REFRESH_SECRET`，必须与 access secret 不同
- `SHARE_PASSWORD_ENCRYPTION_KEY`

每个值可以用以下命令分别生成：

```bash
openssl rand -base64 32
```

PowerShell 没有 OpenSSL 时，可以使用：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

分享加密密钥一旦用于已有数据就必须稳定保存；更换它会导致已有分享密码无法解密。生产环境还需要替换 OnlyOffice、RabbitMQ、Grafana Auth Proxy 等默认凭据，详见[配置参考](../reference/CONFIGURATION.md)。

## 3. 启动完整栈

```bash
docker compose up -d --build
```

首次启动可能需要数分钟，OnlyOffice 通常最晚就绪。查看状态：

```bash
docker compose ps
docker compose logs --tail=100 backend frontend
```

预期 `postgres`、`redis`、`rabbitmq`、`seaweedfs`、`mailpit`、`onlyoffice-documentserver`、`backend` 和 `frontend` 最终为 `healthy`。观测组件也应进入运行或健康状态。

若只想确认后端容器内部健康端点：

```bash
docker compose exec -T backend wget -q -O - http://127.0.0.1:8081/actuator/health
```

预期响应包含 `"status":"UP"`。

## 4. 访问与首次注册

| 服务 | 默认地址 | 用途 |
|------|----------|------|
| Web | `http://localhost:3000` | 登录和主要功能 |
| Mailpit | `http://localhost:8025` | 查看注册验证码和测试邮件 |
| 后端 API | `http://localhost:8080` | API 与 WebDAV 入口 |
| OnlyOffice | `http://localhost:8082` | 文档服务健康与 SDK |

打开 Web 注册页，填写邮箱并发送验证码；到 Mailpit 读取验证码后完成注册。登录后可以创建目录、上传文件，并在团队页面创建团队验证基本链路。

完整 API、基础设施和观测端口见[配置参考](../reference/CONFIGURATION.md)，系统关系见[架构总览](../architecture/OVERVIEW.md)。

## 5. 停止或重启

停止容器但保留数据：

```bash
docker compose stop
```

重新启动：

```bash
docker compose start
```

移除容器但保留命名卷：

```bash
docker compose down
```

不要在仍需数据时使用 `docker compose down -v`；`-v` 会删除 PostgreSQL、SeaweedFS、RabbitMQ 和观测数据卷，通常无法从本机恢复。

## 下一步

- 启动失败：[常见排障](TROUBLESHOOTING.md)
- 修改代码：[开发环境准备](DEVELOPMENT_SETUP.md)
- 理解系统：[架构文档](../architecture/README.md)
- 运行验证：[测试策略](../development/TESTING.md)
