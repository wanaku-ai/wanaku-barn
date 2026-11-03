#!/usr/bin/env bash
# Smoke test for an operator installed through OLM: creates a minimal WanakuRouter, waits for it to be
# ready, then deletes it again.
#
# Usage: smoke-test.sh kubectl|oc

# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

CLI="${1:-kubectl}"
require "${CLI}"

SAMPLE="$(mktemp --suffix=.yaml)"
trap 'rm -f "${SAMPLE}"' EXIT
cat > "${SAMPLE}" <<EOF
apiVersion: wanaku.ai/v1alpha1
kind: WanakuRouter
metadata:
  name: smoke
spec:
  server: {}
EOF

log "Waiting for the ClusterServiceVersion to succeed"
"${CLI}" -n "${NAMESPACE}" wait csv --all --for=jsonpath='{.status.phase}'=Succeeded --timeout=300s

log "Creating a WanakuRouter"
"${CLI}" -n "${NAMESPACE}" apply -f "${SAMPLE}"

log "Waiting for the WanakuRouter to become Ready"
if ! "${CLI}" -n "${NAMESPACE}" wait wanakurouter/smoke --for=condition=Ready --timeout=300s; then
  "${CLI}" -n "${NAMESPACE}" get wanakurouter smoke -o yaml || true
  "${CLI}" -n "${NAMESPACE}" get pods || true
  "${CLI}" -n "${NAMESPACE}" logs deployment/wanaku-operator --tail=50 || true
  die "WanakuRouter did not become Ready"
fi

"${CLI}" -n "${NAMESPACE}" get deployments,services

log "Deleting the WanakuRouter"
"${CLI}" -n "${NAMESPACE}" delete wanakurouter smoke --timeout=120s
log "Smoke test passed"
