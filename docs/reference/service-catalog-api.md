---
title: Service catalog API
description: Reference for the Barn service catalog REST API, including the deploy, validate, version history, removal, restore, and purge rules.
---

# Service catalog API

The Barn backend serves the service catalog API at `/api/v1/service-catalog`. The `wanaku service` commands use this API. For a task-based guide, see [Build and deploy a service catalog](../manage/build-a-service-catalog.md).

All responses use the `WanakuResponse` format: `{"data": ..., "error": ...}`.

## Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/service-catalog` | List the catalogs. Optional query parameters: `search`, `offset`, and `limit`. |
| `GET` | `/api/v1/service-catalog/{name}` | Get one catalog with its systems and their files. |
| `GET` | `/api/v1/service-catalog/download?name={name}` | Get the package of the active version (Base64-encoded ZIP in `data`). |
| `POST` | `/api/v1/service-catalog?expectedVersion={n}` | Deploy a new version. `expectedVersion` is optional. |
| `POST` | `/api/v1/service-catalog/validate` | Validate a package. Barn does not deploy it. |
| `DELETE` | `/api/v1/service-catalog/{name}` | Remove a catalog. |
| `GET` | `/api/v1/service-catalog/removed` | List the removed catalogs. |
| `POST` | `/api/v1/service-catalog/{name}/restore` | Restore a removed catalog. |
| `GET` | `/api/v1/service-catalog/{name}/versions?from={time}&to={time}` | List the versions, newest first. |
| `GET` | `/api/v1/service-catalog/{name}/versions/{version}` | Get the metadata of one version. |
| `GET` | `/api/v1/service-catalog/{name}/versions/{version}/download` | Get the package of one version. |
| `POST` | `/api/v1/service-catalog/{name}/versions/{version}/activate?expectedVersion={n}` | Restore one version as a new version. |

The service template API at `/api/v1/service-template` has the same version, removal, and restore operations, with a different path layout. See [Service templates](../manage/service-templates.md).

## Deploy a catalog

The request body is a JSON object with a package name and the Base64-encoded ZIP package:

```json
{
  "name": "my-service.service.zip",
  "data": "<base64-encoded ZIP>"
}
```

Example:

```shell
wanaku service package --path=my-service -o my-service.b64

jq -n --arg data "$(cat my-service.b64)" '{name: "my-service.service.zip", data: $data}' | \
  curl -X POST -H "Content-Type: application/json" --data @- \
  http://localhost:8180/api/v1/service-catalog
```

The response contains the stored data store entry. Barn identifies the catalog by the `catalog.name` value in `index.properties`, not by the package name.

