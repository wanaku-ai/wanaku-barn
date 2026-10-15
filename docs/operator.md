# Kubernetes Operator Guide

## Overview

The Wanaku Operator manages the following custom resource definitions (CRDs):

- **WanakuRouter** — deploys and configures the Wanaku execution proxy: the Wanaku router engine and,
  optionally, Wanaku Barn
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
`wanaku-dev-server` Deployment, the `server-wanaku-dev` external service, the
`wanaku-dev-barn` Deployment and `barn-wanaku-dev` internal service when `spec.router.enabled`
is `true`, and — when `spec.auth.enabled` is `true` — an
`oauth2-proxy` Deployment and service. `WanakuServiceCatalog` resources require the referenced
router to have `spec.router.enabled: true`, so that Wanaku Barn is deployed for the catalogs
to register against.

On OpenShift, the operator creates two Routes: `wanaku-dev` for the management API and
`wanaku-dev-mcp` for the MCP endpoint. OpenShift generates a hostname for each Route.
The MCP Route sends all paths to the MCP listener. MCP clients use these paths:
`/mcp`, `/<namespace>/mcp`, and the OAuth discovery paths `/.well-known/*`, `/authorize`, `/token` and `/register`.
Set `spec.exposure.host` to use one hostname for both Routes. This needs the
`routes/custom-host` permission. On a shared hostname, the MCP Route receives only `/mcp`.
The namespace endpoints and the OAuth discovery paths are then not available to MCP clients. `status.host` contains the management URL.
`status.sseEndpoint` and `status.streamableEndpoint` contain the MCP URLs.

## Upgrading from the PoC naming

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

## Upgrading Barn resource names

The optional Barn Deployment is now named `<router>-barn` instead of `<router>-mcp-router`,
its Service is `barn-<router>` instead of `internal-<router>`, and its PVC is
`barn-volume-claim` instead of `router-volume-claim`. Stop the old Barn workload, migrate
its data to the new PVC, and remove the old Deployment and Service after verification.
Update any explicit Barn URLs to `http://barn-<router>:8080`. The operator updates the
Wanaku Server and service catalog URLs automatically, but does not migrate storage or
remove the old workloads. Barn is still configured through `spec.router`.

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

### Standard listener ports

