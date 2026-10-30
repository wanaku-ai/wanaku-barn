# Kubernetes Operator Guide

## Overview

The Wanaku Operator manages the following custom resource definitions (CRDs):

- **WanakuRouter** — deploys and configures the Wanaku execution proxy: the Wanaku router engine and,
  optionally, Wanaku Barn
- **WanakuServiceCatalog** — deploys packaged service catalogs (Camel routes + Wanaku rules) to
  a router
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
using `spec.exposure.listeners` (see [Exposure](#exposure)).
`status.host` contains the management URL.
`status.streamableEndpoint` contains the default namespace MCP URL.
`status.sseEndpoint` is deprecated and left empty because Wanaku Server has no SSE transport.

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
`<router>-barn-data` instead of `router-volume-claim`. Stop the old Barn workload, migrate
its data to the new PVC, and remove the old Deployment and Service after verification.
Update any explicit Barn URLs to `http://barn-<router>:8080`. The operator updates the
Wanaku Server and service catalog URLs automatically, but does not migrate storage or
remove the old workloads. Configure Barn through `spec.barn`; the deprecated `spec.router`
field still works when `spec.barn` is not set. A `barn-volume-claim` PVC that the router already
owns stays in use, so existing data is kept.

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

## CRD Reference

### Standard listener ports

The operator uses the embedded listeners described in the
[Wanaku configuration guide](https://github.com/wanaku-ai/wanaku/blob/main/docs/reference/configuration.md):

| Listener | Server port |
|----------|-------------|
| Management API and admin UI | 8080 |
| MCP | 8081 |
| Inference proxy | 8083 |
| A2A proxy | 8084 |

The `server-<router>` Service exposes all four ports. Health probes use the named
`management` port. When authentication is enabled, the oauth2-proxy ports are 4180 (MCP),
4181 (management), 4183 (inference) and 4184 (A2A). See [Exposure](#exposure) for the
listeners that the operator exposes outside the cluster.

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
| `spec.exposure` | object | How the Server listeners are exposed outside the cluster. See [Exposure](#exposure) |
| `spec.barn` | object | The Wanaku Barn deployment. See [Wanaku Barn](#wanaku-barn) |
| `spec.barn.enabled` | bool | Whether Wanaku Barn is deployed (required for `WanakuServiceCatalog`) |
| `spec.barn.image`, `spec.barn.imagePullPolicy` | string | Container image and pull policy for Wanaku Barn |
| `spec.barn.env` | list | Extra environment variables for Wanaku Barn. See [Environment variables](#environment-variables) |
| `spec.barn.storage` | object | `size` (default `5Gi`), `storageClassName` and `existingClaim` for the Barn data |
| `spec.router` | object | Deprecated name of `spec.barn` (without `storage`). The operator reads it only when `spec.barn` is not set |
| `spec.server` | object | The Wanaku router engine: `variant`, `image`, `env`, `imagePullPolicy` |
| `spec.server.variant` | string | `standard` (default) or `headless`. See [Server variants](#server-variants) |
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

#### Server variants

The Wanaku Server has two container images:

| Variant | Default image | Admin UI |
|---------|---------------|----------|
| `standard` | `quay.io/wanaku/wanaku-server:latest` | Embedded at `/admin/` |
| `headless` | `quay.io/wanaku/wanaku-server-headless:latest` | None |

To deploy the headless variant, set `spec.server.variant: headless`:

```yaml
spec:
  server:
    variant: headless
```

The variant selects the default image. If you set `spec.server.image`, the operator uses that image, and the variant only controls how the operator advertises the admin UI.

The headless variant keeps the management APIs, MCP, inference, A2A, persistence, probes and authentication. The probes use `/healthz`, which does not depend on the admin UI. In the headless variant:

- `GET /` and `GET /admin/` return `404`.
- `status.endpoints.adminUi` is not set. The standard variant sets it when the management listener is exposed.
- When authentication is enabled, the management proxy returns `401` for requests without a token on all paths, instead of a redirect to the login page.

#### Wanaku Barn

Wanaku Barn is optional. Set `spec.barn.enabled: true` to deploy it:

```yaml
spec:
  barn:
    enabled: true
    storage:
      size: 10Gi
```

When Barn is enabled, the operator does these steps:

- It creates the `<router>-barn` Deployment, the `barn-<router>` Service and the Barn PVC. The probes use `/q/health/started`, `/q/health/ready` and `/q/health/live`.
- It sets the Barn persistence folder to the PVC mount path. The data does not depend on the home directory of the container user.
- It writes the `<router>-server-config` ConfigMap and mounts it as `/etc/wanaku/wanaku.yaml` in the Wanaku Server. The file points the `wanaku-barn` plugin service `wanaku-barn-api` at `http://barn-<router>:8080`. The Server then proxies `/api/plugins/wanaku-barn/wanaku-barn-api/...` to Barn. When the file changes, the Server restarts.
- It publishes the internal Barn URL in `status.barnUrl`. Other clients, for example the Camel Integration Capability (`REGISTRATION_URL`) and the semantic router (`WSR_BARN_URL`), use this URL.
- It reports the Barn availability in the `BarnAvailable` condition. `Ready` requires it.

The operator selects the Barn PVC in this order:

1. `spec.barn.storage.existingClaim`. The operator does not create or change this PVC.
2. The legacy `barn-volume-claim` PVC, when this router owns it.
3. `<router>-barn-data`, which the operator creates with `storage.size` and `storage.storageClassName`.

`WanakuServiceCatalog` resources wait while Barn is not available. Their `Ready` condition has the reason `BarnUnavailable`, and the operator tries again every 15 seconds.

When you set `spec.barn.enabled: false`, the operator deletes the Barn Deployment, the Barn Service and the Server plugin configuration. It keeps the Barn PVC, so that the data is not lost. To delete the data, delete the PVC manually.

Values that you set earlier through `PUT /api/v1/plugins/wanaku-barn/config` override the operator configuration.
The admin UI plugin archive is a separate installation step (`POST /api/v1/plugins/install`). The headless Server does not need it.

#### Exposure

`spec.exposure` selects the Server listeners that the operator exposes outside the cluster, and how it exposes them.

| Field | Description |
|-------|-------------|
| `type` | `Auto` (default), `Route`, `Ingress` or `None`. `Auto` uses OpenShift Routes when the cluster serves the Route API, and an Ingress otherwise. `None` removes all external access. |
| `listeners` | The listeners to expose, each on its own host. See below. |
| `host` | Shared host for the management and MCP listeners. The operator uses it only when `listeners` is not set. |
| `ingressClassName` | Ingress class (Ingress only). |
| `annotations` | Annotations for every generated Route or Ingress. |

The operator supports two exposure modes:

- **Shared host** (`listeners` is not set): when `host` is set, both listeners use that host. The management listener uses the path `/`, and the MCP listener uses the path `/default/mcp`. Only the default Wanaku namespace is reachable through MCP; use one host per listener for other namespaces and OAuth discovery. On OpenShift without `host`, OpenShift generates a different host for each of the two Routes, and the MCP Route uses the path `/`, so all MCP paths are reachable. Inference and A2A are not exposed. On Kubernetes without `spec.exposure`, the operator exposes nothing.
- **One host per listener** (`listeners` is set): each exposed listener gets its own Route or Ingress, named `<router>-<listener>`, for the path `/`. All MCP endpoints, including `/<namespace>/mcp`, are reachable.

Each entry in `listeners` (`management`, `mcp`, `inference`, `a2a`) has these fields:

| Field | Description |
|-------|-------------|
| `enabled` | Exposes the listener. The default is `true` for `management` and `mcp`, and `false` for `inference` and `a2a`. |
| `host` | Public host. On OpenShift, leave it empty to let the router generate a host. An Ingress requires a host. |

```yaml
spec:
  exposure:
    listeners:
      management: {}
      mcp:
        host: mcp.wanaku.example.com
      a2a:
        enabled: true
```

The operator uses these rules:

- When authentication is enabled, each exposed listener goes through its oauth2-proxy port. Inference and A2A accept only bearer tokens; requests without a token get `401`. A2A agent cards (`/<namespace>/a2a/<agent>/.well-known/...`) do not require a token.
- When the A2A listener is exposed, the operator sets `WANAKU_A2A_PUBLIC_URL` on the Server. Agent proxy URLs then use the public host.
- When you disable a listener, change the mode or set `type: None`, the operator deletes the Routes and Ingresses that are no longer necessary.
- To set a Route host, the operator service account needs the `create` verb on `routes/custom-host`. The Helm chart grants it.

`status.endpoints` contains the public URL of each exposed listener, for example `https://mcp.wanaku.example.com/default/mcp`.
The `ExposureReady` condition is `True` when the OpenShift router admits all Routes. When a Route is rejected (for example, because another Route already uses the host), the reason is `RouteNotAdmitted` and the message contains the router message.

### WanakuServiceCatalog (`wanaku.ai/v1alpha1`)

`WanakuServiceCatalog` deploys packaged service catalogs to a router:

| Field | Type | Description |
|-------|------|-------------|
| `spec.routerRef` | string | Name of the `WanakuRouter` resource. The referenced router must have `spec.barn.enabled: true` |
| `spec.catalogs` | list | Catalogs to deploy, each with a `name` and a `configMapRef` |

The referenced ConfigMap must contain the packaged catalog (`catalog.zip`), created with
`wanaku service package` (see [Deploying catalogs](#deploying-service-catalogs)).

### WanakuSemanticRouter (`wanaku.ai/v1alpha1`)

`WanakuSemanticRouter` runs one Wanaku Semantic Router (WSR) runtime. One runtime serves one semantic route as one MCP tool. The runtime registers itself with the router as an MCP forward when it starts, and removes the forward when it stops.

| Field | Type | Description |
|-------|------|-------------|
| `spec.routerRef` | string | Name of the `WanakuRouter` that the runtime registers with (required) |
| `spec.source.semanticRoute.name` | string | A semantic route published in Wanaku Barn |
| `spec.source.catalog` | object | A pinned service catalog in Wanaku Barn: `name`, `service`, `revision`, `sha256` and optional `main` |
| `spec.source.configMapRef` | object | Route files in a ConfigMap: `name`, `main` and optional `items`. The ConfigMap must also contain `semantic-router.properties` and the files that it references. Without `items`, every key is a file in one directory and `main` is the key of the main route file. With `items` (`key`, `path`), each key is placed at its relative path, and `main` is the path of the main route file |
| `spec.forward.name` | string | Forward name. The default is the resource name. Forward names are unique in a router |
| `spec.forward.namespace` | string | Wanaku namespace of the forward. The default is `default` |
| `spec.dependencies` | list | Extra Maven dependencies (`group:artifact:version`) |
| `spec.wanakuTokenSecretRef`, `spec.barnTokenSecretRef` | object | Secret `name` and `key` (default `token`) of a bearer token for the router management API or for Barn |
| `spec.image`, `spec.imagePullPolicy` | string | Runtime image (default `quay.io/wanaku/wanaku-semantic-router:latest`) and pull policy |
| `spec.env` | list | Extra environment variables, for example the expert provider credentials. See [Environment variables](#environment-variables) |
| `spec.properties` | map | Deployment and Camel component properties. The operator passes each entry to the runtime as `-p KEY=VALUE` |

Set exactly one source. The `semanticRoute` and `catalog` sources need Wanaku Barn on the router (`spec.barn.enabled: true`).

The runtime needs the credentials of the expert that the semantic route uses. For the default TypeSafe AI expert, set `TYPESAFE_API_KEY` from a Secret. Without it, the runtime stops at startup with `WSR command failed: IllegalArgumentException`:

```yaml
spec:
  env:
    - name: TYPESAFE_API_KEY
      valueFrom:
        secretKeyRef:
          name: llm-credentials
          key: api-key
  properties:
    camel.component.typesafe-ai.model: my-model
```

Do not put credential values in `spec.properties`. Use an environment reference such as `{{env:TYPESAFE_API_KEY}}` and set the variable through `spec.env`.

The operator creates these resources:

- The `<name>-wsr` Deployment with one replica. It uses a rolling update: the new pod registers the forward again with a new instance label, so the old pod does not remove it at shutdown.
- The `<name>-wsr` Service on port 8090. It publishes addresses of pods that are not ready, because the router discovers the tool while the runtime registers, before the pod is ready.

The probes use the WSR health endpoints on port 8091 (`/api/v1/health/started`, `/ready` and `/live`). The pod is ready only after the runtime registered the forward. The startup probe allows 10 minutes, because the runtime resolves Maven dependencies at startup.

The status contains these conditions and fields:

| Condition | Status `False` means |
|-----------|----------------------|
| `Reconciled` | Validation failed (`ValidationError`) or a Kubernetes API call failed (`ReconcileError`). |
| `Available` | The runtime pod is not ready. The reason comes from the pod, for example `CrashLoopBackOff`. A failed startup shows the WSR status message as the last termination message. |
| `Registered` | The router has no forward with this name (`NotRegistered`), the forward belongs to another registrant (`ForeignForward`), the forward is unavailable (`ForwardUnavailable`), or the operator cannot reach the router (`RouterUnreachable`). |
| `Ready` | One of the conditions above is `False`. |

`status.forwardName`, `status.mcpAddress` and `status.wanakuEndpoint` (the router MCP endpoint for the forward namespace, when the router exposes MCP) describe the registration.

When you delete the resource, Kubernetes stops the runtime. The runtime removes the forward if it still owns it. If the router is already gone, the runtime cannot remove the forward.

### WanakuIntegration (`wanaku.ai/v1alpha1`)

`WanakuIntegration` runs one Camel Integration Capability (CIC) runtime. The runtime loads Camel routes, serves the tagged routes as MCP tools, and registers itself with the router as an MCP forward. It removes the forward when it stops.

| Field | Type | Description |
|-------|------|-------------|
| `spec.routerRef` | string | Name of the `WanakuRouter` that the runtime registers with (required) |
| `spec.routes.configMapRef` | object | Route files in a ConfigMap: `name`, `main` (the key of the main route file) and optional `items` (`key`, `path`), as for `WanakuSemanticRouter` |
| `spec.routes.routesRef` | string | A routes reference that the runtime downloads, for example `datastore://orders.camel.yaml` |
| `spec.routes.serviceCatalog` | object | A service catalog in Wanaku Barn: `name` and `system` |
| `spec.dependencies`, `spec.repositories` | list | Extra Maven dependencies (`group:artifact:version`) and repositories |
| `spec.mcp.port` | int | MCP server port (default `9090`). The health endpoints use the same port |
| `spec.mcp.tags` | string | Route tags that the runtime serves as MCP tools |
| `spec.registration.enabled` | bool | Registers the runtime with the router (default `true`) |
| `spec.registration.name`, `spec.registration.namespace` | string | Forward name (default: the resource name) and Wanaku namespace (default `default`) |
| `spec.registration.tokenSecretRef` | object | Secret `name` and `key` (default `token`) of a bearer token for the router management API |
| `spec.image`, `spec.imagePullPolicy`, `spec.env` | mixed | Runtime image (default `quay.io/wanaku/camel-integration-capability:latest`) and configuration |

Set exactly one routes source. `datastore://` references and service catalogs need Wanaku Barn on the router (`spec.barn.enabled: true`).

The operator creates the `<name>-cic` Deployment and the `<name>-cic` Service. They follow the same rules as the semantic router workload: one replica, a rolling update that is safe with the ownership label (`cic.instance`), and a Service that publishes the addresses of pods that are not ready.
The probes use the Camel health endpoints on the MCP port (`/observe/health/ready` and `/observe/health/live`). When registration is enabled, the pod is ready only after the runtime registered the forward.

The status uses the same conditions as `WanakuSemanticRouter`: `Reconciled`, `Available`, `Registered` (only when registration is enabled) and `Ready`.

### Environment variables

The `env` lists of `WanakuRouter` (`spec.server.env`, `spec.barn.env`, `spec.auth.env`) and of `WanakuSemanticRouter` and `WanakuIntegration` (`spec.env`) use the same format. Each entry has a `name` and either a `value` or a `valueFrom.secretKeyRef` (`name` and `key`) that reads the value from a Secret in the same namespace:

```yaml
env:
  - name: LOG_LEVEL
    value: debug
  - name: TYPESAFE_API_KEY
    valueFrom:
      secretKeyRef:
        name: llm-credentials
        key: api-key
```

Use `valueFrom` for credentials, so that their values are not stored in the custom resource.

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
[`WanakuIntegration`](#wanakuintegration-wanakuaiv1alpha1) resource. Other capability services
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
wanaku forwards add --service="http://camel-integration-capability:9090/mcp" --name cic
```

See [Forward MCP servers](../cli/forwards.md) for the forward
workflow and [camel-integration-capability](https://github.com/wanaku-ai/camel-integration-capability/)
for the service itself.

## Lifecycle Operations

### Checking Status

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
| `ExposureReady` | Always | A Route is not yet admitted, or the OpenShift router rejected it. See [Exposure](#exposure). |
| `AuthConfigured` | Always | The authentication configuration is not valid. See [Authentication](#authentication). |
| `AuthProxyAvailable` | `spec.auth.enabled: true` | The `<router>-oauth2-proxy` Deployment is not available. |
| `BarnAvailable` | `spec.barn.enabled: true` | The `<router>-barn` Deployment is not available. |
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

- [Use the Wanaku CLI](../cli/index.md) — CLI installation, authentication and forwards
- [Service catalogs guide](../manage/service-catalogs.md) — authoring and packaging catalogs
- [Samples](https://github.com/wanaku-ai/wanaku-barn/tree/main/apps/wanaku-operator/samples) —
  sample `WanakuRouter` and `WanakuServiceCatalog` manifests
