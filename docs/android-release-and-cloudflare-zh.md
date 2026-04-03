# Android APK 发布与 Cloudflare 接入（给用户直接照做版）

这份文档写给**普通使用者**：你想在 Android 手机上使用这个 App，去连接并控制**你自己的电脑**上的 `codex app-server`。

核心概念先讲清楚（非常重要）：

- **手机 App 只是“遥控器/控制台”**：不在手机上执行编码与命令。
- **真正执行任务的是你的电脑**：电脑上运行 `codex app-server`，手机只是通过网络连接到它。
- **扫码的含义**：你扫到谁的二维码，你就连接到谁的电脑（以及那台电脑能访问的目录与权限范围）。

---

## 你要选择哪种使用方式

### 推荐：模式 A（每个用户连接自己的电脑）

这是最适合公开发布 APK 的模式。每个用户都在自己的电脑上安装与运行服务端，然后用手机连接。

- **优点**：安全边界清晰、最容易理解、无需你托管别人的代码/命令执行
- **适合**：公开发版、长期使用、小白用户

### 不推荐：模式 B（所有人都连接你的一台电脑）

这会立刻变成“远程代理服务”问题，而不是“发个 APK”：

- 多用户隔离、权限控制、审计归属、并发/额度、敏感文件暴露风险

如果你真要做模式 B，需要单独设计用户系统、鉴权、审计与权限模型；本文档**不覆盖**。

---

## 公开发版时建议你在 Release 放什么（维护者参考）

如果你在 GitHub Releases 对外发版，建议至少包含：

- `agmente-android-vX.Y.Z.apk`
- `SHA256SUMS.txt`（或同等校验方式）
- “快速上手”（本文档可直接链接）
- “电脑端安装/启动说明”
- “安全声明”（下文有可复制的模板）

如果你只是想先快速产出一个可下载安装的 APK 包，可以在仓库根目录执行：

```bash
bash scripts/agmente_release_bundle.sh
```

脚本会自动：

- 构建 APK
- 输出到 `dist/`
- 生成 `SHA256SUMS.txt`
- 复制一份中文快速上手文档

如果你还想顺手安装到当前已连接的手机：

```bash
bash scripts/agmente_release_bundle.sh --install
```

说明：

- 当前仓库默认产出的是可安装的 debug APK，适合 GitHub Release 分发测试
- 如果你后续要正式长期分发，建议再补自己的 release keystore

---

## 最短可跑通流程（新用户建议先走 Quick Tunnel）

下面这条路线目标很明确：**5 分钟内跑通扫码连接**。它适合演示/内测/首次体验。

### 电脑端：启动 Codex（本地 WebSocket）

先在电脑上启动：

```bash
codex app-server --listen ws://127.0.0.1:9000
```

你应该看到类似“正在监听 127.0.0.1:9000”的输出（或无报错持续运行）。

### 电脑端：安装并启动 Cloudflare Quick Tunnel

macOS（Homebrew）：

```bash
brew install cloudflared
```

然后把本地服务暴露为公网入口（示例以 `http://localhost:9000` 为上游）：

```bash
cloudflared tunnel --url http://localhost:9000
```

你会得到一个临时域名（每次重启会变化）。注意：手机端通常需要 **`wss://`** 形式的 WebSocket 地址。

### 电脑端：先做检查，再一键拉起并生成配对链接

在仓库根目录执行（示例）：

先做环境检查：

```bash
bash scripts/agmente_pair_up.sh doctor
```

如果你只是想快速跑通，默认 Quick Tunnel 即可：

```bash
bash scripts/agmente_pair_up.sh up \
  --quick-tunnel \
  --cwd "/path/to/your/project"
```

- 依赖：本机已安装 `codex`、`cloudflared`、`tmux`
- 脚本会自动检查/拉起本地 `codex app-server`
- 脚本会自动检查/拉起 `cloudflared`
- 脚本会输出最终可扫码的 `agmente://connect?...` 链接
- 如果你本机装了 `qrencode`，它还会直接在终端打印二维码
- 如果当前局域网 DNS 对新生成的 `trycloudflare.com` 解析慢，脚本会自动用公共 DNS 做就绪检查；但如果手机和电脑共用同一 Wi-Fi DNS，手机端仍可能连不上，正式使用更建议命名 Tunnel 或固定 `wss://` 域名

如果你要绑定自己的固定域名，先执行：

```bash
bash scripts/agmente_pair_up.sh doctor \
  --named-tunnel agmente-codex \
  --hostname codex.example.com
```

脚本会告诉你：

- 是否已安装 `cloudflared`
- 是否缺少 `~/.cloudflared/config.yml`
- 是否还没做 `cloudflared tunnel login`
- 命名 Tunnel 需要执行的 `create` / `route dns` 命令
- ingress 里应该如何把 `codex.example.com` 指到 `127.0.0.1:9000`

如果你更想手动指定固定公网地址，也可以：

```bash
bash scripts/agmente_pair_up.sh up \
  --endpoint "wss://codex.example.com" \
  --cwd "/path/to/your/project"
```

如果是命名 Tunnel：

```bash
bash scripts/agmente_pair_up.sh up \
  --named-tunnel agmente-codex \
  --hostname codex.example.com \
  --cwd "/path/to/your/project"
```

### 手机端：扫码连接

1. 安装 APK
2. 打开 App → 点击“扫码连接”
3. 扫电脑上生成的二维码
4. 等待初始化完成

