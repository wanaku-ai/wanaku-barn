#!/usr/bin/env bash
# Deploys the Wanaku Operator through OLM on a local minikube cluster (docker runtime).
#
# Usage: local-minikube.sh up|down
#
# 'up' points docker at the minikube daemon (eval $(minikube docker-env)), so the operator image built
# there is already available to the node. The bundle image is pushed to the minikube registry addon and
# the bundle is run with operator-sdk. OLM and the registry addon must be enabled in minikube.

# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# docker talks to the minikube daemon, which runs inside the node: localhost:5000 is the registry-proxy
# and docker allows plain HTTP for localhost. operator-sdk pulls the bundle from this machine, where
# localhost:5000 is only reachable through a port-forward to the registry service.
IN_CLUSTER_BUNDLE="localhost:5000/wanaku/wanaku-operator-bundle:${LOCAL_VERSION}"

up() {
  require minikube kubectl docker operator-sdk make mvn
  minikube status >/dev/null 2>&1 || die "minikube is not running. Start it with: minikube start"

  if ! kubectl get crd clusterserviceversions.operators.coreos.com >/dev/null 2>&1; then
    die "OLM is required but is not installed in minikube. Install with: \"minikube addons enable olm\" or \"operator-sdk olm install\""
  fi

  if ! kubectl -n kube-system get deployment/registry >/dev/null 2>&1; then
    die "kubernetes registry not found. Install with: \"minikube addons enable registry\""
  fi

  eval "$(minikube docker-env)"
  export CONTAINER_TOOL=docker

  build_operator_and_bundle localhost wanaku

  log "Publishing the bundle image"
  make -C "${OLM_DIR}" bundle-image push-bundle-image BUNDLE_IMAGE="${IN_CLUSTER_BUNDLE}"

  kubectl -n kube-system port-forward service/registry 5000:80 >/dev/null 2>&1 &
  trap 'kill %1 2>/dev/null || true' EXIT
  sleep 3
  run_bundle kubectl "${IN_CLUSTER_BUNDLE}" --use-http
}

down() {
  require kubectl operator-sdk
  cleanup_bundle kubectl
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) die "Usage: $0 up|down" ;;
esac
