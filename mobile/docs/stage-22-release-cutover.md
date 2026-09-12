# KMP 移动端迁移阶段 22 前半部分：发布准备与回归

## 范围

本阶段记录阶段 22 的前半部分：旧 Android 行为矩阵映射、KMP 稳定性回归、同包名签名 Release 与相邻版本升级验证、发布说明、监控检查和应用级回滚准备。

本阶段不评估旧 Android 应用停止维护或下线，不验证旧 Android 包到 KMP 包的覆盖升级，也不迁移旧应用的本地数据。旧 `android/` 只作为行为和测试来源，不构建、不运行。

KMP 继续使用当前包名 `com.betterclouddrive.mobile.spike`。Release 签名通过 `BCD_RELEASE_STORE_FILE`、`BCD_RELEASE_STORE_PASSWORD`、`BCD_RELEASE_KEY_ALIAS` 和 `BCD_RELEASE_KEY_PASSWORD` 注入，签名文件和密码不得进入仓库。

## 行为矩阵

| 旧 Android 场景来源 | KMP 验收入口 | 当前证据 |
| --- | --- | --- |
| 认证、启动恢复、顶层导航 | `ProductShellDeviceTest`、`FeatureScreenDeviceTest`、`mobile-unit` | 已通过 |
| 个人目录、搜索、详情、列表/网格和分页 | KMP Store/Compose 测试、API 黑盒、API 28/34 设备套件 | 已通过阶段 4～12验收 |
| 上传、下载、暂停、恢复、进程重建和 SAF | `TransferEngineTest`、`TransferManagerTest`、`AndroidStreamingDownloadDeviceTest`、API 28/34 | 已通过阶段 2、7、12验收 |
| 个人分享、收藏、标签、回收站和版本 | 对应 KMP Store/Compose 测试、黑盒和设备旅程 | 已通过阶段 9～12验收 |
| 团队浏览、文件协作、成员、权限、邀请和通知 | 团队 Store/Compose 测试、黑盒、Web/Tauri、API 28/34 | 已通过阶段 13～16验收 |
| 团队收藏、标签、分享、回收站和版本 | KMP 团队 Store/Compose 测试、黑盒、API 28/34 | 已通过阶段 17～19验收 |
| 团队改名、退出、删除、所有权转让 | `TeamManagementStoreTest`、Compose、阶段 20 黑盒、API 28/34 | 已通过阶段 20验收 |
| 团队审计、异步导出、任务中心、重试和结果保存 | `TeamGovernanceStoresTest`、Repository 契约、阶段 21 黑盒、API 28/34 | 已通过阶段 21验收 |

矩阵验证的是行为对等，不要求旧 Android 测试类逐个复制到 KMP。旧 Android 测试文件继续保留，但不属于当前构建和验证入口。

## 稳定性回归

- API 28 和 API 34 各执行多轮 `mobile-android`，每轮检查测试结果、Logcat 崩溃/ANR、Room/临时文件/SAF 权限终态。
- 个人和团队传输覆盖暂停、恢复、进程终止、重新启动、权限变化和系统调度；API 28 关注 WorkManager，API 34 关注 UIDT。
- Compose 全栈回归继续使用 `scripts/verify-all.sh` 的唯一运行 ID和清理路径，不把测试残留作为成功证据。

## 签名与升级

使用同一测试签名生成当前版本基线 APK 和递增 `versionCode` 的候选 APK，在同一 AVD 上验证：

1. 基线 APK 安装、登录和基础数据创建。
2. 候选 APK 覆盖升级后，包名、签名、版本号和本地 Room/DataStore/Keystore 数据保持有效。
3. 旧版本 APK 降级安装被 Android 拒绝或明确失败。
4. Release/R8、`apksigner verify` 和 SHA-256 校验通过。

这只验证 KMP 自身的相邻版本升级，不验证旧 `android/` 到 KMP 的升级安装。

## 发布、监控与回滚

发布记录至少保存 commit、APK SHA-256、versionCode/versionName、签名证书摘要、验证矩阵、Compose 镜像 ID 和健康检查结果。上线前沿用[生产部署](../../docs/operations/PRODUCTION_DEPLOYMENT.md)与[升级回滚](../../docs/operations/UPGRADE_ROLLBACK.md)的备份、维护窗口、监控和应用级回滚步骤。

旧 Android 停维护、下线和最终切换决策不属于本阶段前半部分。

## 当前验收结果

2026-09-12 已完成前半部分验收：

- 阶段 0～21 的后端、集成、黑盒、安全、Web、Tauri、可观测性、韧性、KMP 主机和 API 28/34 设备门禁均有记录。
- API 28 重复设备回归 2 轮，每轮 42/42 通过；API 34 重复设备回归 2 轮，每轮 43 项，其中 1 项 WorkManager 条件用例按预期跳过、0 失败。
- 使用仓库外测试 keystore 构建同包名 `versionCode=22` 基线和 `versionCode=23` 候选。基线安装成功，候选覆盖升级成功并保持相同签名证书；version 22 反向安装被 Android 正确拒绝，返回 `INSTALL_FAILED_VERSION_DOWNGRADE`。
- 基线 APK SHA-256 为 `25c877655c27f89b17279fc7e07654d9b58284e528bb4573817e42e8f4001bba`；候选 APK SHA-256 为 `6c36ca19577b6294cbd63f980bf58b49001d33f11695ee220d176c287438bf34`。测试签名证书 SHA-256 为 `8d8a355727b47b2a003841f73a1d4c7d50c82ea9f537388732decb1e933628eb`，仅用于本地演练，不是生产密钥。
- 旧应用停维护、下线和最终切换决策明确留到阶段 22 后半部分；旧 Android 到 KMP 的跨包升级不在本阶段范围内。
