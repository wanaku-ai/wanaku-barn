# Kubernetes Operator Guide

## Overview

The Wanaku Operator manages the following custom resource definitions (CRDs):

- **WanakuRouter** — deploys and configures the Wanaku execution proxy: the Wanaku router engine and,
  optionally, the MCP router
- **WanakuServiceCatalog** — deploys packaged service catalogs (Camel routes + Wanaku rules) to
  a router

> [!NOTE]
> The `WanakuCapability`, `WanakuCamelRoute` and `WanakuCamelCodeExecutionEngine` CRDs were
> removed. Capability services (such as the Camel Integration Capability) are now deployed as
> regular `Deployment` resources and registered with the router using `wanaku forwards add`
> (see [usage.md](usage.md#forwarding-other-mcp-servers-via-the-mcp-forwarder)).

When you create these custom resources, the operator automatically provisions:

- Deployments with health probes
- Services for internal and external access
- ConfigMaps for configuration
- Secrets for OIDC credentials
- Routes (OpenShift) or Ingress (Kubernetes)
- ServiceAccounts and RBAC policies

For a `WanakuRouter` named `wanaku-dev`, the operator creates (among others) the
`wanaku-dev-praxis` Deployment, the `praxis-wanaku-dev` external service, the
`internal-wanaku-dev` internal service and — when `spec.auth.enabled` is `true` — an
`oauth2-proxy` Deployment and service. `WanakuServiceCatalog` resources require the referenced
router to have `spec.router.enabled: true`, so that the MCP router is deployed for the catalogs
to register against.

## Prerequisites

Before installing the operator, ensure you have:

- Kubernetes 1.27+ or OpenShift 4.12+
- `kubectl` or `oc` CLI installed and configured
- `helm` CLI (version 3.x or later)
- Cluster admin or namespace admin permissions
- **Keycloak instance** (for authentication) — see [Keycloak Setup](usage.md#keycloak-setup-for-wanaku)

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

### 3. Verify the Operator

```shell
kubectl get pods -n wanaku
```

You should see the operator pod running. Check its logs to confirm startup:

```shell
kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator
```

## CRD Reference

### WanakuRouter (`wanaku.ai/v1alpha1`)

`WanakuRouter` deploys the Wanaku execution proxy. The `spec` supports:

| Field | Type | Description |
|-------|------|-------------|
| `spec.imagePullPolicy` | string | Image pull policy applied to the managed workloads |
| `spec.exposure` | object | How the proxy is exposed (service type / ingress settings) |
| `spec.router` | object | The MCP router deployment. Set `enabled: true` to deploy the router alongside Wanaku |
| `spec.router.enabled` | bool | Whether the MCP router is deployed (required for `WanakuServiceCatalog`) |
| `spec.router.image` | string | Container image for the MCP router |
| `spec.router.env` | list | Extra environment variables (`name`/`value`) for the router |
| `spec.praxis` | object | The Wanaku router engine: `image`, `env`, `imagePullPolicy` |
| `spec.auth` | object | Authentication via oauth2-proxy instances placed in front of Wanaku |
| `spec.auth.enabled` | bool | Whether the oauth2-proxies are deployed |
| `spec.auth.issuerUrl` | string | OIDC issuer URL (for example, the Keycloak realm URL) |
| `spec.auth.clientId` | string | OIDC client ID used by the proxies |
| `spec.auth.secretName` | string | Secret holding the OIDC credentials and shared cookie secret |
| `spec.auth.image`, `spec.auth.imagePullPolicy`, `spec.auth.env` | mixed | oauth2-proxy image and configuration |

When authentication is enabled, the operator deploys two oauth2-proxy instances: one protecting
the MCP port (4180) and one protecting the management port (4181), sharing the same cookie
secret for single sign-on.

### WanakuServiceCatalog (`wanaku.ai/v1alpha1`)

`WanakuServiceCatalog` deploys packaged service catalogs to a router:

| Field | Type | Description |
|-------|------|-------------|
| `spec.routerRef` | string | Name of the `WanakuRouter` resource. The referenced router must have `spec.router.enabled: true` |
| `spec.catalogs` | list | Catalogs to deploy, each with a `name` and a `configMapRef` |

The referenced ConfigMap must contain the packaged catalog (`catalog.zip`), created with
`wanaku service package` (see [Deploying catalogs](#deploying-service-catalogs)).

## Deployment Examples

### Minimal Router

```yaml
# router.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  router:
    enabled: true
```

Apply:

```shell
kubectl apply -f router.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=120s
```

### Router with Authentication

```yaml
# router-auth.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  router:
    enabled: true
  auth:
    enabled: true
    issuerUrl: http://keycloak:8080/realms/wanaku
    clientId: wanaku-mcp-router
    secretName: wanaku-oidc-secret
```

```shell
kubectl apply -f router-auth.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=120s
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

Capability services (HTTP tools, Camel Integration Capability, etc.) are no longer managed
through CRDs. Deploy them as regular `Deployment` resources in the same namespace and register
them with the router:

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
            - name: WANAKU_SERVICE_REGISTRATION_URL
              value: http://internal-wanaku-dev:8080
```

Then register the running service with the router as a forward:

```shell
wanaku forwards add --service="http://camel-integration-capability:8080" --name cic
```

See [usage.md](usage.md#forwarding-other-mcp-servers-via-the-mcp-forwarder) for the forward
workflow and [camel-integration-capability](https://github.com/wanaku-ai/camel-integration-capability/)
for the service itself.

## Lifecycle Operations

### Checking Status

**List all Wanaku resources:**

```shell
kubectl get wanakurouter,wanakuservicecatalog -n wanaku
```

**Get detailed status:**

```shell
kubectl describe wanakurouter wanaku-dev -n wanaku
kubectl describe wanakuservicecatalog my-catalogs -n wanaku
```

The status section shows:

- `Ready` condition (true/false)
- Deployed catalogs
- Error messages (if reconciliation failed)

**Check operator logs:**

```shell
kubectl logs -n wanaku -l app.kubernetes.io/name=wanaku-operator
```

**Check the managed workloads:**

```shell
kubectl logs -n wanaku deployment/wanaku-dev-praxis
kubectl logs -n wanaku deployment/wanaku-dev-mcp-router
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
kubectl delete crd wanakurouters.wanaku.ai wanakuservicecatalogs.wanaku.ai
```

## References

- [Usage guide](usage.md) — CLI installation, authentication and forwards
- [Service catalogs guide](service-catalogs.md) — authoring and packaging catalogs
- [Samples](https://github.com/wanaku-ai/wanaku-barn/tree/main/apps/wanaku-operator/samples) —
  sample `WanakuRouter` and `WanakuServiceCatalog` manifests
