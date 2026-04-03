#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNTIME_DIR="${PROJECT_ROOT}/.openconnect-runtime"
mkdir -p "${RUNTIME_DIR}"

COMMAND="up"
MODE="quick"
CWD_PATH="${PROJECT_ROOT}"
LISTEN_HOST="127.0.0.1"
LISTEN_PORT="9000"
ENDPOINT=""
HOSTNAME=""
TUNNEL_NAME=""
CLOUDFLARED_CONFIG="${HOME}/.cloudflared/config.yml"
PERMISSION_PRESET="safe"
CREATE_SESSION=1
INITIALIZE=1
CONNECT=1
BEARER_TOKEN=""
CF_ACCESS_CLIENT_ID=""
CF_ACCESS_CLIENT_SECRET=""
RESTART=0
PRINT_QR=1
READY_TIMEOUT_SECONDS="90"
LAST_ENDPOINT_STATUS=""
LAST_ENDPOINT_PROBE_MODE="system"
DOCTOR_HAS_BLOCKER=0
NAMED_TUNNEL_TEMPLATE_FILE="${PROJECT_ROOT}/docs/examples/cloudflared-config.example.yml"

CODEX_PID_FILE="${RUNTIME_DIR}/codex-app-server.pid"
CODEX_LOG_FILE="${RUNTIME_DIR}/codex-app-server.log"
CLOUDFLARED_PID_FILE="${RUNTIME_DIR}/cloudflared.pid"
CLOUDFLARED_LOG_FILE="${RUNTIME_DIR}/cloudflared.log"
CLOUDFLARED_URL_FILE="${RUNTIME_DIR}/cloudflared-public-url.txt"

usage() {
  cat <<'EOF'
用法：
  scripts/openconnect_pair_up.sh [up|status|stop|doctor] [选项]

默认命令：
  up

命令：
  up       检查并拉起 Codex + Tunnel，输出可扫码的 openconnect://connect 链接
  status   查看本地服务、Tunnel、配对链接状态
  stop     停止脚本拉起的 Codex / cloudflared 进程
  doctor   检查依赖、Cloudflare 配置与下一步操作建议

模式：
  --quick-tunnel            使用 Cloudflare Quick Tunnel（默认）
  --endpoint WSS_URL        使用现成公网 WebSocket 地址，不启动 cloudflared
  --named-tunnel NAME       使用命名 Tunnel；需配合 --hostname

常用选项：
  --cwd PATH                手机连接后默认工作目录
  --listen-port PORT        本地 codex app-server 端口，默认 9000
  --listen-host HOST        本地 codex app-server 监听地址，默认 127.0.0.1
  --hostname HOST           命名 Tunnel 对外域名，例如 codex.example.com
  --cloudflared-config PATH 命名 Tunnel 的 cloudflared 配置文件
  --permission safe|full    生成二维码时写入 permissionPreset，默认 safe
  --bearer-token TOKEN
  --cf-access-client-id ID
  --cf-access-client-secret SECRET
  --no-create-session
  --no-initialize
  --no-connect
  --restart                 强制重启脚本管理的 cloudflared / codex 进程
  --no-qr                   不尝试输出终端二维码
  --ready-timeout SECONDS   等待公网入口就绪的超时时间，默认 90 秒

示例：
  scripts/openconnect_pair_up.sh doctor
  scripts/openconnect_pair_up.sh doctor --named-tunnel openconnect-codex --hostname codex.example.com
  scripts/openconnect_pair_up.sh up --quick-tunnel --cwd "$PWD"
  scripts/openconnect_pair_up.sh up --named-tunnel openconnect-codex --hostname codex.example.com --cwd "$PWD"
  scripts/openconnect_pair_up.sh up --endpoint wss://codex.example.com --cwd "$PWD"
  scripts/openconnect_pair_up.sh status
  scripts/openconnect_pair_up.sh stop
EOF
}

log() {
  printf '[openconnect-pair] %s\n' "$*" >&2
}

fail() {
  printf '[openconnect-pair] 错误：%s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "$(missing_cmd_message "$1")"
}

missing_cmd_message() {
  local cmd="$1"
  case "${cmd}" in
    codex)
      cat <<'EOF'
缺少命令：codex
下一步：
1. 先安装并确保 `codex` 在 PATH 里可用
2. 安装完成后先执行：bash scripts/openconnect_pair_up.sh doctor
EOF
      ;;
    cloudflared)
      cat <<'EOF'
缺少命令：cloudflared
下一步：
1. macOS 可执行：brew install cloudflared
2. 如果你只是首次跑通，可直接使用默认 Quick Tunnel
3. 如果你要固定域名，请先执行：
   cloudflared tunnel login
   cloudflared tunnel create openconnect-codex
   cloudflared tunnel route dns openconnect-codex codex.example.com
