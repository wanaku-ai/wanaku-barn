# Wanaku Barn Backend

## Overview

The Wanaku Barn backend provides persistence, service catalog management, and administration APIs for the Wanaku ecosystem.

> **Note:** The primary MCP routing engine is now [Wanaku](https://github.com/wanaku-ai/wanaku) (Rust). This backend serves as the "Classic Wanaku" component, handling persistence and management operations that Wanaku proxies to in hybrid deployments.

## Purpose

The backend is responsible for:

- Providing HTTP management APIs
- Managing service catalogs, templates, data stores, and immutable versions
- Storing semantic router definitions and audit events

Authentication and authorization are not handled by the backend: they are delegated to the Wanaku Governed
Execution Proxy (GEP) and its oauth2 proxy.

## Key Features

- **Management API**: REST API for configuration
- **Web UI**: React-based administration interface (Wanaku plugin)
- **Data Persistence**: Infinispan-based storage for router state

## Architecture

Built on:

- **Quarkus**: Modern Java framework for cloud-native applications
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
wanaku.persistence.infinispan.base-folder=${wanaku.home}/barn/

```

## API Endpoints

- **Management API**: `http://localhost:8080/api/`
- **Web UI**: `http://localhost:8080/`
- **Health**: `http://localhost:8080/q/health`

## Related Documentation

- [Usage Guide](../../docs/usage.md)
- [Architecture](../../docs/architecture.md)
- [Router Internals](../../docs/wanaku-router-internals.md)
- [Configuration Reference](../../docs/configurations.md)
