# OpenConnect Android 发布与 Cloudflare 使用说明

这份文档写给普通用户：你想在 Android 手机上安装 OpenConnect，然后连接你自己电脑上的 `codex app-server`。

## 先把角色讲清楚

- Android App 是控制端，不负责真正执行代码。
- 真正执行任务的是你的电脑。
- 手机通过你自己的 Tunnel 或公网 WebSocket 地址去连接电脑。

所以，谁生成二维码，手机就会连到谁的机器上。

## 你需要准备什么

电脑侧建议准备：

- `codex`
- `tmux`
- `cloudflared`，如果你要用 Quick Tunnel 或命名 Tunnel
- `qrencode`，如果你希望脚本直接在终端打印二维码

手机侧需要：

- 这个仓库打出来的 APK，或者 Release 里下载的 APK

如果你要从电脑直接安装到手机，还需要：

- `adb`

## 最快跑通方式：Quick Tunnel

先做依赖和环境检查：

```bash
bash scripts/openconnect_pair_up.sh doctor
```

如果你的电脑本地 `~/.cloudflared/config.yml` 已经配好了固定域名映射，脚本现在会优先使用那个命名 Tunnel。
只有你明确想走临时 `trycloudflare.com` 地址时，才需要强制指定 Quick Tunnel：

```bash
bash scripts/openconnect_pair_up.sh up \
  --quick-tunnel \
  --cwd "/path/to/your/project"
```

脚本会自动做这些事：

- 检查依赖命令是否齐全
- 在需要时拉起 `codex app-server`
- 在需要时拉起 `cloudflared`
- 输出 `openconnect://connect?...` 配对链接
- 如果本机装了 `qrencode`，还会直接在终端打印二维码

如果你希望把私有域名配置完全放在仓库外，可以把 `.openconnect.local.env.example` 复制为 `.openconnect.local.env`，然后把你自己的 `OPENCONNECT_HOSTNAME` / `OPENCONNECT_TUNNEL_NAME` 或 `OPENCONNECT_ENDPOINT` 写进去。

手机端操作：

1. 安装 APK。
2. 打开 `OpenConnect`。
3. 进入设置页，点击扫码。
4. 扫描脚本输出的二维码。
5. 等待 Initialize 完成，然后打开或新建线程。

常用后续命令：

```bash
bash scripts/openconnect_pair_up.sh status
bash scripts/openconnect_pair_up.sh stop
```

## 固定域名 / 命名 Tunnel

如果你要长期稳定使用，建议准备自己的固定域名。

先检查命名 Tunnel 所需条件：

```bash
bash scripts/openconnect_pair_up.sh doctor \
  --named-tunnel openconnect-codex \
  --hostname codex.example.com
```

然后启动：

```bash
bash scripts/openconnect_pair_up.sh up \
  --named-tunnel openconnect-codex \
  --hostname codex.example.com \
  --cwd "/path/to/your/project"
```

如果你已经有自己的公网 `wss://` 地址，也可以直接生成配对链接：

```bash
bash scripts/openconnect_pair_up.sh up \
  --endpoint "wss://codex.example.com" \
  --cwd "/path/to/your/project"
```

## 构建并安装 APK

生成发布包：

```bash
bash scripts/openconnect_release_bundle.sh
```

生成后直接安装到当前连接的手机：

```bash
bash scripts/openconnect_release_bundle.sh --install
```

`dist/` 目录会包含：

- APK
- `SHA256SUMS.txt`
- `QUICKSTART.md`
- `QUICKSTART.zh-CN.md`
- `RELEASE_NOTES.md`（如果当前版本说明存在）

## 公开发布时的建议

开源版本不要把维护者自己的私有域名当作默认入口。
正确做法是让每个用户自己运行：

- 自己的 Quick Tunnel
- 自己的命名 Tunnel
- 或者自己的固定 `wss://` 地址

建议 Release 至少带上这些文件：

- `openconnect-android-vX.Y.Z-debug.apk`
- `SHA256SUMS.txt`
- `QUICKSTART.md`
- `QUICKSTART.zh-CN.md`
- 发布说明

## 安全提醒

- 配对二维码里可能带有 Bearer Token 或 Cloudflare Access 凭据。
- 带凭据的二维码就等同于密钥，不要随意转发。
- 只连接你信任的机器和域名。
- 这次品牌与包名已经统一为 OpenConnect。由于新旧包名不同，新版会和旧安装包并存。如果你不想保留两个图标，卸载旧版即可。
