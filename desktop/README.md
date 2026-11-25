# Qt 桌面客户端

当前交付目标为 **Windows 11 x64 开发运行版本，通过 uv 启动**。复用现有 Vue 页面，PySide6 承接窗口、原生传输和独立播放器。Web 与 Qt 共用 `frontend/dist`，认证、HTTP、预览和 OnlyOffice 各保留一份实现；原生能力由平台接口按需加载。后端和 KMP 移动端接口不变，标题栏等待异步配置保存。

**2026-11-24：Windows 开发交付与 Tauri 退役收尾完成。** 功能覆盖、133 项 Python/36 项桌面适配回归、220 项 Web 回归及两个统一入口的证据见[开发验收及功能矩阵](../docs/development/QT_DESKTOP_ACCEPTANCE.md)。本机独立环境和自动验收通过，人工试用观察、干净 Windows 系统、安装分发及完整 Linux/macOS 尚未验收；迁移前原型的打包结果不代表当前客户端已分发。

## 环境与分步启动

需要 uv、Node 和 pnpm。当前验证基线为 Node 24.18.0、pnpm 11.16.0；前端依赖以构建文件及锁文件为准。Python 3.12.13、PySide6/Qt 6.12.0、HTTPX 0.28.1 由 `uv.lock` 固定，uv 管理 Python 和依赖，无需手工安装系统 Python、Qt 或 FFmpeg。登录及传输需要可用后端；OnlyOffice 另需 DocumentServer。

从仓库根目录依次运行：

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm run build
cd ../desktop
uv sync --locked
uv run --locked python app.py
```

Web 与 Qt 共用的日常网页输出为 `frontend/dist/`，启动时读取该目录。日常客户端运行时不要重建它正在使用的网页；下面的统一验证使用独立输出，不覆盖该目录。默认应用来源 `http://127.0.0.1:5184`，后端 `http://127.0.0.1:8080/api/v1`，Web 分享地址 `http://127.0.0.1:3000`。脚本不自动启动后端或 Docker。

启动时可用 `--api-base`、`--web-base` 指定初始地址，未提供参数时读取 `BCD_DESKTOP_API_BASE`、`BCD_DESKTOP_WEB_BASE`，再回退到上述回环默认值；已保存的连接设置优先；登录前也可在标题栏配置 API 和分享地址。API 仅接受 HTTP(S)、无嵌入凭据/查询/片段且路径以 `/api/v1` 结尾。保存等待原生确认，成功才关闭设置框；失败保留输入。切服先暂停任务并清除身份，不恢复旧服务器令牌。

普通 Web 保留已保存地址、`VITE_API_BASE_URL`/`VITE_WEB_BASE_URL` 及同源默认值的规则。Qt 忽略 Web 构建默认地址，避免同一份产物带入部署服务器配置。已有当前版本的 `frontend/dist` 时可直接启动，不需要再次构建。`build.py` 是 `pnpm run build` 的薄包装，可在桌面目录使用；不再存在桌面入口别名。

## Profile 与启动参数

默认 Profile 为 `%LOCALAPPDATA%/BetterCloudDriveQt`，包含 Qt 自身设置、队列和 WebEngine 会话存储；不读取或导入 Tauri/实验原型状态。首次使用重新登录，未完成任务在重启后保持暂停，原账号和服务器认证后手动继续。

在 `desktop` 目录运行 `uv run --locked python app.py --help` 查看参数：

| 参数 | 行为 |
| --- | --- |
| `--profile <目录>` | 指定空目录或已标记的本程序目录；同一 Profile 有排他锁 |
| `--port <端口>` | 修改默认 5184；占用时报错，不关闭其他客户端 |
| `--api-base <地址>` | 指定 API 初始地址；已保存的连接配置优先并同步原生层 |
| `--web-base <地址>` | 指定分享 Web 初始地址；已保存的连接配置优先 |
| `--web-root <目录>` | 指定预先构建的共享资源；默认仓库 `frontend/dist` |
| `--debug-port <端口>` | 显式开启回环 CDP，仅供开发验收；普通启动不开启 |

桥接使用隔离 world、可信主页面和命令白名单，外部网页及 OnlyOffice/PDF 子帧没有原生命令权限。页面重载、渲染进程结束、退出和关闭窗口均由原生层暂停任务并清除内存身份。

## 传输、设置与播放器