Barn validates the package before it stores it. The checks are the same as for [Validate a catalog](#validate-a-catalog). If the package has an error, Barn returns HTTP `422` and does not store the package. The error message lists each problem.

## List catalogs

Example response:

```json
{
  "data": [
    {
      "id": "6f1c2e0a-3c4b-4d2f-9a51-2b7f0c9d1e11",
      "name": "my-service",
      "icon": null,
      "description": "Service catalog for my-service",
      "services": ["system-a", "system-b"]
    }
  ],
  "error": null
}
```

## Validate a catalog

The request body is the same as the deploy request. Barn reports all problems at once. Each problem points at the file, and at the property or YAML location, that is at fault:

```json
{
  "data": {
    "type": "catalog",
    "name": "my-service",
    "valid": false,
    "errors": [
      {
        "path": "index.properties#catalog.routes.system-a",
        "message": "Referenced file 'system-a/system-a.camel.yaml' is not present in the package"
      },
      {
        "path": "system-b/system-b.camel.yaml#/0/route/from",
        "message": "Invalid Camel YAML DSL: required property 'steps' not found"
      }
    ],
    "warnings": [
      {
        "path": "index.properties#catalog.icon",
        "message": "Optional property 'catalog.icon' is not set: a default icon will be used"
      }
    ]
  },
  "error": null
}
```

Barn does these checks:

- The payload is valid Base64 and a readable ZIP archive.
- The archive contains an `index.properties` manifest that Barn can parse.
- The manifest declares `catalog.name`, `catalog.description`, and `catalog.services`.
- Each system declares `catalog.routes.<system>`. The file has a safe relative path and is in the archive.
- The optional `catalog.dependencies.<system>` and `catalog.properties.<system>` files are in the archive.
- The routes file of each system is valid Camel YAML DSL. Barn uses the Camel [YAML DSL validator](https://camel.apache.org/manual/camel-yaml-dsl-validator-maven-plugin.html), so it reports YAML syntax errors and DSL errors.
- Each `service.properties` file is a valid properties file.

Barn does not validate rules files.

| Status | Meaning |
|--------|---------|
| `200` | Barn validated the package. Read `data.valid` and `data.errors` for the result. |
| `422` | The request contains no package data. |

A warning does not make a package invalid. For example, Barn warns when a catalog declares parameterized properties, because that package is probably a [service template](../manage/service-templates.md).

## Version history

Each deploy stores an immutable version of the package. These rules apply:

- The first deploy creates version 1. Each later deploy of the same catalog name creates the next version.
- The catalog entry keeps its ID when the content changes. The `wanaku.version` label of the entry contains the active version.
- A version has one of these statuses: `active` (the current content), `superseded` (earlier content), or `rejected` (never activated).
- Barn keeps the newest 50 versions of each catalog. Set `wanaku.catalog.max-versions` to change the limit. Barn never removes the active version.
- A restore creates a new version with the earlier content. Barn never changes an earlier version.
- Version numbers continue after you remove a catalog and deploy it again.
- Barn does not keep the content of rejected versions.
- Catalogs and templates that existed before version history get version 1, with the origin `legacy`, at startup.
- The `from` and `to` query parameters of the version list are optional ISO 8601 UTC timestamps. They filter on `createdAt`, and both are inclusive.

To prevent lost updates, send the active version that you expect in the `expectedVersion` query parameter of the deploy or restore request. If the active version is different, Barn returns HTTP 409 and does not create a version.

The generic data store API (`/api/v1/data-store`) cannot create or update catalog entries. Use the service catalog API, so that each change creates a version.

Example version metadata:

```json
{
  "data": {
    "type": "catalog",
    "name": "my-service",
    "version": 3,
    "status": "active",
    "createdAt": "2026-10-08T10:15:30.123Z",
    "activatedAt": "2026-10-08T10:15:30.125Z",
    "checksum": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "origin": "restore",
    "restoredFrom": 1,
    "dataStoreName": "my-service.service.zip"
  },
  "error": null
}
```

- `checksum` is the SHA-256 digest of the decoded ZIP package.
- `origin` is `api`, `startup`, `instantiate`, `restore`, or `legacy`.
- `actor` is empty, because Barn has no identity source.

## Removal and restore

A removal does not delete the catalog. These rules apply:

- Barn marks the catalog as removed. The `wanaku.type` label changes to `catalog.removed`, and the `wanaku.removed-at` label records the removal time.
- A removed catalog does not appear in lists. Get, download, deployment instructions, and instantiation return HTTP 404.
- The version history of a removed catalog stays available through the version endpoints.
- A removed name is reserved. A deploy with the same name returns HTTP 409 until you restore the catalog.
- A restore makes the catalog visible again with its ID and its active version. A restore does not create a version.
- A second removal of the same catalog returns HTTP 404.
- The generic data store API cannot delete catalogs or templates, removed or not.
- Barn does not deploy a built-in template again at startup after you remove it.

The list of removed catalogs contains `name`, `removedAt`, `version`, and `dataStoreName` for each catalog.

## Purge

Barn can delete removed catalogs and templates permanently after a retention period. Purge is disabled by default.

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.catalog.purge-after` | Not set | The retention period, for example `P30D`. Set it to enable purge. |
| `wanaku.catalog.purge-interval` | `1h` | The interval between checks for expired items. |

- Barn purges an item when its removal time is equal to or earlier than the current time minus `wanaku.catalog.purge-after`.
- A purge deletes the item and all its versions. Barn does not use the version numbers again if you deploy the name again.
- Each purge records an audit event with the operation `service_catalog.purge` or `service_template.purge`.

## Next steps

- [Build and deploy a service catalog](../manage/build-a-service-catalog.md): use the CLI for the same tasks.
- [Audit trail](../manage/audit-trail.md): the events that Barn records for catalog changes.
- [Configuration](configuration.md): other Barn configuration properties.
