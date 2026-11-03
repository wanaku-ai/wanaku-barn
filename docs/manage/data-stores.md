---
title: Data stores
description: Inspect the records in the Barn data store from the Admin UI, and share static data between Wanaku and MCP servers with the Wanaku CLI.
---

# Inspect stored records

The admin UI's **Data Stores** page lists records from the backend data store. Each row shows the record name, the exact `wanaku.type` label, additional labels, creation and update dates, and its persisted revision. Missing metadata on older records appears as **Not recorded**; records without a type appear as **Unspecified**. Dates use your browser's local time zone.

Search by name, ID, label key or value, or audit actor. Combine search with the stored type filter and use pagination to browse the results. **View** shows the ID, all labels, audit actors, and a text preview with formatted JSON where possible. Binary content remains available through **Download**. Semantic records download as JSON; immutable semantic catalogs download as ZIP files; uploaded files retain their names.

Deleting an ordinary upload requires confirmation and sends its recorded revision to avoid deleting a changed record. Managed catalogs, templates, Kamelets and immutable records have deletion disabled; manage those through their corresponding feature pages.

## Manage data stores with the CLI

Wanaku provides a data store feature that allows you to share static data between Wanaku and its downstream MCP servers.

This is particularly useful for storing configuration files, route definitions, and other static resources that downstream MCP servers need to access at runtime.

A primary use case for the data store is storing Apache Camel routes and associated files for the Camel Integration Capability.

By storing route definitions in the data store, you can dynamically configure integrations without rebuilding or redeploying services.

> [!IMPORTANT]
> Authentication is required to access the data store API.
> Make sure you're logged in using `wanaku auth login` before using data store commands.

### Adding Data to the Data Store

The `wanaku data-store add` command allows you to upload files to the data store.
Files are automatically Base64 encoded when stored.

```shell
wanaku data-store add --read-from-file /path/to/file.yaml
```

By default, the data store entry will be named after the filename. You can specify a custom name using the `--name` option:

```shell
wanaku data-store add --read-from-file /path/to/employee-routes.camel.yaml --name employee-routes
```

In this example:

- `--read-from-file`: Specifies the local file path to upload
- `--name`: (Optional) Assigns a custom name to the stored data

The file contents are automatically Base64 encoded before being sent to the server, ensuring binary-safe storage.

### Listing Stored Data

View all data currently stored in the data store:

```shell
wanaku data-store list
```

This displays a table showing:

- **ID**: Unique identifier for each stored item
- **Name**: The name of the stored data
- **Data**: A preview of the stored content (truncated to 50 characters)
- **Labels**: Labels associated with the data store entry

You can filter the list using label expressions:

```shell
# Filter by label expression
wanaku data-store list -e 'category=routes'
```

See the label expression guide (`wanaku man label-expression`) for detailed syntax information.

#### Record Metadata

The data store API (`/api/v1/data-store`) returns metadata for each entry:

- `createdAt` and `updatedAt`: The time of the first and the last write, in UTC.
- `revision`: A number that starts at 1 and increments on each write.
- `createdBy` and `updatedBy`: Empty. Barn does not have an identity source.

The server sets these values. The server ignores metadata values in requests.
Entries written by earlier versions show `revision` 0 and no timestamps until the next write.
An update (`PUT /api/v1/data-store`) for an ID that does not exist returns HTTP 404.

#### Pagination

The data store list endpoint (`GET /api/v1/data-store`) returns all matching entries by default.
To request one page, send the `offset` or the `limit` query parameter:

- `offset` is the number of entries to skip. The default is 0.
- `limit` is the maximum number of entries to return, from 1 to 1000. The default is 100.
- A page is sorted by name, then by ID. Name sorting is case-sensitive.
- The `X-Total-Count` response header contains the number of matching entries.
- A value out of range returns HTTP 400.

The service catalog list (`GET /api/v1/service-catalog`) and the service template list (`GET /api/v1/service-template/list`) accept the same parameters. Their pages are sorted by name without regard to case, then by ID.

#### Concurrent Changes

The data store API uses optimistic concurrency control to prevent lost updates:

- `GET /api/v1/data-store/{id}`, `POST /api/v1/data-store` and `PUT /api/v1/data-store` return the revision in the `ETag` header, for example `ETag: "3"`.
- To make an update or a delete conditional, send the revision that you read in the `If-Match` header or in the `expectedRevision` query parameter.
- If the stored revision is different, the server returns HTTP 409 (Conflict) and does not change the entry.
- If the entry does not exist, the server returns HTTP 404. The server checks existence before it checks the revision.
- If `If-Match` and `expectedRevision` specify different revisions, or if `If-Match` is not a single numeric entity tag, the server returns HTTP 400.
- Requests without a revision are not conditional. Earlier clients continue to work.
- Entry names are unique. A create or a rename that uses the name of another entry returns HTTP 409.
- Bulk deletes (by name or by label expression) are not conditional.

The CLI commands `wanaku data-store label add` and `wanaku data-store label remove` send the revision that they read. If another client changes the entry first, the command fails and reports the conflict. Run the command again to apply the labels to the current entry.

The server makes these checks in one Barn process. Barn does not support more than one process on the same store directory.

### Managing Labels on Data Stores

Data stores support labels for organization and filtering, similar to tools and resources.

**Adding labels to a data store:**

```shell
# Add labels to a specific data store by ID
wanaku data-store label add --id <data-store-id> --label category=routes --label env=production

# Add labels to multiple data stores using label expressions
wanaku data-store label add -e 'category=config' --label migrated=true
```

**Removing labels from a data store:**

```shell
# Remove labels from a specific data store by ID
wanaku data-store label remove --id <data-store-id> --label temporary --label draft

# Remove labels from multiple data stores using label expressions
wanaku data-store label remove -e 'status=deprecated' --label legacy
```

When adding a label with a key that already exists, the value will be updated. When removing a non-existent label, it will be silently ignored.

### Removing Data from the Data Store

Remove stored data using either the ID or name:

```shell
# Remove by ID
wanaku data-store remove --id <data-store-id>

# Remove by name
wanaku data-store remove --name employee-routes
```

> [!NOTE]
> The data store is also accessible via the REST API at `/api/v1/data-store` and through the Wanaku web interface under the Data Stores page,
> where you can upload, download, and manage stored data using a graphical interface.
