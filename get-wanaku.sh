#!/usr/bin/env bash
#
# Installs the Wanaku CLI.
#
#   curl -fsSL https://raw.githubusercontent.com/wanaku-ai/wanaku-barn/main/get-wanaku.sh | bash
#
# Native binaries are installed on Linux x86_64 and macOS arm64. On other
# platforms the Java-based distribution is installed, which requires Java 21+.
#
# Prerequisites: curl, unzip and either sha256sum or shasum.
#
# Environment variables:
#   WANAKU_INSTALL_DIR  Installation directory (default: $HOME/bin)
#   WANAKU_VERSION      Release tag to install, e.g. v0.2.0 (default: latest)
#   WANAKU_FORCE_JAVA   Set to "true" to install the Java-based distribution
#                       even when a native binary is available
set -euo pipefail

REPO="wanaku-ai/wanaku"
INSTALL_DIR="${WANAKU_INSTALL_DIR:-$HOME/bin}"

# Minimum Java version required to run the Java-based distribution.
REQUIRED_JAVA_VERSION=21

if [ -t 1 ]; then
    info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
    warn()  { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
    error() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }
else
    info()  { printf '==> %s\n' "$*"; }
    warn()  { printf 'WARN: %s\n' "$*" >&2; }
    error() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
fi

download() {
    curl --proto '=https' --tlsv1.2 -fsSL "$@"
}

check_prerequisites() {
    local cmd
    for cmd in curl unzip uname mktemp; do
        command -v "$cmd" >/dev/null 2>&1 || error "'$cmd' is required but was not found. Please install it and try again."
    done

    [ -n "$INSTALL_DIR" ] || error "WANAKU_INSTALL_DIR must not be empty"
}

java_cmd() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
    else
        command -v java 2>/dev/null || true
    fi
}

check_java_version() {
    local java version_output major
    java="$(java_cmd)"
    if [ -z "$java" ]; then
        error "Java is not installed. The Wanaku CLI for this platform requires Java ${REQUIRED_JAVA_VERSION} or later. Please install Java ${REQUIRED_JAVA_VERSION}+ and try again."
    fi

    version_output="$("$java" -version 2>&1)"

    # Parse the major version from strings like:
    #   openjdk version "21.0.2" 2024-01-16   -> 21
    #   java version "1.8.0_392"              -> 8 (legacy 1.x scheme)
    major="$(echo "$version_output" | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"

    if [ "$major" = "1" ]; then
        major="$(echo "$version_output" | head -1 | sed -E 's/.*version "1\.([0-9]+).*/\1/')"
    fi

    if [ -z "$major" ] || ! printf '%s' "$major" | grep -Eq '^[0-9]+$'; then
        error "Could not determine the installed Java version. Wanaku requires Java ${REQUIRED_JAVA_VERSION} or later."
    fi

    if [ "$major" -lt "$REQUIRED_JAVA_VERSION" ]; then
        error "Java $major detected, but Wanaku requires Java ${REQUIRED_JAVA_VERSION} or later. Please upgrade your Java installation and try again."
    fi

    info "Detected Java $major (>= ${REQUIRED_JAVA_VERSION})"
}

detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    PLATFORM=""

    case "$os/$arch" in
        Darwin/arm64|Darwin/aarch64) PLATFORM="osx-aarch_64" ;;
        Linux/x86_64|Linux/amd64)    PLATFORM="linux-x86_64" ;;
    esac

    if [ "${WANAKU_FORCE_JAVA:-false}" = "true" ]; then
        info "WANAKU_FORCE_JAVA is set — installing the Java-based distribution"
        PLATFORM="java"
    elif [ -z "$PLATFORM" ]; then
        info "No native binary for $os/$arch — falling back to the Java-based distribution"
        PLATFORM="java"
    fi

    if [ "$PLATFORM" = "java" ]; then
        check_java_version
    fi
}

fetch_version() {
    if [ -n "${WANAKU_VERSION:-}" ]; then
        VERSION="$WANAKU_VERSION"
    else
        info "Fetching latest release version..."
        local response
        response="$(download "https://api.github.com/repos/${REPO}/releases/latest")" \
            || error "Could not fetch release info from GitHub. Check your internet connection or GitHub API rate limits, or set WANAKU_VERSION."

        if command -v jq >/dev/null 2>&1; then
            VERSION="$(echo "$response" | jq -r '.tag_name // empty')"
        else
            VERSION="$(echo "$response" | grep '"tag_name"' | head -1 | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')"
        fi

        [ -n "$VERSION" ] || error "Could not determine the latest version. Check GitHub API rate limits, or set WANAKU_VERSION."
    fi

    VERSION_NUM="${VERSION#v}"
    info "Installing version: $VERSION"
}

build_download_url() {
    local tag="${VERSION}"
    case "$VERSION_NUM" in
        *SNAPSHOT*) tag="early-access" ;;
    esac
    local base="https://github.com/${REPO}/releases/download/${tag}"

    if [ "$PLATFORM" = "java" ]; then
        ARTIFACT="wanaku-cli-${VERSION_NUM}.zip"
    else
        ARTIFACT="wanaku-cli-${VERSION_NUM}-${PLATFORM}.zip"
    fi

    DOWNLOAD_URL="${base}/${ARTIFACT}"
    CHECKSUMS_URL="${base}/checksums_sha256.txt"
}