4. 安装完成后先执行：bash scripts/openconnect_pair_up.sh doctor
EOF
      ;;
    tmux)
      cat <<'EOF'
缺少命令：tmux
下一步：
1. macOS 可执行：brew install tmux
2. Linux 可执行：sudo apt install tmux
3. 安装完成后先执行：bash scripts/openconnect_pair_up.sh doctor
EOF
      ;;
    *)
      printf '缺少命令：%s' "${cmd}"
      ;;
  esac
}

doctor_print() {
  printf '%s\n' "$*"
}

doctor_item() {
  printf '  - %s\n' "$*"
}

doctor_blocker() {
  DOCTOR_HAS_BLOCKER=1
  doctor_item "$*"
}

config_scalar_value() {
  local key="$1"
  local file="$2"
  [[ -f "${file}" ]] || return 1
  grep -E "^[[:space:]]*${key}:" "${file}" | head -n 1 | sed -E "s/^[[:space:]]*${key}:[[:space:]]*//"
}

config_has_hostname() {
  local hostname="$1"
  local file="$2"
  [[ -f "${file}" ]] || return 1
  grep -F "hostname: ${hostname}" "${file}" >/dev/null 2>&1
}

print_named_tunnel_template_hint() {
  if [[ -f "${NAMED_TUNNEL_TEMPLATE_FILE}" ]]; then
    doctor_item "可参考模板：${NAMED_TUNNEL_TEMPLATE_FILE}"
  fi
}

print_named_tunnel_steps() {
  local tunnel_name="${1:-openconnect-codex}"
  local hostname="${2:-codex.example.com}"
  doctor_item "首次配置命名 Tunnel 时，通常需要依次执行："
  doctor_item "1. cloudflared tunnel login"
  doctor_item "2. cloudflared tunnel create ${tunnel_name}"
  doctor_item "3. cloudflared tunnel route dns ${tunnel_name} ${hostname}"
  doctor_item "4. 在 ${CLOUDFLARED_CONFIG} 中加入 hostname=${hostname} -> http://127.0.0.1:${LISTEN_PORT}"
  print_named_tunnel_template_hint
}

doctor_check_cmd() {
  local cmd="$1"
  local label="$2"
  local required="${3:-1}"
  if command -v "${cmd}" >/dev/null 2>&1; then
    doctor_item "${label}：OK -> $(command -v "${cmd}")"
  else
    if [[ "${required}" == "1" ]]; then
      doctor_blocker "${label}：未安装"
    else
      doctor_item "${label}：未安装（可选）"
    fi
    while IFS= read -r line; do
      [[ -n "${line}" ]] && doctor_item "${line}"
    done < <(missing_cmd_message "${cmd}")
  fi
}

doctor_mode_label() {
  case "${MODE}" in
    quick)
      printf 'Quick Tunnel'
      ;;
    named)
      printf '命名 Tunnel'
      ;;
    endpoint)
      printf '固定 Endpoint'
      ;;
    *)
      printf '%s' "${MODE}"
      ;;
  esac
}

validate_named_tunnel_args() {
  [[ -n "${TUNNEL_NAME}" ]] || fail $'命名 Tunnel 模式需要 --named-tunnel NAME\n示例：bash scripts/openconnect_pair_up.sh doctor --named-tunnel openconnect-codex --hostname codex.example.com'
  [[ -n "${HOSTNAME}" ]] || fail $'命名 Tunnel 模式需要 --hostname HOST\n示例：bash scripts/openconnect_pair_up.sh doctor --named-tunnel openconnect-codex --hostname codex.example.com'
}

validate_named_tunnel_config_or_fail() {
  validate_named_tunnel_args

  if [[ ! -f "${CLOUDFLARED_CONFIG}" ]]; then
    fail "$(cat <<EOF
找不到 cloudflared 配置文件：${CLOUDFLARED_CONFIG}
下一步：
1. 先执行：cloudflared tunnel login
2. 再执行：cloudflared tunnel create ${TUNNEL_NAME}
3. 再执行：cloudflared tunnel route dns ${TUNNEL_NAME} ${HOSTNAME}
4. 然后创建配置文件，并把 ${HOSTNAME} 指到 http://127.0.0.1:${LISTEN_PORT}
EOF
)"
  fi

  if ! config_has_hostname "${HOSTNAME}" "${CLOUDFLARED_CONFIG}"; then
    fail "$(cat <<EOF
配置文件 ${CLOUDFLARED_CONFIG} 中未找到 hostname: ${HOSTNAME}
请在 ingress 中加入类似配置：
  - hostname: ${HOSTNAME}
    service: http://127.0.0.1:${LISTEN_PORT}

