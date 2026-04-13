# OpenConnect Android v0.2.6

## English

- Reorganized Codex mode around a clearer project-first hierarchy: project list, project detail, and thread detail
- Grouped multiple related entries under the same project so thread browsing is less noisy and easier to share in screenshots
- Added project-level session creation so a new chat can start directly inside the selected project
- Hid local project paths from the home project list to reduce accidental disclosure when sharing screenshots
- Reworked the top-left brand area into a compact pixel-art mark and simplified the top navigation status into a short `Codex` / `ACP` state badge
- Bumped the Android app version to 0.2.6; the repository still does not include a release keystore, so the release artifact remains an unsigned APK for manual signing

## 中文

- 将 Codex 模式重组为更清晰的“项目列表 -> 项目详情 -> 线程详情”层级
- 把同一项目下的多个相关条目归到同一个项目中，降低线程列表噪音，也更适合截图分享
- 新增项目级 Session 创建入口，可以直接在选中的项目下发起新聊天
- 隐藏首页项目列表中的本地项目路径，减少截图分享时泄露本机目录的风险
- 重做左上角品牌区为紧凑的像素风标记，同时把顶栏状态压缩成简短的 `Codex` / `ACP` 状态牌
- Android 版本号提升到 0.2.6；由于仓库仍未包含 release keystore，本次 release 产物依然是需要后续手动签名的 unsigned APK
