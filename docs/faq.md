# Frequently Asked Questions (FAQ)

## General Questions

### What is Wanaku?

Wanaku is an MCP (Model Context Protocol) Router that acts as a centralized hub for managing and governing how AI agents access tools and resources. It doesn't host tools or resources directly but instead routes requests to MCP servers providing unified access, security, and resource management for AI-enabled applications.

### What does "Wanaku" mean?

The project name comes from the origins of the word "Guanaco," a camelid native to South America. The connection to Apache Camel (the integration framework used by Wanaku) makes this name particularly fitting.

### What is the Model Context Protocol (MCP)?

The Model Context Protocol (MCP) is an open protocol that standardizes how applications provide context to Large Language Models (LLMs). It defines how tools, resources, and prompts are exposed and accessed by AI agents. Learn more at [modelcontextprotocol.io](https://modelcontextprotocol.io/).

### Is Wanaku open source?

Yes, Wanaku is open source and licensed under Apache 2.0. You can find the source code at [github.com/wanaku-ai/wanaku](https://github.com/wanaku-ai/wanaku).

### What's the difference between tools and resources in Wanaku?

- **Tools** operate in a request/reply mode, performing processing on input (e.g., making HTTP requests, executing commands, querying databases)
- **Resources** provide read access to data without necessarily processing input (e.g., reading files, accessing S3 objects, reading Kafka topics)

## Architecture and Design

### Why does Wanaku use a router architecture instead of hosting tools directly?

The router architecture provides several advantages:

- **Isolation**: Each MCP service runs independently, improving security and reliability
- **Scalability**: Services can be scaled independently based on demand
- **Flexibility**: Easy to add, remove, or update MCP servers without affecting the router
- **Language independence**: MCP services can be written in any supported language

### What is the role of Keycloak in Wanaku?

Keycloak provides authentication and authorization for:

- Router management API and web UI access
- Service-to-service authentication between the router and downstream MCP servers
- Future support for fine-grained access control to tools and resources

## Installation and Setup

### What are the prerequisites for running Wanaku?

**For development:**

- Java 21 or later
- Maven 3.x
- Keycloak instance (optional — can run via Podman/Docker, or set `wanaku.http.auth=none`)

**For production deployment:**

- OpenShift or Kubernetes cluster (optional but recommended)
- Keycloak instance (recommended for production; optional with `wanaku.http.auth=none`)
- Container runtime (Podman/Docker)

### Do I need to install Keycloak separately?

Keycloak is required only if you want to run Wanaku with authentication enabled. You can:

- Run Keycloak locally using Podman/Docker (for development)
- Deploy Keycloak to OpenShift/Kubernetes (for production)
- Use an existing Keycloak instance

If you don't need authentication (e.g., for local development or testing), you can set `wanaku.http.auth=none`
and skip the Keycloak setup entirely. See [Running Without Authentication](usage.md#running-without-authentication).

### Can I run Wanaku without Kubernetes?

Yes, Wanaku can run standalone on any machine with Java. You can:

- Download pre-built binaries from the releases page
- Build from source
- Run locally for development and testing

Kubernetes/OpenShift deployment is recommended for production but not required.

### How do I install the Wanaku CLI?

See the [Installing the CLI](usage.md#installing-the-command-line-interface-cli) section in the usage guide for all installation methods and PATH configuration.

## Usage and Configuration

### How do I add tools and resources to Wanaku?

Tools and resources are managed through MCP servers added to the router. Register an MCP server with the router and its tools and resources become available automatically.

See the [Usage Guide](usage.md#managing-mcp-tools) for detailed instructions.

### What are namespaces and when should I use them?

Namespaces allow you to organize tools and resources into isolated groups. Use cases include:

- Separating tools by environment (dev, staging, prod)
- Organizing by team or project
- Isolating tools by security level
- Providing different tool sets to different AI agents

Wanaku provides 10 namespaces (ns-1 through ns-10) plus a default namespace and a public one.

### How do I connect an MCP client to Wanaku?

**For SSE transport:**

```text
http://localhost:8080/mcp/sse
```

**For Streamable HTTP:**

```text
http://localhost:8080/mcp/
```

**For a specific namespace:**

```text
http://localhost:8080/ns-1/mcp/sse
```

> The `wanaku mcp` CLI commands only support Streamable HTTP, so pass the `/mcp/` endpoint to `--uri`
> (for example, `http://localhost:8080/ns-1/mcp/`).

See [Supported/Tested Clients](usage.md#supportedtested-clients) for client-specific configuration.

### Can I use Wanaku with Claude Desktop?

Yes, Wanaku works with Claude Desktop and other MCP-compatible clients. See the [Usage Guide](usage.md#claude) for configuration examples.

## Security

### Is Wanaku secure for production use?

Wanaku provides security features including:

- OIDC-based authentication via Keycloak
- Service-to-service authentication
- TLS support for external endpoints
- Network isolation via Kubernetes

However, you must properly configure these features. See the [Security Guide](../SECURITY.md) for best practices.

### How do I secure API keys and secrets used by tools?

- Store secrets in Kubernetes Secrets
- Configure tools to reference secrets via environment variables
- Never commit secrets to version control

### Does Wanaku support fine-grained access control?

Currently, all authenticated users have admin access to tools and resources. Fine-grained access control is planned for future versions.

### Can I disable authentication for development?

While not recommended for production, you can set `wanaku.http.auth=none` to disable authentication for development and testing.

### How to skip certificate validation for development purposes

- Wanaku CLI: you can set the `--insecure` parameter to trust the server certificates.
- Wanaku MCP server: set the environment variable `QUARKUS_TLS_TRUST_ALL=true`.

## Troubleshooting

### Why can't my MCP client connect to Wanaku?

Check:

1. Router is running and accessible
2. Correct endpoint URL (include `/sse` for SSE transport)
3. Firewall rules allow traffic
4. CORS is properly configured (for web clients)

### How do I enable debug logging?

Add to `application.properties`:

```properties
quarkus.log.level=DEBUG
quarkus.log.category."ai.wanaku".level=DEBUG
quarkus.mcp.server.traffic-logging.enabled=true
```

When using the Wanaku CLI, set the `--verbose` parameter to show additional logging.

### Where can I get help?

- Check the [Troubleshooting Guide](usage.md#troubleshooting)
- Search [GitHub Issues](https://github.com/wanaku-ai/wanaku/issues)
- Ask in [GitHub Discussions](https://github.com/wanaku-ai/wanaku/discussions)
- Review the full [documentation](https://github.com/wanaku-ai/wanaku/tree/main/docs)

## Performance and Scaling

### What are the resource requirements for Wanaku?

**Minimum for development:**

- Router backend: 512MB RAM, 1 CPU

**Recommended for production:**

- Router backend: 1-2GB RAM, 2 CPU

### Can Wanaku handle multiple concurrent requests?

Yes, Wanaku is built on Quarkus and designed for concurrent request handling. Performance depends on:

- Available system resources
- Number and type of MCP services
- Network latency between components

### Can I scale Wanaku horizontally?

The router backend can be scaled horizontally in Kubernetes.

## Compatibility

### Which MCP clients are supported?

Any MCP client compliant with the MCP protocol is supported.

Wanaku was tested with different agents frameworks and MCP clients that include:

- Claude Desktop
- Langflow
- LangChain4j MCP client
- HyperChat
- LibreChat
- Witsy
- Embedded LLMChat
- And many others

Any MCP-compliant client should work with Wanaku.

### What MCP protocol versions does Wanaku support?

Wanaku supports the current stable MCP protocol specification. Check the releases page for version compatibility information.

### Can Wanaku act as a bridge to other MCP servers?

Yes! Wanaku includes an MCP-to-MCP bridge feature that allows it to forward requests to other MCP servers using HTTP transport, effectively aggregating multiple MCP servers behind a single endpoint.

## Miscellaneous

### What's the difference between Wanaku and running MCP servers directly?

Benefits of using Wanaku:

- Centralized management and governance
- Unified authentication and authorization
- Tool and resource organization via namespaces
- Service discovery and health monitoring
- Ability to aggregate multiple MCP servers
- Consistent interface regardless of backend services

### Is there a web UI for Wanaku?

Yes, Wanaku includes a React-based web UI for managing the router, accessible by default at `http://localhost:8080`. However, some features are currently only available via the CLI.

### How often is Wanaku updated?

Check the [releases page](https://github.com/wanaku-ai/wanaku/releases) for the latest versions and release notes. The project follows semantic versioning.

### How can I contribute to Wanaku?

See the [Contributing Guide](../CONTRIBUTING.md) for information on:

- Setting up your development environment
- Submitting pull requests
- Reporting issues

### Where can I find examples and tutorials?

- [Getting Started Video](https://www.youtube.com/watch?v=-fuNAo2j4SA)
- [Wanaku Examples Repository](https://github.com/wanaku-ai/wanaku-examples)
- [Wanaku Demos](https://github.com/wanaku-ai/wanaku-demos)
- Project documentation in the `docs/` directory
