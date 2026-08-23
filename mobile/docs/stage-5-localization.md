# 阶段 5 国际化基础

## 交付边界

阶段 5 将阶段 0～4 的界面、无障碍描述、稳定错误、Debug 诊断、文件选择器和后台传输通知迁移到共享资源，应用版本为 `6 / 0.6.0-stage5`。本阶段支持默认英语以及中国大陆、新加坡两套简体中文资源，不增加繁体翻译，不修改业务页面、后端协议、Room schema、传输状态机或旧 Android 应用。

当前 Compose Multiplatform 1.11.1 只支持语言和地区资源限定符，不支持 `Hans/Hant` 脚本限定符。因此 KMP 明确支持 `en-US`、`zh-CN` 和 `zh-SG`：带 CN 或 SG 地区的简体标签使用对应中文目录；无地区 `zh-Hans`、繁体中文、无限定 `zh` 和其他语言回退默认英语。Android 13+ 的系统应用语言清单声明上述三个标签。应用内语言选择、账号 `preferredLocale` 同步和繁体中文留到后续阶段。

## 实现约定

- `composeResources/values` 保存完整默认英语，`values-zh-rCN` 与 `values-zh-rSG` 保存完整简体中文；三个目录使用相同且不带语言前缀的资源键。
- `MobileString` 每项只引用一个类型安全 `StringResource`，由 Compose Resources 根据当前平台资源环境选择翻译，不在 Kotlin 中维护中英文资源对。
- `PlatformLocaleProvider` 提供有序 BCP 47 标签；公共 resolver 只识别明确支持的地区，用于通知缓存和稳定有效 locale。iOS 公共代码不依赖 Android API。
- `UiMessage` 保存稳定键和参数，控制器不把翻译结果写入 `StateFlow`；传输错误继续使用 Room 中的稳定 `errorKey`。
- Android 文件选择器、OnlyOffice 状态和 WorkManager/UIDT 通知复用同一资源。后台通知在受控临界区按有效 locale 解析并缓存，解析后恢复进程原默认 locale。
- 根面包屑和临时目录名称由界面按资源渲染，服务端返回的文件名、用户名和 MIME 保持原样。
- `checkMobileI18n` 禁止生产 Kotlin 中的用户可见字符串字面量，校验三个资源目录的键集合、格式参数、空值和重复键，并禁止语言前缀资源名。
- 将来加入繁体中文时新增 TW、HK、MO 地区目录并复用现有键；在升级到支持脚本限定符的 Compose 版本前，不使用含糊的通用 `values-zh`。

## 验收记录

阶段 5 的可扩展资源结构已于 2026-08-23 完成重新验收：

- `scripts/verify-all.ps1 -Module mobile-unit` 通过，包括国际化门禁、Android host tests、Debug、Release/R8 和 iOS 公共代码元数据编译。
- `bcd_api28`（Android 9/API 28）完整 Android Device Tests 通过，共执行 20 项且无跳过、无失败。
- `bcd_api34`（Android 14/API 34）完整 Android Device Tests 通过；API 28 专用 WorkManager 用例按既有条件跳过，其余无失败。
- 设备测试使用真实 Android 资源配置验证英语、CN/SG 简体、繁体与无地区中文回退，以及界面和通知语言一致性。
- API 34 的系统应用语言清单只包含 `en-US`、`zh-CN`、`zh-SG`。
- 两台 AVD 验收完成后均已关闭，未依赖连接中的默认设备。
