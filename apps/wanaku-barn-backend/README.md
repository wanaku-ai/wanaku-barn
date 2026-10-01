# Wanaku Barn Backend

## Overview

The Wanaku Barn backend provides persistence, service catalog management, MCP server registration, and administration APIs for the Wanaku ecosystem.

> **Note:** The primary MCP routing engine is now [Wanaku Praxis](https://github.com/wanaku-ai/wanaku) (Rust). This backend serves as the "Classic Wanaku" component, handling persistence and management operations that Praxis proxies to in hybrid deployments.

## Purpose

The backend is responsible for:

- Managing tool and resource registrations (persistence)
- Providing HTTP management API for configuration
- Managing multiple MCP namespaces
- Serving service catalogs and data stores
- Routes tool invocations to appropriate tool services via MCP
- Routes resource read requests to appropriate providers via MCP

Authentication and authorization are not handled by the backend: they are delegated to the Wanaku Governed
Execution Proxy (GEP) and its oauth2 proxy.

## Key Features

- **MCP Protocol Support**: SSE and Streamable HTTP transports
- **Multi-Namespace**: Support for 10+ isolated namespaces
- **MCP Communication**: Communication with downstream MCP servers via MCP protocol
- **Management API**: REST API for configuration
- **Web UI**: React-based administration interface (Praxis plugin)
- **Service Discovery**: Automatic registration and health monitoring of downstream MCP servers
- **Data Persistence**: Infinispan-based storage for router state

## Architecture

Built on:

- **Quarkus**: Modern Java framework for cloud-native applications
- **Quarkus MCP Server Extension**: MCP protocol implementation
- **MCP**: Service communication protocol
- **Infinispan**: Embedded data grid for persistence

## Running

### Development Mode

```shell
mvn quarkus:dev
```

### Production Mode

```shell
java -jar target/quarkus-app/quarkus-run.jar
```

### Container

```shell
podman run -p 8080:8080 quay.io/wanaku/wanaku-barn-backend:latest
```

## Configuration

Key configuration properties (see [Configuration Guide](../../docs/configurations.md) for complete reference):

```properties
# HTTP
quarkus.http.port=8080

# Persistence
wanaku.persistence.infinispan.base-folder=${wanaku.home}/router/

# MCP
quarkus.mcp.server.traffic-logging.enabled=true
```

## API Endpoints

- **MCP (SSE)**: `http://localhost:8080/mcp/sse`
- **MCP (HTTP)**: `http://localhost:8080/mcp/`
- **Management API**: `http://localhost:8080/api/`
- **Web UI**: `http://localhost:8080/`
- **Health**: `http://localhost:8080/q/health`

## Related Documentation

- [Usage Guide](../../docs/usage.md)
- [Architecture](../../docs/architecture.md)
- [Router Internals](../../docs/wanaku-router-internals.md)
- [Configuration Reference](../../docs/configurations.md)
