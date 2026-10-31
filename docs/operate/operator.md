---
title: Deploy with the Wanaku Operator
description: Install the Wanaku Operator on Kubernetes or OpenShift, deploy a WanakuRouter with Barn and authentication, and check, update or remove it.
---

# Deploy with the Wanaku Operator

## Overview

The Wanaku Operator manages the following custom resource definitions (CRDs):

- **WanakuRouter** — deploys and configures the Wanaku execution proxy: the Wanaku router engine and,
  optionally, Wanaku Barn
- **WanakuServiceCatalog** — deploys packaged service catalogs (Camel routes and their dependencies)
  to the Barn backend of a router
- **WanakuSemanticRouter** — runs one Wanaku Semantic Router (WSR) runtime and registers it with a
  router as an MCP forward
- **WanakuIntegration** — runs one Camel Integration Capability (CIC) runtime and registers its
  MCP tools with a router as an MCP forward

> [!NOTE]
> The `WanakuCapability`, `WanakuCamelRoute` and `WanakuCamelCodeExecutionEngine` CRDs were
> removed. Capability services (such as the Camel Integration Capability) are now deployed as
> regular `Deployment` resources and registered with the router using `wanaku forwards add`
> (see [Forward MCP servers](../cli/forwards.md)).

When you create these custom resources, the operator automatically provisions:

- Deployments with health probes
- Services for internal and external access
- ConfigMaps for configuration
- Secrets for OIDC credentials
- Routes (OpenShift) or Ingress (Kubernetes)
- ServiceAccounts and RBAC policies

For a `WanakuRouter` named `wanaku-dev`, the operator creates (among others) the
`wanaku-dev-server` Deployment, the `server-wanaku-dev` external service, the
`wanaku-dev-barn` Deployment and `barn-wanaku-dev` internal service when `spec.barn.enabled`
is `true`, and — when `spec.auth.enabled` is `true` — an
`oauth2-proxy` Deployment and service. `WanakuServiceCatalog` resources require the referenced
router to have `spec.barn.enabled: true`, so that Wanaku Barn is deployed for the catalogs
to register against.