- 个人/团队、多文件/目录上传下载保留任务树、中文、零字节及空目录；上传和下载分别按文件分配并发，默认各 3，范围 1～16。网页拖放仍走现有 Web 上传路径。
- 暂停保留断点，取消只清理所属下载分片，不删除已完成文件；远端取消失败另行提示。恢复校验源文件、上传会话、Range、长度和 MD5，最终文件原子发布且不覆盖已有目标。
- 安全操作最多尝试 3 次，退避 1/2 秒，429 遵循最多 30 秒的 Retry-After；401 最多刷新一次。创建目录/初始化上传不自动重放；结果待确认时先核对服务端，再取消或重新发起。403、磁盘、源文件变化、校验与协议错误不作为网络故障重试。
- 并发降低不强杀执行中的文件，限速动态生效；默认下载目录只影响新任务，代理变更只影响后续请求。支持关闭、系统固定代理及手工 HTTP(S)/SOCKS5；PAC/WPAD 未支持。代理认证只保留内存，重启需重新输入；原生 SQLite 不保存令牌。
- 图片、文本、PDF 和 OnlyOffice 使用网页预览，HTML/XML 按纯文本显示。视频使用独立 Qt Multimedia 窗口，支持播放/暂停、跳转、音量、全屏、空格/Escape和错误重试；退出或切服关闭播放器及媒体请求。视频不依赖 WebEngine 自带的 H.264 解码。

## 无窗口统一验证

在仓库根目录运行任一入口：

```powershell
./scripts/verify-all.ps1 -Module qt
```

```bash
bash scripts/verify-all.sh --module qt
```

两个入口共同调用跨平台的 `verify.py`，锁定同步开发依赖、执行 Python 状态机/真实媒体解码回归、桌面适配及公共 Web Vitest、类型检查及共享网页构建。单独选择 `qt` 时不启动 Docker、不读取或生成后端配置，也不运行真实后端或原生交互；`all` 已纳入 `qt`，也可单独选择。

标准库引导先同步开发依赖，再在项目解释器中执行通用工作进程；进程归属使用仅开发依赖 psutil。每轮生成带归属标记的 `test-results/desktop/<id>/work`，收纳 TEMP/TMP、pytest 夹具、缓存及构建输出。子进程清除继承的 Python/Qt/媒体路径覆盖，父环境不变；结束核对所属子进程、日常网页指纹并清理本轮资源，报告保留为同目录 `results.json`；平台专属用例单列“不适用”，适用测试意外跳过和缺少系统依赖均失败。Linux/macOS 通用入口已适配，本轮未在这些系统实测，不宣布完整客户端支持。必需步骤、资源核对或清理失败均返回非零。

`build.py --out-dir <目录>` 供隔离验证使用，仅接受标记匹配的本轮 `work` 内安全子目录；未指定时仍生成日常网页。不要给它传入普通任意目录。

## 真实后端与交互验收

共享网页上传（包括 Qt 中的网页拖放）使用按 API 绝对地址和用户 ID 分区的断点缓存；仅在登录或会话恢复确认后显示，退出和切服立即隐藏。重新登录原账号、原地址后需手动选择原文件继续。旧版无账号/服务器归属的网页缓存会被清除，不推断迁移、不取消未知服务端会话；Qt 自身 SQLite 队列不受此次缓存升级影响。网络或权限错误保留断点，仅确认会话失效/不存在才清除对应记录。

Web 注销使用站内路由切回登录页，并等待捕获的旧令牌撤销请求；失败时提示服务端注销未确认，本机身份仍立即清除。迟到的注销结果不会影响随后登录。团队历史版本请求和恢复确认同样绑定文件及会话版本，切换目标后丢弃旧结果。

按修改范围选择专项；它们不会自动启动后端、修改限流或覆盖 env。批量 Web/Office 账号验收沿用统一验证脚本的功能测试限流：DEFAULT、AUTH、UPLOAD、UPLOAD_CHUNK 四类临时设为 10000 次/1 秒，运行者必须在 finally 路径恢复原值和服务状态；默认认证 30 次/60 秒不适合此批量账号场景。启动 Docker 前确认 Linux 虚拟机未运行，结束恢复服务原状态。不要在现有客户端使用网页时重建同一份资源。

