---
name: wanaku-operator
description: Deploy and operate Wanaku on Kubernetes or OpenShift with the Wanaku Operator. Covers installing the operator via Helm, the WanakuRouter, WanakuCapability, WanakuServiceCatalog, WanakuCamelRoute and WanakuCamelCodeExecutionEngine CRDs, and common deployment patterns. Use when running or administering Wanaku on a cluster.
---

# Wanaku Operator

## Overview

The Wanaku Operator manages these custom resource definitions (CRDs):

- **WanakuRouter** — deploys and configures the MCP router gateway.
- **WanakuCapability** — deploys capability services (HTTP tools, Camel integrations, etc.)
  and connects them to a router.
- **WanakuCamelRoute** — packages inline Camel routes into service catalogs and deploys them
  to a router.
- **WanakuServiceCatalog** — deploys packaged service catalogs (Camel routes + Wanaku rules)
  to a router.
- **WanakuCamelCodeExecutionEngine** — deploys the Camel Code Execution Engine in-cluster or
  targets a remote engine endpoint.

When these resources are created, the operator provisions Deployments with health probes,
Services, ConfigMaps, Secrets for OIDC credentials, Routes (OpenShift) or Ingress
(Kubernetes), and ServiceAccounts with RBAC.

## When to use this skill

Use it when an agent needs to install the operator, deploy a router or capabilities to a
cluster, tune resources or replicas, or troubleshoot Wanaku workloads on Kubernetes.

## Prerequisites

- Kubernetes 1.27+ or OpenShift 4.12+
- `kubectl` or `oc`, and Helm 3.x
- A Keycloak instance for authentication, or `wanaku.http.auth=none` for unauthenticated
  access (development only)

## Install the operator

```shell
kubectl create namespace wanaku

helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku \
  --set operatorNamespace=wanaku
```

By default the operator watches only its own namespace. To watch all namespaces, set the
controller namespace variables to `JOSDK_ALL_NAMESPACES` during the Helm install:

```shell
helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_ROUTER_NAMESPACES=JOSDK_ALL_NAMESPACES \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_CAPABILITY_NAMESPACES=JOSDK_ALL_NAMESPACES \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_SERVICE_CATALOG_NAMESPACES=JOSDK_ALL_NAMESPACES \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_CAMEL_CODE_EXECUTION_ENGINE_NAMESPACES=JOSDK_ALL_NAMESPACES
```

Verify:

```shell
kubectl get pods -n wanaku
kubectl logs -n wanaku -l app=wanaku-operator
```

## Minimal router + capability

```yaml
# router.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  auth:
    authServer: http://keycloak:8080
---
# capabilities.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuCapability
metadata:
  name: wanaku-capabilities
spec:
  auth:
    authServer: http://keycloak:8080
  secrets:
    oidcCredentialsSecret: wanaku-oidc-secret
  routerRef: wanaku-dev
  capabilities:
    - name: wanaku-http
      image: quay.io/wanaku/wanaku-tool-service-http:latest
```

Apply and wait for readiness:

```shell
kubectl apply -f router.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=120s

kubectl apply -f capabilities.yaml -n wanaku
kubectl wait wanakucapability/wanaku-capabilities --for=condition=Ready --timeout=120s
```

## Router + service catalog

Package a catalog with the CLI, put it in a ConfigMap, then reference it:

```shell
wanaku service package --path=employee-system -o employee-system.b64
kubectl create configmap employee-catalog-data --from-file=catalog.zip=employee-system.b64 -n wanaku
```

```yaml
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
```

## Operating notes

- Set resource limits and HA replica counts on routers and capabilities for production
  deployments (see the deployment patterns in the operator guide).
- Reference Secrets for OIDC credentials instead of inline values.
- Deployed catalogs can be inspected with `wanaku service catalog list` and the admin UI.
- The CLI itself can authenticate against the cluster router with
  `wanaku auth login --api-token <token>`; point commands at the router with `--host`.

## Checklist for agents

1. Confirm the operator pod is running before applying CRDs (`kubectl get pods -n wanaku`).
2. Create the `WanakuRouter` first, wait for `Ready`, then apply dependent capabilities or catalogs.
3. Package catalogs with `wanaku service package` before creating their ConfigMaps.
4. Use `kubectl wait --for=condition=Ready` on Wanaku resources instead of sleeping between steps.
5. Check `kubectl logs -n wanaku -l app=wanaku-operator` when resources do not converge.

## References

- [Operator guide](../../docs/operator.md) — full CRD reference, lifecycle operations, HA and resource patterns
- [Usage guide](../../docs/usage.md) — installing and running Wanaku on OpenShift or Kubernetes
- [Service catalogs skill](../wanaku-service-catalogs/SKILL.md) — authoring and packaging catalogs
