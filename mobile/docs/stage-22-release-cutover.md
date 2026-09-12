# KMP 移动端迁移阶段 22：发布与切换

## 结论与范围

阶段 22 于 2026-09-12 收口。旧 Android 的 78 项测试方法已逐组核对业务意图，并迁移到 KMP 共享主机测试、Android 设备测试或现有真实 API 黑盒验收层。旧架构反射、实现细节和重复测试基础设施不再保留；仓库中的旧 `android/` 工程已删除。

KMP Android 的正式应用 ID 为 `com.betterclouddrive.mobile`，当前发布版本为 `versionCode=23`、`versionName=1.0.0`。这与旧应用 `com.betterclouddrive.android` 是不同包，不做覆盖安装，也不迁移旧令牌、偏好、缓存或传输记录。移动端 OnlyOffice 继续强制只读，iOS 应用发布和全局管理员移动界面不在本阶段范围内。

Release 签名通过 `BCD_RELEASE_STORE_FILE`、`BCD_RELEASE_STORE_PASSWORD`、`BCD_RELEASE_KEY_ALIAS` 和 `BCD_RELEASE_KEY_PASSWORD` 注入；签名文件和密码不得进入仓库。

## 测试行为审计

迁移以业务行为为单位，不复制旧类名和实现结构。下表覆盖旧工程全部 48 项 JVM 测试方法。

| 旧测试组 | 数量 | 处置 | KMP/替代证据 |
| --- | ---: | --- | --- |
| `UtilityBehaviorTest` | 3 | 调整后迁移 | `ServerAddressTest`、`PreviewClassificationTest`、`PersonalFormattingTest`；负数大小归零，不保留旧图标颜色实现断言 |
| `DeepLinkParserTest` | 3 | 调整后迁移 | `TeamInvitationStoresTest`；只接受严格自定义 scheme，旧 localhost HTTP 入口删除 |
| `ThemeTest` | 2 | 迁移 | `ThemeBehaviorTest` 覆盖存储值回退和系统/浅色/深色解析 |
| `TeamEffectivePermissionTest` | 4 | 分层迁移 | `DomainApiContractTest`、`TeamAuthorizationStoresTest` 与团队权限黑盒 |
| `UploadRepositoryTest` | 3 | 分层迁移 | `TransferEngineTest`、`DomainApiContractTest`、Android 流式传输设备测试 |
| `TransferRepositoryTest` | 2 | 迁移 | `TransferModelsTest`、`TransferManagerTest` 与 Room 设备测试 |
| `TransferStateTest` | 3 | 迁移 | `TransferBatchExecutorTest`、`TransferManagerTest`、传输设备场景 |
| `AuthRepositoryTest` | 2 | 迁移 | 认证 Store 边界与 `AuthenticatedHttpClient` 契约测试 |
| `ApiCallHelperTest` | 2 | 迁移 | HTTP envelope、结构化错误和领域 API 契约测试 |
| `NetworkInterceptorsTest` | 6 | 迁移 | 单飞刷新、一次重放、服务器重写、会话失效和日志脱敏测试 |
| `ApiServiceContractTest` | 12 | 迁移 | `DomainApiContractTest` 覆盖个人、团队、邀请、通知、审计和任务路由 |
| `TeamTasksViewModelTest` | 3 | 迁移 | `TeamGovernanceStoresTest`、`TeamGovernanceRepositoriesTest`、设备任务中心场景 |
| `ViewModelSurfaceTest` | 2 | 删除旧约束 | 反射检查只保护旧 ViewModel 表面；KMP 由显式 Store 状态与动作测试替代 |
| `RepositorySurfaceTest` | 1 | 删除旧约束 | 反射检查只保护旧 Repository 表面；KMP 由窄 Repository 和 API 契约替代 |
| **合计** | **48** |  | 无遗漏 |

下表覆盖旧工程全部 30 项 instrumentation 测试方法。

| 旧测试组 | 数量 | 处置 | KMP/替代证据 |
| --- | ---: | --- | --- |
| `AndroidAuthAndNavigationE2ETest` | 5 | 分层迁移 | 认证 Store、`ProductShellDeviceTest`、真实认证黑盒 |
| `AndroidCoreE2ETest` | 4 | 分层迁移 | `FeatureStoreTest`、产品外壳/文件设备测试、个人文件黑盒 |
| `AndroidFilesAndSearchE2ETest` | 3 | 分层迁移 | 浏览/搜索/预览 Store、Compose 设备场景和真实 API 旅程 |
| `AndroidSharesFavoritesRecycleTagsVersionsE2ETest` | 3 | 分层迁移 | 对应个人功能 Store、设备场景和阶段 9～12 黑盒 |
| `AndroidTeamsE2ETest` | 11 | 调整后分层迁移 | 团队 Store/设备场景和阶段 13～21 黑盒；OnlyOffice 编辑改为 KMP 只读约束 |
| `AndroidTransferE2ETest` | 2 | 分层迁移 | 传输引擎、Android 流式/调度设备测试和 64 MiB 真实旅程 |
| `TeamTasksContentTest` | 2 | 迁移 | `FeatureScreenDeviceTest` 的任务状态、重试与保存行为 |
| **合计** | **30** |  | 无遗漏 |

