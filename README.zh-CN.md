# OpenConnect Android

[English README](README.md)

OpenConnect 是一个原生 Android 控制端，用来连接你自己电脑上的 `codex app-server`。
推荐的使用方式很直接：手机负责控制和查看，电脑负责实际执行。

## 功能

- 扫描 `openconnect://connect?...` 配对二维码，连接到你自己的 `codex app-server`
- 在手机上完成初始化、创建线程、恢复线程、发送 prompt
- 实时查看 transcript、工具调用、文件变更和审批请求
- 应用内支持英文、简体中文、跟随系统三种语言模式
- 支持透传 `Bearer Token`、Cloudflare Access Service Token 等请求头
- 一键打包 APK，并附带中英文快速上手文档

## 快速开始

推荐链路：

`Android App -> WSS -> Cloudflare Tunnel / Access -> 你的电脑（codex app-server）`

先检查依赖和环境：

```bash
bash scripts/openconnect_pair_up.sh doctor
```

如果你的电脑本地 `~/.cloudflared/config.yml` 已经有固定域名映射，脚本现在会优先自动使用这个固定域名。
只有你明确想走临时地址时，才需要强制指定 Quick Tunnel：

```bash
bash scripts/openconnect_pair_up.sh up \
  --quick-tunnel \
  --cwd "/path/to/your/project"
```

如果你想把这些本地私有默认值放在仓库外配置，可以把 [.openconnect.local.env.example](/Users/bingsun/code/git-repo/app-cli-fluter/openconnet/.openconnect.local.env.example) 复制成 `.openconnect.local.env`，然后写入你自己的域名或 endpoint。

如果你要用自己的固定域名，先检查命名 Tunnel：

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

如果你已经有公网 `wss://` 地址，也可以直接生成配对链接：

```bash
bash scripts/openconnect_pair_up.sh up \
  --endpoint "wss://codex.example.com" \
  --cwd "/path/to/your/project"
```

常用后续命令：

```bash
bash scripts/openconnect_pair_up.sh status
bash scripts/openconnect_pair_up.sh stop
```

## 截图

扫码配对：

![扫码配对](images/08d92912bf217ad5952f05e15d5f5047.png)

线程列表：

![线程列表](images/684995766768bffae799d29a1ad1169f.jpg)

设置与连接状态：

![设置页](images/ccb703376e3b1c4ed6b3a579dee43378.jpg)

展示封面：

![展示图](images/de4531fc88851e10bcab946dcfdb7730.png)

## 发布打包

生成可分发的 APK 包：

```bash
bash scripts/openconnect_release_bundle.sh
```

打包后顺手安装到当前连接的手机：

```bash
bash scripts/openconnect_release_bundle.sh --install
```

产物会输出到 `dist/`，包含：

- APK
- `SHA256SUMS.txt`
- `QUICKSTART.md`
- `QUICKSTART.zh-CN.md`
- `RELEASE_NOTES.md`（如果对应版本的说明文件存在）

## 开发构建

在仓库根目录执行：

```bash
./gradlew :app:assembleDebug
```

通过 `adb` 安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如果 Android SDK 没有自动识别，可在 `local.properties` 里指定：

```properties
sdk.dir=/path/to/Android/sdk
```

## 安全说明

- 开源版不会内置维护者私有域名或私有 Tunnel。
- 每个用户都应该使用自己的 Quick Tunnel、命名 Tunnel，或自己的固定 `wss://` 地址。
- 如果二维码里带了 Bearer Token 或 Cloudflare Access 凭据，请把它当作敏感信息。

## 文档

- [English quickstart](docs/android-release-and-cloudflare.md)
- [中文快速上手](docs/android-release-and-cloudflare-zh.md)
- [v0.2.2 发布说明](docs/release-notes-v0.2.2.md)
