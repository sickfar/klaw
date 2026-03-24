#!/bin/sh
# Uninstall klaw: stop services, remove XDG directories, workspace, and binaries.
# Usage:
#   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/uninstall.sh)"
#   or locally: ./scripts/uninstall.sh
set -eu

# --- helpers ----------------------------------------------------------------

info()    { printf '  \033[1;32m%s\033[0m %s\n' "$1" "$2"; }
warn()    { printf '  \033[1;33mWARN\033[0m %s\n' "$1"; }
err()     { printf '  \033[1;31mERROR\033[0m %s\n' "$1" >&2; }
removed() { printf '  \033[1;31m✗\033[0m %s\n' "$1"; }
skipped() { printf '  \033[90m- %s (skipped)\033[0m\n' "$1"; }

ask_yn() {
    printf '  %s [y/N] ' "$1"
    read -r answer
    case "${answer}" in
        [Yy]|[Yy][Ee][Ss]) return 0 ;;
        *) return 1 ;;
    esac
}

# --- paths ------------------------------------------------------------------

HOME_DIR="${HOME:-/root}"
CONFIG_DIR="${XDG_CONFIG_HOME:-${HOME_DIR}/.config}/klaw"
DATA_DIR="${XDG_DATA_HOME:-${HOME_DIR}/.local/share}/klaw"
STATE_DIR="${XDG_STATE_HOME:-${HOME_DIR}/.local/state}/klaw"
CACHE_DIR="${XDG_CACHE_HOME:-${HOME_DIR}/.cache}/klaw"
WORKSPACE_DIR="${KLAW_WORKSPACE:-${HOME_DIR}/klaw-workspace}"
INSTALL_DIR="${KLAW_INSTALL_DIR:-${HOME_DIR}/.local/bin}"

# --- detect deployment mode -------------------------------------------------

DEPLOY_MODE=""
COMPOSE_FILE=""

detect_deploy_mode() {
    DEPLOY_CONF="${CONFIG_DIR}/.deploy"
    if [ -f "${DEPLOY_CONF}" ]; then
        DEPLOY_MODE="$(grep -oP '(?<=mode=)\w+' "${DEPLOY_CONF}" 2>/dev/null || true)"
    fi
    COMPOSE_FILE="${CONFIG_DIR}/docker-compose.json"
}

# --- stop and remove services -----------------------------------------------

stop_services() {
    echo ""
    info "Services" "Stopping and removing..."

    # Docker containers
    if [ -n "${DEPLOY_MODE}" ] && [ "${DEPLOY_MODE}" != "native" ] && [ -f "${COMPOSE_FILE}" ]; then
        if command -v docker >/dev/null 2>&1; then
            docker compose -f "${COMPOSE_FILE}" down 2>/dev/null && removed "Docker containers stopped and removed" || warn "docker compose down failed"
        fi
    fi

    # systemd user services (Linux)
    if command -v systemctl >/dev/null 2>&1; then
        for svc in klaw-engine klaw-gateway; do
            if systemctl --user is-active "${svc}" >/dev/null 2>&1; then
                systemctl --user stop "${svc}" 2>/dev/null || true
            fi
            if systemctl --user is-enabled "${svc}" >/dev/null 2>&1; then
                systemctl --user disable "${svc}" 2>/dev/null || true
            fi
        done
        SYSTEMD_DIR="${HOME_DIR}/.config/systemd/user"
        for unit in klaw-engine.service klaw-gateway.service; do
            if [ -f "${SYSTEMD_DIR}/${unit}" ]; then
                rm -f "${SYSTEMD_DIR}/${unit}"
                removed "${SYSTEMD_DIR}/${unit}"
            fi
        done
        systemctl --user daemon-reload 2>/dev/null || true
    fi

    # launchd (macOS)
    if command -v launchctl >/dev/null 2>&1; then
        LAUNCH_DIR="${HOME_DIR}/Library/LaunchAgents"
        for plist in io.github.klaw.engine io.github.klaw.gateway; do
            if [ -f "${LAUNCH_DIR}/${plist}.plist" ]; then
                launchctl unload -w "${LAUNCH_DIR}/${plist}.plist" 2>/dev/null || true
                rm -f "${LAUNCH_DIR}/${plist}.plist"
                removed "${LAUNCH_DIR}/${plist}.plist"
            fi
        done
    fi
}

# --- remove directories ----------------------------------------------------

remove_dir() {
    _dir="$1"
    _label="$2"
    if [ -d "${_dir}" ]; then
        if ask_yn "Remove ${_label} (${_dir})?"; then
            rm -rf "${_dir}"
            removed "${_dir}"
        else
            skipped "${_label}"
        fi
    fi
}

# --- remove binaries --------------------------------------------------------

remove_binaries() {
    echo ""
    info "Binaries" "Checking ${INSTALL_DIR}..."
    for bin in klaw klaw-engine klaw-gateway; do
        if [ -f "${INSTALL_DIR}/${bin}" ]; then
            rm -f "${INSTALL_DIR}/${bin}"
            removed "${INSTALL_DIR}/${bin}"
        fi
    done
}

# --- main -------------------------------------------------------------------

main() {
    echo ""
    echo "  Klaw Uninstaller"
    echo "  ================"
    echo ""

    detect_deploy_mode

    if [ -n "${DEPLOY_MODE}" ]; then
        info "Detected" "deployment mode: ${DEPLOY_MODE}"
    fi

    echo ""
    echo "  The following directories may contain klaw data:"
    echo ""
    [ -d "${CONFIG_DIR}" ]    && echo "    Config:    ${CONFIG_DIR}"
    [ -d "${DATA_DIR}" ]      && echo "    Data:      ${DATA_DIR}"
    [ -d "${STATE_DIR}" ]     && echo "    State:     ${STATE_DIR}"
    [ -d "${CACHE_DIR}" ]     && echo "    Cache:     ${CACHE_DIR}"
    [ -d "${WORKSPACE_DIR}" ] && echo "    Workspace: ${WORKSPACE_DIR}"
    echo ""

    if ! ask_yn "Proceed with uninstall?"; then
        echo ""
        info "Aborted" "Nothing was removed."
        exit 0
    fi

    # 1. Stop and remove services
    stop_services

    # 2. Remove XDG directories (ask individually)
    echo ""
    info "Directories" "Select what to remove:"
    echo ""
    remove_dir "${CONFIG_DIR}"    "Config (engine.json, gateway.json, .env)"
    remove_dir "${DATA_DIR}"      "Data (databases, conversations, memory, JARs)"
    remove_dir "${STATE_DIR}"     "State (logs, buffers)"
    remove_dir "${CACHE_DIR}"     "Cache (models)"
    remove_dir "${WORKSPACE_DIR}" "Workspace (agent files, skills, identity)"

    # 3. Remove binaries
    remove_binaries

    echo ""
    info "Done" "Klaw has been uninstalled."
    echo ""
}

main "$@"
