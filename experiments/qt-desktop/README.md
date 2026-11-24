# 历史原型（非当前交付入口）

该目录仅保留迁移前证据；Tauri 对照基准脚本已经退役。当前安装、验证及平台状态见 [Qt 桌面开发](../../desktop/README.md)。以下说明保留当时上下文，不作为当前可执行命令。

# Windows Qt 桌面迁移验证原型

复用当前 Vue 前端，验证 PySide6 + Qt WebEngine 是否能够承接桌面窗口、认证、文件传输和预览。它是独立实验工程，现有 Web/Tauri 构建、Rust 实现和统一验收模块保持原有行为。

验证结论和本机实测记录见[迁移前验证报告](../../docs/development/QT_DESKTOP_VALIDATION.md)。

## 环境与启动

在仓库根目录启动已配置的基础栈；本实验不需要数仓 overlay，不覆盖已有 env 文件：

```powershell
docker compose up -d --no-build
cd experiments/qt-desktop
uv sync --locked
uv run --locked python build.py --web-only
uv run --locked python app.py --profile ../../test-results/qt-desktop/manual-profile
```

Python 固定为 3.12.13，依赖位于本目录 `.venv`，由 `uv.lock` 固定。不要使用系统 Python 或自由线程版本混装依赖。

默认页面来源为 `http://127.0.0.1:5184`，API 为 `http://127.0.0.1:8080/api/v1`。其他 API 服务需同时设置 `POC_API_BASE` 重新构建前端，并给应用传入相同的 `--api-base`。前端连接设置不会扩大原生 API 的来源白名单。

`--profile` 必须指向独立实验目录。程序拒绝使用未标记的非空目录，不接受现有 Tauri Profile。关闭后原型保留自己的任务和设置；浏览器登录态只在该实验 Profile 内使用。

## 验证命令

```powershell
uv run --locked pytest -q
uv run --locked python validate.py
uv run --locked python validate.py --office-only
uv run --locked python validate.py --previews-only
uv run --locked python build.py
uv run --locked python validate.py --packaged dist/BetterCloudDriveQtPoc/BetterCloudDriveQtPoc.exe
uv run --locked python benchmark.py
```

完整验证会为运行生成独立 ID，通过 Mailpit 创建测试账号，运行真实上传下载和 OnlyOffice 场景；H.264/AAC 缺失会使命令返回非零，不能把其他项目通过当作整体验收通过。`--office-only` 和 `--previews-only` 用于针对失败项目复验。

自动结果、截图和进程日志写入仓库 `test-results/qt-desktop/<run-id>/`。报告不保存凭据、请求正文或 CDP 流量。结束后删除运行专属的浏览器存储和缓存，避免 LevelDB 残留已删除的 Token。测试账号保留供审计，文件和团队按本次创建的 ID 清理，清理使用独立短期登录并最终退出。不会清空 Mailpit，也不会扩大后端限流。

原生文件选择需要在实际 Windows 对话框中选择本次生成的合成文件：

```powershell
uv run --locked python native_picker.py
```

命令会打印该文件的完整路径，选中文件后检查后端 MD5。取消或未完成选择不能计为通过。桌面自动化期间避免并行运行其他会抢焦点的窗口测试。

中断后的恢复清理必须显式指定已有的运行 ID，可重复 `--run`：

```powershell
uv run --locked python cleanup_runs.py --run <10位运行ID>
```

此工具仅适用于本机 Compose 开发环境：从 PostgreSQL 识别精确测试账号，使用本地测试签名密钥在内存中生成短期身份，通过正常 API 清理其数据；不删除数据库记录或卷，不运行全局 E2E 清理。恢复结果另写一份 `cleanup-*.json`。

## 实现边界

- 原型专用 Vite aliases 替换 Tauri 桥接和运行时判断，生产 Vue 源码无需迁移。QWebChannel 位于隔离 JS world，只对主页面注入请求中继；外部 iframe 没有 Python 对象访问能力。
- 静态服务器仅绑定回环地址，不提供原生命令 HTTP API；主页面限制导航，下载和上传仅接受启动时配置的 API 及其团队子路径。
- 原生命令采用现有参数、队列 DTO 和事件名称。支持单文件上传、下载、暂停/继续/取消、设置和队列；目录递归、多文件批次及其余命令返回 `UNSUPPORTED_IN_POC`。
- 网络、摘要和文件 I/O 在后台执行。队列与设置保存到 SQLite；访问令牌和代理密码只保留在内存，不写入数据库。代理密码需要重启后重新输入。
- 新任务绑定账号及服务器；旧 JSON 可读取，但缺少账号绑定的旧任务必须重新确认身份，不能自动恢复。兼容性检查只使用合成样本，不读取真实 Tauri 数据。
- `--debug-port` 仅用于本地验证，启用回环 CDP 和有限诊断命令。普通启动没有远程调试端口。`--fixture-root` 仅允许指向公开的合成媒体目录。
- WebM/MP4 是引擎能力探针，当前 Vue 文件预览组件没有视频 UI；本实验不会顺带增加该产品功能。

## 打包

PyInstaller 目录分发产物位于 `dist/BetterCloudDriveQtPoc/`。整个目录都必须保留，不可只拷贝 `.exe`：

```powershell
./dist/BetterCloudDriveQtPoc/BetterCloudDriveQtPoc.exe --profile ../../test-results/qt-desktop/packaged-profile
```

构建脚本清理 PyInstaller 分析阶段的 PATH，防止采集到 Poppler/Conda 等其他工具的 ICU/OpenSSL DLL。原型分发给其他机器之前，仍需完成干净系统、Windows 最低版本、签名及第三方许可证检查。本轮没有启用 Qt 专有编解码器，也没有把开发用 ffmpeg 媒体生成器打入应用。
