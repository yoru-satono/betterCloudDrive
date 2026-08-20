# 架构决策记录

本目录保存已经接受的重要技术和产品边界决策。ADR 解释背景、选择、替代方案和后果；当前运行方式仍应由架构、参考和运维文档描述。

## 当前决策

- [ADR-001 Android 全局管理范围](001-android-admin-scope.md)：全局管理保留在 Web/Tauri，Android 聚焦成员和所属团队工作流。
- [ADR-002 RabbitMQ 运行时投递](002-message-queue-selection.md)：PostgreSQL 保持任务/Outbox 事实，RabbitMQ 负责可靠投递。
- [ADR-003 模块化单体](003-modular-monolith.md)：七个 Maven 模块组装为一个 Spring Boot 部署单元。
- [ADR-004 元数据与对象内容分离](004-metadata-object-storage.md)：PostgreSQL 与 S3 兼容存储共同构成业务恢复状态。
- [ADR-005 团队文件权限解析](005-team-file-permissions.md)：最近祖先优先，同一节点 USER 优先 ROLE。
- [ADR-006 客户端边界](006-client-boundaries.md)：Web/Tauri 复用 Vue，Android 独立实现并共享服务端协议。
- [ADR-007 OnlyOffice 异步保存](007-onlyoffice-async-save.md)：回调预留持久任务，Worker 完成对象和版本写入。
- [ADR-008 语义本地化协议](008-semantic-localization.md)：服务端返回稳定 key/params，客户端负责自然语言渲染。

## 格式与状态

新增 ADR 使用 `NNN-short-title.md` 命名，编号只表示接受顺序。正文包含：

1. 状态和日期。
2. 背景与约束。
3. 最终决策。
4. 考虑过的替代方案。
5. 正面和负面后果。
6. 对应实现和验证位置。

状态使用“提议”“已接受”“已取代”或“已拒绝”。已经实施的决策不能直接重写为相反结论；应新建 ADR，并在新旧文档中互相标明取代关系。ADR 不复制完整配置、接口或操作步骤，只链接当前权威文档。
