---
title: Install the Barn backend
description: Run the Barn backend as a Wanaku plugin, run it locally without authentication, or deploy it on Kubernetes or OpenShift with the Wanaku Operator.
---

# Install the Barn backend

The Barn backend stores catalogs, templates, data stores, and audit records for Wanaku. This page shows how to run it and how to configure it.

## Run Barn as a Wanaku plugin

Barn is listed in the default plugin catalog of Wanaku. When Wanaku asks for the plugin configuration, give the address where the Barn backend listens, for example `http://localhost:8180`.

Download a Barn backend deliverable from the [releases page](https://github.com/wanaku-ai/wanaku-barn/releases), unpack it, and start it:

```shell
java -jar /path/to/quarkus-run.jar
```

The Wanaku Admin UI then shows an **Extensions** menu with the Barn pages, for example **Data Stores**.

## Run the backend without authentication

The Barn backend does not perform authentication or authorization. It runs without Keycloak and without any
auth-related flag (the former `wanaku.http.auth` / `WANAKU_HTTP_AUTH` setting has been removed). Authentication and
authorization are handled by Wanaku, which fronts the backend with oauth2-proxy.

```shell
java -jar quarkus-run.jar
```

A compose file without Keycloak is provided at `deploy/docker-compose/docker-compose-noauth.yml`:

```shell
docker compose -f deploy/docker-compose/docker-compose-noauth.yml up
```

If you extend that compose file with downstream MCP servers, set `WANAKU_HTTP_AUTH=none` on those services.

> [!WARNING]
> The backend exposes its APIs and admin UI without access control. Do not expose it directly in production; place it
> behind Wanaku.

## Deploy on Kubernetes or OpenShift with the Wanaku Operator

The Wanaku Operator simplifies the deployment and management of Wanaku instances on Kubernetes and OpenShift clusters.
It automates the creation and configuration of all necessary resources, making it the recommended approach for production deployments.

> [!NOTE]
> For the full operator procedures, see [Deploy with the Wanaku Operator](operator.md). For the resource fields, see [Wanaku Operator custom resources](../reference/operator-crds.md).

### Quick Start

**Prerequisites:**

- Kubernetes 1.27+ or OpenShift 4.12+
- `kubectl` or `oc` CLI, `helm` 3.x
- Keycloak instance (see [Set up Keycloak for Wanaku](keycloak.md)) for Wanaku (optional for development)
- Wanaku container image (optional)

**Build the container image:**

You can build the container image and push it to a container registry, or use a pre-built image.

There is a pre-built image in `quay.io/wanaku/wanaku-barn-backend:latest`

**Install the operator:**

```shell
kubectl create namespace wanaku
helm install wanaku-operator ./apps/wanaku-operator/deploy/helm/wanaku-operator \
  --namespace wanaku
```

**Deploy a router:**

```yaml
# wanaku-router.yaml
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  barn:
    enabled: true
```

```shell
kubectl apply -f wanaku-router.yaml -n wanaku
kubectl wait wanakurouter/wanaku-dev --for=condition=Ready --timeout=120s
```

The `WanakuCapability`, `WanakuCamelRoute` and `WanakuCamelCodeExecutionEngine` CRDs were removed.
Deploy capability services (such as the Camel Integration Capability) as regular `Deployment`
resources and register them with the router using `wanaku forwards add` — see
[Forward MCP servers](../cli/forwards.md).

## Deploy with OIDC

You can deploy Wanaku on OpenShift or Kubernetes using the operator Helm chart.

After having deployed Keycloak, then run the following command to get its route:

```shell
kubectl get route keycloak -o jsonpath='{.spec.host}'
```

Then install the operator and apply a router with your OIDC configuration:

```shell
helm install wanaku-operator apps/wanaku-operator/deploy/helm/wanaku-operator --namespace <your-namespace>

cat <<'EOF2' | kubectl apply -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: wanaku-dev
spec:
  barn:
    enabled: true
  auth:
    enabled: true
    issuerUrl: <your-keycloak-url>/realms/wanaku
    clientId: wanaku-mcp-router
    secretName: wanaku-oidc-secret
EOF2
```

## Install with the Helm chart

It is also possible to manually run Wanaku on Kubernetes distributions, such as OpenShift,
without the operator. You can use the Helm chart directly:

1. Install the operator Helm chart:

   ```shell
   helm install wanaku-operator apps/wanaku-operator/deploy/helm/wanaku-operator --namespace <your-namespace>
   ```

2. Create and apply a `WanakuRouter` custom resource for your environment (see the
   [Operator Guide](operator.md) for the spec reference and examples)

## Configure the backend

The Barn backend is built on [Quarkus](https://quarkus.io/) and uses `application.properties` files for configuration. Each
component ships with built-in defaults, but you can override any property at runtime in three ways:

1. **External file**: place an `application.properties` in a `config/` directory next to the JAR
2. **System properties**: pass `-D<property>=<value>` on the command line
3. **Environment variables**: export the property name in uppercase with dots/hyphens replaced by underscores

For example, to change the HTTP port:

```shell
# Via system property
java -Dquarkus.http.port=9090 -jar wanaku-barn-backend-runner.jar

# Via environment variable
export QUARKUS_HTTP_PORT=9090
java -jar wanaku-barn-backend-runner.jar
```

For the full list of available properties and more details on configuration precedence, see the
[Configuration Guide](../reference/configuration.md).

## Next steps

- [Set up Keycloak for Wanaku](keycloak.md): add authentication to the deployment.
- [Kubernetes Operator guide](operator.md): the custom resources of the Wanaku Operator.
