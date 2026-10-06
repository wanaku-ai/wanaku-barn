---
name: wanaku-service-catalogs
description: Create, package, deploy, and manage Wanaku service catalogs and instantiate service templates. Covers the wanaku service CLI workflow (init, expose, package, deploy), listing and instantiating templates with properties, and preparing catalogs for Kubernetes deployment. Use when building or operating Wanaku capabilities.
---

# Wanaku Service Catalogs

## Overview

- A **service catalog** bundles Camel routes, Wanaku rules (which expose routes as MCP tools
  and resources), and dependency metadata into one deployable unit.
- A **service template** is a ready-made catalog skeleton for a common integration. Templates
  live under `services/service-templates/src/main/services/` in this repository and include,
  among others: `echo-tool`, `kafka-tool`, `sql-tool`, `aws-s3-resource`, `aws-sqs-tool`,
  `sftp-resource`, `jira-add-comment-tool`, `tavily-search-tool`, `langchain4j-agent-tool`,
  and `a2a-agent`.

## When to use this skill

Use it when an agent needs to scaffold a new capability from a template, author a service
catalog from scratch, package it for deployment, or manage catalogs deployed to a router.

## Template workflow

List available templates (requires a reachable router):

```shell
wanaku service template list
```

Instantiate a template into a service catalog, passing the properties the template needs:

```shell
wanaku service template instantiate --name kafka-tool \
  --property kafka.brokers=localhost:9092 --property kafka.topic=ai.requests
```

Notes:

- `--property key=value` may be repeated and takes precedence over `--properties-from <file>`
  (a Java `.properties` file) and over the deprecated comma-separated `--properties`.
- `--service-name` and `--service-system` override the service name and system identifier
  from the template.

After instantiating, edit the generated Camel routes, then follow the catalog lifecycle below.

## Catalog lifecycle (CLI)

### 1. Initialize

```shell
wanaku service init --name=my-service --services=system-a,system-b
```

This creates a `my-service/` directory with `index.properties` and a subdirectory per service
containing skeleton files.

### 2. Define routes

Edit the `*.camel.yaml` files in each service directory. Give every route a meaningful `id` —
route IDs become the exposed MCP tool names:

```yaml
- route:
    id: list-employees
    description: List all employees
    from:
      uri: direct:list-employees
      steps:
        - to:
            uri: "http://hr-api/api/employees"
```

### 3. Expose routes

Generate Wanaku rules from the route IDs:

```shell
wanaku service expose --path=my-service
wanaku service expose --path=my-service --namespace=production   # optional namespace
```

Only route-level IDs (the `id` directly under `- route:`) are extracted; step-level IDs are
ignored.

### 4. Package

```shell
wanaku service package --path=my-service                    # creates my-service.b64
wanaku service package --path=my-service -o /tmp/my-service.b64
```

The output is a Base64-encoded ZIP, suitable for the Kubernetes operator or version control.

### 5. Deploy

Deploy directly to a router (validates the catalog, packages it, and uploads it via the REST
API):

```shell
wanaku service deploy --path=my-service --host=http://localhost:8080
```

Once deployed, the catalog appears in the admin UI under the **Service Catalog** page.

### 6. Manage deployed catalogs

```shell
wanaku service catalog list --host=http://localhost:8080
wanaku service catalog remove --name=my-catalog --host=http://localhost:8080
```

Use `wanaku service deploy` for quick iteration during development; use the operator approach
for production deployments on Kubernetes.

## Deploying catalogs on Kubernetes

Package the catalog, store it in a ConfigMap, and reference it from a `WanakuServiceCatalog`
resource:

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

See the [operator skill](../wanaku-operator/SKILL.md) for installing the operator and
the full CRD reference.

## Checklist for agents

1. Prefer `wanaku service template list` + `instantiate` over hand-writing a catalog when a suitable template exists.
2. Keep route IDs stable — they become tool names exposed through the router.
3. Run `wanaku service expose` after every route change to regenerate rules.
4. Iterate with `wanaku service deploy`; switch to ConfigMap + `WanakuServiceCatalog` for Kubernetes.
5. Verify deployments with `wanaku service catalog list` and the admin UI.

## References

- [Service catalogs guide](../../docs/service-catalogs.md) — manifest format, rules files, end-to-end example
- [Usage guide](../../docs/usage.md) — CLI installation and authentication
- [Operator guide](../../docs/operator.md) — CRD reference and deployment patterns
