# Backup, Restore and Upgrade

This guide explains how to back up the Barn data, restore it, and upgrade Barn safely.
Barn keeps its own storage. Barn and Wanaku do not share a persistence backend, so back up each of them separately.

## What Barn Stores

Barn stores its data in embedded Infinispan caches. When the file store is enabled (the default), the caches are written to `wanaku.persistence.infinispan.base-folder` (default `${wanaku.home}/router/`).

| Data | Exported | Description |
|------|----------|-------------|
| Data stores | Yes | All entries with their metadata: service catalogs, service templates (active and removed), Kamelets, semantic router records and generic entries. |
| Catalog and template versions | Yes | All versions with their content, and the version counters. |
| Audit events | Optional | The retained audit trail and its sequence number. |
| Schema version | Yes | The schema version of the data, as the `schemaVersion` field of the archive. |
| Service discovery state | No | Registered services and their health. Services register again after a restart. |
| Code execution tasks | No | Short-lived task state. |
| Forward and prompt references | No | Not used by Barn since the routing engine moved to Wanaku. |

## Export the Data

Export the data to a JSON archive:

```shell
wanaku backup export --output barn-backup.json
```

Add `--no-audit` to leave out the audit events.

The REST endpoint is `GET /api/v1/management/export?includeAudit=true`. The archive is in the `data` field of the response.

The archive is a logical export. It does not depend on the Infinispan file format. It contains:

- `formatVersion`: The archive format. The current format is `1`.
- `schemaVersion`: The schema version of the exported data.
- `exportedAt`: The export time.
- `dataStores`, `catalogVersions`, `versionCounters`: The data.
- `auditEvents`, `auditSequence`: The audit trail, or `null` when the export leaves it out.

The export holds the catalog lock, so it does not include a half-finished deploy.

> [!WARNING]
> The archive contains all data store content, including service catalogs and their configuration. Store the archive securely.

## Import the Data

Import an archive that an export created:

```shell
wanaku backup import --input barn-backup.json
```

The REST endpoint is `POST /api/v1/management/import?replace=false`, with the archive as the request body.

These rules apply:

- The import replaces the data stores, the catalog and template versions and the version counters with the content of the archive. Barn keeps the identifiers, revisions and timestamps of the archive.
- If the archive contains audit events, the import replaces the audit trail. Otherwise, Barn keeps the current audit trail.
- Without `--replace` (`replace=true`), the import requires an empty Barn. Barn is empty when it contains only the built-in templates that it deployed at startup and that nobody changed. Otherwise, the import returns HTTP 409.
- Barn validates the complete archive before it changes any data. An archive with an unsupported format, a newer schema version, or duplicate identifiers returns HTTP 400.
- If the import fails while it writes the data, Barn restores the data that it had before the import.
- If the archive has an earlier schema version, Barn runs the migration steps after the import.
- Barn records the export and the import in the audit trail (`barn.export` and `barn.import`).

## Schema Version and Migrations

Barn stores the schema version of its data in the `barn-metadata` cache.

At startup, Barn runs the migration steps that have a version greater than the stored version:

- The steps run in order, before Barn deploys the built-in templates.
- Barn stores the new version after each successful step. If Barn stops during a migration, the next startup continues with the step that did not finish.
- If the stored version is newer than the version that this Barn release supports, the startup fails and Barn does not change the data. Use a newer Barn release, or restore a backup that the older release created.
- The readiness check `Persistence schema` (`/q/health/ready`) reports `DOWN` until the migration is complete.

| Version | Change |
|---------|--------|
| 1 | Creates the version history of existing catalogs and templates, and adds the `wanaku.catalog-name` label. |

## Upgrade Procedure

1. Export the data:

   ```shell
   wanaku backup export --output barn-before-upgrade.json
   ```

2. Stop Barn.
3. Copy the data directory (`wanaku.persistence.infinispan.base-folder`) to a safe location.
4. Start the new Barn release.
5. Check that the readiness endpoint `/q/health/ready` reports `UP`.

To go back to the earlier release, stop Barn, restore the copied data directory, and start the earlier release.
Do not start an earlier release on data that a newer release migrated.

## Restore From a Backup

1. Start Barn with an empty data directory.
2. Import the archive:

   ```shell
   wanaku backup import --input barn-backup.json
   ```

3. Check the data, for example with `wanaku service catalog list`.

To restore over existing data, add `--replace`.

## Limits

- Barn supports one process for each data directory. Do not start two Barn processes on the same directory.
- The file store directory still uses the legacy `router` folder name. Issue #191 tracks the move to `${wanaku.home}/barn/`.
