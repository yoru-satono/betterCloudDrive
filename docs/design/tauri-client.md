# Tauri 客户端概要设计

## 职责

Tauri 客户端是桌面端交付形态，复用 Web 前端 UI，并通过 Rust 提供浏览器缺失的本地能力。重点能力包括本地目录选择、文件夹递归上传、文件夹直接下载、断点续传、传输队列、限速、代理、默认下载位置、无边框窗口和桌面端设置。

## 不负责的内容

- 不绕过后端权限直接访问对象存储。
- 不在本地保存服务端业务真相。
- 不在 Rust 侧重新实现后端业务规则。
- 不把桌面端能力强加给浏览器端。

## 架构

```text
Vue UI
  Settings Page
  Transfer Queue
  File Browser
  Upload / Download Actions

Tauri Bridge
  invoke(command)
  listen(event)

Rust Core
  settings.rs
  transfer.rs
  upload.rs
  download.rs

Local OS
  File picker
  File system
  App config dir
  Network stack / proxy
```

## Rust 模块

| 模块 | 职责 |
|------|------|
| `lib.rs` | 注册 Tauri command、插件和共享状态 |
| `settings.rs` | 读写设置、归一化限速/并发/代理、构建 HTTP client |
| `transfer.rs` | 统一传输队列、树形节点、状态聚合、暂停恢复取消 |
| `upload.rs` | 本地文件读取、目录遍历、MD5、分片上传、续传 |
| `download.rs` | Range 下载、本地写入、目录创建、递归文件夹下载 |

## Tauri command 边界

前端通过 command 调用 Rust：

- 设置：读取、保存、选择默认下载目录。
- 队列：读取队列、恢复未完成任务、暂停、恢复、取消、清除完成项。
- 上传：上传文件、上传文件夹、取消上传。
- 下载：下载文件、下载文件夹、选择目录、写入指定路径、创建子目录。

Command 应只暴露必要参数。token 和 API 地址由前端传入，Rust 不自行管理登录态。

## 本地配置

配置保存到 Tauri `app_config_dir()`：

| 文件 | 内容 |
|------|------|
| `settings.json` | 上传/下载限速、并发数、默认下载位置、代理模式和代理凭据 |
| `transfers.json` | 传输任务、节点树、进度、远端/本地路径、上传会话信息 |

跨平台说明：

- 路径由 Tauri 提供，Windows/macOS/Linux 各自落到系统标准配置目录。
- 文档和代码不应硬编码 Windows 路径。
- 本地路径进入 UI 前要转换为展示路径，写文件时使用系统 Path API。

## 传输队列模型

传输队列由 `TransferTask` 和 `TransferNode` 组成。

`TransferTask` 表示一次顶层上传或下载：

- `direction`：上传或下载。
- `rootNodeId`：根节点。
- `status`：聚合状态。
- `progress`、`bytesDone`、`bytesTotal`：聚合进度。
- `apiBaseUrl`：恢复时使用的 API 地址。

`TransferNode` 表示文件或文件夹：

- `kind`：file 或 folder。
- `parentId`：树形父节点。
- `localPath`：本地路径。
- `remoteFileId` / `remoteParentId`：远端资源。
- `uploadId`、`md5Hash`、`chunkSize`、`totalChunks`：上传续传信息。
- `status`、`progress`、`bytesDone`、`bytesTotal`：节点状态。

## 状态机

```text
Pending
  -> Hashing
  -> Transferring
  -> Done

Pending / Hashing / Transferring
  -> Paused
  -> Pending

Pending / Hashing / Transferring / Paused
  -> Canceled

Any active state
  -> Error

Upload only:
  -> Instant
```

规则：

- `Done` 和 `Instant` 是成功终态。
- `Canceled` 是用户终止终态。
- `Error` 可以通过恢复动作重新进入 `Pending`。
- 暂停文件夹会暂停所有后代节点。
- 父节点状态由子节点聚合，但用户主动暂停的父节点保持暂停。

## 上传流程

### 单文件

1. 前端传入本地文件路径、目标父目录、token 和 API 地址。
2. Rust 读取文件元数据并计算 MD5。
3. 创建或更新传输任务。
4. 调用秒传接口。
5. 秒传成功则节点进入 `Instant`。
6. 秒传失败则初始化上传会话。
7. 查询缺失分片。
8. 逐片读取本地文件并上传。
9. complete 后节点进入 `Done`。

### 文件夹

1. Rust 遍历本地目录树。
2. 为目录和文件创建节点。
3. 远端逐级创建目录。
4. 文件节点按单文件流程上传。
5. 父节点进度由子节点聚合。

失败恢复：

- 已创建远端目录应保存在节点中。
- 已初始化上传会话应保留 `uploadId`。
- 恢复时先查询后端缺失分片，避免重复上传完整文件。

## 下载流程

### 单文件

1. 用户选择保存路径或使用默认下载目录。
2. Rust 检查本地已存在文件长度。
3. 如果可续传，带 `Range: bytes=<localLength>-` 请求后端。
4. 将响应追加写入文件。
5. 完成后校验字节数并更新节点状态。

### 文件夹

1. 用户选择目标目录。
2. Rust 递归请求远端目录内容。
3. 本地创建目录结构。
4. 文件逐个按单文件下载。
5. 节点进度向父目录聚合。

桌面端文件夹下载不使用 ZIP 缓存，因为用户预期是得到真实目录结构，并且每个子文件都可以暂停和恢复。

## 限速与并发

`settings.rs` 提供：

- 上传限速。
- 下载限速。
- 最大同时上传数。
- 最大同时下载数。
- 代理模式。

并发限制应在 Rust 调度层生效，前端只展示配置。限速应按实际传输字节节流，不应只限制任务数量。

## 代理设计

代理模式：

- 系统代理：使用系统默认网络行为。
- 手动代理：支持 `http://`、`https://`、`socks5://`。
- 禁用代理：构建 no-proxy client。

代理凭据保存在 `settings.json`。如果后续提高安全级别，可以迁移到系统凭据库。

## 窗口与 UI

Tauri 窗口使用无边框模式，前端提供自定义标题栏和窗口控制按钮。圆角和阴影由窗口配置与前端样式共同实现。桌面端 UI 应保持与 Web 端一致，只在本地能力入口上做差异。

## 失败处理

| 场景 | 处理 |
|------|------|
| 本地文件不存在 | 节点进入 Error，提示重新选择或取消 |
| 写入权限不足 | 节点进入 Error，提示选择其他目录 |
| 网络中断 | 保留队列，恢复时续传 |
| token 失效 | 返回错误给前端，由前端刷新或重新登录 |
| 后端 Range 不可用 | 重新完整下载或提示失败 |
| 代理配置错误 | 保存设置时校验，传输时返回明确错误 |

## 测试关注点

- `settings.rs`：配置归一化、代理校验、并发限制。
- `transfer.rs`：节点聚合、暂停恢复、取消、持久化。
- `upload.rs`：文件夹遍历、分片恢复、目录暂停。
- `download.rs`：Range 续传、目录创建、写入失败。
- E2E：真实窗口、登录、上传文件夹、下载文件夹、传输队列标签页。

## 扩展建议

- 新 Tauri command 应有对应前端 API 封装和单测。
- 传输相关状态统一进入 `transfer.rs`，避免上传下载各自维护不兼容队列。
- 涉及本地文件系统的功能必须验证 Windows、macOS、Linux 路径差异。
- 如果引入系统通知，应把通知作为传输状态的订阅者，而不是状态源。
