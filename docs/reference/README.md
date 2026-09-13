# 参考资料

本目录保存需要精确查询的当前协议和配置。Reference 描述“是什么”，不负责解释完整设计动机或提供生产值班流程。

## 当前资料

- [API 总入口](API.md)：基础地址、统一响应、错误码、通用约定和[业务域索引](api/README.md)。
- [配置与部署](CONFIGURATION.md)：Compose、环境变量、反向代理、后端配置和上线检查。
- [本地化协议](LOCALIZATION.md)：服务端语义 key 与 Web、Tauri、KMP 移动端文案约定。
- [测试策略迁移指针](TESTING.md)：旧链接兼容入口；当前正文位于[开发测试策略](../development/TESTING.md)。

## 维护边界

- HTTP 基础地址、统一响应、错误码、认证、分页和跨域结构只在 `API.md` 维护。
- 具体端点按业务域维护在 `api/`，入口和主题清单见 [API 业务域目录](api/README.md)。
- `CONFIGURATION.md` 仍是配置的单一权威文件；只有在部署流程和变量查询明显妨碍使用时才继续拆分。
- 测试策略正文已经迁入 `development`，本目录中的旧路径只用于兼容既有链接。
