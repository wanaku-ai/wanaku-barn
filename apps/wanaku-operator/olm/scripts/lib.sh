#!/usr/bin/env bash
# Shared helpers for the local OLM test scripts. Source it, do not execute it.

set -euo pipefail

OLM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAMESPACE="${NAMESPACE:-wanaku-olm-test}"
CHANNEL="${CHANNEL:-alpha}"
# Must be valid semver: it becomes the CSV version
LOCAL_VERSION="${LOCAL_VERSION:-0.0.1-dev}"

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require() {
  for tool in "$@"; do
    command -v "${tool}" >/dev/null 2>&1 || die "'${tool}' is required but was not found in PATH"
  done
}

# Builds the operator image (Jib + $CONTAINER_TOOL, podman by default) and the bundle that references it.
# $1 registry, $2 group (the image is <registry>/<group>/wanaku-operator:<LOCAL_VERSION>)
build_operator_and_bundle() {
  local registry="$1" group="$2" tool="${CONTAINER_TOOL:-podman}"
  log "Building the operator image and bundle (${registry}/${group}/wanaku-operator:${LOCAL_VERSION})"
  make -C "${OLM_DIR}" bundle \
    REGISTRY="${registry}" GROUP="${group}" VERSION="${LOCAL_VERSION}" CHANNEL="${CHANNEL}" \
    MVN_EXTRA="-Dquarkus.container-image.build=true -Dquarkus.jib.docker-executable-name=${tool}"
}

# Runs the bundle with operator-sdk and waits for the CSV to succeed.
# $1 CLI (kubectl|oc), $2 bundle image as seen from inside the cluster, remaining args go to operator-sdk
run_bundle() {
  local cli="$1" image="$2"
  shift 2
  log "Installing the bundle ${image} in namespace ${NAMESPACE}"
  "${cli}" create namespace "${NAMESPACE}" --dry-run=client -o yaml | "${cli}" apply -f -
  operator-sdk run bundle "${image}" \
    --namespace "${NAMESPACE}" --install-mode OwnNamespace --timeout 5m "$@"
  "${cli}" -n "${NAMESPACE}" get csv
}

cleanup_bundle() {
  local cli="$1"
  log "Removing the operator from namespace ${NAMESPACE}"
  operator-sdk cleanup wanaku-operator --namespace "${NAMESPACE}" --delete-all || true
  "${cli}" delete namespace "${NAMESPACE}" --ignore-not-found --wait=false
}
