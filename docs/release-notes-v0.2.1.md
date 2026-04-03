# OpenConnect Android v0.2.1

## English

- Rebranded the public Android client to `OpenConnect`
- Renamed the deep-link scheme to `openconnect://`
- Cleaned old brand references from source paths, scripts, app strings, and repository docs
- Added bilingual documentation:
  - `README.md`
  - `README.zh-CN.md`
  - `docs/android-release-and-cloudflare.md`
  - `docs/android-release-and-cloudflare-zh.md`
- Updated the release bundle script to export both `QUICKSTART.md` and `QUICKSTART.zh-CN.md`
- Kept the public release workflow focused on user-owned tunnels and domains instead of maintainer private endpoints
- Includes the previously added fixes for in-app language switching, multi-thread running-state visibility, and pairing helper scripts

## 中文

- 对外品牌统一切换为 `OpenConnect`
- deep link scheme 改为 `openconnect://`
- 清理了源码路径、脚本、应用文案、仓库文档里的旧品牌信息
- 补齐了双语文档：
  - `README.md`
  - `README.zh-CN.md`
  - `docs/android-release-and-cloudflare.md`
  - `docs/android-release-and-cloudflare-zh.md`
- 更新发布打包脚本，产物里同时附带 `QUICKSTART.md` 和 `QUICKSTART.zh-CN.md`
- 公开版本继续强调“用户自己准备 Tunnel / 域名 / 固定 `wss://` 地址”，不内置维护者私有入口
- 同时包含此前已经完成的改动：应用内中英切换、多个运行中线程显示修复、配对检查与一键脚本
