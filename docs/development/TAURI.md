# Tauri 开发指南

本文面向修改桌面端原生能力的开发者。Tauri 复用 Vue 应用，Rust 层只负责浏览器无法可靠完成的本地文件系统、传输队列和平台设置。

## 边界

`frontend/src-tauri/src` 当前分为：

| 文件 | 职责 |
|------|------|
| `settings.rs` | API/分享地址、本地下载目录和传输限速 |
| `transfer.rs` | 任务/节点状态、暂停恢复取消、队列持久化 |
| `upload.rs` | 本地文件与目录遍历、分片上传、恢复 |
| `download.rs` | 文件/目录下载、目标选择、本地写入 |
| `lib.rs` | plugin、共享 state 和 command 注册 |

业务权限、团队状态和 API 字段仍由共享 TypeScript client 与后端决定。Rust command 不应复制服务端权限或建立另一套 API envelope。

## 前置条件与启动

- Node/pnpm 与 Web 开发一致。
- Rust 1.88.0，由 `frontend/src-tauri/rust-toolchain.toml` 固定。
- Windows 需要 WebView2 和 C++ 构建工具；Linux 需要 Tauri 2 对应的 WebKitGTK 4.1 系统依赖。

```bash
cd frontend
pnpm install
pnpm run tauri:dev
```

默认 API 是 `http://127.0.0.1:8080/api/v1`，默认 Web 分享地址是 `http://127.0.0.1:3000`。登录页和桌面设置可调整地址；调试目录上传/下载时确认应用具有目标目录权限。

桌面端冷启动会在挂载主界面前校验本地会话。过期、吊销或无法恢复的凭据会被清除，并通过 Hash 路由直接进入登录页；运行中会话失效也遵循同一路径。全局提示由固定定位的 Toaster 承载，自动消失且最多同时显示三条，不能参与主页面布局或无限挤压内容区。

Linux Wayland 下应用保持原生 Wayland backend。为规避部分 WebKitGTK/显卡组合在 DMA-BUF 分配失败后触发的 GDK protocol error，应用会在调用方未显式配置时于 WebView 初始化前设置 `WEBKIT_DISABLE_DMABUF_RENDERER=1`，不会回退到 XWayland。

## Command 设计

- 参数和返回值保持可序列化、稳定且不暴露绝对内部状态。
- 长任务立即返回 task ID，通过传输队列事件/查询更新进度，不阻塞 UI command。
- 路径必须规范化并限制在用户明确选择的目录内，防止 `..`、符号链接或错误拼接越界。
- 暂停、恢复、取消和应用重启恢复都应保持幂等。
- Rust `ApiEnvelope` 必须与后端 `code/messageKey/messageParams/data` 保持一致，不恢复已移除的自然语言 `message` 字段。
- token、密码和完整敏感 URL 不写日志或持久传输记录。

## 构建与单元测试

```bash
cd frontend/src-tauri
cargo fmt --check
cargo test --locked

cd ..
pnpm run tauri:build
```

普通 bundle 不包含 E2E instrumented WebDriver。修改 command 注册、Tauri config、capability 或 plugin 时，应验证 release bundle，而不仅是 Web dev server。

## 桌面窗口 E2E

```bash
cd frontend
pnpm run tauri:e2e:build
pnpm run test:e2e:tauri
```

测试复用 Web E2E API helper，因此默认 Compose 后端栈必须运行。

Windows 需要 `tauri-driver` 与匹配 WebView2 的 EdgeDriver，可通过 `TAURI_DRIVER_PATH`、`EDGEWEBDRIVER_PATH` 指定。Linux E2E 使用 instrumented embedded WebDriver，需要图形显示或 `xvfb-run`，不要求普通 `tauri-driver`；测试入口也会为 WebKitGTK 默认禁用有兼容问题的 DMA-BUF renderer。

Linux 推荐运行 `./scripts/verify-all.sh --module tauri`，脚本会临时放宽 E2E 限流并在结束后恢复。`TAURI_E2E_APP_PATH` 可指定不同可执行文件。

## 修改检查

| 修改 | 验证 |
|------|------|
| 纯 Rust helper/状态机 | `cargo test --locked` |
| command 参数或 TypeScript bridge | Rust 单测 + 前端 API 单测 |
| 传输恢复/持久化 | Rust 单测 + 真实窗口 E2E |
| 路径和文件写入 | 临时目录测试、取消/失败清理和越界用例 |
| Tauri 配置/plugin/capability | E2E build + release build |

共享 Vue 页面规范见 [Web 开发指南](WEB.md)，整体客户端边界见[客户端架构](../architecture/CLIENTS.md)。

桌面 E2E 的 API 请求与连接设置断言统一使用 `E2E_BACKEND_URL`（默认 `http://127.0.0.1:8080`），Web 分享地址使用 `E2E_BASE_URL`（默认 `http://127.0.0.1:3000`）。宿主机端口冲突时可通过进程环境覆盖，并对应设置 Compose 的 `HOST_BACKEND_PORT`。

下载刷新场景使用真实签名的过期 JWT，并先验证服务端返回 `401001`；测试签名密钥从进程 `JWT_ACCESS_SECRET` 或本地 `config/env-files/security.env` 读取。格式非法的 Token 用于验证退出登录，不用于模拟过期刷新。
