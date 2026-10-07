# Wanaku Configuration

This document provides a comprehensive overview of the configuration options for all components of the Wanaku project.

Described here are both Wanaku-specific configurations, prefixed with `wanaku`, and relevant [Quarkus-specific](https://quarkus.io/guides/all-config)
configurations, prefixed with `quarkus`.

> [!NOTE]
> Quarkus is the ultimate source for their descriptions. In case the description here conflicts with the ones from
> Quarkus, please consider the ones from them as being the actual correct value.

Properties are typically stored in `application.properties` files within each module and can be set in runtime using
`-D<property.name>=<value>` or by exporting equivalent environment variables (i.e.: `PROPERTY_NAME=<value>`).

> [!IMPORTANT]
> Some of the settings can only be set at build time.

## Configuration Basics

Wanaku is built on [Quarkus](https://quarkus.io/), a Java framework that uses `application.properties` files for
configuration. If you are unfamiliar with Quarkus, this section explains how configuration works.

### What is `application.properties`?

`application.properties` is a plain text file containing key-value pairs, one per line. Each line sets a configuration
property:

```properties
quarkus.http.port=8080
wanaku.service.name=my-tool
quarkus.log.level=INFO
```

Lines starting with `#` are comments. Blank lines are ignored.

### Where is `application.properties` located?

Each Wanaku component ships with a built-in `application.properties` file inside its JAR/binary at
`src/main/resources/application.properties`. These files contain sensible defaults and are embedded at build time.

For the main components:

- **Router Backend**: `apps/wanaku-barn-backend/src/main/resources/application.properties`
- **Tool Services**: each downstream MCP server has its own `src/main/resources/application.properties` (see the [Wanaku Capabilities Java SDK](https://github.com/wanaku-ai/wanaku-capabilities-java-sdk))
- **CLI**: `apps/wanaku-cli/src/main/resources/application.properties`

### How to override configuration at runtime

You do **not** need to modify the built-in files. Quarkus provides several ways to override configuration values when
running Wanaku:

#### 1. External `application.properties` file

Place an `application.properties` file in a `config/` directory next to the Wanaku binary. Quarkus automatically reads
it and any properties defined there override the built-in defaults:

```text
my-deployment/
├── wanaku-barn-backend-runner.jar
└── config/
    └── application.properties    ← your overrides go here
```

You only need to include the properties you want to change, not the entire file.

#### 2. System properties (`-D` flags)

Pass individual properties on the command line using `-D`:

```shell
java -Dquarkus.http.port=9090 -jar wanaku-barn-backend-runner.jar
```

#### 3. Environment variables

Export properties as environment variables. Convert the property name to uppercase, replacing dots (`.`) and hyphens
(`-`) with underscores (`_`):

```shell
export QUARKUS_HTTP_PORT=9090
java -jar wanaku-barn-backend-runner.jar
```

#### Priority order

When the same property is defined in multiple places, the following priority applies (highest to lowest):

1. System properties (`-D`)
2. Environment variables
3. External `config/application.properties`
4. Built-in `application.properties` (inside the JAR)

For complete details, see the [Quarkus Configuration Guide](https://quarkus.io/guides/config).

## 1. Router Backend

Configuration for the main Wanaku Router Backend (`wanaku-barn-backend`), which orchestrates all services.

### General & HTTP

| Property                          | Description                                                                           |
|-----------------------------------|---------------------------------------------------------------------------------------|
| `quarkus.http.port`               | `8080` - The primary HTTP port for the router backend.                                |
| `quarkus.http.cors.enabled`       | `true` - Enables Cross-Origin Resource Sharing (CORS).                                |
| `quarkus.http.cors.origins`       | A comma-separated list of allowed origins for CORS requests (e.g., for the admin UI). |
| `quarkus.http.access-log.enabled` | `true` - Enables the HTTP access log for monitoring requests.                         |

### Multi-Component Protocol (MCP) Server

| Property                                           | Description                                                                                        |
|----------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `quarkus.mcp.server.wanaku-internal.sse.root-path` | `/wanaku-internal/mcp` - The SSE endpoint path for the internal MCP namespace.                     |
| `quarkus.mcp.server.ns-*.sse.root-path`            | `/ns-*/mcp` - The SSE endpoint paths for the 10 available external namespaces (`ns-1` to `ns-10`). |
| `quarkus.mcp.server.traffic-logging.enabled`       | `true` - Enables logging of all MCP traffic for debugging.                                         |
| `quarkus.mcp.server.traffic-logging.text-limit`    | `1000000` - The maximum length of the body to log for MCP traffic.                                 |
| `quarkus.mcp.server.server-info.name`              | `Wanaku` - The name of the server.                                                                 |
| `quarkus.mcp.server.server-info.version`           | The version of the server, taken from the project version.                                         |
| `quarkus.mcp.server.client-logging.default-level`  | `debug` - The default logging level for MCP clients.                                               |

### Authentication & Authorization

The Barn backend does not perform authentication or authorization itself. These concerns are handled by the
Wanaku Governed Execution Proxy (GEP), which sits in front of the backend and uses the oauth2 proxy. No auth-related
properties (such as `wanaku.http.auth`) are required to run the backend.

### Home Directory Resolution

Wanaku uses a single home directory for all persistent data. The location is resolved by `WanakuHome` in the following
precedence order:

1. System property `wanaku.home`
2. Environment variable `WANAKU_HOME`
3. Default: `${user.home}/.wanaku`

> [!IMPORTANT]
> `wanaku.home` is a **Wanaku-native** property. It is resolved by the custom `WanakuHome` class, not directly by
> Quarkus MicroProfile Config. This ensures consistent resolution across all components (CLI, router, MCP servers)
> regardless of Quarkus' own `${...}` placeholder handling.

```properties
# Set the Wanaku home directory (all components)
wanaku.home=/path/to/custom/home
```

```shell
# Or via environment variable (recommended for containers)
export WANAKU_HOME=/path/to/custom/home
```

```shell
# Or via system property (direct router/MCP server start)
java -Dwanaku.home=/path/to/custom/home -jar quarkus-run.jar
```

#### What is stored under the home directory?

| Directory | Purpose |
|-----------|---------|
| `<home>/barn/` | Infinispan data store (SoftIndexFileStore for tools, resources, namespaces) |
| `<home>/local/logs/` | Router log file (`wanaku-router.log`) when running with the `local` Quarkus profile |
| `<home>/credentials` | CLI credential store (0600 permissions) |

> [!NOTE]
> Older versions stored the Infinispan data under `<home>/router/`. If you have existing data there, move it to the
> new location before starting Wanaku Barn (e.g., `mv ~/.wanaku/router ~/.wanaku/barn`), or keep using the old
> location by setting `wanaku.persistence.infinispan.base-folder=${wanaku.home}/router/`.
> Docker Compose and operator deployments keep their existing volumes, so no manual migration is needed there.

#### Behavior with direct JVM start

When starting the router or an MCP server directly with `java -jar`, the system property takes precedence over the
environment variable. Set both to test precedence:

```shell
export WANAKU_HOME=/tmp/env-var-home
java -Dwanaku.home=/tmp/sysprop-home -jar quarkus-run.jar
# Uses /tmp/sysprop-home (system property wins)
```

### Health Check

These `wanaku.router.health-check.*` properties control the periodic health probing of registered downstream MCP servers.

| Property                                      | Description                                                                |
|-----------------------------------------------|----------------------------------------------------------------------------|
| `wanaku.router.health-check.enabled`          | `true` - Enables periodic health checks of registered downstream MCP servers. |
| `wanaku.router.health-check.interval-seconds` | `60` - The interval in seconds between health check sweeps.                |
| `wanaku.router.health-check.max-concurrent`   | `10` - The maximum number of concurrent health check probes.               |

### Persistence (Infinispan)

| Property                                    | Description                                                                   |
|---------------------------------------------|-------------------------------------------------------------------------------|
| `wanaku.persistence.infinispan.base-folder` | Where to store Infinispan files (defaults to `${wanaku.home}/barn/`). |
| `wanaku.infinispan.max-state-count`         | `10` - The maximum number of historical states to keep for each service.      |

### Namespaces

| Property                                 | Description                                                                                                                                                                                                                                     |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `wanaku.router.namespace-age-hard-limit` | `100000000000` - Threshold used to distinguish epoch-seconds from epoch-milliseconds when parsing namespace age timestamps. Values above this limit are interpreted as epoch-milliseconds; values at or below are interpreted as epoch-seconds. |

## 2. Downstream MCP Servers

### Common Settings

These settings apply to downstream MCP servers and are foundational for their operation.

#### Common Settings (All Modes)

These settings apply to all downstream MCP servers:

| Property | Description |
|------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `wanaku.http.auth` | `keycloak` - Controls authentication mode for downstream MCP servers. Set to `none` to disable OIDC client. Also settable via `WANAKU_HTTP_AUTH` environment variable. |
| `wanaku.service.name` | The unique, lowercase name of the service (e.g., `exec`, `http`). |
| `wanaku.service.base-uri` | The base URI scheme for tools provided by this service (e.g., `exec://`). |
| `wanaku.service.exec.allowed-executables` | Comma-separated absolute executable paths that the Exec tool may run. |
| `quarkus.qute.strict-rendering` | `false` - Allows for more lenient Qute template rendering. |
| `quarkus.oidc-client.auth-server-url` | The URL of the Keycloak realm for authentication. |
| `quarkus.oidc-client.client-id` | `wanaku-service` - The shared OIDC client ID for all downstream MCP servers. |
| `quarkus.oidc-client.credentials.secret` | The OIDC client secret for the MCP server. **Must be replaced with a real secret.** |

#### Exec Tool Security

The `wanaku.service.exec.allowed-executables` property controls which programs the Exec tool can run. This is a critical security control because the Exec tool allows AI agents to invoke shell commands.

**Security enforcement (`ExecCommandPolicy`):**

1. **Absolute paths required** — The allowlist must use absolute paths like `/usr/bin/python3`, never relative names like `python3`. This prevents PATH hijacking attacks where a malicious binary shadows a legitimate one.

2. **Shell metacharacters blocked** — Characters that enable command injection (`;`, `|`, `&`, `<`, `>`, `` ` ``, `$`) are rejected. This prevents chaining multiple commands or redirecting input/output.

3. **Newlines blocked** — Prevents header injection and multi-command injection via embedded newlines.

4. **Empty allowlist = deny all** — If `allowed-executables` is empty or unset, all execution requests are denied.

5. **Path normalization** — Both the allowlist and requested executable paths are resolved to absolute, normalized paths to prevent traversal tricks (e.g., `/usr/bin/../../../tmp/malicious`).

> [!WARNING]
> Only allowlist the specific executables your deployment requires. Avoid broad allowlists like `/usr/bin/*` — explicitly name each trusted binary.

**Secure configuration example:**

```properties
# Allow only specific, vetted executables
wanaku.service.exec.allowed-executables=/usr/bin/python3,/usr/local/bin/jq,/opt/myapp/scripts/data-export.sh
```

**Insecure configuration (do not use):**

```properties
# DANGEROUS: Uses relative paths and broad allowlist
wanaku.service.exec.allowed-executables=python3,bash,sh
```

#### HTTP Listener

| Property | Description |
|------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `quarkus.http.host-enabled` | `true` - Enables the HTTP server for downstream MCP servers. |
| `quarkus.http.port` | The HTTP port for the MCP server (e.g., `9000` for `http` service). |
| `quarkus.http.host` | `0.0.0.0` - Binds the HTTP server to all available network interfaces. |

### Common Service Registration Settings

These `wanaku.service.registration.*` properties are available for all downstream MCP servers to manage their discovery and lifecycle.

| Property                                         | Description                                                                          |
|--------------------------------------------------|--------------------------------------------------------------------------------------|
| `wanaku.service.registration.enabled`            | `true` - Enables the service registration feature.                                   |
| `wanaku.service.registration.uri`                | The URI of the router backend for registration (e.g., `http://localhost:8080`).      |
| `wanaku.service.registration.interval`           | `10s` - The interval at which the service should ping the router to show it's alive. |
| `wanaku.service.registration.retries`            | `3` - Number of times to retry a failed registration.                                |
| `wanaku.service.registration.retry-wait-seconds` | `1` - Seconds to wait before retrying a failed registration.                         |
| `wanaku.service.registration.delay-seconds`      | `3` - Seconds to delay the initial registration after startup.                       |
| `wanaku.service.registration.announce-address`   | A custom address to announce to the router, overriding the auto-detected one.        |

### Secret Encryption

Secrets can be encrypted at rest using AES-256. Set both environment variables to enable:

| Environment Variable                 | Description                 |
|--------------------------------------|-----------------------------|
| `WANAKU_SECRETS_ENCRYPTION_PASSWORD` | Password for key derivation |
| `WANAKU_SECRETS_ENCRYPTION_SALT`     | Salt for key derivation     |

When both are set, secrets are automatically encrypted when written and decrypted when read.

## 3. CLI

Configuration for the Wanaku command-line interface (`wanaku-cli`).

| Property                         | Description                                                                                           |
|----------------------------------|-------------------------------------------------------------------------------------------------------|
| `wanaku.cli.tool.create-cmd`     | The full Maven command to execute when creating a new tool service via `wanaku tool create`.          |
| `wanaku.cli.resource.create-cmd` | The full Maven command to execute when creating a new resource provider via `wanaku resource create`. |
| `wanaku.cli.mcp.create-cmd`      | The full Maven command to execute when creating a new MCP server via `wanaku mcp create`.             |

## 4. Testing

Properties primarily used when running tests.

| Property                        | Description                                                                                                                                      |
|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `keycloak.docker.image`         | Overrides the default Keycloak Docker image used for tests. This is set via a system property in the `pom.xml`, not in `application.properties`. |
| `%test.quarkus.log.file.enable` | `true` - Enables logging to a file during tests.                                                                                                 |
| `%test.quarkus.log.file.path`   | `target/wanaku.log` - The path to the log file for test runs.                                                                                    |

## Global Concepts

### Quarkus Profiles

Quarkus uses profiles to manage environment-specific configurations. You will see properties prefixed with `%dev`, `%test`, or other custom profiles. These properties are only active when that profile is enabled.

- **`%dev`**: Used when running in development mode (`quarkus dev`).
- **`%test`**: Used when running automated tests.
- **`%prod`**: Used for production deployments (default when no profile is specified).

### Environment Variables

Most properties can be set via environment variables by converting the property name:

1. Convert to uppercase
2. Replace dots (`.`) with underscores (`_`)
3. Replace hyphens (`-`) with underscores (`_`)

**Example:**

```properties
quarkus.http.port=8080
```

Becomes:

```shell
QUARKUS_HTTP_PORT=8080
```

## Configuration Examples

### Example: Router Backend

```properties
# application.properties for router backend
quarkus.http.port=8080
quarkus.http.cors.enabled=true
quarkus.http.cors.origins=http://localhost:3000,https://my-frontend.example.com

wanaku.persistence.infinispan.base-folder=/var/lib/wanaku/data
wanaku.infinispan.max-state-count=20
```

### Example: Tool Service

```properties
# application.properties for a tool service
quarkus.http.host-enabled=true
quarkus.http.port=9010
quarkus.http.host=0.0.0.0

wanaku.service.name=my-custom-tool
wanaku.service.base-uri=custom://

wanaku.service.registration.enabled=true
wanaku.service.registration.uri=http://wanaku-router:8080
wanaku.service.registration.interval=15s
wanaku.service.registration.announce-address=my-custom-tool.example.com:9010

quarkus.oidc-client.auth-server-url=https://keycloak.example.com/realms/wanaku
quarkus.oidc-client.client-id=wanaku-service
quarkus.oidc-client.credentials.secret=${WANAKU_SERVICE_SECRET}
```

> The realm name defaults to `wanaku` and can be configured via the `AUTH_REALM` environment variable or the `auth.realm` property.

### Example: Enabling Secret Encryption

```shell
export WANAKU_SECRETS_ENCRYPTION_PASSWORD="your-strong-password"
export WANAKU_SECRETS_ENCRYPTION_SALT="unique-salt-value"
```

## Additional Resources

- [Quarkus Configuration Guide](https://quarkus.io/guides/config) - Comprehensive Quarkus configuration documentation
- [Keycloak Documentation](https://www.keycloak.org/documentation) - OIDC and authentication setup
- [Infinispan Configuration](https://infinispan.org/docs/stable/titles/configuring/configuring.html) - Persistence layer configuration
