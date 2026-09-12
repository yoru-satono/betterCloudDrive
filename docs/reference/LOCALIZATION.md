# 客户端本地化与语义文案协议

BetterCloudDrive 的服务端 API 不返回面向用户的自然语言。服务端只传递稳定的文案键和结构化参数，由 Web/Tauri 与 Android 使用本地简体中文或英文资源渲染；选择原因见[语义本地化 ADR](../decisions/008-semantic-localization.md)。

后端账号偏好接受 `en-US`、`zh-Hans`、`zh-CN` 和 `zh-SG`。未登录时使用当前设备已保存的选择，首次访问按系统语言选择；注册会保存当前语言。登录后以账号的 `preferredLocale` 为准，并同步到当前设备。全新数据库实例中的账号默认使用 `zh-Hans`。

## API 响应

所有 JSON API 响应使用以下信封：

```json
{
  "code": 200,
  "messageKey": "common.success",
  "messageParams": {},
  "data": {},
  "timestamp": 1783939800000,
  "requestId": "a1b2c3d4"
}
```

- `code` 是程序分支和自动化断言的首选依据。
- `messageKey` 是稳定语义键，不是可直接展示的文本。
- `messageParams` 只包含插值所需的结构化值；文件名、团队名、用户名等用户输入保持原样。
- 响应中不存在旧 `message` 字段。内部异常统一返回 `error.internal`，不会序列化诊断文本。

## 业务对象

| 对象 | 语义字段 |
|------|----------|
| 通知 | `titleKey`、`contentKey`、`messageParams` |
| 团队任务 | `titleKey/titleParams`、`resultMessageKey/resultMessageParams`、`errorMessageKey/errorMessageParams` |
| 团队活动 | `summaryKey`、`summaryParams` |
| 权限来源 | `descriptionKey`、`descriptionParams` |
| Outbox | `messageKey`、`messageParams`，dispatch payload 同样携带参数 |

客户端和测试不得根据翻译后的中文判断业务状态，应使用 `code`、`type`、`status` 或稳定 key。

## Key 命名与兼容性

- 所有协议 key 和前端内部 key 使用 `domain.feature.intent` 形式，每段均为 lowerCamelCase。
- 禁止哈希、序号、自然语言、`migrated.*` 和 snake_case 作为 key；通用动作、状态和分页文案归入 `common`。
- 本次语义化改名是破坏性协议升级，不提供旧 key 别名或双读。后端、Web/Tauri 与 Android 必须同步发布。
- HTTP 路径、响应结构和 `messageParams` 形状不变；只有 `messageKey`、通知、任务、活动和权限描述 key 改名。

## Web 与 Tauri

- 入口位于 `frontend/src/i18n.ts`，资源位于 `frontend/src/locales/zh-Hans.ts` 和 `frontend/src/locales/en-US.ts`。
- 生产源码中的用户可见文本必须通过 locale key 获取。`pnpm run check:i18n` 检查 Vue 裸文本、用户可见静态属性和 TypeScript 中文字符串；技术名词例外集中登记在 `frontend/i18n-hardcode.config.mjs`，并必须说明原因。
- `pnpm run test` 和 `pnpm run build` 会自动执行硬编码检查。词典、i18n 最终兜底和测试断言不在门禁范围内。
- 公开认证页提供全局语言入口，登录后可在个人资料中修改。切换立即生效并更新页面 `lang`；保存账号失败时回滚。
- `resolveMessage(key, params)` 负责插值和未知键降级。
- 未知键会写客户端诊断日志；生产界面只显示当前语言的本地通用提示，不显示服务端 key。
- Tauri Rust 网络信封同样解析 `messageKey/messageParams`，不得重新依赖旧 `message`。

## KMP 移动端

- KMP 默认英语资源位于 `mobile/shared/src/commonMain/composeResources/values`，简体中文资源分别位于 `values-zh-rCN` 和 `values-zh-rSG`；所有目录使用相同且不带语言前缀的稳定资源键。生产 Kotlin 源码不得硬编码用户可见文本。
- 当前支持 `en-US`、`zh-CN` 与 `zh-SG`。`zh-CN`、`zh-Hans-CN`、`zh-SG`、`zh-Hans-SG` 选择对应简体资源；`zh-Hant`、`zh-TW`、`zh-HK`、`zh-MO`、无限定 `zh`、无地区 `zh-Hans` 和其他语言回退英语，不能把繁体中文区域误映射为简体。
- Compose Multiplatform 1.11.1 尚不支持 `Hans/Hant` 脚本资源限定符，因此 KMP 当前使用明确地区目录。未来繁体中文通过增加 TW、HK、MO 地区目录实现，不修改资源键或业务调用。
- 认证页和“我的”可选择英语、中国大陆简体中文或新加坡简体中文；Android 13+ 系统应用语言页面提供相同列表。未登录选择立即持久化；登录或恢复后使用账号精确地区，历史账号值 `zh-Hans` 映射为 `zh-CN`。登录后修改语言先写后端，失败时不改变设备语言。
- 控制器和持久化任务只保存稳定消息描述或语义键，在 Compose 显示和 Android 发通知时按当前语言解析，因此语言变化不会把旧语言固化进状态或 Room。

## 服务端文本边界

- 固定日志、内部诊断和数据库注释使用英文。
- 邮件由服务端外部 HTML/纯文本模板生成，不复用客户端翻译文本。服务端接受 `en-US`、`zh-Hans`、`zh-CN` 与 `zh-SG`；CN、SG 当前复用 `zh-Hans` 简体模板。已注册收件人优先使用账号语言，外部收件人使用操作发起人的语言。
- 后端生产源码、资源及 DDL 不应包含面向用户的中文；测试中的显式 Unicode 用户输入样本除外。

## 兼容性与数据库

数据库仅提供面向全新实例的合并基线。`V1__schema.sql` 直接包含规范化 BCP 47 语言偏好字段和语义化 message key 字段，不提供旧数据库或旧 snake_case key 的原地升级路径。

管理员的语言偏好也会在创建 Grafana 会话和修改偏好时同步到 Grafana。`zh-Hans`、`zh-CN` 和 `zh-SG` 均映射到 Grafana 的 `zh-Hans`，`en-US` 保持不变；Web/Tauri 同样把 CN、SG 映射到现有 `zh-Hans` 词典。映射不改写账号保存的精确地区，Grafana 同步失败也不会影响主应用语言保存或管理员排障入口。
