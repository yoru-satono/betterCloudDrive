# 开发指南

本目录面向修改 BetterCloudDrive 代码的开发者，负责说明各端目录、启动入口、数据流、调试方法和最低验证要求。

## 专项指南

- [开发工作流](WORKFLOW.md)：事实来源、作用域、跨模块实现顺序、验证和完成定义。
- [后端开发](BACKEND.md)：七个 Maven 模块、运行、数据库、事务、调试和验证。
- [Web 开发](WEB.md)：Vue 目录、数据流、API client、本地化、主题和 Playwright。
- [Tauri 开发](TAURI.md)：Rust command、文件系统、传输恢复、构建和桌面 E2E。
- [Qt 桌面开发](../../desktop/README.md)：Windows 11 x64、uv 启动、独立 Vue 构建、传输与原生媒体验收。
- [Qt 桌面迁移前验证](QT_DESKTOP_VALIDATION.md)：独立 Windows 原型、关键链路、打包实测和迁移阻塞项。
- [KMP 移动端开发](KMP.md)：Compose Multiplatform、Ktor、Store/Repository、跨平台边界、地址和验证矩阵。
- [测试策略](TESTING.md)：测试分层、完整脚本、最低验证矩阵、数据清理和报告。

首次配置工具和组合启动请先阅读[开发环境准备](../getting-started/DEVELOPMENT_SETUP.md)。

## 共通规则

- 开发指南不复制 API 字段和环境变量定义；精确定义链接到 `reference`。
- 修改共同协议时同时检查后端、Web/Tauri 和 KMP 移动端。
- 新功能包含加载、成功、空、失败、权限拒绝与恢复路径。
- 测试选择以修改边界和风险为依据，不以单纯测试数量代替场景完整性。
