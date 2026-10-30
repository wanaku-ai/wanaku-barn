# Operator minimum deployment working notes

These are working notes for the manual deployment effort, to be used when updating
the documentation after a minimum installation succeeds.

## Scope and workflow

- User deploys manually; adapt the operator one observed failure at a time.
- First milestone: a minimum working Wanaku Server installation. Extend features later.
- Record each failure, its cause, the change made, validation, and outstanding decisions.
- Do not treat a successful build or rendered chart as proof of a working cluster installation.

## Supplied legacy baseline

```yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  auth:
    authServer: http://keycloak-orp-wanaku.camel-dev-eu-de-1-bx2-4x1-b0521fcfdb6f3f868b0758fbda095bbe-0000.eu-de.containers.appdomain.cloud
    authProxy: "auto"
  router:
    image: quay.io/orpiske/wanaku-server:latest
    imagePullPolicy: Always
```

## Initial findings

- This manifest creates a `WanakuRouter` custom resource; the schema is the
  `wanakurouters.wanaku.ai` CRD shipped with the operator.
- Current Wanaku Server configuration belongs under `spec.server`. `spec.router`
  configures the optional Barn backend, which requires `enabled: true`.
- In the legacy baseline, the custom Server image would not select the Server
  workload: it is under the Barn configuration, and Barn is not enabled.
- `authServer` and `authProxy` are absent from the current schema. Current
  authentication uses `enabled`, `issuerUrl`, `clientId`, and `secretName` to
  configure oauth2-proxy. Do not assume legacy authentication settings take effect.
- The installed CRD and operator image must include the recent `spec.server` and
  port changes. The cluster's installed versions have not yet been verified.

## Proposed first smoke test

Use `apps/wanaku-operator/samples/wanaku-router-minimal.yaml` to select the user's
Server image with `imagePullPolicy: Always`.

The proposed smoke test omits authentication and Barn to isolate Server deployment.
This is a proposed starting point, not a decision to remove authentication from the
eventual installation. Authentication, public exposure, and inference/A2A upstream
configuration remain follow-up work unless the user makes them part of the first milestone.

## Changes already made

- Replaced the Praxis codename with Wanaku Server, including `spec.server`, resource
  names, and the default `quay.io/wanaku/wanaku-server:latest` image.
- Renamed Barn workload templates and entities. Barn's internal Service is
  `barn-<router>`, exposing 8080 and forwarding to the backend's 8180.
- Aligned Server ports: management 8080, MCP 8081, inference 8083, A2A 8084.
  Updated health probes, management ingress routing, and oauth2-proxy upstreams.
- Inference and A2A are exposed on the Server's internal Service. The existing
  Ingress exposes MCP and management.
- Confirmed the Helm capability-template error is reproducible with a retired
  template restored to a chart copy. The current chart renders without it; no Helm
  cleanup change was retained.

## Observed failures and validation

The first deployment result has been reported; see the failure record below.
Previous full Maven builds, static analysis, Helm rendering, and Compose configuration
validation passed. The user reports the Server and operator pods Running and Ready.
External management/MCP access and persistence behavior still require cluster validation.

For each subsequent failure, append: symptom/evidence, cause, change/decision,
validation result, and documentation follow-up.

### Failure 1: no OpenShift Route

- Evidence: `wanaku-dev-server` and `wanaku-operator` pods are Running and Ready.
  `oc get routes` lists only the existing Keycloak Route after five minutes.
- Cause: without `spec.exposure`, external-access reconciliation returns an internal
  Service name. With exposure configured it creates only a Kubernetes Ingress.
  No code creates OpenShift Routes despite existing Route RBAC permissions.
- Decision: automatically detect the Route API. On OpenShift, create a management
  Route named `<router>` for `/` and an MCP Route named `<router>-mcp` for `/mcp`.
  OpenShift generates a separate hostname for each Route. An optional
  `spec.exposure.host` sets one shared hostname for both Routes. Setting a Route host
  needs the `routes/custom-host` permission, so the operator sets it only on request.
- Follow-up (found while validating #248): with its own hostname, the MCP Route still used the path `/mcp`.
  The OpenShift router returned 503 for `/.well-known/oauth-protected-resource/mcp`, so MCP clients could not
  discover the authorization server. Namespace endpoints (`/<namespace>/mcp`) were not reachable either.
  The MCP Route now uses `/` when it has its own hostname, and `/mcp` only with a shared `spec.exposure.host`.
- Use edge TLS with HTTP-to-HTTPS redirection, consistent with the HTTPS endpoints
  already reported in `WanakuRouter.status`. Both Routes are owned by the CR.
- Target Server ports 8080/8081 without authentication, or oauth2-proxy ports
  4181/4180 when authentication is enabled. Preserve the Kubernetes Ingress behavior.
- Compare Route host, path, Service, target port, and TLS settings during reconciliation,
  so later changes (including enabling auth) update the existing Routes.
- Validation: 73 focused operator tests and the full Maven verification build passed.
  Helm rendering and the ripwire quality gate also passed. Static analysis is pending.
  Cluster rollout and Route access remain unverified; a Ready pod alone is not proof
  of reachable management or MCP endpoints.
- Documentation follow-up: explain automatic OpenShift Routes, shared hostname,
  termination, and the difference from Kubernetes exposure requirements.

### Failure 2: Ready condition timeout

- Evidence: `oc apply` creates the `WanakuRouter`. `oc wait --for=condition=Ready --timeout=120s` times out.
- Cause (code analysis, not yet confirmed on the cluster): the operator set `Ready=True` without any check,
  so a timeout means that reconciliation threw before the status patch. Reconciliation errors did not
  update the status. The most likely error is the MCP Route creation: it sets `spec.host`, which needs
  `create` on `route.openshift.io/routes/custom-host`. The operator role did not grant it.
- Change (wanaku-ai/wanaku-barn#247): grant `routes/custom-host`. Publish `Reconciled`, per-workload
  availability conditions, and an aggregated `Ready` condition. Record reconciliation errors in the status.
  Emit Warning events. Watch owned Deployments. Detect drift with a desired-spec hash, so that upgrades
  update existing Services and Deployments.
- Cluster finding: the Route still failed with HTTP 422 after the change, and the status stayed empty.
  The cluster ClusterRole came from an older chart, so it had no `routes/custom-host` rule. Helm also
  does not upgrade CRDs: the installed CRD had no `status.observedGeneration`, so the API server rejected
  the server-side-apply status patch.
- Follow-up change: the MCP Route no longer copies the management hostname. Each Route gets its own
  generated hostname unless `spec.exposure.host` is set. `status.sseEndpoint` and
  `status.streamableEndpoint` use the MCP Route hostname. The default deployment does not need
  `routes/custom-host`.
- Validation: unit and mock-API-server tests. Cluster validation is pending.
- Documentation follow-up: conditions table and troubleshooting steps in `docs/operate/operator.md`.
