# OnlyOffice Document Server 部署与排障

## 组件职责

Better Cloud Drive 不在前端或后端进程内解析 Office 文档。`onlyoffice/documentserver` 镜像提供浏览器编辑器 SDK、文档转换和渲染、协同编辑状态，以及保存回调。没有 Document Server 镜像时，普通上传和下载仍可用，但 `docx`、`xlsx`、`pptx` 等文件不能在线预览或编辑。

Compose 服务名是 `onlyoffice-documentserver`，容器名是 `bcd-onlyoffice`，当前固定使用镜像 `onlyoffice/documentserver:9.3.0.1`。本地端口为 `8082 -> 80`。生产升级镜像前应先确认版本兼容性，并运行 OnlyOffice E2E；发布和回滚顺序见[升级与回滚](UPGRADE_ROLLBACK.md)。

## 地址和 JWT

| 配置 | Compose 默认值 | 使用方 |
|------|----------------|--------|
| `ONLYOFFICE_PUBLIC_URL` | `http://localhost:8082` | 浏览器加载编辑器 SDK |
| `ONLYOFFICE_INTERNAL_URL` | `http://onlyoffice-documentserver` | 后端访问 Document Server |
| `APP_INTERNAL_API_BASE_URL` | `http://backend:8080/api/v1` | Document Server 下载文件并回调后端 |
| `ONLYOFFICE_JWT_SECRET` | 本地开发密钥 | 后端和 Document Server 共同签名、校验配置 |
| `HOST_ONLYOFFICE_PORT` | `8082` | Document Server 暴露到宿主机的端口 |

根目录 `.env` 和 `config/env-files/backend.env` 中的 `ONLYOFFICE_JWT_SECRET` 必须完全一致。生产密钥应是独立随机值，修改密钥后需要同时重建 Document Server 和后端容器。

跨主机部署时，三个 URL 必须分别从对应调用方可达：浏览器能访问 public URL，后端能访问 internal URL，Document Server 容器能访问 app internal API URL。HTTPS 页面不能加载 HTTP 的 public URL。

## 启动和验证

```powershell
docker compose up -d onlyoffice-documentserver backend frontend
docker compose ps onlyoffice-documentserver backend frontend
Invoke-RestMethod http://127.0.0.1:8082/healthcheck
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8082/web-apps/apps/api/documents/api.js
```

`healthcheck` 应返回 `true`，SDK 请求应返回 `200`，三个容器最终都应显示 `healthy`。再确认容器间双向地址：

```powershell
docker exec bcd-backend wget -q -O /dev/null http://onlyoffice-documentserver/healthcheck
docker exec bcd-onlyoffice curl -fsS http://backend:8080/actuator/health
```

最后执行真实预览和保存验证：

```powershell
.\scripts\verify-all.ps1 -Module office -SkipDockerBuild
```

该验证覆盖个人 `docx/xlsx/pptx` 只读预览，以及团队可写文档编辑后内容变化和历史版本增加。只检查 `/healthcheck` 不能证明保存回调可用。

## 保存链路

1. Web、Tauri 或 KMP 移动端从后端获取带 JWT 的 OnlyOffice 配置。
2. 浏览器从 public URL 加载 SDK。
3. Document Server 通过配置中的内容 URL 从后端下载源文件。
4. 保存时 Document Server 调用团队 callback URL。
5. 后端校验 token 和文档 key，创建 `ONLYOFFICE_SAVE` 任务。
6. 后台 worker 下载编辑结果，保存当前内容为历史版本，再更新文件对象。

任务状态位于团队设置的任务中心。失败任务会保留错误信息，可重试；团队审计日志和 `TEAM_OFFICE_SAVE_FAILED` 通知可用于关联排查。

## 常见错误

| 现象 | 优先检查 |
|------|----------|
| 编辑器空白或 SDK 加载失败 | `ONLYOFFICE_PUBLIC_URL` 是否能从用户浏览器访问，HTTPS 是否混入 HTTP |
| 编辑器显示“下载失败” | `APP_INTERNAL_API_BASE_URL` 是否能从 Document Server 容器访问，内容 token 是否过期 |
| 配置或 callback 返回 token 错误 | 两处 `ONLYOFFICE_JWT_SECRET` 是否一致，容器是否已重建 |
| 能预览但保存后没有新版本 | 团队是否有写权限，`ONLYOFFICE_SAVE` 任务状态，后端 callback/worker 日志 |
| callback 连接失败 | callback URL 是否使用 Document Server 可解析的主机名，后端端口和网络是否开放 |
| Document Server 长时间不健康 | `docker compose logs onlyoffice-documentserver`，内存/磁盘是否充足，端口是否冲突 |
| 修改 public URL 后仍请求旧地址 | 重建后端和前端，关闭旧编辑器会话并重新打开文档 |

排查日志：

```powershell
docker compose logs --since 30m onlyoffice-documentserver
docker compose logs --since 30m backend | Select-String "OnlyOffice|ONLYOFFICE_SAVE|callback"
```
