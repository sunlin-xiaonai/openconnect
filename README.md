# Android 客户端（Open Connect）

这是一个**原生 Android 控制端**。推荐的使用形态是：**手机负责“控制与查看”，电脑负责“实际执行”**。

当前支持的连接模式：

- **Codex 远程控制**：Android 端连接远程 `Codex app-server`

## 你能用它做什么

App 支持：

- **扫码连接**到远程 `Codex app-server`
- 发送 `initialize` 并进入会话
- **创建/恢复/刷新线程**，在当前线程中发送 prompt
- **实时查看** transcript、工具输出与文件变更
- **处理审批**（如命令执行/文件变更审批等）
- 支持通过请求头透传鉴权信息（例如 `Bearer Token`、`Cloudflare Access` Client ID/Secret）

## 快速开始（Codex 远程控制）

推荐链路：

`Android App -> WSS -> Cloudflare Tunnel/Access -> 你的电脑（Codex app-server）`

典型流程：

1. 在电脑上启动 `Codex app-server`（WebSocket）。
2. 使用仓库脚本拉起服务并生成 `agmente://connect?...` 配对链接（可选：同时启动 Cloudflare Tunnel）。
3. 手机打开 App，点击 **扫码连接**，扫描二维码后自动填入参数并连接。
4. 进入线程后直接发任务，在手机上查看执行过程与变更。

## 截图与演示

扫码配对（电脑端生成二维码，手机扫码后自动填入并连接）：

![扫码配对示例](images/08d92912bf217ad5952f05e15d5f5047.png)

线程列表（查看/切换会话线程）：

![线程列表](images/684995766768bffae799d29a1ad1169f.jpg)

设置页（连接状态、重扫二维码、自动重连等）：

![设置页](images/ccb703376e3b1c4ed6b3a579dee43378.jpg)

节目效果（封面图/展示用）：

![节目效果](images/de4531fc88851e10bcab946dcfdb7730.png)

常用脚本（示例参数请替换成你的实际路径/地址）：

依赖说明：

- 需要本机已安装 `codex`
- Quick Tunnel / 命名 Tunnel 需要已安装 `cloudflared`
- 脚本会用 `tmux` 托管 `codex app-server` / `cloudflared`
- 如果安装了 `qrencode`，脚本会额外在终端打印二维码

建议第一次先执行环境检查：

```bash
bash scripts/agmente_pair_up.sh doctor
```

如果你只是想先快速跑通，使用默认 Quick Tunnel：

```bash
bash scripts/agmente_pair_up.sh up \
  --quick-tunnel \
  --cwd "/path/to/your/project"
```

如果你准备绑定自己的固定域名，先检查命名 Tunnel 所需项：

```bash
bash scripts/agmente_pair_up.sh doctor \
  --named-tunnel agmente-codex \
  --hostname codex.example.com
```

然后再启动：

```bash
bash scripts/agmente_pair_up.sh up \
  --named-tunnel agmente-codex \
  --hostname codex.example.com \
  --cwd "/path/to/your/project"
```

如果你已经有公网 `wss://` 端点，也可以直接生成配对链接：

```bash
bash scripts/agmente_pair_up.sh up \
  --endpoint "wss://agent.example.com" \
  --cwd "/path/to/your/project"
```

查看状态：

```bash
bash scripts/agmente_pair_up.sh status
```

停止脚本拉起的本地 `codex app-server` / `cloudflared`：

```bash
bash scripts/agmente_pair_up.sh stop
```

如果 Quick Tunnel 已生成但你当前网络的本地 DNS 解析很慢，脚本会自动回退到公共 DNS 做就绪检查；但如果手机和电脑共用同一个 Wi-Fi DNS，手机端仍可能连不上。这种情况下更建议使用 `--named-tunnel` 或现成的 `--endpoint wss://...`。

如果你是仓库维护者并且额外保留了自己的固定域名封装脚本，请不要把它当作开源用户的默认入口。开源用户应该使用 `agmente_pair_up.sh` 的 `doctor` / `up` 流程，并填写自己的 Tunnel 名称与域名。

如果你要准备一个可上传到 GitHub Release 的 APK 包，可以执行：

```bash
bash scripts/agmente_release_bundle.sh
```

如果你想在打包后顺手安装到当前连接的手机：

```bash
bash scripts/agmente_release_bundle.sh --install
```

产物会输出到 `dist/`，包含：

- APK
- `SHA256SUMS.txt`
- `QUICKSTART.zh-CN.md`
- `RELEASE_NOTES.md`（如果当前版本已提供）

## 构建与安装（开发者）

在仓库根目录执行：

```bash
./gradlew :app:assembleDebug
```

安装到手机（需要已配置好 `adb`）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如果 Android SDK 没有自动识别，可以在仓库根目录的 `local.properties` 中指定：

```properties
sdk.dir=/path/to/Android/sdk
```

## 文档

- `docs/android-codex-remote.md`
- `docs/android-release-and-cloudflare-zh.md`
- `setup.md`

## 致谢

- 本项目（Open Connect）脱胎于 `Amentum` 项目，感谢其提供的基础架构与实现思路。

## 备注与边界

- 当前 README 仅描述 **Codex 远程控制** 路线：手机作为控制台，电脑作为执行端。
