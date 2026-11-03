#!/usr/bin/env bash
# Deploys the Wanaku Operator through OLM on a local OpenShift cluster (CRC / OpenShift Local).
# OLM is part of OpenShift, so it is not installed here.
#
# Usage: local-crc.sh up|down
#
# 'up' starts CRC when needed, builds the operator image and the bundle, pushes both to the cluster's
# internal image registry and runs the bundle with operator-sdk.

# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

INTERNAL_REGISTRY="image-registry.openshift-image-registry.svc:5000"

up() {
  require crc oc podman operator-sdk make mvn

  if ! crc status 2>/dev/null | grep -q "OpenShift:[[:space:]]*Running"; then
    die "CRC not started. Start crc with \"crc start\""
  fi
  eval "$(crc oc-env)"
  oc login -u kubeadmin -p "$(crc console --credentials | sed -n "s/.*kubeadmin' .*'\(.*\)'.*/\1/p" | head -1)" \
    https://api.crc.testing:6443 >/dev/null 2>&1 || log "oc login skipped (using the current context)"

  log "Exposing the internal image registry"
  oc patch configs.imageregistry.operator.openshift.io/cluster --type merge -p '{"spec":{"defaultRoute":true}}'
  local route
  route="$(oc -n openshift-image-registry get route default-route -o jsonpath='{.spec.host}')"

  oc create namespace "${NAMESPACE}" --dry-run=client -o yaml | oc apply -f -
  oc project "${NAMESPACE}" >/dev/null

  # The CSV references the operator image through the internal registry address
  build_operator_and_bundle "${INTERNAL_REGISTRY}" "${NAMESPACE}"

  log "Pushing the images to ${route}"
  podman login --tls-verify=false -u "$(oc whoami)" -p "$(oc whoami -t)" "${route}"
  podman tag "${INTERNAL_REGISTRY}/${NAMESPACE}/wanaku-operator:${LOCAL_VERSION}" \
    "${route}/${NAMESPACE}/wanaku-operator:${LOCAL_VERSION}"
  podman push --tls-verify=false "${route}/${NAMESPACE}/wanaku-operator:${LOCAL_VERSION}"
  make -C "${OLM_DIR}" bundle-image \
    BUNDLE_IMAGE="${route}/${NAMESPACE}/wanaku-operator-bundle:${LOCAL_VERSION}"
  podman push --tls-verify=false "${route}/${NAMESPACE}/wanaku-operator-bundle:${LOCAL_VERSION}"

  # operator-sdk pulls the bundle from this machine, where only the route resolves, and the catalog pod
  # pulls it by the same name, so the pod needs credentials for the route. The route has a self-signed
  # certificate, hence --skip-tls-verify (adding --skip-tls makes the pull fall back to HTTP).
  oc -n "${NAMESPACE}" create secret docker-registry registry-route-pull \
    --docker-server="${route}" --docker-username="$(oc whoami)" --docker-password="$(oc whoami -t)" \
    --dry-run=client -o yaml | oc apply -f -
  oc -n "${NAMESPACE}" secrets link default registry-route-pull --for=pull
  run_bundle oc "${route}/${NAMESPACE}/wanaku-operator-bundle:${LOCAL_VERSION}" \
    --skip-tls-verify --pull-secret-name registry-route-pull
}

down() {
  require oc operator-sdk
  eval "$(crc oc-env)"
  cleanup_bundle oc
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) die "Usage: $0 up|down" ;;
esac
