# BetterCloudDrive KMP 移动端

`mobile/` 是当前活跃的 Kotlin Multiplatform 移动端工程。共享源码包含业务 API、认证、Repository、Store、Compose UI、导航、传输状态机和本地化资源；Android 已有完整应用壳和设备验证，iOS 当前保持共享源码元数据可编译，尚无应用壳或平台测试。

## 当前基线

- Kotlin 2.4.10、Compose Multiplatform 1.11.1、Ktor 3.5.1、Room 2.8.4。
- Android Gradle Plugin 9.1.1、Gradle 9.3.1，`compileSdk/targetSdk=37`、`minSdk=26`。
- Android 应用 ID 为 `com.betterclouddrive.mobile`，当前版本为 `versionCode=23`、`versionName=1.0.0`。
- `shared` 声明 Android、`iosArm64` 和 `iosSimulatorArm64` 目标；未配置桌面、JS、Wasm 或 Web 目标。

## 工程范围

```text
mobile/
├── androidApp/                    # Android Application、Manifest 与设备测试
└── shared/src/
    ├── commonMain/                # 共享 UI、Store、Repository、API、模型与平台契约
    ├── commonTest/                # 共享业务行为、状态机与协议测试
    ├── androidMain/               # Keystore、SAF、Room、调度、通知和媒体实现
    └── androidHostTest/           # Android/JVM 平台边界测试
```

共享代码采用 Compose Screen → feature Store → 窄 Repository / `TransferEngine` → domain API / `AuthenticatedHttpClient` / Room → 平台接口的依赖方向。Android 平台能力不进入 `commonMain`；后续 iOS 应用壳应复用现有共享业务边界，并补齐对应平台服务与测试。

当前产品范围覆盖认证与账号设置、个人和团队文件工作流、搜索与只读预览、可恢复上传下载、收藏、标签、分享、回收站、历史版本、成员与权限、邀请与通知、团队生命周期、审计和任务中心。移动端 OnlyOffice 保持只读，全局管理员界面不在当前完整支持范围内。

## 构建与验证

从仓库根目录运行：

```bash
bash scripts/verify-all.sh --module mobile-unit
bash scripts/verify-all.sh --module mobile-android --android-serial emulator-5556
```

`mobile-unit` 包含共享主机测试、Android Debug/Release 构建和 `-PenableIos=true` 的 iOS 共享源码元数据编译。`mobile-android` 需要显式指定 adb 设备；涉及调度、SAF、Keystore、通知或其他设备能力时，应分别在 Android 9/API 28 和 Android 14/API 34 上运行。

完整工程结构、服务器地址、平台边界和修改矩阵见 [KMP 移动端开发指南](../docs/development/KMP.md)，统一测试入口见[测试策略](../docs/development/TESTING.md)。迁移过程和历史验收只在 [`docs/`](docs/) 下的阶段记录中保留。
