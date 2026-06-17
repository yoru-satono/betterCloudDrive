# 测试体系概要设计

## 目标

测试体系用于验证业务正确性、接口契约、客户端交互、跨端一致性和部署可运行性。重点覆盖高风险链路：认证、上传下载、断点续传、分享、权限、日志和管理员功能。

## 分层

| 层级 | 工具 | 覆盖 |
|------|------|------|
| 后端单元测试 | JUnit 5、Mockito | Service、工具类、异常、状态机 |
| 后端 Web 测试 | Spring Test | Controller、Security、Filter、DTO |
| 后端任务测试 | JUnit 5 | 定时任务和清理策略 |
| 前端单元测试 | Vitest、Vue Test Utils | 组件、store、composable、API 封装 |
| Web E2E | Playwright | 浏览器完整用户路径 |
| Tauri E2E | WebDriverIO | 真实桌面窗口和 Tauri command |
| Android E2E | AndroidX Test、UIAutomator | 模拟器真实 UI |
| 黑盒 API | pytest、httpx | Docker 后端端到端接口 |
| 配置校验 | Docker Compose | 编排文件和 env 注入 |

## 测试数据原则

- 每个 E2E 用例创建独立用户或独立命名资源。
- 文件名带随机后缀，避免同名冲突。
- 测试结束尽量清理创建的资源。
- 不依赖固定数据库初始状态，除非是明确 seed 数据。
- 测试中的密码和密钥只使用测试值。

## 后端测试重点

| 领域 | 应测内容 |
|------|------|
| 认证 | 注册、登录、刷新、登出、禁用用户、token 失效 |
| 文件 | 列表、创建、重命名、移动、复制、删除、恢复、彻底删除 |
| 上传 | 初始化、秒传、分片、缺失分片、取消、完成、过期 |
| 下载 | ticket、Range、ZIP 缓存、无权限、文件夹限制 |
| 分享 | 创建、密码、过期、访问次数、取消、保存、公开下载 |
| WebDAV | Basic Auth、PROPFIND、GET、PUT、MKCOL、MOVE、COPY、LOCK |
| 管理员 | 用户状态、配额、用户文件、审计日志、系统日志 |
| 可观测性 | requestId、traceId、脱敏、Grafana auth |

## 前端测试重点

- 登录态路由守卫。
- API client token 注入和刷新。
- 文件列表和面包屑。
- 搜索候选与当前目录隔离。
- 右键菜单和批量操作。
- 上传队列并发、暂停、恢复。
- 下载 ticket 调用。
- 分享密码按需读取。
- 管理后台日志筛选。
- 桌面环境下设置页和传输队列。

## Tauri 测试重点

Rust 单测：

- 设置归一化。
- 代理校验。
- 限速和并发 limiter。
- 传输队列聚合。
- 节点暂停、恢复、取消。

E2E：

- 真实窗口启动。
- 登录。
- 上传文件夹。
- 下载文件夹。
- 传输队列上传/下载标签页。
- 默认下载目录和选择目录。

注意：Tauri E2E 可能依赖 WebView2、EdgeDriver 和可见窗口环境。

## Android 测试重点

- 登录页服务器地址设置。
- 登录注册。
- 文件列表和目录导航。
- 搜索。
- 上传下载。
- 分享、收藏、标签、版本、回收站。
- 图片、文本、视频预览。
- token 过期或网络失败提示。

Android E2E 应只在模拟器上跑真实 UI，并连接 Docker 后端。服务器地址通过 Gradle instrumentation 参数注入。

## 黑盒 API 测试

黑盒测试不依赖源码内部结构，只通过 HTTP 验证：

- 服务可用性。
- 认证流程。
- 文件上传下载。
- 分享公开访问。
- 管理员接口。
- 错误码和响应格式。

适合在 Docker Compose 环境启动后执行，用于验证打包镜像和运行配置。

## 回归优先级

变更类型与最小测试集：

| 变更 | 最小测试 |
|------|------|
| 后端业务逻辑 | 相关 service 单测 + controller/security 测试 |
| 数据库迁移 | Flyway 启动 + 相关黑盒测试 |
| 前端页面 | Vitest + 相关 Playwright |
| Tauri Rust | cargo test + Tauri E2E 冒烟 |
| Android UI | connectedDebugAndroidTest 相关用例 |
| Docker 配置 | docker compose config + 服务启动验证 |
| 日志/Grafana | 后端测试 + 未登录/管理员访问验证 |

## 失败诊断

测试失败时优先记录：

- 后端 requestId。
- traceId。
- HTTP 状态码和业务错误码。
- 测试用户和资源名。
- 容器状态。
- 客户端截图或视频。

## 扩展建议

- 每次修复 bug 都补对应测试。
- 传输类功能必须覆盖暂停、恢复、取消和失败重试。
- 管理员和安全变更必须覆盖无权限路径。
- E2E 数量应聚焦关键路径，细节逻辑放单测。
