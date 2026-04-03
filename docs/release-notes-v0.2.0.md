# Agmente Android v0.2.0

本次版本重点：

- 新增中英国际化与应用内语言切换
- 修复线程列表无法同时显示多个执行中线程的问题
- 新增 `scripts/agmente_pair_up.sh doctor`，用户可先检查缺失依赖与 Tunnel 配置
- 新增一键配对脚本，支持 Quick Tunnel、命名 Tunnel、固定 `wss://` endpoint
- 补充面向普通用户的中文接入文档与 `cloudflared` 配置示例

建议下载后先阅读：

- `QUICKSTART.zh-CN.md`
- `docs/android-release-and-cloudflare-zh.md`

首次使用推荐流程：

1. 在电脑上准备好 `codex`、`tmux`、`cloudflared`
2. 先执行 `bash scripts/agmente_pair_up.sh doctor`
3. 首次体验建议执行 `bash scripts/agmente_pair_up.sh up --quick-tunnel --cwd "/path/to/project"`
4. 手机打开 App，进入扫码页面，扫描终端二维码

注意：

- 本公开版本不会内置维护者私有域名
- 用户应当使用自己的 Quick Tunnel、自己的命名 Tunnel，或自己的固定 `wss://` 地址
