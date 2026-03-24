#!/bin/sh
# Install klaw CLI binary from GitHub Releases.
# Usage:
#   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/sickfar/klaw/main/scripts/get-klaw.sh)"
#
# Environment variables:
#   KLAW_VERSION      - pin to a specific release tag (e.g. v0.5.0); default: latest
#   KLAW_INSTALL_DIR  - installation directory; default: ~/.local/share/klaw/bin
set -eu

OWNER="sickfar"
REPO="klaw"
INSTALL_DIR="${KLAW_INSTALL_DIR:-${HOME}/.local/share/klaw/bin}"
SYMLINK_DIR="${HOME}/.local/bin"
VERSION="${KLAW_VERSION:-}"

# --- helpers ----------------------------------------------------------------

info() { printf '  \033[1;32m%s\033[0m %s\n' "$1" "$2"; }
warn() { printf '  \033[1;33mWARN\033[0m %s\n' "$1"; }
err()  { printf '  \033[1;31mERROR\033[0m %s\n' "$1" >&2; }

# --- platform detection -----------------------------------------------------

detect_platform() {
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    case "${OS}" in
        Linux)
            case "${ARCH}" in
                x86_64)  PLATFORM="klaw-linuxX64"   ;;
                aarch64) PLATFORM="klaw-linuxArm64"  ;;
                *)       err "Unsupported Linux architecture: ${ARCH}"; exit 1 ;;
            esac
            ;;
        Darwin)
            case "${ARCH}" in
                arm64) PLATFORM="klaw-macosArm64" ;;
                *)     err "macOS Intel (x86_64) is not supported. Apple Silicon (arm64) required."; exit 1 ;;
            esac
            ;;
        *)
            err "Unsupported operating system: ${OS}"
            exit 1
            ;;
    esac
    info "Platform" "${OS}/${ARCH} → ${PLATFORM}"
}

# --- download tool ----------------------------------------------------------

detect_downloader() {
    if command -v curl >/dev/null 2>&1; then
        DOWNLOAD_CMD="curl"
    elif command -v wget >/dev/null 2>&1; then
        DOWNLOAD_CMD="wget"
    else
        err "Neither curl nor wget found. Install one and retry."
        exit 2
    fi
}

download() {
    # download URL OUTPUT_FILE
    _url="$1"
    _out="$2"
    if [ "${DOWNLOAD_CMD}" = "curl" ]; then
        curl -fsSL -o "${_out}" "${_url}"
    else
        wget -qO "${_out}" "${_url}"
    fi
}

# Try to download; return 1 on failure instead of exiting (set -e safe).
try_download() {
    _url="$1"
    _out="$2"
    if [ "${DOWNLOAD_CMD}" = "curl" ]; then
        curl -fsSL -o "${_out}" "${_url}" 2>/dev/null && return 0
    else
        wget -qO "${_out}" "${_url}" 2>/dev/null && return 0
    fi
    return 1
}

# --- temp dir ---------------------------------------------------------------

create_tmpdir() {
    TMPDIR_KLAW="$(mktemp -d)"
    # shellcheck disable=SC2154
    trap 'rm -rf "${TMPDIR_KLAW}"' EXIT INT TERM
}

# --- download binary --------------------------------------------------------

resolve_base_url() {
    if [ -n "${VERSION}" ]; then
        BASE_URL="https://github.com/${OWNER}/${REPO}/releases/download/${VERSION}"
    else
        BASE_URL="https://github.com/${OWNER}/${REPO}/releases/latest/download"
    fi
}

download_binary() {
    info "Downloading" "${BASE_URL}/${PLATFORM}"
    if ! download "${BASE_URL}/${PLATFORM}" "${TMPDIR_KLAW}/${PLATFORM}"; then
        err "Download failed. Check your network connection and that the release exists."
        exit 3
    fi
}

# --- checksum verification --------------------------------------------------

