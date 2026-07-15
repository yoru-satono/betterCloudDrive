# 客户端本地化与语义文案协议

BetterCloudDrive 的服务端 API 不返回面向用户的自然语言。服务端只传递稳定的文案键和结构化参数，由 Web/Tauri 与 Android 使用本地简体中文资源渲染。

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

## Web 与 Tauri

- 入口位于 `frontend/src/i18n.ts`，简体中文资源位于 `frontend/src/locales/zh-CN.ts`。
- `resolveMessage(key, params)` 负责插值和未知键降级。
- 未知键会写客户端诊断日志；生产界面只显示本地通用中文提示，不显示服务端 key。
- Tauri Rust 网络信封同样解析 `messageKey/messageParams`，不得重新依赖旧 `message`。

## Android

- 文案资源位于 `android/app/src/main/res/values/strings.xml`。
- `MessageResolver` 集中映射服务端 key，并负责参数格式化和未知键降级。
- 网络错误模型只携带 `code/messageKey/messageParams/requestId`。

## 服务端文本边界

- 固定日志、内部诊断、数据库注释和服务端邮件模板使用英文。
- 邮件由服务端英文模板根据事件类型和参数生成，不复用客户端翻译文本。
- 后端生产源码、资源及 DDL 不应包含面向用户的中文；测试中的显式 Unicode 用户输入样本除外。

## 兼容性与数据库

这是破坏性协议升级，旧客户端不能与新后端混用。Flyway 使用合并后的 `V1__schema.sql`，仅支持创建全新数据库；已有旧 baseline 数据库必须备份并重建，不能对修改后的 V1 执行常规增量升级。

