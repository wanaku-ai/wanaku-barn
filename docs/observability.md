# Observability: Tracing, Logging, and Auditing

This document describes the observability architecture in Wanaku, covering distributed tracing, request ID propagation, and structured logging with traceability.

## Overview

Wanaku integrates OpenTelemetry (OTel) for distributed tracing and Micrometer for metrics, providing end-to-end observability across the entire request chain:

- **MCP Client** → **Router Backend** → **Downstream MCP Server** → **Downstream Services**

Every request flowing through the system carries:

1. **W3C `traceparent`** header for distributed trace context propagation (automatic via OTel)
2. **`x-wanaku-request-id`** for MCP request correlation (explicit propagation)

## Architecture

### Request Flow and Trace Propagation

```text
┌──────────┐     HTTP/MCP      ┌────────────────┐                 ┌──────────────────┐
│ MCP Client │ ──────────────→ │ Router Backend  │ ─────────────→ │ Downstream MCP Srv│
│             │  traceparent    │                 │  traceparent   │                   │
│             │  x-wanaku-      │  MDC: requestId │  x-wanaku-     │  MDC: requestId   │
│             │  request-id     │  MDC: traceId   │  request-id    │  Span: requestId  │
└──────────┘                   └────────────────┘                 └──────────────────┘
                                        │
                                        ▼
                                 ┌──────────────┐
                                 │ OTel Collector│
                                 │  (4317 gRPC)  │
                                 └──────┬───────┘
                                        │
                                        ▼
                                 ┌──────────────┐
                                 │   Jaeger UI   │
                                 │  (16686 HTTP) │
                                 └──────────────┘
```

### Components

| Component | Role |
|-----------|------|
| `quarkus-opentelemetry` | Auto-instruments HTTP and Vert.x; creates spans; manages trace context |
| `McpTracingInstrumenter` | Built into `quarkus-mcp-server`; auto-creates MCP spans with `requestId`, `toolName`, `session_id` attributes |
| `McpHeadersSupplier` | Propagates W3C `traceparent` and `x-wanaku-request-id` from MDC to downstream MCP calls |
| `quarkus-micrometer-registry-prometheus` | Provides Prometheus metrics export |

## Configuration

### Router Backend

The router backend's `application.properties` configures OTel:

```properties
# OpenTelemetry
quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
quarkus.otel.exporter.otlp.traces.protocol=grpc
quarkus.otel.resource.attributes=service.name=wanaku-router,service.version=${quarkus.application.version}
quarkus.otel.traces.sampler=parentbased_always_on
quarkus.otel.propagators=tracecontext,baggage
```

### Downstream MCP Servers

Downstream MCP servers use similar configuration:

```properties
quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
quarkus.otel.exporter.otlp.traces.protocol=grpc
quarkus.otel.resource.attributes=service.name=${wanaku.service.name:wanaku-mcp-server},service.version=${quarkus.application.version}
quarkus.otel.traces.sampler=parentbased_always_on
quarkus.otel.propagators=tracecontext,baggage
```

Each downstream MCP server must set `wanaku.service.name` to identify itself (e.g., `wanaku-file-resource`, `wanaku-http-tool`).

### Log Format

All log profiles include trace and request context:

```text
%d{yyyy-MM-dd HH:mm:ss} %-5p [%c] (%t) [traceId=%X{traceId}, requestId=%X{requestId}] %s%e%n
```

MDC keys available in log output:

| MDC Key | Source | Description |
|---------|--------|-------------|
| `traceId` | OTel (automatic) | W3C trace ID for distributed tracing |
| `requestId` | `x-request-id` header (Barn backend) | Correlation ID of the HTTP request. See [Request Correlation in the Barn Backend](#request-correlation-in-the-barn-backend). |
| `connectionId` | MCP connection | MCP connection/session identifier |

## Request Correlation in the Barn Backend

The Barn backend gives every HTTP request a correlation ID.
Use this ID to find the log lines and the audit records of one request.

### Behavior

- The backend reads the `x-request-id` request header.
- The backend keeps the value if it has 1 to 128 characters and contains only letters, digits, `.`, `_`, `:` and `-`.
- If the header is missing or the value is not valid, the backend generates a new ID (a UUID).
- The backend returns the ID in the `x-request-id` response header. This applies to all responses, including error responses (4xx and 5xx) and responses for unknown paths.
- The backend puts the ID in the `requestId` MDC key. The log format writes this key on each log line of the request.
- CORS exposes the `x-request-id` and `ETag` response headers, so browser clients can read them.

### Trace ID

- When OpenTelemetry is enabled (`quarkus.otel.sdk.disabled=false`), the backend reads the W3C `traceparent` header.
- The trace ID of the current span is available to the backend code and is written to the `traceId` MDC key.
- When OpenTelemetry is disabled (the default), no trace ID is available.

### Identity

Barn has no trusted identity source. The backend does not read identity headers such as `Authorization` or `X-Forwarded-User` to identify an actor.

### Example

```shell
curl -i -H 'x-request-id: deploy-42' http://localhost:8180/api/v1/data-store
```

The response contains `x-request-id: deploy-42`, and the log lines of the request contain `requestId=deploy-42`.

## How Request ID Propagation Works

### 1. MCP Request Arrives

When an MCP request arrives, `quarkus-mcp-server`'s `McpTracingInstrumenter` automatically creates a span with `requestId` and `toolName` attributes.

### 2. Downstream MCP Calls

When outbound MCP calls are made, `ClientUtil.createClient()` (Streamable HTTP transport only) uses `McpHeadersSupplier` to propagate:

- W3C `traceparent` (from OTel context propagation)
- `x-wanaku-request-id` (from MDC)

## OTel Span Attributes

The following custom span attributes are set:

| Attribute | Set By | Description |
|-----------|--------|-------------|
| `wanaku.mcp.request_id` | Router request handling | MCP request ID |
| `wanaku.mcp.connection_id` | Router request handling | MCP connection/session ID |
| `wanaku.mcp.tool_name` | Router tool routing | Name of the tool being invoked |
| `wanaku.mcp.resource_name` | Router resource routing | Name of the resource being acquired |

These attributes are searchable in Jaeger/Grafana for quick request correlation.

## Deployment

### Docker Compose

The `docker-compose.yml` and `docker-compose-noauth.yml` include:

- **OTel Collector** (`otel-collector:4317`): Receives OTLP data from router and downstream MCP servers
- **Jaeger** (`jaeger:16686`): UI for trace visualization; receives data from OTel Collector via OTLP

Environment variables set on services:

```yaml
QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
```

### OTel Collector Pipeline

The `otel-collector-config.yaml` configures:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/jaeger]
```

### Accessing Jaeger

After starting the stack with Docker Compose, Jaeger UI is available at:

```text
http://localhost:16686
```

Search by:

- **Service**: `wanaku-router` or the downstream MCP server name
- **Span attribute**: `wanaku.mcp.request_id=<request-id>`
- **Operation**: tool invocation or resource acquisition
