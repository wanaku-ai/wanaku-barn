---
title: Wanaku Barn architecture
description: Learn how the Barn components fit around Wanaku Server, with the CLI, the operator, the Barn backend, the Admin UI plugins, and the Keycloak tools.
---

# Wanaku Barn architecture

Wanaku Server is the governed action proxy. It sits between AI agents and backend systems, and it lives in the [wanaku-ai/wanaku](https://github.com/wanaku-ai/wanaku) repository. Wanaku Barn adds the tools to manage, deploy, and extend Wanaku. Wanaku runs without Barn, and you add the Barn components that you need.

> [!NOTE]
> Barn used to contain the Java router of Classic Wanaku (0.2.x). That router is not part of Barn anymore. For Classic Wanaku, see the [Classic documentation](https://wanaku.ai/docs/classic/).

## Components

```text
                 ┌──────────────── Wanaku Server ────────────────┐
AI agents ──MCP──► MCP proxy :8081 ──forward──► MCP servers        │
                 │ Management API and Admin UI :8080              │
                 │   └─ /api/plugins/wanaku-barn/... ──┐          │
                 └─────────────────────────────────────│──────────┘
                                                       ▼
  Wanaku CLI ──────────────────────────────► Barn backend :8180
  Wanaku Operator ── deploys Server, Barn,      (catalogs, templates,
                     catalogs, integrations      data stores, Kamelets,
                                                 semantic routes, audit)
                                                       ▲
         Camel Integration Capability, semantic router ┘ download catalogs and routes
```

| Component | Location | What it does |
|-----------|----------|--------------|
| Wanaku CLI | `apps/wanaku-cli` | Manages Wanaku through its management API: forwards, namespaces, tools, resources, prompts, and evaluators. Manages Barn through the Barn API: service catalogs, templates, data stores, and backups. |
| Keycloak tools | `apps/wanaku-keycloak-admin` | Administer the Keycloak clients and credentials that Wanaku uses. |
| Barn backend | `apps/wanaku-barn-backend` | A Quarkus service on port 8180. It stores service catalogs, service templates, data store records, Kamelets, and semantic routes, and records an audit trail of the changes. |
| Admin UI plugin | `apps/ui/admin` | The `wanaku-barn` plugin. It adds an **Extensions** menu with the Barn pages to the Wanaku Admin UI. |
| Deployment debugger plugin | `apps/ui/debugger` | An Admin UI plugin that inspects a deployment. It does not need the Barn backend. |
| Wanaku Operator | `apps/wanaku-operator` | Deploys Wanaku Server, the Barn backend, oauth2-proxy, service catalogs, Camel integrations, and semantic routers on Kubernetes and OpenShift. |
| Built-in service templates | `services/service-templates` | The templates that the Barn backend deploys at startup. |

## How the components connect

- **Barn as a Wanaku plugin.** When you install the `wanaku-barn` plugin, Wanaku proxies `/api/plugins/wanaku-barn/wanaku-barn-api/...` to the Barn backend. The Admin UI then shows the Barn pages. The operator writes this configuration for you when `spec.barn.enabled` is `true`.
- **Service catalogs.** The CLI or the operator deploys a catalog to the Barn backend. A Camel Integration Capability downloads one system of the catalog, serves its routes as MCP tools, and Wanaku reaches them through an MCP forward. See [Service catalogs](../manage/service-catalogs.md).
- **Semantic routers.** The Barn backend stores semantic routes. A Wanaku Semantic Router runtime downloads a route and registers itself with Wanaku as a forward. See [Create a semantic router](../manage/semantic-routing-wizard.md).
- **Authentication.** Wanaku does not validate tokens itself. oauth2-proxy instances in front of Wanaku validate them against Keycloak. The CLI gets its tokens from Keycloak, and the Keycloak tools create the clients. See [Set up Keycloak for Wanaku](../operate/keycloak.md).

## Persistence

The Barn backend stores its data in an embedded Infinispan cache, on a local volume. The operator mounts a PersistentVolumeClaim for it. Barn keeps a version history of each service catalog and template. For the storage layout, see [Persistence internals](../contributing/persistence-internals.md). For backups, see [Backup, restore, and upgrade](../operate/backup-and-upgrade.md).

## Next steps

- [Install the Wanaku CLI](../cli/install.md): manage Wanaku from the command line.
- [Install the Barn backend](../operate/install.md): run Barn as a Wanaku plugin, locally, or with the operator.
- [Deploy with the Wanaku Operator](../operate/operator.md): deploy Wanaku and Barn on Kubernetes or OpenShift.
