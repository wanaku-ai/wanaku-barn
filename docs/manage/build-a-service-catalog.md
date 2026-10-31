---
title: Build and deploy a service catalog
description: Create a service catalog with the Wanaku CLI, deploy it to Barn, serve it with the Camel Integration Capability, and add it to Wanaku as a forward.
---

# Build and deploy a service catalog

This guide builds an `hr-system` catalog with two systems, `employees` and `payroll`, and makes its tools available in Wanaku. For the catalog format, see [Service catalogs](service-catalogs.md).

## Prerequisites

- The [Wanaku CLI](../cli/install.md).
- A running Barn backend, configured as the `wanaku-barn` plugin of Wanaku. The examples use `http://localhost:8180`, the default address of the backend.
- A running Wanaku instance. The examples use the management API at `http://localhost:8080`.
- The [Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability/blob/main/docs/get-started/index.md), as a JAR file or as the `quay.io/wanaku/camel-integration-capability` container image.

> [!IMPORTANT]
> The `wanaku service` commands send their requests to Barn through the Wanaku plugin proxy, at `<host>/api/plugins/wanaku-barn/wanaku-barn-api`. The `--host` option is the address of Wanaku. Its default value is `http://localhost:8080`. To call Barn directly, add `--direct-access` and set `--host` to the address of the Barn backend, for example `--host=http://localhost:8180 --direct-access`.

## Create the catalog

1. Create the catalog directory:

   ```shell
   wanaku service init --name=hr-system --services=employees,payroll
   ```

   The command creates `hr-system/index.properties` and one directory with skeleton files for each system. Add `--template` to create a [service template](service-templates.md) instead.

2. Write the routes of each system. Use an `ai-tool:` endpoint for each route that is an MCP tool. Edit `hr-system/employees/employees.camel.yaml`:

   ```yaml
   - route:
       id: list-employees
       from:
         uri: ai-tool:list-employees
         parameters:
           tags: wanaku
           description: "List all employees"
         steps:
           - setHeader:
               name: CamelHttpMethod
               constant: GET
           - to:
               uri: "http://hr-api:8080/api/employees"

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
           - setHeader:
               name: CamelHttpMethod
               constant: GET
           - toD: "http://hr-api:8080/api/employees/${header.employeeId}"
   ```

3. Edit `hr-system/payroll/payroll.camel.yaml`:

   ```yaml
   - route:
       id: calculate-payroll
       from:
         uri: ai-tool:calculate-payroll
         parameters:
           tags: wanaku
           description: "Calculate the payroll of an employee"
           parameter.employeeId: string
           parameter.employeeId.description: "The ID of the employee"
         steps:
           - setHeader:
               name: CamelHttpMethod
               constant: POST
           - toD: "http://payroll-service:8080/api/calculate/${header.employeeId}"
   ```

4. Add the Maven dependencies of each system to its `*.dependencies.txt` file. Remove the dependencies files and their `catalog.dependencies.<system>` lines if the routes need no dependencies.

> [!NOTE]
> `wanaku service init` also creates `*.wanaku-rules.yaml` files, and `wanaku service expose` fills them from the route IDs. The `ai-tool:` routes replace these legacy rules files. You can delete them and their `catalog.rules.<system>` lines.

## Validate the catalog

Validate the catalog before you deploy it:

```shell
wanaku service validate --path=hr-system
```

Barn checks the package and does not deploy it. The command lists each problem with the file and the property or YAML location at fault:

```text
index.properties#catalog.description: Required property 'catalog.description' is missing or empty in index.properties
employees/employees.camel.yaml#/0/route: Invalid Camel YAML DSL: required property 'from' not found
index.properties#catalog.icon: Optional property 'catalog.icon' is not set: a default icon will be used
Service catalog 'hr-system' is invalid: 2 error(s), 1 warning(s)
```

