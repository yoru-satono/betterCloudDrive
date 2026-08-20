# Web 开发指南

本文面向修改 Vue Web 客户端的开发者。Web 与 Tauri 复用同一应用；原生桌面边界另见 [Tauri 开发指南](TAURI.md)。

## 目录与数据流

```text
src/
├── api/          # 按业务域封装 HTTP 协议
├── components/   # 可复用 UI 与文件操作组件
├── composables/  # 跨组件行为
├── layouts/      # Public/Main/Admin 页面骨架
├── pages/        # Router 页面
├── stores/       # Pinia 会话、文件、团队和传输状态
├── types/        # 客户端协议类型
├── locales/      # zh-Hans / en-US 文案
├── router/       # 公开、个人、团队、管理员路由
└── test/         # Vitest 全局设置
```

推荐调用方向为 Page/Component → Store/Composable → `src/api` → 共享 Axios client。页面不要自行创建 Axios 实例，也不要复制 token 刷新、API envelope 或下载处理。

## 启动

```bash
cd frontend
pnpm install
pnpm run dev
```

访问 `http://localhost:5173`。`vite.config.ts` 将 `/api` 和 `/webdav` 代理到 `VITE_DEV_BACKEND_PROXY_BASE`，默认 `http://localhost:8080`。环境值从仓库根目录、模块配置文件和进程变量加载，进程变量优先。

## 常见修改位置

| 需求 | 优先位置 |
|------|----------|
| 新接口 | `src/api/<domain>.ts` 与 `src/types` |
| 跨页面业务状态 | `src/stores` |
| 页面内短期状态 | 页面或 composable |
| 文件通用动作 | `src/components/file`、相关 composable |
| 新页面 | `src/pages` + `src/router/index.ts` |
| 管理功能 | `src/pages/admin`，并保留服务端管理员校验 |
| 新文案 | `src/locales/zh-Hans.ts`、`en-US.ts` |

团队文件按钮应读取服务端有效权限；前端隐藏按钮不是授权。个人与团队页面可以复用组件，但请求作用域、Store 状态和路由参数必须显式区分。

## API 与错误处理

- 业务请求使用 `/api/v1` 相对地址，生产通过同域 Nginx 代理。
- 统一 client 注入 access token，并合并并发 refresh；页面只处理业务结果。
- 下载、分享和 SSE 使用各自短期 ticket，不把 access token 放进查询参数。
- 后端语义错误通过 `code`、`messageKey`、`messageParams` 处理；未知 key 使用本地通用回退并记录诊断。
- 不向用户展示后端堆栈、内部对象路径或原始死信 payload。

## 本地化与主题

运行 `pnpm run check:i18n` 检查用户可见硬编码。新增语义 key 时同步后端 catalog、Web 两种语言与 Android；完整约定见[本地化参考](../reference/LOCALIZATION.md)。

主题模式是 `system`、`light`、`dark`，设备本地键为 `bcd.themeMode`，跨退出登录保留但不与后端同步。新组件应使用现有主题 token，不直接写仅适合浅色模式的颜色。

## 测试和构建

```bash
pnpm run check:i18n
pnpm run test
pnpm run build
```

涉及真实浏览器、认证、团队权限、上传下载或 OnlyOffice 时运行 Playwright：

```bash
pnpm run test:e2e:docker
```

完整脚本中的 `web` 模块会运行 Web 单元测试/JavaScript 覆盖率和 core E2E；`office` 单独验证 OnlyOffice。测试数据必须使用运行前缀并接受脚本清理。修改共同 API 时还要运行 Android/Tauri 受影响测试。

## 提交前检查

- `pnpm run build` 无 TypeScript/Vue 错误。
- 新增 UI 有加载、空、失败和权限拒绝状态。
- 个人/团队路由切换不会复用错误 Store 上下文。
- 中英文 key 对齐且没有用户可见硬编码。
- 桌面条件分支不会破坏普通浏览器环境。
