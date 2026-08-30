# 阶段 11.5：KMP 架构边界收口

## 目标

本次重构不改变后端契约、数据库、导航路径、版本号或用户可见行为。目标是在团队功能扩展前消除全应用状态、业务 Controller、单体网络客户端和全局 actions 集合，使后续功能能按业务域独立演进。

## 最终结构

```text
Compose 页面
  -> 对应 feature Store / StateFlow
  -> 窄 Repository
  -> 领域 API
  -> AuthenticatedHttpClient

AppCoordinator
  -> 启动与会话生命周期
  -> 退出、换服务器和会话失效的全局 reset
```

- `AuthenticatedHttpClient` 是唯一 Token 所有者，提供单飞刷新、一次重放、统一响应解析、受控流式响应和会话失效事件。
- 领域 API 划分为认证、个人文件、个人整理、个人分享、预览和传输；各自不管理 Token。
- Repository 按个人文件、收藏、标签、分享和预览拆分，`TransferEngine` 只依赖传输 API。
- 业务 Store 分为认证、个人浏览、搜索、收藏、标签、分享、预览、传输队列和诊断。每个 Store 有独立子 `CoroutineScope`、作业、缓存和 `reset()`。
- `AppCoordinator` 不导入文件、标签、分享、预览或分页模型。上传完成刷新通过明确的目录完成事件连接传输与浏览。
- 导航按个人浏览、搜索、整理、分享、预览和产品工具分组注册。根组件只收集认证/主题与未结束传输数，目的地只收集自己的 Store。

## 安全与生命周期不变式

- 退出、有效的服务器切换或会话失效会精确重置每个 Store 一次。
- 分享明文密码、媒体 ticket、预览内容和页面缓存在重置时同步清除。
- 领域 API 不能访问安全会话存储，也不能自行重放请求。
- 页面不直接持有 `NavController`、`AppContainer` 或其他 Store。

## 验收记录

2026-08-30 完成架构收口验收：

- 生产代码中已不存在 `AppController`、`AppUiState`、`ProductShellActions`、`BackendClient` 或单体 `FileRepository`。`AppCoordinator` 对文件、标签、分享、预览和分页模型的导入数为零。
- 74 项 KMP 主机测试通过，覆盖领域 API 契约、并发刷新、流式响应、各 Store 边界、全局重置与敏感状态清除；重构前的目录分页、失败保留、搜索取消、整理与分享断言已迁移到对应 Store 测试，没有保留第二套聚合状态夹具。
- `mobile-unit` 验证通过，包含国际化门禁、Debug、Release/R8 和 iOS 公共代码元数据编译。
- Android 9/API 28 与 Android 14/API 34 均完成 27 项设备测试套件；分别覆盖 WorkManager 与 UIDT，并回归 Keystore、Room、SAF、流式下载、窄屏/宽屏导航、文件管理、传输页面、搜索与标签页面边界，以及英语、CN/SG 简体和繁体中文回退资源环境。
- 最终验证运行编号为：`mobile-unit` `bcd_20260830_200239_31676`、API 28 `bcd_20260830_201923_27504`、API 34 `bcd_20260830_201819_33660`、可观测性 `bcd_20260829_123123_23036`。
- 完整 Docker 栈重新构建并健康启动，7 项可观测性验收通过。
- API 34 真实旅程使用一次性账号登录并加载预置根目录；`am force-stop` 后重开能恢复会话与目录，Logcat 无崩溃或 ANR。账号、服务端数据与应用数据已清理。
