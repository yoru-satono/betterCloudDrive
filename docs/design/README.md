# 设计文档索引

本文档集按子系统划分，描述系统职责、边界、依赖、核心流程、关键数据、失败处理、测试关注点和扩展建议。它定位为“概要设计”，用于理解和维护系统；具体 API 见 [API.md](../API.md)，配置和部署参数见 [CONFIGURATION.md](../CONFIGURATION.md)。

## 阅读顺序

1. [总体架构](./architecture-overview.md)
2. [数据与存储](./data-storage.md)
3. [后端服务](./backend.md)
4. [Web 前端](./frontend.md)
5. [Tauri 客户端](./tauri-client.md)
6. [Android 客户端](./android.md)
7. [可观测性](./observability.md)
8. [部署与运行](./deployment.md)
9. [测试体系](./testing.md)

## 文档边界

- 本目录说明“为什么这样设计”和“各模块如何协作”。
- [API.md](../API.md) 说明接口路径、参数和响应。
- [CONFIGURATION.md](../CONFIGURATION.md) 说明环境变量、部署命令和生产配置。
- 代码级细节以源码和测试为准。