| 入口（在 `desktop` 下通过 `uv run --locked python` 执行） | 范围 |
| --- | --- |
| `validate.py --native-only` / `validate.py --office` | 原生传输基础；后者包含 Vue、预览及真实 Office 保存，需要可见窗口 |
| `m1_check.py --offscreen` | 后台 Qt/Vue 会话、连接确认、刷新、切服和退出 |
| `m2_check.py` | 后台强制结束恢复、故障/代理/权限、真实设置保存；HTTPS 代理夹具需 OpenSSL（Windows 可用 Git 自带版本） |
| `tests/web_cutover.py --office` | 同一份产物的 Web 9 项、Qt M1、预览/Office 和短 MP4/WebM；需后端、OnlyOffice 及预先准备的功能测试限流 |
| `m3_check.py --suite functional` / `--suite access` | 后台预览及媒体授权边界 |
| `m3_check.py --suite playback` / `--suite interaction` | 可见播放性能或人工控件检查，需空闲桌面 |
| `native_check.py --m1` / `--pickers-only` / `--window-only` | 原生对话框或窗口手势人工验收，不能由后台测试替代 |

交互确认只能在实际观察后按脚本要求填写，不预先生成确认文件。测试凭据只在内存/匿名管道中传递，临时账号保留审计，文件、团队和上传会话按运行 ID 清理。详细测试条件和历史失败追溯见[验收记录](../docs/development/QT_DESKTOP_ACCEPTANCE.md)及[测试策略](../docs/development/TESTING.md)。

M4/M5 从仓库根目录执行，使用全新 uv 环境及独立网页/Profile，不替换日常资源：

```powershell
uv run --no-project --python 3.12.13 desktop/m4_check.py
uv run --no-project --python 3.12.13 desktop/m5_check.py
```

M4 默认 5198/9230，需要后端和 OnlyOffice，前四轮后台、第五轮短暂显示窗口，不操作鼠标键盘；M5 默认 5199/9231，只需后端，后台验证 Qt 自身状态初始化、恢复及隔离。两者端口冲突直接退出，不接管其他客户端。

## 独立试用与清理

从仓库根目录执行；准备步骤不打开窗口、不创建账号：

```powershell
uv run --no-project --python 3.12.13 desktop/m5_check.py --prepare-trial
uv run --no-project --python 3.12.13 desktop/m5_check.py --launch-trial <id>
uv run --no-project --python 3.12.13 desktop/m5_check.py --cleanup-trial <id>
```

试用环境、网页和 Profile 保存在该 ID 的 `work` 中，属于刻意保留项；默认无 CDP，用户自行登录。已有试用使用保存的端口，不能再传准备阶段的 API/端口参数；服务器可在应用中正常配置。运行时代码或锁文件变化会要求重新准备。启动/清理互斥，Profile 锁存在或归属不明时拒绝操作，不强制关闭客户端、不猜测删除陈旧锁。

历史试用 **`a12ee1fa2a`** 和其数据仍保留，未做人工观察。当前锁文件增加了开发依赖，该快照会因运行时摘要不匹配而拒绝启动；需要使用 `--prepare-trial` 生成新环境，不覆盖旧 Profile。试用资源、人工待验收和[历史清理例外](../docs/development/QT_DESKTOP_ACCEPTANCE.md#人工待验收试用保留与清理例外)分别记录，不算全部清理完成。

## 常见问题与当前限制

| 现象 | 处理方式 |
| --- | --- |
| 页面资源缺失 | 先按分步命令构建，或检查指定的 `--web-root` 是否有 `index.html` |
| 应用或诊断端口占用 | 换独立端口；不接管已有客户端 |
| Profile 属于其他程序或被占用 | 换空目录，或确认对应客户端正常退出；不覆盖现有目录 |
| 本地状态损坏/初始化失败 | 保留原目录，检查权限和空间后用新 Profile 排查；不自动重建或导入旧数据 |
| 继续传输失败 | 核对原账号、原服务器、源文件和上传会话；按错误修复后手动继续 |
| 代理重启后缺少认证 | 重新输入认证；PAC/WPAD 环境改用明确的手工代理 |
| OnlyOffice 打不开或无法保存 | 检查后端、DocumentServer 可达性及既有回调配置，不用只看到编辑器替代保存验收 |

本机已验收的能力以功能矩阵为准。没有安装器、签名、自动更新或旧 Tauri 数据兼容；没有把本机通过外推为干净 Windows、完整 Linux/macOS、更多媒体格式/HDR或长期使用通过。Tauri 源码、依赖及验证入口已经移除，KMP 移动端继续保持跨平台定位。