`MainDispatcherRule`、`E2EConfig`、`E2EApiClient`、`AndroidSystemUi` 和 `ComposeE2E` 是旧测试基础设施，不是测试方法。KMP 使用 `runTest`、Compose Test API、统一验证脚本和 `backend-test` 夹具，不迁移这些辅助实现。

## 主动调整或删除的旧断言

- 旧 ViewModel/Repository 方法存在性的反射测试与 KMP 的 Store/窄 Repository 架构不相容，改为可观察状态、动作和协议测试。
- 文件图标颜色、旧音频/压缩包图标分支及旧 MIME 猜测属于已删除 UI 实现细节；KMP 只测试实际预览分类和不支持类型。
- 邀请只接受 `betterclouddrive://team-invites/{token}`。旧测试用的 localhost HTTP 链接不再作为客户端深链入口。
- 旧移动端 OnlyOffice 编辑旅程不符合当前产品边界，替换为后端签发 `mobile=true` 的只读配置和无保存回调断言。
- 纯后端权限、分页、配额和资源生命周期不在 KMP UI 中重复造一套真实后端 E2E；由 KMP DTO/Store 契约加 `backend-test` 黑盒共同覆盖。

## 发布、升级与回滚

- KMP 新正式包与旧包并存时是一次全新安装；删除旧包不会自动删除新包的数据，旧包数据也不会进入新包。
- KMP 自身使用同一签名的 `versionCode=22` 基线与 `versionCode=23` 候选验证相邻升级；反向安装应被 Android 以版本降级拒绝。
- 发布记录应保存 commit、APK SHA-256、versionCode/versionName、签名证书摘要、验证矩阵、Compose 镜像 ID 和健康检查结果。
- 应用回滚使用已归档、同签名且更高 versionCode 的回滚构建；源码回溯使用 Git。旧工程不再作为仓库内运行时回滚方案。
- 服务端发布与数据回滚继续遵循[生产部署](../../docs/operations/PRODUCTION_DEPLOYMENT.md)和[升级回滚](../../docs/operations/UPGRADE_ROLLBACK.md)。

## 验收入口

```sh
bash scripts/verify-all.sh --module mobile-unit
bash scripts/verify-all.sh --module mobile-android --android-serial emulator-5556
bash scripts/verify-all.sh --module all --android-serial emulator-5556
```

KMP Android 设备测试必须在 API 28 和 API 34 上分别执行，以覆盖 WorkManager 与 UIDT。`mobile-unit` 同时验证 Android Debug/Release 和 iOS 共享源码元数据；iOS 元数据门禁不等同于 iOS 应用发布或平台测试。

## 当前验收结果

2026-09-12 的阶段收口结果：

- `mobile-unit` 通过：共享主机测试 126/126、Android Debug/Release（含 R8）和 `compileIosMainKotlinMetadata` 均成功。
- API 28 设备测试 42/42 通过；API 34 发现 43 项，42 项通过，WorkManager 专用场景按系统版本条件跳过 1 项。UIDT 场景在 API 34 通过。
- `all --android-serial emulator-5556 --skip-docker-build` 全部 21 个步骤通过，运行 ID 为 `bcd_20260912_181713_456054`；其中后端黑盒 156/156、安全验收 7/7、Web 单元 171/171、Web E2E 30/30、OnlyOffice E2E 7/7、Tauri Rust 14/14、Tauri E2E 4/4、WebDAV 4/4、观测 7/7、韧性 2/2，最终数据清理成功。
- 使用仓库外临时 PKCS12 对 KMP `versionCode=22` 基线和 `versionCode=23` 候选进行同签名升级演练：覆盖安装成功且 UID 保持 `10195`，反向安装被 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝。测试证书 SHA-256 为 `1a15e00dbd713969d5eaad51f9734db8fe54b00505ebd6ee4f630ae0fae86f91`，基线 APK 为 `7796ea8ab35374079667928c3fad522daa185da8cb18eec94c2bacebbc7a3f65`，候选 APK 为 `4fd1f4e308410d1a004951a376cca27bc7bdaac945a3021ae1791a18c871235c`。临时密钥与 APK 已删除；这些摘要只证明本地演练，不是生产签名或发布产物。
- 首次 `all` 尝试在安装 Debug 测试包时遇到本地临时候选包的签名冲突；移除该演练安装后，同一实现的完整复跑通过。该问题属于设备前置状态，不是实现失败，当前没有未运行或遗留失败项。
