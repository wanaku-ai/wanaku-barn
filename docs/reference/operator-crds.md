---
title: Wanaku Operator custom resources
description: Reference for the WanakuRouter, WanakuServiceCatalog, WanakuSemanticRouter and WanakuIntegration resources, their ports and status conditions.
---

# Wanaku Operator custom resources

This page lists the fields, ports and status conditions of the custom resources that the Wanaku Operator manages. To install the operator and deploy these resources, see [Deploy with the Wanaku Operator](../operate/operator.md).

## Standard listener ports

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

> [!NOTE]
> The Docker Compose setup in the Wanaku repository uses the reverse mapping: 4180 for the
> management proxy and 4181 for the MCP proxy. Use the ports of the deployment that you run.

Management and A2A use `WANAKU_MGMT_LISTEN` and `WANAKU_A2A_LISTEN`; MCP and inference
use the embedded pipeline configuration. Changing listener ports through custom
configuration also requires matching Service, probe, and exposure changes.

Wanaku Barn listens on 8180. Its `barn-<router>` Service exposes 8080 and forwards to
8180, keeping the internal Barn URL unchanged. In Docker Compose, both processes use
host networking, so Wanaku Server reaches Barn at `http://localhost:8180`.

## WanakuRouter (`wanaku.ai/v1alpha1`)

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

### Authentication

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

### Server variants

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

### Wanaku Barn

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

### Exposure

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

## WanakuServiceCatalog (`wanaku.ai/v1alpha1`)

`WanakuServiceCatalog` deploys packaged service catalogs to a router:

| Field | Type | Description |
|-------|------|-------------|
| `spec.routerRef` | string | Name of the `WanakuRouter` resource. The referenced router must have `spec.barn.enabled: true` |
| `spec.catalogs` | list | Catalogs to deploy, each with a `name` and a `configMapRef` |

The referenced ConfigMap must contain the packaged catalog (`catalog.zip`), created with
`wanaku service package` (see [Deploy a catalog with the Wanaku Operator](../manage/build-a-service-catalog.md#deploy-a-catalog-with-the-wanaku-operator)).

## WanakuSemanticRouter (`wanaku.ai/v1alpha1`)

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

## WanakuIntegration (`wanaku.ai/v1alpha1`)

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

## Environment variables

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

## WanakuRouter conditions

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

## Next steps

- [Deploy with the Wanaku Operator](../operate/operator.md): install the operator and deploy a router.
- [Build and deploy a service catalog](../manage/build-a-service-catalog.md): package a catalog for `WanakuServiceCatalog`.
- [Wanaku Operator on OperatorHub](../operate/operator-olm.md): install the operator with OLM.