随后重新执行：
  bash scripts/openconnect_pair_up.sh up --named-tunnel ${TUNNEL_NAME} --hostname ${HOSTNAME} --cwd "${CWD_PATH}"
EOF
)"
  fi

  local credentials_file
  credentials_file="$(config_scalar_value 'credentials-file' "${CLOUDFLARED_CONFIG}" || true)"
  if [[ -n "${credentials_file}" && ! -f "${credentials_file}" ]]; then
    fail "$(cat <<EOF
cloudflared 配置里引用的 credentials-file 不存在：${credentials_file}
请确认你已经执行过：
  cloudflared tunnel create ${TUNNEL_NAME}
EOF
)"
  fi
}

run_doctor() {
  DOCTOR_HAS_BLOCKER=0

  doctor_print "OpenConnect Pair Doctor"
  doctor_print "当前模式：$(doctor_mode_label)"
  doctor_print "工作目录：${CWD_PATH}"
  doctor_print "本地监听：ws://${LISTEN_HOST}:${LISTEN_PORT}"
  doctor_print ""
  doctor_print "依赖检查："
  doctor_check_cmd "codex" "Codex CLI"
  doctor_check_cmd "tmux" "tmux"
  case "${MODE}" in
    quick|named)
      doctor_check_cmd "cloudflared" "cloudflared"
      ;;
  esac
  doctor_check_cmd "qrencode" "qrencode" 0

  doctor_print ""
  case "${MODE}" in
    quick)
      doctor_print "模式说明："
      doctor_item "Quick Tunnel 是默认模式，不需要固定域名，也不需要先执行 cloudflared tunnel login"
      doctor_item "每次启动都会生成新的 trycloudflare.com 域名"
      doctor_item "如果你想长期稳定使用自己的域名，请改用命名 Tunnel："
      doctor_item "  bash scripts/openconnect_pair_up.sh doctor --named-tunnel openconnect-codex --hostname codex.example.com"
      ;;
    named)
      doctor_print "命名 Tunnel 检查："
      if [[ -z "${TUNNEL_NAME}" ]]; then
        doctor_blocker "缺少 --named-tunnel NAME"
      else
        doctor_item "Tunnel 名称：${TUNNEL_NAME}"
      fi
      if [[ -z "${HOSTNAME}" ]]; then
        doctor_blocker "缺少 --hostname HOST"
      else
        doctor_item "目标域名：${HOSTNAME}"
      fi

      if [[ -f "${HOME}/.cloudflared/cert.pem" ]]; then
        doctor_item "Cloudflare 登录态：OK -> ${HOME}/.cloudflared/cert.pem"
      else
        doctor_item "Cloudflare 登录态：未发现 ${HOME}/.cloudflared/cert.pem"
        doctor_item "如果你还没创建过 Tunnel，请先执行：cloudflared tunnel login"
      fi

      if [[ -f "${CLOUDFLARED_CONFIG}" ]]; then
        doctor_item "配置文件：OK -> ${CLOUDFLARED_CONFIG}"
        local config_tunnel
        local credentials_file
        config_tunnel="$(config_scalar_value 'tunnel' "${CLOUDFLARED_CONFIG}" || true)"
        credentials_file="$(config_scalar_value 'credentials-file' "${CLOUDFLARED_CONFIG}" || true)"
        [[ -n "${config_tunnel}" ]] && doctor_item "config.tunnel：${config_tunnel}"
        if [[ -n "${credentials_file}" ]]; then
          if [[ -f "${credentials_file}" ]]; then
            doctor_item "credentials-file：OK -> ${credentials_file}"
          else
            doctor_blocker "credentials-file 不存在：${credentials_file}"
          fi
        else
          doctor_blocker "配置文件缺少 credentials-file"
        fi
        if [[ -n "${HOSTNAME}" ]]; then
          if config_has_hostname "${HOSTNAME}" "${CLOUDFLARED_CONFIG}"; then
            doctor_item "ingress hostname：已包含 ${HOSTNAME}"
          else
            doctor_blocker "ingress 中未找到 hostname: ${HOSTNAME}"
          fi
        fi
      else
        doctor_blocker "未找到 cloudflared 配置文件：${CLOUDFLARED_CONFIG}"
      fi

      print_named_tunnel_steps "${TUNNEL_NAME:-openconnect-codex}" "${HOSTNAME:-codex.example.com}"
      ;;
    endpoint)
      doctor_print "固定 Endpoint 检查："
      if [[ -z "${ENDPOINT}" ]]; then
        doctor_blocker "缺少 --endpoint WSS_URL"
        doctor_item "示例：bash scripts/openconnect_pair_up.sh doctor --endpoint wss://codex.example.com"
      else
        doctor_item "公网地址：${ENDPOINT}"
      fi
      doctor_item "Endpoint 模式不会自动启动 cloudflared；你需要自己保证这个地址已经可用"
      ;;
  esac

  doctor_print ""
  if [[ "${DOCTOR_HAS_BLOCKER}" == "1" ]]; then
    doctor_print "结果：当前仍有未完成项，暂时不建议直接执行 up。"
    doctor_print "完成上面的步骤后，可重新执行：bash scripts/openconnect_pair_up.sh doctor $([[ "${MODE}" == "named" ]] && printf -- '--named-tunnel %q --hostname %q' "${TUNNEL_NAME:-openconnect-codex}" "${HOSTNAME:-codex.example.com}")"
    return 1
  fi

  doctor_print "结果：当前环境可以尝试启动。"
  case "${MODE}" in
    quick)
      doctor_print "下一步：bash scripts/openconnect_pair_up.sh up --quick-tunnel --cwd \"${CWD_PATH}\""
      ;;
    named)
      doctor_print "下一步：bash scripts/openconnect_pair_up.sh up --named-tunnel ${TUNNEL_NAME} --hostname ${HOSTNAME} --cwd \"${CWD_PATH}\""
      ;;
    endpoint)
      doctor_print "下一步：bash scripts/openconnect_pair_up.sh up --endpoint ${ENDPOINT} --cwd \"${CWD_PATH}\""
      ;;
  esac
}

