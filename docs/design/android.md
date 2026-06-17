# Android 客户端概要设计

## 职责

Android 客户端提供移动端云盘体验，覆盖登录注册、服务器地址配置、文件浏览、搜索、上传下载、预览、分享、收藏、标签、回收站、版本、个人资料和传输队列。它通过后端 API 操作业务数据，不直接访问数据库、Redis 或对象存储。

## 不负责的内容

- 不做最终权限判断。
- 不保存跨设备业务事实。
- 不绕过后端下载或上传对象。
- 不直接提供管理员后台全量能力，除非后续明确规划。

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 主语言 |
| Jetpack Compose | UI |
| Material 3 | 设计组件 |
| Hilt | 依赖注入 |
| Retrofit | API 调用 |
| OkHttp | HTTP client 和拦截器 |
| Kotlin Serialization | JSON 编解码 |
| DataStore Preferences | 本地轻量配置 |
| Coil | 图片加载 |
| Media3 | 视频预览 |
| AndroidX Test / UIAutomator | E2E 测试 |

## 分层结构

```text
UI Layer
  Screen
  Components
  Navigation

Presentation Layer
  ViewModel
  UI State
  User Actions

Data Layer
  Repository
  ApiService
  DTO
  Interceptors

Local Layer
  TokenManager
  ServerConfigStore

Domain Layer
  FileItem
  User
  ShareLink
  Tag
  UploadSession
```

## 目录职责

| 目录 | 职责 |
|------|------|
| `ui/auth` | 登录、注册、忘记密码 |
| `ui/files` | 文件浏览和操作 |
| `ui/search` | 搜索结果 |
| `ui/preview` | 图片、文本、视频预览 |
| `ui/shares` | 分享管理 |
| `ui/favorites` | 收藏 |
| `ui/tags` | 标签和标签文件 |
| `ui/recyclebin` | 回收站 |
| `ui/versions` | 文件版本 |
| `ui/transfer` | 传输队列 |
| `data/repository` | 各领域数据访问 |
| `data/remote` | Retrofit 接口和 DTO |
| `data/local` | token 和服务器地址 |
| `di` | Hilt 模块 |

## 网络设计

Android 端支持用户自定义服务器地址。设计上 Retrofit 需要一个固定 baseUrl，但真实请求通过 `ServerBaseUrlInterceptor` 动态改写。

请求链路：

1. Repository 调用 `ApiService`。
2. OkHttp 请求进入 `ServerBaseUrlInterceptor`。
3. 拦截器读取 `ServerConfigStore` 中的服务器地址并重写 URL。
4. `AuthInterceptor` 添加 access token。
5. `TokenRefreshInterceptor` 处理 token 过期和刷新。
6. 请求发送到后端。

这样可以避免把真实部署地址硬编码到 App 中，同时保留构建期默认地址。

## 本地存储

| 组件 | 内容 | 说明 |
|------|------|------|
| `TokenManager` | access token、refresh token、过期信息 | 登录态 |
| `ServerConfigStore` | 自定义服务器地址 | 登录页设置 |

本地存储使用 DataStore Preferences。它适合保存轻量键值数据，不适合作为大量文件元数据缓存。

## 导航设计

`BCDNavHost` 管理主要页面。`MainScaffold` 提供登录后的主布局。`Screen` 定义路由目标。预览页面通过 `PreviewRouterViewModel` 按文件类型分发到图片、文本或视频预览。

导航原则：

- 登录前只能进入认证相关页面。
- 登录后进入文件页。
- 文件夹点击进入下级目录。
- 文件点击进入预览。
- 搜索结果点击文件夹应同步当前路径。

## UI 状态设计

ViewModel 负责：

- 加载状态。
- 错误状态。
- 分页或列表数据。
- 当前目录或当前筛选条件。
- 用户动作触发的 Repository 调用。

Composable 负责：

- 渲染 UI。
- 收集 state。
- 发出用户动作。
- 不直接持有长期业务状态。

## 文件能力

Android 端文件能力包括：

- 列表和目录进入。
- 新建文件夹。
- 重命名、移动、复制、删除。
- 搜索。
- 收藏和取消收藏。
- 标签管理。
- 回收站恢复和彻底删除。
- 文件版本查看和删除。
- 文件预览。

文件操作后应刷新当前列表或执行局部更新，并处理后端返回的业务错误。

## 上传下载设计

上传：

1. 用户选择本地文件。
2. Repository 初始化上传会话。
3. 按后端分片大小上传。
4. 更新传输队列状态。
5. 完成后刷新远端目录。

下载：

1. 用户点击下载。
2. Repository 请求后端下载接口。
3. 写入 Android 可访问的位置。
4. 更新传输队列状态。

移动端后续应重点补齐：

- 后台传输。
- 系统通知。
- 网络切换后的恢复。
- 大文件断点续传。
- 文件夹上传/下载策略。

## 分享设计

Android 端分享管理应遵循后端安全模型：

- 列表只显示 `hasPassword`，不默认显示密码原文。
- 用户主动查看密码时调用认证接口读取。
- 创建和编辑分享时密码只在当前交互中可见。
- 公开分享访问需要按后端返回状态展示密码输入、过期或失效。

## 错误处理

| 场景 | 处理 |
|------|------|
| 服务器地址无效 | 登录页设置直接校验并提示 |
| token 过期 | 自动刷新，失败后清除登录态 |
| 网络不可达 | 保留页面状态，提示重试 |
| 上传失败 | 传输队列标记失败 |
| 权限不足 | 显示无权限并返回安全页面 |
| 后端业务错误 | 显示后端 message，必要时附 requestId |

## E2E 测试设计

Android E2E 在模拟器上连接真实 Docker 后端，覆盖：

- 登录、注册和服务器地址配置。
- 文件列表、目录进入、搜索。
- 上传、下载和传输队列。
- 分享、收藏、标签、版本、回收站。
- 预览页面。

测试地址通过 Gradle 从环境变量或 `config/env-files` 注入。模拟器访问宿主服务时通常使用 `10.0.2.2` 或指定的 localhost 端口映射。

## 扩展建议

- 新接口先补 DTO 和 `ApiService`，再补 Repository。
- 新页面先定义 `Screen`，再接入 `BCDNavHost`。
- 大型交互应进入 ViewModel，不要塞进 Composable。
- 涉及系统文件读写时优先使用 Android 标准存储访问框架。