到这里你就跑通了“手机控制自己的电脑”这条链路。

---

## Quick Tunnel vs 命名 Tunnel：什么时候用哪个

### Quick Tunnel（推荐先跑通）

适合：演示、内测、首次跑通

- 不需要自己的域名
- 配置最少、最快上手
- **缺点**：地址会变化，不适合长期依赖

### 命名 Tunnel（正式长期使用推荐）

适合：长期稳定、给别人长期用、需要固定域名、想加 Cloudflare Access

- 需要你有一个在 Cloudflare 托管 DNS 的域名
- 地址稳定，可持续维护

---

## 命名 Tunnel（正式版）最小步骤

前提：

- 你有一个域名，并已把 DNS 托管到 Cloudflare
- 电脑上能运行 `codex app-server`
- 安装 `cloudflared`

### 1）登录 Cloudflare

```bash
cloudflared tunnel login
```

### 2）创建 Tunnel

```bash
cloudflared tunnel create agmente-codex
```

记下输出中的 Tunnel 名称/UUID，并确认生成了凭据文件（通常在 `~/.cloudflared/` 下）。

### 3）绑定域名

```bash
cloudflared tunnel route dns agmente-codex codex.example.com
```

把 `codex.example.com` 换成你的实际域名。

### 4）写 `cloudflared` 配置文件

最小配置示例（保存为 `~/.cloudflared/config.yml` 或你习惯的位置）：

```yaml
tunnel: YOUR_TUNNEL_ID
credentials-file: /Users/YOU/.cloudflared/YOUR_TUNNEL_ID.json

ingress:
  - hostname: codex.example.com
    service: http://localhost:9000
  - service: http_status:404
```

仓库里也提供了一个可复制修改的模板：

- `docs/examples/cloudflared-config.example.yml`

你需要改的只有三处：

- `YOUR_TUNNEL_ID`
- `credentials-file` 的实际路径
- `hostname`（你的域名）

### 5）启动 Tunnel

```bash
cloudflared tunnel --config ~/.cloudflared/config.yml run agmente-codex
```

这时手机端要连接的 endpoint 通常是：

```text
wss://codex.example.com
```

---

## （可选但强烈推荐）启用 Cloudflare Access：别让“知道域名的人都能连”

如果你把入口暴露到公网，建议加 Access 做最基本的访问控制。

### 在 Cloudflare 后台创建 Access 应用（Self-hosted）

1. 打开 Cloudflare Zero Trust（Cloudflare One）
2. 进入 `Access` → `Applications`
3. 新建 `Self-hosted` 应用
4. 绑定你的域名（例如 `codex.example.com`）

### 创建 Service Token

1. 进入 `Access` → `Service Auth`（或 `Service Tokens/Service credentials`）
2. 创建 Service Token
3. 记录：
   - `Client ID`
   - `Client Secret`

### 给应用添加策略

建议策略动作使用 **Service Auth**，这样客户端可以通过请求头：

- `CF-Access-Client-Id`
- `CF-Access-Client-Secret`

访问你的入口。

---

## 二维码里要不要直接带 Access 凭据？

这点必须写清楚，因为它直接决定“二维码泄露”的风险级别。

### 方案 A：二维码里直接带 Access 凭据（体验最好，风险最高）

适合：自用、小范围可信用户、临时测试

```bash
python3 scripts/agmente_pair_qr.py \
  --mode codex \
  --endpoint "wss://codex.example.com" \
  --cwd "/path/to/your/project" \
  --cf-client-id "YOUR_CLIENT_ID" \
  --cf-client-secret "YOUR_CLIENT_SECRET" \
  --create-session
```

**风险**：二维码一旦泄露，等价于凭据泄露。请把二维码当作密钥对待。

### 方案 B：二维码只带地址，不带凭据（推荐默认）

适合：公开发版、给更多人使用、需要更可控的安全边界

```bash
python3 scripts/agmente_pair_qr.py \
  --mode codex \
  --endpoint "wss://codex.example.com" \
  --cwd "/path/to/your/project" \
  --create-session
```

然后让用户在 App 的手动配置区填写：

- `CF Access Client ID`
- `CF Access Client Secret`

---

## 安全声明（可直接复制到 Release/README）

> 本 App 是移动控制台，不在手机本地执行编码任务。  
> 实际命令会在你连接的电脑上执行。  
> 请只连接你信任的电脑和你信任的公网入口。  
> 请不要扫描来源不明的配对二维码。  
> 如果二维码中包含 Bearer Token 或 Cloudflare Access 凭据，请将其视为敏感信息（等同密钥）。  
> 建议默认使用 `safe` 权限预设，并优先为公网入口启用 Cloudflare Access。

---

## 两个必须特别提醒用户的点

1. **你扫到谁的二维码，就在控制谁的电脑**（以及那台电脑能访问的目录与权限范围）。
2. **包含凭据的二维码就是密钥**：不要截图传播，不要发到群里，不要放进公开文档。

---

## 官方资料（建议在发布页附上）

- Cloudflare Quick Tunnel：`https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/`
- Cloudflare locally-managed tunnel：`https://developers.cloudflare.com/tunnel/advanced/local-management/create-local-tunnel/`
- Cloudflare Access self-hosted application：`https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/`
- Cloudflare service tokens：`https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/`

## 本仓库相关文档

- `docs/android-codex-remote.md`