is_pid_running() {
  local pid="${1:-}"
  [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null
}

read_pid_file() {
  local pid_file="$1"
  [[ -f "${pid_file}" ]] || return 1
  local pid
  pid="$(tr -d '[:space:]' <"${pid_file}")"
  [[ -n "${pid}" ]] || return 1
  printf '%s' "${pid}"
}

stop_pid_file() {
  local pid_file="$1"
  local label="$2"
  local pid
  if ! pid="$(read_pid_file "${pid_file}")"; then
    return 0
  fi
  if is_pid_running "${pid}"; then
    log "停止 ${label}（pid=${pid}）"
    kill "${pid}" 2>/dev/null || true
    sleep 1
    if is_pid_running "${pid}"; then
      kill -9 "${pid}" 2>/dev/null || true
    fi
  fi
  rm -f "${pid_file}"
}

codex_tmux_session_name() {
  printf 'openconnect-codex-app-server-%s' "${LISTEN_PORT}"
}

cloudflared_tmux_session_name() {
  printf 'openconnect-cloudflared-%s' "${LISTEN_PORT}"
}

tmux_session_exists() {
  local session_name="$1"
  tmux has-session -t "${session_name}" >/dev/null 2>&1
}

tmux_session_pid() {
  local session_name="$1"
  tmux list-panes -t "${session_name}" -F '#{pane_pid}' 2>/dev/null | head -n 1
}

stop_tmux_session() {
  local session_name="$1"
  local label="$2"
  if tmux_session_exists "${session_name}"; then
    log "停止 ${label}（tmux=${session_name}）"
    tmux kill-session -t "${session_name}" >/dev/null 2>&1 || true
  fi
}

port_listener_line() {
  lsof -nP -iTCP:"${LISTEN_PORT}" -sTCP:LISTEN 2>/dev/null | tail -n +2 | head -n 1 || true
}

wait_for_port() {
  local deadline=$((SECONDS + 20))
  while (( SECONDS < deadline )); do
    if lsof -nP -iTCP:"${LISTEN_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

start_codex_server_if_needed() {
  local listener
  local session_name
  local start_command
  listener="$(port_listener_line)"
  session_name="$(codex_tmux_session_name)"

  if [[ "${RESTART}" == "1" ]]; then
    stop_tmux_session "${session_name}" "codex app-server"
    rm -f "${CODEX_PID_FILE}"
    listener=""
  fi

  if [[ -n "${listener}" ]]; then
    if grep -qi 'codex' <<<"${listener}"; then
      log "检测到已存在的 codex app-server 监听在 ${LISTEN_HOST}:${LISTEN_PORT}，直接复用"
      return 0
    fi
    fail "端口 ${LISTEN_PORT} 已被其他进程占用：${listener}"
  fi

  require_cmd codex
  require_cmd tmux
  if tmux_session_exists "${session_name}"; then
    log "检测到残留的 codex tmux 会话 ${session_name}，先清理"
    stop_tmux_session "${session_name}" "codex app-server"
  fi
  log "启动 codex app-server -> ws://${LISTEN_HOST}:${LISTEN_PORT}"
  : >"${CODEX_LOG_FILE}"
  printf -v start_command 'cd %q && exec codex app-server --listen %q >>%q 2>&1' \
    "${PROJECT_ROOT}" \
    "ws://${LISTEN_HOST}:${LISTEN_PORT}" \
    "${CODEX_LOG_FILE}"
  tmux new-session -d -s "${session_name}" "${start_command}"
  printf '%s\n' "${session_name}" >"${CODEX_PID_FILE}"

  if ! wait_for_port; then
    fail "codex app-server 未能在端口 ${LISTEN_PORT} 上启动，日志见 ${CODEX_LOG_FILE}"
  fi
}

extract_trycloudflare_url() {
  [[ -f "${CLOUDFLARED_LOG_FILE}" ]] || return 1
  grep -Eo 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' "${CLOUDFLARED_LOG_FILE}" | tail -n 1
}

public_https_from_endpoint() {
  local ws_url="$1"
  if [[ "${ws_url}" == wss://* ]]; then
    printf 'https://%s' "${ws_url#wss://}"
  elif [[ "${ws_url}" == ws://* ]]; then
    printf 'http://%s' "${ws_url#ws://}"
  else
    printf '%s' "${ws_url}"
  fi
}

public_ws_from_https() {
  local http_url="$1"
  if [[ "${http_url}" == https://* ]]; then
    printf 'wss://%s' "${http_url#https://}"
  elif [[ "${http_url}" == http://* ]]; then
    printf 'ws://%s' "${http_url#http://}"
  else
    printf '%s' "${http_url}"
  fi
}

url_host() {
  local url="$1"
  local host="${url#*://}"
  host="${host%%/*}"
  host="${host%%:*}"
  printf '%s' "${host}"
}

public_dns_ipv4() {
  local host="$1"
  local ip=""

  command -v dig >/dev/null 2>&1 || return 1
  ip="$(dig +short @1.1.1.1 "${host}" A | head -n 1)"
  if [[ -z "${ip}" ]]; then
    ip="$(dig +short @8.8.8.8 "${host}" A | head -n 1)"
  fi
  [[ -n "${ip}" ]] || return 1
  printf '%s' "${ip}"
}

http_status_code() {
  local url="$1"
  local status_code=""
  local host
  local port="443"
  local resolved_ip=""

  LAST_ENDPOINT_PROBE_MODE="system"
  status_code="$(curl -I -sS -o /dev/null -w '%{http_code}' --max-time 10 "${url}" 2>/dev/null || true)"
  if [[ -n "${status_code}" && "${status_code}" != "000" ]]; then
    printf '%s' "${status_code}"
    return 0
  fi

  host="$(url_host "${url}")"
  [[ "${url}" == http://* ]] && port="80"
  resolved_ip="$(public_dns_ipv4 "${host}" || true)"
  if [[ -z "${resolved_ip}" ]]; then
    printf '%s' "${status_code:-000}"
    return 0
  fi

  LAST_ENDPOINT_PROBE_MODE="public-dns:${resolved_ip}"
  curl --resolve "${host}:${port}:${resolved_ip}" -I -sS -o /dev/null -w '%{http_code}' --max-time 10 "${url}" 2>/dev/null || true
}

ensure_endpoint_reachable() {
  local ws_endpoint="$1"
  local public_url
  local status_code
  public_url="$(public_https_from_endpoint "${ws_endpoint}")"
  status_code="$(http_status_code "${public_url}")"

  if [[ -z "${status_code}" || "${status_code}" == "000" ]]; then
    fail "无法访问公网入口：${public_url}"
  fi

  if [[ "${status_code}" == "530" ]]; then
    fail "公网入口返回 530，Tunnel 当前不可用：${public_url}"
  fi
}

wait_for_endpoint_ready() {
  local ws_endpoint="$1"
  local timeout_seconds="${2:-${READY_TIMEOUT_SECONDS}}"
  local pid_to_watch="${3:-}"
  local public_url
  local status_code=""
  local deadline=$((SECONDS + timeout_seconds))

  public_url="$(public_https_from_endpoint "${ws_endpoint}")"
  LAST_ENDPOINT_STATUS=""

  while (( SECONDS < deadline )); do
    if [[ -n "${pid_to_watch}" ]] && ! is_pid_running "${pid_to_watch}"; then
      return 2
    fi

    status_code="$(http_status_code "${public_url}")"
    LAST_ENDPOINT_STATUS="${status_code:-000}"
    if [[ -n "${status_code}" && "${status_code}" != "000" && "${status_code}" != "530" ]]; then
      return 0
    fi
    sleep 2
  done

  return 1
}

start_quick_tunnel() {
  require_cmd cloudflared
  require_cmd tmux

  local session_name
  local start_command
  session_name="$(cloudflared_tmux_session_name)"

  if [[ "${RESTART}" == "1" ]]; then
    stop_tmux_session "${session_name}" "cloudflared"
    rm -f "${CLOUDFLARED_PID_FILE}"
    rm -f "${CLOUDFLARED_URL_FILE}"
  fi

  local pid
  local public_url=""
  if tmux_session_exists "${session_name}"; then
    pid="$(tmux_session_pid "${session_name}" || true)"
    if [[ -f "${CLOUDFLARED_URL_FILE}" ]]; then
      public_url="$(tr -d '[:space:]' <"${CLOUDFLARED_URL_FILE}")"
    fi
    if [[ -z "${public_url}" ]]; then
      public_url="$(extract_trycloudflare_url || true)"
    fi
    if [[ -n "${public_url}" ]]; then
      local current_endpoint
      current_endpoint="$(public_ws_from_https "${public_url}")"
      if wait_for_endpoint_ready "${current_endpoint}" "${READY_TIMEOUT_SECONDS}" "${pid}"; then
        printf '%s\n' "${public_url}" >"${CLOUDFLARED_URL_FILE}"
        printf '%s' "${current_endpoint}"
        return 0
      fi
    fi
    log "现有 Quick Tunnel 不可用，准备重启"
    stop_tmux_session "${session_name}" "cloudflared"
    rm -f "${CLOUDFLARED_PID_FILE}"
    rm -f "${CLOUDFLARED_URL_FILE}"
  fi

  : >"${CLOUDFLARED_LOG_FILE}"
  log "启动 Cloudflare Quick Tunnel -> http://${LISTEN_HOST}:${LISTEN_PORT}"
  printf -v start_command 'cd %q && exec cloudflared tunnel --no-autoupdate --url %q --logfile %q >/dev/null 2>&1' \
    "${PROJECT_ROOT}" \
    "http://${LISTEN_HOST}:${LISTEN_PORT}" \
    "${CLOUDFLARED_LOG_FILE}"
  tmux new-session -d -s "${session_name}" "${start_command}"
  pid="$(tmux_session_pid "${session_name}" || true)"
  [[ -n "${pid}" ]] && printf '%s\n' "${pid}" >"${CLOUDFLARED_PID_FILE}"

  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    public_url="$(extract_trycloudflare_url || true)"
    if [[ -n "${public_url}" ]]; then
      printf '%s\n' "${public_url}" >"${CLOUDFLARED_URL_FILE}"
      local ws_endpoint
      ws_endpoint="$(public_ws_from_https "${public_url}")"
      if wait_for_endpoint_ready "${ws_endpoint}" "${READY_TIMEOUT_SECONDS}" "${pid}"; then
        printf '%s' "${ws_endpoint}"
        return 0
      fi
      if ! is_pid_running "${pid}"; then
        fail "cloudflared 已退出，日志见 ${CLOUDFLARED_LOG_FILE}"
      fi
      fail "Quick Tunnel 在 ${READY_TIMEOUT_SECONDS} 秒内仍未就绪（最后状态=${LAST_ENDPOINT_STATUS:-unknown}）：${public_url}"
    fi
    if ! is_pid_running "${pid}"; then
      fail "cloudflared 已退出，日志见 ${CLOUDFLARED_LOG_FILE}"
    fi
    sleep 1
  done

  fail "30 秒内没有拿到 Quick Tunnel 公网地址，日志见 ${CLOUDFLARED_LOG_FILE}"
}

start_named_tunnel() {
  require_cmd cloudflared
  require_cmd tmux
  validate_named_tunnel_config_or_fail

  local session_name
  local start_command
  session_name="$(cloudflared_tmux_session_name)"

  if [[ "${RESTART}" == "1" ]]; then
    stop_tmux_session "${session_name}" "cloudflared"
    rm -f "${CLOUDFLARED_PID_FILE}"
  fi

  local pid
  if tmux_session_exists "${session_name}"; then
    pid="$(tmux_session_pid "${session_name}" || true)"
    local existing_endpoint="wss://${HOSTNAME}"
    if wait_for_endpoint_ready "${existing_endpoint}" "${READY_TIMEOUT_SECONDS}" "${pid}"; then
      printf '%s' "${existing_endpoint}"
      return 0
    fi
    log "现有命名 Tunnel 不可用，准备重启"
    stop_tmux_session "${session_name}" "cloudflared"
    rm -f "${CLOUDFLARED_PID_FILE}"
  fi

  : >"${CLOUDFLARED_LOG_FILE}"
  log "启动命名 Tunnel ${TUNNEL_NAME}（hostname=${HOSTNAME}）"
  printf -v start_command 'cd %q && exec cloudflared tunnel --config %q --no-autoupdate --logfile %q run %q >/dev/null 2>&1' \
    "${PROJECT_ROOT}" \
    "${CLOUDFLARED_CONFIG}" \
    "${CLOUDFLARED_LOG_FILE}" \
    "${TUNNEL_NAME}"
  tmux new-session -d -s "${session_name}" "${start_command}"
  pid="$(tmux_session_pid "${session_name}" || true)"
  [[ -n "${pid}" ]] && printf '%s\n' "${pid}" >"${CLOUDFLARED_PID_FILE}"

  local endpoint="wss://${HOSTNAME}"
  if wait_for_endpoint_ready "${endpoint}" "${READY_TIMEOUT_SECONDS}" "${pid}"; then
    printf '%s' "${endpoint}"
    return 0
  fi
  if ! is_pid_running "${pid}"; then
    fail "cloudflared 已退出，日志见 ${CLOUDFLARED_LOG_FILE}"
  fi

  fail "命名 Tunnel 启动后 ${READY_TIMEOUT_SECONDS} 秒内仍不可用（最后状态=${LAST_ENDPOINT_STATUS:-unknown}）：${endpoint}"
}

ensure_endpoint_reachable_safe() {
  local ws_endpoint="$1"
  local public_url
  local status_code
  public_url="$(public_https_from_endpoint "${ws_endpoint}")"
  status_code="$(http_status_code "${public_url}")"

  if [[ -z "${status_code}" || "${status_code}" == "000" || "${status_code}" == "530" ]]; then
    return 1
  fi
  return 0
}

urlencode() {
  local raw="$1"
  local encoded=""
  local i char byte_hex
  local LC_ALL=C
  for ((i = 0; i < ${#raw}; i++)); do
    char="${raw:i:1}"
    case "${char}" in
      [a-zA-Z0-9.~_-])
        encoded+="${char}"
        ;;
      *)
        printf -v byte_hex '%%%02X' "'${char}"
        encoded+="${byte_hex}"
        ;;
    esac
  done
  printf '%s' "${encoded}"
}

build_pair_url() {
  local ws_endpoint="$1"
  local query="mode=codex"
  query+="&endpoint=$(urlencode "${ws_endpoint}")"
  query+="&cwd=$(urlencode "${CWD_PATH}")"
  query+="&permissionPreset=$(urlencode "${PERMISSION_PRESET}")"
  query+="&connect=${CONNECT}"
  query+="&initialize=${INITIALIZE}"
  query+="&createSession=${CREATE_SESSION}"

  if [[ -n "${BEARER_TOKEN}" ]]; then
    query+="&bearerToken=$(urlencode "${BEARER_TOKEN}")"
  fi
  if [[ -n "${CF_ACCESS_CLIENT_ID}" ]]; then
    query+="&cfAccessClientId=$(urlencode "${CF_ACCESS_CLIENT_ID}")"
  fi
  if [[ -n "${CF_ACCESS_CLIENT_SECRET}" ]]; then
    query+="&cfAccessClientSecret=$(urlencode "${CF_ACCESS_CLIENT_SECRET}")"
  fi

  printf 'openconnect://connect?%s' "${query}"
}

print_qr_if_available() {
  local pair_url="$1"
  if [[ "${PRINT_QR}" != "1" ]]; then
    return 0
  fi
  if command -v qrencode >/dev/null 2>&1; then
    printf '%s' "${pair_url}" | qrencode -t ansiutf8
  else
    log "未检测到 qrencode，已跳过终端二维码输出。可先执行：brew install qrencode"
  fi
}

copy_to_clipboard_if_available() {
  local text="$1"
  if command -v pbcopy >/dev/null 2>&1; then
    printf '%s' "${text}" | pbcopy
    log "配对链接已复制到剪贴板"
  fi
}

stop_all() {
  stop_tmux_session "$(cloudflared_tmux_session_name)" "cloudflared"
  rm -f "${CLOUDFLARED_PID_FILE}"
  stop_tmux_session "$(codex_tmux_session_name)" "codex app-server"
  rm -f "${CODEX_PID_FILE}"
  rm -f "${CLOUDFLARED_URL_FILE}"
}

status() {
  log "项目目录：${PROJECT_ROOT}"
  log "默认工作目录：${CWD_PATH}"
  log "本地 Codex 监听：ws://${LISTEN_HOST}:${LISTEN_PORT}"

  local listener
  listener="$(port_listener_line)"
  if [[ -n "${listener}" ]]; then
    log "本地监听状态：OK -> ${listener}"
  else
    log "本地监听状态：未监听"
  fi

  local ws_endpoint=""
  case "${MODE}" in
    quick)
      if [[ -f "${CLOUDFLARED_URL_FILE}" ]]; then
        ws_endpoint="$(public_ws_from_https "$(tr -d '[:space:]' <"${CLOUDFLARED_URL_FILE}")")"
      fi
      ;;
    endpoint)
      ws_endpoint="${ENDPOINT}"
      ;;
    named)
      ws_endpoint="wss://${HOSTNAME}"
      ;;
  esac

  if [[ -n "${ws_endpoint}" ]]; then
    if ensure_endpoint_reachable_safe "${ws_endpoint}"; then
      log "公网入口状态：OK -> ${ws_endpoint}"
      log "配对链接：$(build_pair_url "${ws_endpoint}")"
    else
      log "公网入口状态：不可用 -> ${ws_endpoint}"
    fi
  else
    log "公网入口状态：尚未生成"
  fi
}

run_up() {
  start_codex_server_if_needed

  local ws_endpoint=""
  case "${MODE}" in
    quick)
      ws_endpoint="$(start_quick_tunnel)"
      ;;
    endpoint)
      [[ -n "${ENDPOINT}" ]] || fail "endpoint 模式需要 --endpoint WSS_URL"
      ensure_endpoint_reachable "${ENDPOINT}"
      ws_endpoint="${ENDPOINT}"
      ;;
    named)
      ws_endpoint="$(start_named_tunnel)"
      ;;
    *)
      fail "未知模式：${MODE}"
      ;;
  esac

  local pair_url
  pair_url="$(build_pair_url "${ws_endpoint}")"

  log "公网 WebSocket：${ws_endpoint}"
  log "配对链接：${pair_url}"
  if [[ "${LAST_ENDPOINT_PROBE_MODE}" == public-dns:* ]]; then
    log "注意：当前机器默认 DNS 无法解析 Tunnel 域名，脚本已改用公共 DNS 探测。若手机与电脑共用同一 Wi-Fi DNS，扫码后仍可能连不上；可改用移动网络，或使用 --named-tunnel / --endpoint 配置稳定域名。"
  fi
  copy_to_clipboard_if_available "${pair_url}"
  print_qr_if_available "${pair_url}"

  cat <<EOF

下一步：
1. 打开手机 App
2. 进入“设置 -> 连接方式 -> 扫码”
3. 扫描上面的二维码；如果当前终端没显示二维码，就用剪贴板里的链接自行生成二维码

日志文件：
- ${CODEX_LOG_FILE}
- ${CLOUDFLARED_LOG_FILE}
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      up|status|stop|doctor)
        COMMAND="$1"
        shift
        ;;
      --quick-tunnel)
        MODE="quick"
        shift
        ;;
      --endpoint)
        MODE="endpoint"
        ENDPOINT="${2:-}"
        shift 2
        ;;
      --named-tunnel)
        MODE="named"
        TUNNEL_NAME="${2:-}"
        shift 2
        ;;
      --hostname)
        HOSTNAME="${2:-}"
        shift 2
        ;;
      --cwd)
        CWD_PATH="${2:-}"
        shift 2
        ;;
      --listen-port)
        LISTEN_PORT="${2:-}"
        shift 2
        ;;
      --listen-host)
        LISTEN_HOST="${2:-}"
        shift 2
        ;;
      --cloudflared-config)
        CLOUDFLARED_CONFIG="${2:-}"
        shift 2
        ;;
      --permission)
        PERMISSION_PRESET="${2:-}"
        shift 2
        ;;
      --bearer-token)
        BEARER_TOKEN="${2:-}"
        shift 2
        ;;
      --cf-access-client-id)
        CF_ACCESS_CLIENT_ID="${2:-}"
        shift 2
        ;;
      --cf-access-client-secret)
        CF_ACCESS_CLIENT_SECRET="${2:-}"
        shift 2
        ;;
      --no-create-session)
        CREATE_SESSION=0
        shift
        ;;
      --no-initialize)
        INITIALIZE=0
        shift
        ;;
      --no-connect)
        CONNECT=0
        shift
        ;;
      --restart)
        RESTART=1
        shift
        ;;
      --no-qr)
        PRINT_QR=0
        shift
        ;;
      --ready-timeout)
        READY_TIMEOUT_SECONDS="${2:-}"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        fail "未知参数：$1"
        ;;
    esac
  done

  [[ "${READY_TIMEOUT_SECONDS}" =~ ^[0-9]+$ ]] || fail "--ready-timeout 必须是正整数"
}

main() {
  parse_args "$@"

  case "${COMMAND}" in
    up)
      run_up
      ;;
    status)
      status
      ;;
    stop)
      stop_all
      ;;
    doctor)
      run_doctor
      ;;
    *)
      fail "未知命令：${COMMAND}"
      ;;
  esac
}

main "$@"
