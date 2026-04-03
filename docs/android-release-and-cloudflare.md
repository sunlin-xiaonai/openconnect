# OpenConnect Android Release And Cloudflare Guide

This guide is for end users who want to install the Android app and connect it to their own computer running `codex app-server`.

## What This App Does

- The Android app is a controller, not the machine that runs code.
- Your computer runs `codex app-server`.
- The phone connects to your computer through your own tunnel or WebSocket endpoint.

If you scan a pairing QR code, you are connecting to the machine behind that QR code.

## Requirements

On the computer side, prepare:

- `codex`
- `tmux`
- `cloudflared` if you want Quick Tunnel or a named tunnel
- `qrencode` if you want the script to print a QR code directly in the terminal

On the phone side, prepare:

- the APK built from this repository or downloaded from Releases

If you want to install from your development machine, also prepare:

- `adb`

## Fastest Path: Quick Tunnel

Run the dependency and environment check first:

```bash
bash scripts/openconnect_pair_up.sh doctor
```

Then start the default Quick Tunnel flow:

```bash
bash scripts/openconnect_pair_up.sh up \
  --quick-tunnel \
  --cwd "/path/to/your/project"
```

The script will:

- verify required commands
- start `codex app-server` if needed
- start `cloudflared` if needed
- print an `openconnect://connect?...` pairing link
- print a QR code in the terminal when `qrencode` is installed

On the phone:

1. Install the APK.
2. Open `OpenConnect`.
3. Tap the QR scan action in Settings.
4. Scan the QR code shown by the script.
5. Wait for Initialize to complete, then open or create a thread.

Useful follow-up commands:

```bash
bash scripts/openconnect_pair_up.sh status
bash scripts/openconnect_pair_up.sh stop
```

## Fixed Domain Or Named Tunnel

If you want a stable hostname, check the named tunnel setup:

```bash
bash scripts/openconnect_pair_up.sh doctor \
  --named-tunnel openconnect-codex \
  --hostname codex.example.com
```

Then start it:

```bash
bash scripts/openconnect_pair_up.sh up \
  --named-tunnel openconnect-codex \
  --hostname codex.example.com \
  --cwd "/path/to/your/project"
```

If you already operate a public WebSocket endpoint, you can skip tunnel startup:

```bash
bash scripts/openconnect_pair_up.sh up \
  --endpoint "wss://codex.example.com" \
  --cwd "/path/to/your/project"
```

## Build And Install The APK

Build the release bundle:

```bash
bash scripts/openconnect_release_bundle.sh
```

Build and install it to a connected phone:

```bash
bash scripts/openconnect_release_bundle.sh --install
```

The bundle in `dist/` contains:

- APK
- `SHA256SUMS.txt`
- `QUICKSTART.md`
- `QUICKSTART.zh-CN.md`
- `RELEASE_NOTES.md` when the version notes exist

## Public Release Guidance

For the open-source build, do not ship a maintainer-only private domain as the default endpoint.
Users should run their own Quick Tunnel, named tunnel, or fixed `wss://` endpoint.

Recommended release assets:

- `openconnect-android-vX.Y.Z-debug.apk`
- `SHA256SUMS.txt`
- `QUICKSTART.md`
- `QUICKSTART.zh-CN.md`
- release notes

## Security Notes

- A pairing QR code may contain secrets such as bearer tokens or Cloudflare Access credentials.
- Treat those QR codes as sensitive.
- Only connect to machines and domains you trust.
- The renamed OpenConnect package installs separately from older builds that used a different package name. Uninstall the old app if you do not want two icons.