verify_checksum() {
    CHECKSUM_FILE="${TMPDIR_KLAW}/checksums.sha256"
    if ! try_download "${BASE_URL}/checksums.sha256" "${CHECKSUM_FILE}"; then
        warn "Checksum file not available for this release — skipping verification."
        return
    fi

    # Detect available checksum tool
    SHA_CMD=""
    if command -v sha256sum >/dev/null 2>&1; then
        SHA_CMD="sha256sum"
    elif command -v shasum >/dev/null 2>&1; then
        SHA_CMD="shasum -a 256"
    fi

    if [ -z "${SHA_CMD}" ]; then
        warn "Neither sha256sum nor shasum found — skipping checksum verification."
        return
    fi

    # Extract expected checksum for our platform
    EXPECTED="$(awk -v file="${PLATFORM}" '$2 == file {print $1}' "${CHECKSUM_FILE}")"
    if [ -z "${EXPECTED}" ]; then
        warn "No checksum entry for ${PLATFORM} — skipping verification."
        return
    fi

    ACTUAL="$(cd "${TMPDIR_KLAW}" && ${SHA_CMD} "${PLATFORM}" | awk '{print $1}')"
    if [ "${EXPECTED}" != "${ACTUAL}" ]; then
        err "Checksum mismatch!"
        err "  Expected: ${EXPECTED}"
        err "  Actual:   ${ACTUAL}"
        exit 4
    fi
    info "Checksum" "verified ✓"
}

# --- install ----------------------------------------------------------------

install_binary() {
    mkdir -p "${INSTALL_DIR}"
    mv "${TMPDIR_KLAW}/${PLATFORM}" "${INSTALL_DIR}/klaw"
    chmod +x "${INSTALL_DIR}/klaw"

    # Remove macOS quarantine attribute if present
    case "$(uname -s)" in
        Darwin) xattr -d com.apple.quarantine "${INSTALL_DIR}/klaw" 2>/dev/null || true ;;
    esac

    # Create symlink in ~/.local/bin so the binary is on PATH
    mkdir -p "${SYMLINK_DIR}"
    ln -sf "${INSTALL_DIR}/klaw" "${SYMLINK_DIR}/klaw"
}

# --- post-install -----------------------------------------------------------

print_success() {
    echo ""
    INSTALLED_VERSION=""
    if INSTALLED_VERSION="$("${INSTALL_DIR}/klaw" --version 2>/dev/null)"; then
        info "Installed" "klaw ${INSTALLED_VERSION}"
    else
        info "Installed" "klaw → ${INSTALL_DIR}/klaw"
    fi

    case ":${PATH}:" in
        *":${SYMLINK_DIR}:"*) ;;
        *)
            echo ""
            warn "${SYMLINK_DIR} is not in your PATH."

            SHELL_NAME="$(basename "${SHELL:-/bin/sh}")"
            case "${SHELL_NAME}" in
                zsh)  SHELL_RC="${HOME}/.zshrc"
                      PATH_LINE="export PATH=\"${SYMLINK_DIR}:\$PATH\""  ;;
                bash) SHELL_RC="${HOME}/.bashrc"
                      PATH_LINE="export PATH=\"${SYMLINK_DIR}:\$PATH\""  ;;
                fish) SHELL_RC="${HOME}/.config/fish/config.fish"
                      PATH_LINE="set -gx PATH \"${SYMLINK_DIR}\" \$PATH" ;;
                *)    SHELL_RC=""  ;;
            esac

            if [ -n "${SHELL_RC}" ]; then
                echo "  Run this command to add it permanently:"
                echo ""
                echo "    echo '${PATH_LINE}' >> ${SHELL_RC}"
                echo ""
                echo "  Then restart your shell or run:  source ${SHELL_RC}"
            else
                echo "  Add this to your shell config:"
                echo ""
                echo "    export PATH=\"${SYMLINK_DIR}:\$PATH\""
            fi
            echo ""
            ;;
    esac

    echo ""
    echo "  Run 'klaw init' to get started."
    echo ""
}

# --- main -------------------------------------------------------------------

main() {
    echo ""
    echo "  Installing klaw CLI..."
    echo ""
    detect_platform
    detect_downloader
    resolve_base_url
    create_tmpdir
    download_binary
    verify_checksum
    install_binary
    print_success
}

main "$@"
