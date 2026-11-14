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
- Ingress resources, when `spec.exposure` is set and enabled
- ServiceAccounts and RBAC policies

For a `WanakuRouter` named `wanaku-dev`, the operator creates (among others) the
`wanaku-dev-server` Deployment, the `server-wanaku-dev` external service, the
`wanaku-dev-barn` Deployment and `barn-wanaku-dev` internal service when `spec.barn.enabled`
is `true`, and — when `spec.auth.enabled` is `true` — an
`oauth2-proxy` Deployment and service. `WanakuServiceCatalog` resources require the referenced
router to have `spec.barn.enabled: true`, so that Wanaku Barn is deployed for the catalogs
to register against.

When `spec.exposure` is set, the operator creates Ingress resources, because `spec.exposure.enabled` defaults to `true`.
Set `spec.exposure.enabled: false` to keep the router internal to the cluster. Without `spec.exposure`, the operator exposes nothing.
With `spec.exposure.host`, the operator creates one Ingress named `wanaku-dev`. The management API uses the path `/`, and the MCP endpoint uses the path `/default/mcp`.
Other Wanaku namespaces and the OAuth discovery paths (`/.well-known/*`, `/authorize`, `/token` and `/register`) require one hostname for each listener
using `spec.exposure.listeners` (see [Exposure](../reference/operator-crds.md#exposure)).
The operator does not create OpenShift Routes. On OpenShift, the Ingress controller creates the Routes from the Ingress resources.
Earlier operator releases created Routes directly. Delete the Routes `wanaku-dev` and `wanaku-dev-mcp` manually after you upgrade.
If you had `spec.exposure.type: None`, replace it with `spec.exposure.enabled: false`. The `type` field is no longer recognized, and `enabled` defaults to `true`.
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

The chart installs the CRDs, and `helm upgrade` updates them. `helm uninstall` does not remove them.

> [!IMPORTANT]
> If the cluster has Wanaku CRDs from the 0.3.0 chart or earlier, do the one-time step in
> [Adopt the CRDs of an earlier chart](#adopt-the-crds-of-an-earlier-chart) before you install or upgrade.

The CRDs are cluster-scoped, and only one Helm release can own them. To install a second release of the
operator in a different namespace of the same cluster, add `--set crds.install=false` to that release.
Upgrade the release that owns the CRDs first.

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

### Router with a custom wanaku.yaml

Without a custom `wanaku.yaml`, the Wanaku Server denies tool calls, resource reads and prompt requests that no evaluator matches. To set your own governance posture, forwards and evaluators, put your `wanaku.yaml` in a ConfigMap. This example permits actions that no rule matches:

```yaml
# wanaku.yaml
governance:
  default:
    mode: enforce
    no_match: allow
    on_failure: deny
```

```shell
kubectl create configmap wanaku-config --from-file=wanaku.yaml -n wanaku
```

Reference the ConfigMap from the router:

```yaml
# router-config.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  barn:
    enabled: true
  server:
    configMapRef:
      name: wanaku-config
```

```shell
kubectl apply -f router-config.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=300s
```

The operator adds the Barn plugin to the file and restarts the Server when the ConfigMap changes. See [Server configuration](../reference/operator-crds.md#server-configuration).

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

An empty `status` usually means that the installed CRD and the operator image come from different releases.
The API server rejects a status update that contains fields that the CRD schema does not declare.
The chart updates the CRDs on `helm install` and `helm upgrade`, unless the release sets `crds.install=false`.
Upgrade the release that owns the CRDs, then restart the operator:

```shell
helm upgrade wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator --namespace wanaku
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
> Uninstalling the operator does **not** delete the CRDs or existing custom resources
> (the CRDs have the `helm.sh/resource-policy: keep` annotation). To fully
> clean up, delete the custom resources and the CRDs first:

```shell
kubectl delete wanakuservicecatalog,wanakurouter --all -n wanaku
kubectl delete wanakusemanticrouter,wanakuintegration --all -n wanaku
kubectl delete crd wanakurouters.wanaku.ai wanakuservicecatalogs.wanaku.ai \
  wanakusemanticrouters.wanaku.ai wanakuintegrations.wanaku.ai
```

## Upgrade from earlier operator versions

### Upgrade the CRDs

`helm upgrade` updates the CRDs together with the operator:

```shell
helm upgrade wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator --namespace wanaku
```

A release that sets `crds.install=false` does not change the CRDs.

### Adopt the CRDs of an earlier chart

The 0.3.0 chart and earlier charts installed the CRDs from the `crds/` directory, without Helm ownership metadata.
On a cluster that has these CRDs, `helm install` and `helm upgrade` of the current chart fail with
`invalid ownership metadata`. Do this step one time, before the install or the upgrade.
Use the release name and the namespace of your operator release:

```shell
for crd in wanakurouters wanakuservicecatalogs wanakusemanticrouters wanakuintegrations; do
  kubectl label crd "${crd}.wanaku.ai" app.kubernetes.io/managed-by=Helm --overwrite
  kubectl annotate crd "${crd}.wanaku.ai" --overwrite \
    meta.helm.sh/release-name=wanaku-operator \
    meta.helm.sh/release-namespace=wanaku
done
```

Skip a CRD that does not exist in the cluster. Helm creates it.

If you applied the CRDs with `kubectl apply` (the workaround for the 0.3.0 chart), Helm 4 stops with
`conflict with "kubectl-client-side-apply"`. Add `--force-conflicts` to the first `helm install` or
`helm upgrade`, so that Helm takes the ownership of the CRD fields. If a `helm install` failed with
this conflict, the release exists in the failed state. Use `helm upgrade --install` for the next attempt:

```shell
helm upgrade --install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku --force-conflicts
```

The same step is necessary when you install the operator again with a different release name or in a different
namespace: the CRDs that `helm uninstall` kept still belong to the earlier release.

### Upgrade from the PoC naming

Rename `spec.praxis` to `spec.server` in existing `WanakuRouter` manifests and install
the updated CRD before applying them. The default engine image is
`quay.io/wanaku/wanaku-server:0.3.1`.

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
