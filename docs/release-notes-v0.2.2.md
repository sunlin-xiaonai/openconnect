# OpenConnect Android v0.2.2

## English

- Fixed the pairing helper so public builds no longer default back to `trycloudflare.com` when a local fixed-domain tunnel mapping already exists
- Added automatic local preference order for `scripts/openconnect_pair_up.sh`:
  - `.openconnect.local.env`
  - local `~/.cloudflared/config.yml` mapping for the current port
  - Quick Tunnel only as fallback
- Added `.openconnect.local.env.example` so maintainers can keep private hostnames and tokens out of the repository
- Kept the open-source repository free of maintainer-only domain values; only generic examples remain in docs
- Updated release guidance to recommend the generic `up` flow instead of forcing Quick Tunnel first

## 中文

- 修复了配对脚本的默认行为：如果本地已经有固定域名的 Tunnel 映射，公开版本不再退回到 `trycloudflare.com`
- `scripts/openconnect_pair_up.sh` 现在按下面顺序自动选择本地默认配置：
  - `.openconnect.local.env`
  - 本机 `~/.cloudflared/config.yml` 里映射到当前端口的域名
  - 都没有时才回退 Quick Tunnel
- 新增 `.openconnect.local.env.example`，方便维护者把私有域名和凭据放在仓库外，不提交到开源仓库
- 再次确认公开仓库里不包含维护者私有域名，只保留通用示例
- 更新发布指引，默认推荐 `up` 通用流程，不再先强制走 Quick Tunnel
