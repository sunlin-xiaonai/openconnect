# AgmenteAndroid Guide

## Scope
- Native Android ACP client built with Kotlin and Jetpack Compose.
- Handles ACP transport, basic session flow, and client-side terminal execution for `terminal/*` requests.
- Receives WeChat-friendly deep links through the activity intent layer.

## Ownership Boundaries
- `app/src/main/java/com/agmente/android/` contains app state, screens, and Android entrypoints.
- `app/src/main/java/com/agmente/android/acp/` owns JSON-RPC transport and local terminal execution.
- `docs/wechat-android-acp.md` documents the WeChat entry architecture and platform limits.

## Extension Points
- Add new client capabilities in `AcpViewModel.buildInitializeParams`.
- Add inbound ACP client methods in `AcpTransport.handleClientRequest`.
- Extend local execution in `LocalTerminalManager`; keep shell/process policy isolated there.

## Constraints
- Current MVP supports `initialize`, `session/new`, `session/prompt`, and `terminal/*`.
- File-system ACP methods are not implemented yet; do not advertise them in capabilities until the SAF-backed implementation exists.
- Android shell execution is sandboxed to locations the app process can access.
- WeChat is an entry surface, not the in-process ACP runtime host.
- Deep-link entry now includes both `agmente://task?...` and pairing-style `agmente://connect?...`.

## Build And Test
- Use the Gradle wrapper inside `AgmenteAndroid/`.
- Expected local SDK path is supplied through `local.properties` or `ANDROID_HOME`.
- Minimum verification for changes:
  - `./gradlew :app:assembleDebug`

## Documentation Policy
- Update this file when changing:
  - ACP method support,
  - terminal execution behavior,
  - deep-link contract,
  - Android component boundaries.