download_and_verify() {
    WORK_DIR="$(mktemp -d)"
    trap 'rm -rf "$WORK_DIR"' EXIT

    info "Downloading $ARTIFACT..."
    download -o "${WORK_DIR}/${ARTIFACT}" "$DOWNLOAD_URL" \
        || error "Failed to download $DOWNLOAD_URL"

    info "Downloading checksums..."
    download -o "${WORK_DIR}/checksums_sha256.txt" "$CHECKSUMS_URL" \
        || error "Failed to download $CHECKSUMS_URL"

    local entry sha_cmd
    entry="$(grep -E "^[0-9a-fA-F]{64} [ *]${ARTIFACT}\$" "${WORK_DIR}/checksums_sha256.txt" || true)"
    if [ -z "$entry" ]; then
        warn "No checksum entry for $ARTIFACT — skipping verification"
        return
    fi

    if command -v sha256sum >/dev/null 2>&1; then
        sha_cmd="sha256sum"
    elif command -v shasum >/dev/null 2>&1; then
        sha_cmd="shasum -a 256"
    else
        error "Neither sha256sum nor shasum was found — cannot verify the download. Please install one of them and try again."
    fi

    info "Verifying SHA-256 checksum..."
    (cd "$WORK_DIR" && echo "$entry" | $sha_cmd -c - >/dev/null) \
        || error "Checksum verification failed for $ARTIFACT. The download may be corrupted or tampered with."
}

install_wanaku() {
    info "Installing to ${INSTALL_DIR}..."
    mkdir -p "$INSTALL_DIR" || error "Could not create $INSTALL_DIR"
    [ -w "$INSTALL_DIR" ] || error "$INSTALL_DIR is not writable. Set WANAKU_INSTALL_DIR to a writable directory."

    unzip -qo "${WORK_DIR}/${ARTIFACT}" -d "${WORK_DIR}/extract" \
        || error "Failed to extract $ARTIFACT"

    if [ "$PLATFORM" = "java" ]; then
        install_java
    else
        install_native
    fi

    if ! echo "$PATH" | tr ':' '\n' | grep -Fxq "$INSTALL_DIR"; then
        warn "$INSTALL_DIR is not in your PATH. Add it with:"
        warn "  export PATH=\"${INSTALL_DIR}:\$PATH\""
    fi
}

install_native() {
    local bin_dir
    bin_dir="$(find "${WORK_DIR}/extract" -type d -name bin | head -1)"

    if [ -z "$bin_dir" ] || [ ! -f "${bin_dir}/wanaku-cli" ]; then
        error "Unexpected archive layout — bin/wanaku-cli not found"
    fi

    install -m 755 "${bin_dir}/wanaku-cli" "${INSTALL_DIR}/wanaku-cli"
    # bin/wanaku is a small launcher that delegates to the sibling wanaku-cli binary
    if [ -f "${bin_dir}/wanaku" ]; then
        install -m 755 "${bin_dir}/wanaku" "${INSTALL_DIR}/wanaku"
    else
        ln -sf wanaku-cli "${INSTALL_DIR}/wanaku"
    fi

    # Remove a previous Java-based installation, now superseded by the native binary
    rm -rf "${INSTALL_DIR}/wanaku-java"
}

install_java() {
    local extract_dir
    extract_dir="$(find "${WORK_DIR}/extract" -mindepth 1 -maxdepth 1 -type d -name "wanaku-cli-*" | head -1)"

    if [ -z "$extract_dir" ] || [ ! -f "${extract_dir}/quarkus-run.jar" ]; then
        error "Unexpected Java archive layout — quarkus-run.jar not found"
    fi

    local java_home="${INSTALL_DIR}/wanaku-java"
    rm -rf "$java_home"
    cp -R "$extract_dir" "$java_home"

    # Remove previous launchers first so a leftover symlink is not written through
    rm -f "${INSTALL_DIR}/wanaku" "${INSTALL_DIR}/wanaku-cli"
    cat > "${INSTALL_DIR}/wanaku" <<'WRAPPER'
#!/bin/sh
# --add-opens is included unconditionally so the installed wrapper works across
# all supported Java versions (21+), including Java 25 which tightened
# strong encapsulation of java.lang internals. The flag is idempotent on JVMs
# where the package is already open.
installDir="$(dirname "$0")/wanaku-java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    java="${JAVA_HOME}/bin/java"
else
    java="java"
fi
exec "$java" --add-opens=java.base/java.lang=ALL-UNNAMED -jar "${installDir}/quarkus-run.jar" "$@"
WRAPPER
    chmod 755 "${INSTALL_DIR}/wanaku"
    cp "${INSTALL_DIR}/wanaku" "${INSTALL_DIR}/wanaku-cli"
}

main() {
    check_prerequisites
    detect_platform
    fetch_version
    build_download_url
    download_and_verify
    install_wanaku
    info "Wanaku CLI installed successfully!"
    "${INSTALL_DIR}/wanaku" --version 2>/dev/null || true
}

main "$@"
