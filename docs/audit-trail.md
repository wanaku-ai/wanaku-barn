# Barn Audit Trail

Barn records every change to the resources that it manages in a durable audit trail.
Each record tells you what changed, when it changed, which request caused the change, and whether the change succeeded.
The records use the field names of the Wanaku audit schema version `1.0`, so tools that read Wanaku audit events can also read Barn events.

## What Barn Records

Barn records one administrative event for each request to these operations:

| Operation | Endpoint |
|-----------|----------|
| `data_store.create` | `POST /api/v1/data-store` |
| `data_store.update` | `PUT /api/v1/data-store` |
| `data_store.delete` | `DELETE /api/v1/data-store/{id}` |
| `data_store.delete_by_name` | `DELETE /api/v1/data-store?name={name}` |
| `data_store.delete_by_labels` | `DELETE /api/v1/data-store/labels?labelExpression={expression}` |
| `service_catalog.deploy` | `POST /api/v1/service-catalog` |
| `service_catalog.remove` | `DELETE /api/v1/service-catalog/{name}` |
| `service_catalog.restore` | `POST /api/v1/service-catalog/{name}/versions/{version}/activate` |
| `service_template.deploy` | `POST /api/v1/service-template/deploy` |
| `service_template.remove` | `DELETE /api/v1/service-template/remove?name={name}` |
| `service_template.instantiate` | `POST /api/v1/service-template/instantiate` |
| `service_template.restore` | `POST /api/v1/service-template/versions/activate` |
| `service_template.seed` | Startup: deployment of a built-in template |
| `kamelet.upload` | `POST /api/v1/kamelets` |
| `kamelet.remove` | `DELETE /api/v1/kamelets/{name}` |
| `semantic_router.create` | `POST /api/v1/semantic-routers` |
| `semantic_router.update` | `PUT /api/v1/semantic-routers/{id}` |
| `semantic_router.remove` | `DELETE /api/v1/semantic-routers/{id}` |
| `semantic_router.publish` | `POST /api/v1/semantic-routers/{id}/publish` |

These rules apply:

- Barn records the event after the outcome is known. Barn records successful and rejected requests.
- Barn records one event for each request. An operation that calls another operation internally (for example, template instantiation deploys a catalog) records only the outer operation.
- A bulk delete records one event. The `count` attribute contains the number of removed entries.
- Barn does not record read operations, validation-only requests (`/validate`), or reads of the audit trail.

## Event Fields

| Field | Description |
|-------|-------------|
| `schema_version` | Always `1.0`. |
| `event_id` | A unique ID for the event. |
| `stream_id` | The value of `wanaku.audit.stream-id` (default `barn`). |
| `sequence` | A number that increments for each stored event. The number continues after a restart. |
| `timestamp` | The event time as an ISO 8601 UTC timestamp. |
| `category` | Always `administrative`. |
| `decision` | `allow`, `reject_malformed` or `error`. See [Decisions and Reason Codes](#decisions-and-reason-codes). |
| `reason_code` | A stable code for the outcome. |
| `explanation` | A fixed description of the reason code. |
| `correlation_id`, `request_id` | Barn does not read a request ID from the caller. The correlation ID is the event ID, and the request ID is empty. |
| `protocol` | `http` for API requests, `startup` for built-in template seeding. |
| `operation` | The operation, for example `service_catalog.deploy`. |
| `target_type`, `target` | The type and the ID or name of the changed resource. Barn limits the target to 256 characters. |
| `policy_revision` | The resulting version, when the operation creates one: the catalog or template version for deploys and restores, or the revision of a semantic router publication. |
| `response_status` | The HTTP status of the response. |
| `duration_ms` | The time to process the request. |
| `attributes` | `http_method`, `revision` (the data store revision after a write), `count` (for bulk deletes) and `trace_id` (when OpenTelemetry is enabled). |
| `redaction` | Barn never captures payloads. `payload_captured` is always `false`. |
| `coverage_complete`, `dropped_events` | `dropped_events` is the number of events that Barn failed to store before this event. `coverage_complete` is `false` when this number is not 0. |

Barn has no trusted identity source, so Barn does not set the `actor` field.
Barn ignores identity headers such as `Authorization` and `X-Forwarded-User`.

Barn never stores request or response bodies, data store content, ZIP packages, labels or configuration values in audit events.

## Decisions and Reason Codes

| HTTP status | `decision` | `reason_code` |
|-------------|------------|---------------|
| 2xx, 3xx | `allow` | `completed` |
| 400, 422 | `reject_malformed` | `invalid_request` |
| 404 | `reject_malformed` | `not_found` |
| 409 | `reject_malformed` | `conflict` |
| Other 4xx | `reject_malformed` | `client_error` |
| 5xx | `error` | `server_error` |

Built-in template seeding uses the reason codes `seeded` (`allow`) and `seed_failed` (`error`).

## Query the Audit Trail

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/audit/events` | List events, newest first. |
| `GET` | `/api/v1/audit/events/{id}` | Get one event. Returns HTTP 404 if the event does not exist. |
| `GET` | `/api/v1/audit/schema` | Get the schema version. |
| `GET` | `/api/v1/audit/health` | Get the health of the audit store and the number of dropped events. |

The list endpoint accepts these query parameters:

| Parameter | Description |
|-----------|-------------|
| `from`, `to` | Time range, as ISO 8601 UTC timestamps (inclusive). An invalid timestamp returns HTTP 400. |
| `operation`, `target_type`, `target`, `decision`, `reason_code`, `actor` | Exact-match filters. For example, `target_type=service_catalog` returns the events of all service catalogs. |
| `offset` | The number of events to skip. The default is 0. |
| `limit` | The maximum number of events to return. The default is 100. The maximum is 1000. |

The response contains `events`, `offset`, `limit` and `total` (the number of matching events).

```shell
curl 'http://localhost:8180/api/v1/audit/events?operation=service_catalog.deploy&limit=10'
```

## Change History Page

The admin UI shows the audit trail on the **Change History** page.

- Select a resource type in **Resource** to show only the events of that type, for example **Service catalogs** or **Semantic routers**.
- The page shows the newest events first, with the operation, target, decision, reason code and HTTP status.
- The **Service Catalog** and **Semantic Routers** pages have a **View the change history** link. The link opens the Change History page with the matching filter.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.audit.max-records` | `10000` | The number of events to keep. Barn removes the oldest events when it stores more. |
| `wanaku.audit.stream-id` | `barn` | The `stream_id` of the events. Set a different value for each Barn instance if you combine their audit trails. |

## Storage and Failures

- Barn stores events in the Infinispan caches `audit-event` and `audit-state`. When the file store is enabled, the events survive a restart.
- Barn keeps the newest `wanaku.audit.max-records` events. If the limit is reduced, Barn removes the extra events at startup.
- Audit storage is separate from Wanaku storage. Barn and Wanaku do not share audit data.
- An audit storage failure does not change or block the management operation.
- When Barn cannot store an event, Barn logs an error without event content, increments the `wanaku.audit.storage.failures` metric, increments `dropped_events` in the health response and reports `healthy: false`. The next stored event reports the gap in `dropped_events`.
- Recording is best effort. If the process stops after a change and before the event is stored, the event is lost.
- Events are not signed or chained. Barn does not provide cryptographic tamper evidence.