The operator uses the embedded listeners described in the
[Wanaku configuration guide](https://github.com/wanaku-ai/wanaku/blob/main/docs/configuration.md):

| Listener | Server port |
|----------|-------------|
| Management API and admin UI | 8080 |
| MCP | 8081 |
| Inference proxy | 8083 |
| A2A proxy | 8084 |

The `server-<router>` Service exposes all four ports. Health probes use the named
`management` port. Ingress sends `/mcp` to MCP and `/` to management; when authentication
is enabled, these paths use oauth2-proxy ports 4180 and 4181 respectively. Inference and
A2A are available through the internal Service and are not exposed by this Ingress.

Management and A2A use `WANAKU_MGMT_LISTEN` and `WANAKU_A2A_LISTEN`; MCP and inference
use the embedded pipeline configuration. Changing listener ports through custom
configuration also requires matching Service, probe, and exposure changes.

Wanaku Barn listens on 8180. Its `barn-<router>` Service exposes 8080 and forwards to
8180, keeping the internal Barn URL unchanged. In Docker Compose, both processes use
host networking, so Wanaku Server reaches Barn at `http://localhost:8180`.

### WanakuRouter (`wanaku.ai/v1alpha1`)

`WanakuRouter` deploys the Wanaku execution proxy. The `spec` supports:

| Field | Type | Description |
|-------|------|-------------|
| `spec.imagePullPolicy` | string | Image pull policy applied to the managed workloads |
| `spec.exposure` | object | How the proxy is exposed (service type / ingress settings) |
| `spec.router` | object | The Wanaku Barn deployment. Set `enabled: true` to deploy Barn alongside Wanaku Server |
| `spec.router.enabled` | bool | Whether Wanaku Barn is deployed (required for `WanakuServiceCatalog`) |
| `spec.router.image` | string | Container image for Wanaku Barn |
| `spec.router.env` | list | Extra environment variables (`name`/`value`) for Wanaku Barn |
| `spec.server` | object | The Wanaku router engine: `image`, `env`, `imagePullPolicy` |
| `spec.auth` | object | Authentication via oauth2-proxy instances placed in front of Wanaku |
| `spec.auth.enabled` | bool | Whether the oauth2-proxies are deployed |
| `spec.auth.issuerUrl` | string | Full public OIDC issuer URL, including the realm (for example, `https://keycloak.example.com/realms/wanaku`) |
| `spec.auth.internalIssuerUrl` | string | Optional in-cluster URL of the same issuer (for example, `http://keycloak.keycloak.svc:8080/realms/wanaku`) |
| `spec.auth.clientId` | string | OIDC client ID used by the proxies (default `wanaku-mcp-router`) |
| `spec.auth.secretName` | string | Secret holding the OIDC credentials and shared cookie secret |
| `spec.auth.image`, `spec.auth.imagePullPolicy`, `spec.auth.env` | mixed | oauth2-proxy image and configuration |

When authentication is enabled, the operator deploys two oauth2-proxy instances: one protecting
the MCP port (4180) and one protecting the management port (4181), sharing the same cookie
secret for single sign-on.

#### Authentication

The operator uses these rules for authentication:

- The proxies validate tokens. The Wanaku Server does not validate tokens.
- Browsers use `issuerUrl` for the login page.
- The proxies use `internalIssuerUrl` for token and key requests. If you do not set `internalIssuerUrl`, the proxies use `issuerUrl`.
- The operator sets `WANAKU_AUTH_ISSUER` (and `WANAKU_AUTH_UPSTREAM_ISSUER` when `internalIssuerUrl` is set) on the Wanaku Server. MCP clients then discover the authorization server through `/.well-known/oauth-protected-resource`.
- Requests to the MCP endpoints and to `/api/` without a valid token get `401`. They are not redirected to the login page.
- `/healthz` and `/health` do not require a token.

The Secret named in `spec.auth.secretName` must contain two keys:

| Key | Content |
|-----|---------|
| `client-secret` | The OIDC client secret of `clientId` |
| `cookie-secret` | 16, 24 or 32 bytes, or the base64 encoding of such a value |

To create the Secret, use these commands:

```shell
kubectl create secret generic wanaku-oidc-secret -n wanaku \
  --from-literal=client-secret=<client secret from Keycloak> \
  --from-literal=cookie-secret="$(openssl rand -hex 16)"
```

The operator reads the Secret before it deploys the proxies. If the Secret is missing or not valid, the operator does not deploy the proxies.
When the Secret changes, the operator restarts the proxies.

The `AuthConfigured` condition reports the authentication configuration:

| Status | Reason | Meaning |
|--------|--------|---------|
| `True` | `Configured` | Authentication is configured. |
| `True` | `AuthDisabled` | `spec.auth.enabled` is not `true`. The exposed endpoints are not protected. The operator records one `Warning` event. |
| `True` | `DeprecatedIssuerFormat` | `issuerUrl` has no `/realms/` path. The operator appends `/realms/wanaku`. Set the full issuer URL. |
| `False` | `SecretNotFound`, `SecretKeyMissing`, `InvalidCookieSecret` | The Secret is missing or not valid. |
| `False` | `DeprecatedFields` | The resource uses `spec.auth.authServer` or `spec.auth.authProxy`. These fields have no effect. Replace them with `enabled`, `issuerUrl`, `clientId` and `secretName`. |

Earlier operator versions appended `/realms/wanaku` to `issuerUrl`. Resources that set only the Keycloak base URL continue to work, with the `DeprecatedIssuerFormat` reason.

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
  router:
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
              value: http://barn-wanaku-dev:8080
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

#### WanakuRouter conditions

The operator sets `Ready=True` only when all required workloads are available.
The operator records the observed generation in `status.observedGeneration` and in each condition.

| Condition | When the operator sets it | Status `False` means |
|-----------|---------------------------|----------------------|
| `Reconciled` | Always | Spec validation failed (`ValidationError`) or a Kubernetes API call failed (`ReconcileError`). The message contains the error. |
| `ServerAvailable` | Always | The `<router>-server` Deployment is not available. |
| `AuthConfigured` | Always | The authentication configuration is not valid. See [Authentication](#authentication). |
| `AuthProxyAvailable` | `spec.auth.enabled: true` | The `<router>-oauth2-proxy` Deployment is not available. |
| `BarnAvailable` | `spec.router.enabled: true` | The `<router>-barn` Deployment is not available. |
| `Ready` | Always | One of the conditions above is `False`. The reason and the message identify the first failed condition. |

A workload condition uses these reasons:

- `Available`: all replicas are available.
- `Progressing`: the rollout continues.
- `ImagePullBackOff`, `ErrImagePull`, `CrashLoopBackOff`, `CreateContainerConfigError` and similar: a pod container cannot start. The message contains the container message and the last termination message.
- `Unschedulable`: the scheduler cannot place a pod.
- `ProgressDeadlineExceeded`: the rollout did not complete in the Deployment progress deadline.
- `NotFound`: the Deployment does not exist.

When `Ready` changes to `False`, or the failure reason changes, the operator records a `Warning` event on the custom resource.
While the router is not ready, the operator checks the workloads again every 30 seconds.

#### Troubleshooting a Ready timeout

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
kubectl delete crd wanakurouters.wanaku.ai wanakuservicecatalogs.wanaku.ai
```

## References

- [Usage guide](usage.md) — CLI installation, authentication and forwards
- [Service catalogs guide](service-catalogs.md) — authoring and packaging catalogs
- [Samples](https://github.com/wanaku-ai/wanaku-barn/tree/main/apps/wanaku-operator/samples) —
  sample `WanakuRouter` and `WanakuServiceCatalog` manifests
