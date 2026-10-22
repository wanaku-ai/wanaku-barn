---
name: wanaku-operator
description: Deploy and operate Wanaku on Kubernetes or OpenShift with the Wanaku Operator. Covers installing the operator via Helm, the WanakuRouter and WanakuServiceCatalog CRDs, and common deployment patterns. Use when running or administering Wanaku on a cluster.
---

# Wanaku Operator

## Overview

The Wanaku Operator manages these custom resource definitions (CRDs):

- **WanakuRouter** — deploys the Wanaku MCP engine, an optional barn-backend for
  service catalog persistence, and an optional oauth2-proxy for authentication.
- **WanakuServiceCatalog** — deploys packaged service catalogs (Camel routes + Wanaku rules)
  to a router with barn-backend enabled.

The router reconciler provisions Deployments, Services, persistent storage, and external
access. The service catalog reconciler reads catalog data from ConfigMaps and deploys it
through the barn-backend API.

## When to use this skill

Use it when an agent needs to install the operator, deploy a router or service catalogs to a
cluster, configure authentication, or troubleshoot Wanaku workloads on Kubernetes.

## Prerequisites

- Kubernetes 1.27+ or OpenShift 4.12+
- `kubectl` or `oc`, and Helm 3.x
- An OIDC provider such as Keycloak when authentication is enabled. The minimal router
  example below leaves authentication disabled for development.

## Install the operator

```shell
kubectl create namespace wanaku

helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku
```

By default the operator watches only its own namespace. To watch all namespaces, set the
controller namespace variables to `JOSDK_ALL_NAMESPACES` during the Helm install:

```shell
helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_ROUTER_NAMESPACES=JOSDK_ALL_NAMESPACES \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_SERVICE_CATALOG_NAMESPACES=JOSDK_ALL_NAMESPACES
```

Verify:

```shell
kubectl get pods -n wanaku
kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator
```

## Minimal development router

```yaml
# router.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  server: {}
```

Apply and wait for readiness:

```shell
kubectl apply -f router.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev -n wanaku --for=condition=Ready --timeout=120s
```

For authenticated deployments, configure `spec.auth.enabled`, `issuerUrl`, `clientId`,
and `secretName` for oauth2-proxy. See the current [router CRD schema](../../apps/wanaku-operator/deploy/helm/wanaku-operator/crds/wanakurouters.wanaku.ai-v1.yml)<!-- wanaku:versioned -->
for supported fields.

## Router + service catalog

Enable barn-backend in `router.yaml` before deploying catalogs, then apply the router
and wait for readiness as above:

```yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  server: {}
  router:
    enabled: true
```

Package a catalog with the CLI, put it in a ConfigMap, then reference it. The ConfigMap,
router, and catalog resource must be in the same namespace:

```shell
wanaku service package --path=employee-system -o employee-system.b64
kubectl create configmap employee-catalog-data --from-file=catalog.zip=employee-system.b64 -n wanaku
```

```yaml
# service-catalog.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: my-catalogs
spec:
  routerRef: wanaku-dev
  catalogs:
    - name: employee-system-v2
      configMapRef: employee-catalog-data
```

```shell
kubectl apply -f service-catalog.yaml -n wanaku
kubectl wait wanakuservicecatalog/my-catalogs -n wanaku --for=condition=Ready --timeout=120s
```

## Operating notes

- Configure router images, environment variables, and exposure using the current CRD schema.
- Reference an oauth2-proxy Secret through `spec.auth.secretName` for authentication credentials.
- Deployed catalogs can be inspected with `wanaku service catalog list` and the admin UI.
- The CLI itself can authenticate against the cluster router with
  `wanaku auth login --api-token <token>`; point management commands at barn-backend with `--host`.

## Checklist for agents

1. Confirm the operator pod is running before applying CRDs (`kubectl get pods -n wanaku`).
2. Create the `WanakuRouter` first, wait for `Ready`, then apply dependent catalogs.
3. Package catalogs with `wanaku service package` before creating their ConfigMaps.
4. Use `kubectl wait --for=condition=Ready` on Wanaku resources instead of sleeping between steps.
5. Check `kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator` when resources do not converge.

## References

- [Operator guide](../../docs/operator.md)<!-- wanaku:versioned --> — operator deployment and lifecycle background; consult the current CRD schema for fields
- [Usage guide](../../docs/usage.md)<!-- wanaku:versioned --> — installing and running Wanaku on OpenShift or Kubernetes
- [Service catalogs skill](../wanaku-service-catalogs/SKILL.md) — authoring and packaging catalogs
