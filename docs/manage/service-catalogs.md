---
title: Service catalogs
description: Learn what a Barn service catalog contains, how Barn, the Camel Integration Capability, and Wanaku serve it, and how it relates to templates.
---

# Service catalogs

A service catalog packages Apache Camel routes and their dependencies as one versioned ZIP file. Barn stores the catalog. A Camel Integration Capability loads one system of the catalog and serves its routes as MCP tools. Wanaku reaches those tools through an MCP forward.

Catalogs are a good fit when you want to:

- Share prebuilt integrations across teams.
- Deploy several related routes as one unit.
- Keep a version history of an integration and restore an earlier version.

> [!NOTE]
> The catalog format does not depend on a specific runtime. Apache Camel is the runtime that Barn supports today.

## How a catalog reaches an AI agent

```text
wanaku service deploy ──► Barn (stores the catalog and its versions)
                              │
                              ▼  downloads one system of the catalog
                Camel Integration Capability (MCP server)
                              │
                              ▼  MCP forward
                         Wanaku ──► AI agent
```

1. You deploy the catalog to Barn with the Wanaku CLI, the REST API, or the Wanaku Operator.
2. Barn stores the catalog and creates a new version.
3. You start a Camel Integration Capability with `--service-catalog <catalog>` and `--service-catalog-system <system>`. The capability downloads the catalog from Barn, loads the routes of that system, and starts an MCP server.
4. You add the capability to Wanaku as an MCP forward, or the capability registers itself.
5. Wanaku lists the tools of the capability to AI agents and forwards each tool call to it.

## Service catalogs and service templates

- A **service catalog** contains concrete routes, values, and dependencies. You can deploy it as it is.
- A **service template** is a catalog with `{{placeholder}}` values. You cannot run a template. You instantiate it with your values, and Barn creates a catalog from it.

```text
Service template ({{placeholders}})
    │  wanaku service template instantiate
    ▼
Service catalog (stored in Barn)
    │  Camel Integration Capability
    ▼
MCP tools in Wanaku
```

For templates, see [Service templates](service-templates.md).

## Catalog structure

A service catalog is a directory with one subdirectory for each system:

```text
hr-system/
├── index.properties                  # Manifest of the catalog
├── employees/
│   ├── employees.camel.yaml          # Camel routes of the system
│   ├── employees.wanaku-rules.yaml   # Optional, legacy
│   └── employees.dependencies.txt    # Optional Maven dependencies
└── payroll/
    ├── payroll.camel.yaml
    └── payroll.dependencies.txt
```

### The manifest file

The `index.properties` file describes the catalog and maps each system to its files:

```properties
catalog.name=hr-system
catalog.description=Employee and payroll tools
catalog.services=employees,payroll

catalog.routes.employees=employees/employees.camel.yaml
catalog.routes.payroll=payroll/payroll.camel.yaml

catalog.dependencies.employees=employees/employees.dependencies.txt
catalog.dependencies.payroll=payroll/payroll.dependencies.txt
```

| Property | Required | Description |
|----------|----------|-------------|
| `catalog.name` | Yes | The catalog name. Barn identifies the catalog by this value. Give the same value to `--service-catalog`. |
| `catalog.description` | Yes | A description of the catalog. |
| `catalog.services` | Yes | A comma-separated list of system names. |
| `catalog.icon` | No | An icon for the catalog. Barn uses a default icon if you do not set it. |
| `catalog.routes.<system>` | Yes | The path of the Camel routes file of the system. |
| `catalog.dependencies.<system>` | No | The path of the dependencies file of the system. |
| `catalog.properties.<system>` | No | The path of the `service.properties` file of the system. Templates use it. |
| `catalog.rules.<system>` | No | The path of a Wanaku rules file. This property is legacy. Barn does not validate it, and the Camel Integration Capability does not read it. |

All paths are relative to the catalog root.

### Routes files

Each system has a Camel YAML DSL routes file (`*.camel.yaml`). A route that starts from an `ai-tool:` endpoint becomes an MCP tool:

```yaml
- route:
    id: get-employee-by-id
    from:
      uri: ai-tool:get-employee-by-id
      parameters:
        tags: wanaku
        description: "Get the profile of an employee"
        parameter.employeeId: string
        parameter.employeeId.description: "The ID of the employee"
      steps:
        - toD: "http://hr-api:8080/api/employees/${header.employeeId}"
```

- `description` is the tool description that AI agents see.
- Each `parameter.<name>` entry declares a tool argument and its type. The route receives the argument as the `<name>` header.
- The Camel Integration Capability exposes only the `ai-tool:` routes that have one of its MCP tags. The default tag is `wanaku`.

### Dependencies files

A dependencies file (`*.dependencies.txt`) lists the Maven coordinates that the routes need, one for each line:

```text
org.apache.camel:camel-http
org.apache.camel:camel-jackson
org.apache.activemq:artemis-commons:2.44.0
```

Apache Camel and Forage dependencies do not need a version. All other dependencies must include a version.

### Rules files

Older catalogs contain Wanaku rules files (`*.wanaku-rules.yaml`), which `wanaku service expose` generates. The `ai-tool:` routes replace them. As of 0.3.0, rules files are optional, and Barn does not validate them.

## Service catalogs in the Admin UI

When Barn runs as a Wanaku plugin, the Wanaku Admin UI shows a **Service Catalog** page in the **Extensions** menu. The page shows the catalogs that you deployed with the CLI, the REST API, or the Wanaku Operator. On this page, you can:

- Search the deployed catalogs.
- See the systems and routes of each catalog.
- Remove a catalog.

## Next steps

- [Build and deploy a service catalog](build-a-service-catalog.md): create a catalog with the CLI and serve it to Wanaku.
- [Service catalog API](../reference/service-catalog-api.md): the REST endpoints, the validation checks, and the version rules.
- [Service templates](service-templates.md): create parameterized catalogs.
- [Camel Integration Capability usage](https://github.com/wanaku-ai/camel-integration-capability/blob/main/docs/usage.md): the options of the capability that serves a catalog.