On OpenShift, the operator creates two Routes: `wanaku-dev` for the management API and
`wanaku-dev-mcp` for the MCP endpoint. OpenShift generates a hostname for each Route.
The MCP Route sends all paths to the MCP listener. MCP clients use these paths:
`/<namespace>/mcp` (for example `/default/mcp`), and the OAuth discovery paths `/.well-known/*`, `/authorize`, `/token` and `/register`.
Set `spec.exposure.host` to use one hostname for both Routes. This needs the
`routes/custom-host` permission. On a shared hostname, the MCP Route receives `/default/mcp`.
Other Wanaku namespaces and the OAuth discovery paths require a separate MCP hostname
using `spec.exposure.listeners` (see [Exposure](../reference/operator-crds.md#exposure)).
`status.host` contains the management URL.
`status.streamableEndpoint` contains the default namespace MCP URL.
`status.sseEndpoint` is deprecated and left empty because Wanaku Server has no SSE transport.

## Prerequisites

Before installing the operator, ensure you have:

- Kubernetes 1.27+ or OpenShift 4.12+
- `kubectl` or `oc` CLI installed and configured
- `helm` CLI (version 3.x or later)
- Cluster admin or namespace admin permissions
- **Keycloak instance** (for authentication) — see [Set up Keycloak for Wanaku](keycloak.md)

## Installation

### 1. Create a Namespace

```shell
kubectl create namespace wanaku
```

### 2. Install the Operator via Helm

```shell
helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku
```

By default, the operator watches only the namespace where it is installed
(`JOSDK_WATCH_CURRENT`). To watch all namespaces, override the controller environment
variables during the Helm install:

```shell
helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_ROUTER_NAMESPACES=JOSDK_ALL_NAMESPACES \
  --set app.envs.QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_SERVICE_CATALOG_NAMESPACES=JOSDK_ALL_NAMESPACES
```

### Alternative: install through OLM

On clusters with the Operator Lifecycle Manager (OpenShift, or Kubernetes with OLM installed), install the operator
from OperatorHub instead of Helm. The bundle supports the `OwnNamespace` and `SingleNamespace` install modes. See
[Wanaku Operator on OperatorHub](operator-olm.md) for building and testing the bundle locally.

### 3. Verify the Operator

```shell
kubectl get pods -n wanaku
```

You should see the operator pod running. Check its logs to confirm startup:

```shell
kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator
```

For the fields of each resource, see [Wanaku Operator custom resources](../reference/operator-crds.md).

## Deployment Examples

### Minimal Router

```yaml
# router.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  barn:
    enabled: true
```

Apply:

```shell
kubectl apply -f router.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=300s
```

### Router with Authentication

```yaml
# router-auth.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  barn:
    enabled: true
  auth:
    enabled: true
    issuerUrl: https://keycloak.example.com/realms/wanaku
    internalIssuerUrl: http://keycloak:8080/realms/wanaku
    clientId: wanaku-mcp-router
    secretName: wanaku-oidc-secret
```

```shell
kubectl apply -f router-auth.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=300s
```

### Deploying Service Catalogs

Package a catalog with the CLI, put it in a ConfigMap, then reference it:

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
kubectl wait wanakuservicecatalog/my-catalogs --for=condition=Ready --timeout=120s
```

### Registering Capability Services

To run the Camel Integration Capability with registration that the operator manages, use a
[`WanakuIntegration`](../reference/operator-crds.md#wanakuintegration-wanakuaiv1alpha1) resource. Other capability services
(for example, HTTP tools) are not managed through CRDs. Deploy them as regular `Deployment`
resources in the same namespace and register them with the router:

```yaml
# camel-integration-capability.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: camel-integration-capability
spec:
  replicas: 1
  selector:
    matchLabels:
      app: camel-integration-capability
  template:
    metadata:
      labels:
        app: camel-integration-capability
    spec:
      containers:
        - name: cic
          image: quay.io/wanaku/camel-integration-capability:latest
          env:
            # Barn base URL for data-store and service-catalog downloads
            - name: REGISTRATION_URL
              value: http://barn-wanaku-dev:8080
```

Then register the running service with the router as a forward:

```shell
wanaku forwards add --service="http://camel-integration-capability:9090/mcp" --name cic --namespace default
```

See [Forward MCP servers](../cli/forwards.md) for the forward
workflow and [camel-integration-capability](https://github.com/wanaku-ai/camel-integration-capability/)
for the service itself.

## Lifecycle Operations

### Check the status

**List all Wanaku resources:**

```shell
kubectl get wanakurouter,wanakuservicecatalog,wanakusemanticrouter,wanakuintegration -n wanaku
```

**Get detailed status:**

```shell
kubectl describe wanakurouter wanaku-dev -n wanaku
kubectl describe wanakuservicecatalog my-catalogs -n wanaku
```

The status section shows:

- The `Ready` condition (true or false). See [WanakuRouter conditions](../reference/operator-crds.md#wanakurouter-conditions)
- Deployed catalogs
- Error messages (if reconciliation failed)

### Troubleshoot a Ready timeout

If `kubectl wait --for=condition=Ready` times out, do these steps:

1. Read the conditions:

   ```shell
   kubectl get wanakurouter wanaku-dev -n wanaku -o jsonpath='{.status.conditions}'
   ```

2. Read the events of the custom resource:

   ```shell
   kubectl get events -n wanaku --field-selector involvedObject.name=wanaku-dev
   ```

3. Read the operator log:

   ```shell
   kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator | grep -iE 'forbidden|exception|error'
   ```

4. On OpenShift, if you set `spec.exposure.host`, make sure that the operator service account can set Route hosts:

   ```shell
   oc auth can-i create routes/custom-host -n wanaku --as=system:serviceaccount:wanaku:wanaku-operator
   ```

An empty `status` usually means that the installed CRD and the operator image come from different releases.
The API server rejects a status update that contains fields that the CRD schema does not declare.
Helm does not upgrade the CRDs in the `crds/` directory of a chart.
After an operator upgrade, apply the CRDs manually and restart the operator:

```shell
kubectl apply -f apps/wanaku-operator/deploy/helm/wanaku-operator/crds/
kubectl rollout restart deployment/wanaku-operator -n wanaku
```

The Wanaku Server startup probe allows up to 150 seconds. Image pulls add more time.
Use a wait timeout of at least 300 seconds.

**Check operator logs:**

```shell
kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator
```

**Check the managed workloads:**

```shell
kubectl logs -n wanaku deployment/wanaku-dev-server
kubectl logs -n wanaku deployment/wanaku-dev-barn
```

### Updating Resources

Edit the custom resource directly:

```shell
kubectl edit wanakurouter wanaku-dev -n wanaku
```

Or update your YAML and reapply:

```shell
kubectl apply -f router.yaml -n wanaku
```

The operator detects changes and reconciles automatically, triggering a rolling update of the
managed Deployments.

### Removing Resources

Delete the custom resources in reverse order (catalogs first, then router):

```shell
kubectl delete wanakuservicecatalog my-catalogs -n wanaku
kubectl delete wanakurouter wanaku-dev -n wanaku
```

The operator cleans up all managed Kubernetes objects (deployments, services, configmaps,
routes/ingresses, etc.).

### Uninstalling the Operator

```shell
helm uninstall wanaku-operator -n wanaku
```

> [!WARNING]
> Uninstalling the operator does **not** delete the CRDs or existing custom resources. To fully
> clean up, delete the custom resources and the CRDs first:

```shell
kubectl delete wanakuservicecatalog,wanakurouter --all -n wanaku
kubectl delete wanakusemanticrouter,wanakuintegration --all -n wanaku
kubectl delete crd wanakurouters.wanaku.ai wanakuservicecatalogs.wanaku.ai \
  wanakusemanticrouters.wanaku.ai wanakuintegrations.wanaku.ai
```

## Upgrade from earlier operator versions

### Upgrade from the PoC naming

Rename `spec.praxis` to `spec.server` in existing `WanakuRouter` manifests and install
the updated CRD before applying them. The default engine image is
`quay.io/wanaku/wanaku-server:latest`.

The engine Deployment changes from `<router>-praxis` to `<router>-server`, its Service
from `praxis-<router>` to `server-<router>`, and its PVC from `praxis-volume-claim` to
`server-volume-claim`. Stop the old engine before starting the new one, migrate the
registry data to the new PVC, and remove the old Deployment and Service after verifying
the new engine. The operator does not migrate existing storage or remove the old resources
automatically. Docker Compose users must also migrate the old `wanaku-praxis` volume to
the new `wanaku-server` volume.

### Upgrade the Barn resource names

The optional Barn Deployment is now named `<router>-barn` instead of `<router>-mcp-router`,
its Service is `barn-<router>` instead of `internal-<router>`, and its PVC is
`<router>-barn-data` instead of `router-volume-claim`. Stop the old Barn workload, migrate
its data to the new PVC, and remove the old Deployment and Service after verification.
Update any explicit Barn URLs to `http://barn-<router>:8080`. The operator updates the
Wanaku Server and service catalog URLs automatically, but does not migrate storage or
remove the old workloads. Configure Barn through `spec.barn`; the deprecated `spec.router`
field still works when `spec.barn` is not set. A `barn-volume-claim` PVC that the router already
owns stays in use, so existing data is kept.

### Upgrade the Wanaku Server PVC name

The Wanaku Server PVC is named `<router>-server-data` instead of `server-volume-claim`.
Each router in a namespace now has its own registry volume.
A `server-volume-claim` PVC that the router already owns stays in use, so existing data is kept.
The operator does not migrate storage. When you delete the old PVC, the operator creates an
empty `<router>-server-data` PVC. Back up the registry data before you delete the old PVC, and
restore it into the new PVC.

## Next steps

- [Wanaku Operator custom resources](../reference/operator-crds.md): the fields, ports and conditions of each resource.
- [Wanaku Operator on OperatorHub](operator-olm.md): install the operator with OLM.
- [Build and deploy a service catalog](../manage/build-a-service-catalog.md): package a catalog for `WanakuServiceCatalog`.
- [Set up Keycloak for Wanaku](keycloak.md): create the OIDC client for `spec.auth`.