An error makes the command exit with a non-zero status. A warning does not. Barn also does these checks when you deploy a catalog, and it rejects a catalog that has an error. You can use the command in a CI pipeline. For the full list of checks, see [Validate a catalog](../reference/service-catalog-api.md#validate-a-catalog).

## Deploy the catalog to Barn

```shell
wanaku service deploy --path=hr-system
```

The command packages the directory as a ZIP file and sends it to Barn. Barn stores it as a new version of the `hr-system` catalog. Each later deploy of the same catalog name creates the next version.

To create the package without a deploy, for example for the Wanaku Operator or for version control:

```shell
wanaku service package --path=hr-system -o hr-system.b64
```

The output file contains the Base64-encoded ZIP package. The default output file is `<catalog-name>.b64` in the current directory.

## Serve the catalog to Wanaku

Start one Camel Integration Capability for each system of the catalog. The capability downloads the catalog from Barn and serves the tools of one system on its MCP port.

1. Start the capability for the `employees` system:

   ```shell
   java -jar camel-integration-capability-main-*-jar-with-dependencies.jar \
     --registration-url http://localhost:8180 \
     --service-catalog hr-system \
     --service-catalog-system employees \
     --name employees \
     --mcp-port 9090
   ```

   `--registration-url` is the address of Barn. The capability uses it only to download the catalog.

2. Add the capability to Wanaku as an MCP forward:

   ```shell
   wanaku forwards add --name employees --service http://localhost:9090/mcp --namespace default
   ```

3. Do steps 1 and 2 again for the `payroll` system, with a different name and MCP port.

The capability can also register itself as a forward when it starts and remove the forward when it stops. Use `--register`, `--wanaku-url`, and `--advertised-address`. See [Wanaku self-registration parameters](https://github.com/wanaku-ai/camel-integration-capability/blob/main/docs/reference/cli.md#wanaku-self-registration-parameters).

## Verify the tools

1. List the tools in Wanaku:

   ```shell
   wanaku tools list
   ```

   The list shows `list-employees`, `get-employee-by-id`, and `calculate-payroll`.

2. In the Wanaku Admin UI, open **Extensions** > **Service Catalog**. The page shows `hr-system` with its systems and routes.

## Manage deployed catalogs

List the deployed catalogs:

```shell
wanaku service catalog list
```

Barn keeps a version history of each catalog. You can download an earlier version, or make its content active again:

```shell
# List the versions, newest first
wanaku service versions list --name=hr-system

# Download the package of version 2
wanaku service versions download --name=hr-system --version=2 --output=hr-system-v2.service.zip

# Make the content of version 2 active again
wanaku service versions restore --name=hr-system --version=2
```

A restore creates a new version with the earlier content. Restart the capabilities of the catalog to load the new content.

Remove a catalog:

```shell
wanaku service catalog remove --name=hr-system
```

Barn hides a removed catalog, but keeps it and its versions. To list the removed catalogs and restore one:

```shell
wanaku service removed
wanaku service restore --name=hr-system
```

Add `--template` to the `versions`, `removed`, and `restore` commands to work with a service template. For the version and removal rules, see the [Service catalog API](../reference/service-catalog-api.md#version-history).

## Deploy a catalog with the Wanaku Operator

On Kubernetes or OpenShift, the Wanaku Operator can deploy catalogs from a `WanakuServiceCatalog` resource. For the operator itself, see the [Deploy with the Wanaku Operator](../operate/operator.md).

1. Create the package with `wanaku service package`, as shown in [Deploy the catalog to Barn](#deploy-the-catalog-to-barn).

2. Store the package in a ConfigMap, under the `catalog.zip` key:

   ```shell
   kubectl create configmap hr-system-catalog \
     --from-file=catalog.zip=hr-system.b64 \
     -n wanaku
   ```

3. Create a file named `wanaku-service-catalog.yaml`:

   ```yaml
   apiVersion: "wanaku.ai/v1alpha1"
   kind: WanakuServiceCatalog
   metadata:
     name: hr-catalogs
   spec:
     # The name of the WanakuRouter resource
     routerRef: wanaku-dev
     catalogs:
       - name: hr-system
         configMapRef: hr-system-catalog
   ```

4. Apply the resource:

   ```shell
   kubectl apply -f wanaku-service-catalog.yaml -n wanaku
   ```

   The operator reads the ConfigMap and deploys the catalog to the Barn backend of the router.

5. Make sure that the catalog is deployed:

   ```shell
   kubectl describe wanakuservicecatalog hr-catalogs -n wanaku
   ```

   The status shows the deployed catalogs and the `Ready` condition.

The `routerRef` value must be the name of a `WanakuRouter` resource in the same namespace. The operator sends the catalog to `http://barn-<routerRef>:8080`.

When you delete the `WanakuServiceCatalog` resource, the operator removes its catalogs from Barn:

```shell
kubectl delete wanakuservicecatalog hr-catalogs -n wanaku
```

## Next steps

- [Service catalog API](../reference/service-catalog-api.md): deploy and validate catalogs from your own tools.
- [Service templates](service-templates.md): create parameterized catalogs.
- [Forward MCP servers](../cli/forwards.md): manage the forwards that serve the catalog tools.
