#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${PROJECT_ROOT}/dist"
BUILD_TYPE="debug"
INSTALL_TO_DEVICE=0
CLEAN_FIRST=0
ADB_SERIAL=""

usage() {
  cat <<'EOF'
用法：
  scripts/openconnect_release_bundle.sh [选项]

默认行为：
  - 构建可安装的 debug APK
  - 输出到 dist/
  - 生成 SHA256SUMS.txt
  - 复制中英文快速上手文档到 dist/

选项：
  --build-type debug|release   默认 debug
  --install                    构建完成后通过 adb 安装到已连接手机
  --serial SERIAL              指定 adb 设备 serial
  --clean                      构建前先执行 ./gradlew clean
  -h, --help                   显示帮助

说明：
  1. 当前仓库默认没有 release keystore。
  2. 所以默认推荐发布 debug APK，用户可以直接安装体验。
  3. 如果你选择 --build-type release，产物通常会是 unsigned APK，仅适合你后续自行签名。
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '[openconnect-release] 错误：缺少命令：%s\n' "$1" >&2
    exit 1
  }
}

version_name() {
  local line
  line="$(rg -oN 'versionName = "[^"]+"' "${PROJECT_ROOT}/app/build.gradle.kts" | head -n 1)"
  printf '%s' "${line#versionName = \"}" | sed 's/"$//'
}

version_code() {
  local line
  line="$(rg -oN 'versionCode = [0-9]+' "${PROJECT_ROOT}/app/build.gradle.kts" | head -n 1)"
  printf '%s' "${line#versionCode = }"
}

adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf 'adb -s %q' "${ADB_SERIAL}"
  else
    printf 'adb'
  fi
}

connected_device_count() {
  adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}'
}

release_notes_file() {
  local version="$1"
  local file="${PROJECT_ROOT}/docs/release-notes-v${version}.md"
  if [[ -f "${file}" ]]; then
    printf '%s' "${file}"
  fi
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --build-type)
        BUILD_TYPE="${2:-}"
        shift 2
        ;;
      --install)
        INSTALL_TO_DEVICE=1
        shift
        ;;
      --serial)
        ADB_SERIAL="${2:-}"
        shift 2
        ;;
      --clean)
        CLEAN_FIRST=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        printf '[openconnect-release] 错误：未知参数：%s\n' "$1" >&2
        usage >&2
        exit 1
        ;;
    esac
  done

  case "${BUILD_TYPE}" in
    debug|release)
      ;;
    *)
      printf '[openconnect-release] 错误：--build-type 只能是 debug 或 release\n' >&2
      exit 1
      ;;
  esac
}

main() {
  parse_args "$@"

  require_cmd rg
  require_cmd shasum
  require_cmd cp

  local version
  local code
  local gradle_task
  local source_apk
  local artifact_name
  local artifact_path
  local notes_file

  version="$(version_name)"
  code="$(version_code)"

  mkdir -p "${DIST_DIR}"

  if [[ "${CLEAN_FIRST}" == "1" ]]; then
    printf '[openconnect-release] 执行清理构建\n'
    (cd "${PROJECT_ROOT}" && ./gradlew clean)
  fi

  if [[ "${BUILD_TYPE}" == "debug" ]]; then
    gradle_task=":app:assembleDebug"
    source_apk="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
    artifact_name="openconnect-android-v${version}-debug.apk"
  else
    gradle_task=":app:assembleRelease"
    source_apk="${PROJECT_ROOT}/app/build/outputs/apk/release/app-release-unsigned.apk"
    artifact_name="openconnect-android-v${version}-release-unsigned.apk"
  fi

  printf '[openconnect-release] 开始构建 %s（versionName=%s, versionCode=%s）\n' "${BUILD_TYPE}" "${version}" "${code}"
  (cd "${PROJECT_ROOT}" && ./gradlew "${gradle_task}")

  [[ -f "${source_apk}" ]] || {
    printf '[openconnect-release] 错误：未找到构建产物：%s\n' "${source_apk}" >&2
    exit 1
  }

  artifact_path="${DIST_DIR}/${artifact_name}"
  cp "${source_apk}" "${artifact_path}"

  (
    cd "${DIST_DIR}"
    shasum -a 256 "${artifact_name}" >SHA256SUMS.txt
  )

  cp "${PROJECT_ROOT}/docs/android-release-and-cloudflare.md" "${DIST_DIR}/QUICKSTART.md"
  cp "${PROJECT_ROOT}/docs/android-release-and-cloudflare-zh.md" "${DIST_DIR}/QUICKSTART.zh-CN.md"
  notes_file="$(release_notes_file "${version}" || true)"
  if [[ -n "${notes_file}" ]]; then
    cp "${notes_file}" "${DIST_DIR}/RELEASE_NOTES.md"
  fi

  printf '[openconnect-release] 已生成：%s\n' "${artifact_path}"
  printf '[openconnect-release] 校验文件：%s\n' "${DIST_DIR}/SHA256SUMS.txt"
  printf '[openconnect-release] 快速上手（EN）：%s\n' "${DIST_DIR}/QUICKSTART.md"
  printf '[openconnect-release] 快速上手：%s\n' "${DIST_DIR}/QUICKSTART.zh-CN.md"

  if [[ "${BUILD_TYPE}" == "release" ]]; then
    printf '[openconnect-release] 注意：当前 release 产物通常是 unsigned APK，需要你后续自行签名后再分发。\n'
  else
    printf '[openconnect-release] 说明：当前输出的是可直接安装的 debug APK，适合 GitHub Release 分发测试。\n'
  fi

  if [[ "${INSTALL_TO_DEVICE}" == "1" ]]; then
    require_cmd adb
    if [[ "$(connected_device_count)" -eq 0 ]]; then
      printf '[openconnect-release] 错误：未检测到已连接的 Android 设备\n' >&2
      exit 1
    fi
    if [[ "${BUILD_TYPE}" != "debug" ]]; then
      printf '[openconnect-release] 错误：release unsigned APK 不能直接安装，请改用默认 debug 构建或先自行签名。\n' >&2
      exit 1
    fi
    printf '[openconnect-release] 安装到手机中\n'
    if [[ -n "${ADB_SERIAL}" ]]; then
      adb -s "${ADB_SERIAL}" install -r "${artifact_path}"
    else
      adb install -r "${artifact_path}"
    fi
  fi

  cat <<EOF

下一步：
1. 把 ${artifact_name} 和 SHA256SUMS.txt 上传到 GitHub Release
2. 把 QUICKSTART.md / QUICKSTART.zh-CN.md 作为发布说明附件或文档链接
3. 引导用户先执行：
   bash scripts/openconnect_pair_up.sh doctor
   bash scripts/openconnect_pair_up.sh up --quick-tunnel --cwd "/path/to/project"
4. 如果用户要固定域名，再执行：
   bash scripts/openconnect_pair_up.sh doctor --named-tunnel openconnect-codex --hostname codex.example.com
EOF
}

main "$@"
