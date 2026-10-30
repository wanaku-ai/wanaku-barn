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
- Keycloak instance (optional — only needed by the Governed Execution Proxy; the Barn backend runs without it)

**For production deployment:**

- OpenShift or Kubernetes cluster (optional but recommended)
- Keycloak instance (recommended for production, used by the Governed Execution Proxy)
- Container runtime (Podman/Docker)

### Do I need to install Keycloak separately?

Keycloak is required only if you want to run Wanaku with authentication enabled. You can:

- Run Keycloak locally using Podman/Docker (for development)
- Deploy Keycloak to OpenShift/Kubernetes (for production)
- Use an existing Keycloak instance

The Barn backend itself does not require Keycloak: authentication is handled by the Governed Execution Proxy (GEP),
so you can skip the Keycloak setup for local development or testing. See [Run the backend without authentication](install.md#run-the-backend-without-authentication).

### Can I run Wanaku without Kubernetes?

Yes, Wanaku can run standalone on any machine with Java. You can:

- Download pre-built binaries from the releases page
- Build from source
- Run locally for development and testing

Kubernetes/OpenShift deployment is recommended for production but not required.

### How do I install the Wanaku CLI?

See [Install the Wanaku CLI](../cli/install.md) for all installation methods and PATH configuration.

## Usage and Configuration

### How do I add tools and resources to Wanaku?

Tools and resources are managed through MCP servers added to the router. Register an MCP server with the router and its tools and resources become available automatically.

See [Inspect tools, resources, and prompts](../cli/capabilities.md) for detailed instructions.

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

### Can I use Wanaku with Claude Desktop?

Yes, Wanaku works with Claude Desktop and other MCP-compatible clients.

## Security

### Is Wanaku secure for production use?

Wanaku provides security features including:

- OIDC-based authentication via Keycloak
- Service-to-service authentication
- TLS support for external endpoints
- Network isolation via Kubernetes

However, you must properly configure these features. See the [Security Guide](https://github.com/wanaku-ai/wanaku-barn/blob/main/SECURITY.md) for best practices.

### How do I secure API keys and secrets used by tools?

- Store secrets in Kubernetes Secrets
- Configure tools to reference secrets via environment variables
- Never commit secrets to version control

### Does Wanaku support fine-grained access control?

Currently, all authenticated users have admin access to tools and resources. Fine-grained access control is planned for future versions.

### Can I disable authentication for development?

The Barn backend has no authentication of its own, so no flag is needed. Authentication is enforced by the Governed Execution Proxy (GEP); for development you can run the backend without it.

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

- Check the [common problems](#common-problems)
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
- Ability to aggregate multiple MCP servers
- Consistent interface regardless of backend services

### Is there a web UI for Wanaku?

Yes, Wanaku includes a React-based web UI for managing the router, accessible by default at `http://localhost:8080`. However, some features are currently only available via the CLI.

### How often is Wanaku updated?

Check the [releases page](https://github.com/wanaku-ai/wanaku/releases) for the latest versions and release notes. The project follows semantic versioning.

### How can I contribute to Wanaku?

See the [Contributing Guide](https://github.com/wanaku-ai/wanaku-barn/blob/main/CONTRIBUTING.md) for information on:

- Setting up your development environment
- Submitting pull requests
- Reporting issues

### Where can I find examples and tutorials?

- [Getting Started Video](https://www.youtube.com/watch?v=-fuNAo2j4SA)
- [Wanaku Examples Repository](https://github.com/wanaku-ai/wanaku-examples)
- [Wanaku Demos](https://github.com/wanaku-ai/wanaku-demos)
- Project documentation in the `docs/` directory

## Common problems

> [!TIP]
> If you are setting up Wanaku for the first time, see the [First-Run Troubleshooting Guide](../operate/first-run.md) for the most common setup issues (authentication, MCP connections, Docker Compose, SDK, and deployment).

This section provides solutions to common issues you may encounter while using Wanaku.

### Authentication Issues

#### Cannot authenticate with the router

**Symptoms:**

- CLI commands fail with authentication errors
- Web UI redirects to Keycloak but login fails

**Solutions:**

1. Verify Keycloak is running and accessible:

   ```shell
   curl http://localhost:8543/health
   ```

2. Check that the Keycloak realm is properly configured:
   - Ensure the `wanaku` realm exists
   - Verify the `wanaku-mcp-router` client is configured
   - Confirm user accounts have been created

3. Clear stored credentials and re-authenticate against Keycloak with the `wanaku-mcp-router` client:

   ```shell
   wanaku auth logout
   WANAKU_CLIENT_SECRET=<secret> wanaku auth login \
     --auth-server http://localhost:8543 \
     --username alice \
     --password
   ```

4. Verify oauth2-proxy can reach Keycloak:
   - Check the `OAUTH2_PROXY_OIDC_ISSUER_URL` (and, when discovery is skipped, the redeem and JWKS URLs)
   - Ensure network connectivity between components

#### Token expired errors

**Symptoms:**

- Commands work initially but fail after some time
- Error messages about expired tokens

**Solutions:**

1. The CLI refreshes access tokens automatically. When the refresh token has also expired, log in again:

   ```shell
   WANAKU_CLIENT_SECRET=<secret> wanaku auth login \
     --username alice \
     --password
   ```

2. Check token lifetime settings in Keycloak if tokens expire too quickly

### Connection Issues

#### Cannot connect to the router from MCP clients

**Symptoms:**

- MCP clients fail to connect
- Timeout errors when connecting

**Solutions:**

1. Verify the router is running and accessible:

   ```shell
   curl http://localhost:8080/q/health
   ```

2. Check the correct MCP endpoint is being used:
   - SSE transport: `http://localhost:8080/mcp/sse`
   - Streamable HTTP: `http://localhost:8080/mcp/`
   - The `wanaku mcp` CLI commands require the Streamable HTTP endpoint (`/mcp/`, not `/mcp/sse`)

3. For namespace-specific connections, ensure the correct path:

   ```shell
   # For namespace ns-1
   http://localhost:8080/ns-1/mcp/sse
   ```

4. Verify firewall rules allow traffic on port 8080

5. Check CORS settings if connecting from a web application:

   ```shell
   quarkus.http.cors.enabled=true
   quarkus.http.cors.origins=http://localhost:3000
   ```

#### `wanaku mcp` commands report a redirect

**Symptoms:**

- `wanaku mcp tool list` (or any other `wanaku mcp` command) fails with `The MCP server responded with a redirect (HTTP 302) to ...`

The `wanaku mcp` commands do not follow redirects, because MCP requests are `POST` requests and following them can send the
bearer token to a different host. When the server redirects, the CLI shows the redirect target, when it can read it, and a hint:

- `The server redirects to HTTPS. Use --uri https://...`: the endpoint forces HTTPS. This is the case for OpenShift Routes
  created by the operator, and for most Ingress controllers and load balancers. Run the command again with the `https://` URI
  that the message shows.
- `The server asks for a browser login, which the CLI cannot do`: the redirect goes to a login page (for example, the
  oauth2-proxy `/oauth2/start` page or the Keycloak login page). Check that you pass a token with `--token`, that the token is
  not expired, and that the server accepts its audience.
- `The CLI does not follow redirects for MCP requests. Try --uri ...`: the server redirects to another URL. Check that the URL
  is the MCP endpoint you want and use it with `--uri`.
- `The CLI does not follow redirects for MCP requests. Possible causes:`: the CLI could not read the redirect target. Try the
  `https://` URI, then check the token.

When you pass `--token` with an `http://` URI, the `wanaku mcp` commands also show a warning that the token is sent over plain
HTTP. The warning is not shown for `localhost` and other loopback addresses.

### Tool and Resource Issues

#### Tools or resources not appearing in MCP clients

**Symptoms:**

- `wanaku tools list` shows tools, but they don't appear in the MCP client
- Resources are registered but not accessible

**Solutions:**

1. Verify the tool/resource is in the correct namespace:

   ```shell
   wanaku tools list
   wanaku namespaces list
   ```

2. Check if the client is connected to the correct namespace endpoint

3. Refresh the MCP client connection

4. Verify the downstream MCP server providing the tool is online by checking the admin UI

#### Tool invocation fails

**Symptoms:**

- Tool appears in client but execution fails
- Error messages when calling a tool

**Solutions:**

1. Check the tool URI is correct:

   ```shell
   wanaku tools list
   ```

2. Verify the downstream MCP server is running and healthy

3. Review service logs for errors during tool execution

4. Ensure required configuration or secrets are properly set:

   ```shell
   wanaku tools list
   ```

5. For HTTP tools, verify the target endpoint is accessible from the service

#### Resource read fails

**Symptoms:**

- Resource appears but cannot be read
- Empty or error responses when accessing resources

**Solutions:**

1. Verify the resource URI and that the target exists:

   ```shell
   wanaku resources list
   ```

2. Check file permissions if using file-based resources

3. Verify network access if using remote resources (S3, FTP, etc.)

4. Review provider service logs for errors

### Build and Deployment Issues

#### Build fails with missing dependencies

**Symptoms:**

- Maven build errors
- Missing artifact errors

**Solutions:**

1. Ensure you're using the correct Maven version:

   ```shell
   mvn --version  # Should be 3.x
   ```

2. Clear Maven cache and rebuild:

   ```shell
   rm -rf ~/.m2/repository/ai/wanaku
   mvn clean install
   ```

3. Verify internet connectivity for downloading dependencies

#### Native build fails

**Symptoms:**

- Native compilation errors
- GraalVM-related failures

**Solutions:**

1. Verify GraalVM is properly installed:

   ```shell
   java -version  # Should show GraalVM
   native-image --version
   ```

2. Check the [Quarkus native build guide](https://quarkus.io/guides/building-native-image) for system requirements

3. Try building without native mode first to isolate the issue:

   ```shell
   mvn clean package
   ```

#### Container deployment fails

**Symptoms:**

- Pods in CrashLoopBackOff state
- ImagePullBackOff errors

**Solutions:**

1. Verify the container image exists and is accessible:

   ```shell
   podman pull quay.io/wanaku/wanaku-barn-backend:latest
   ```

2. Check pod logs for startup errors:

   ```shell
   kubectl logs <pod-name>
   ```

3. Verify ConfigMaps and Secrets are properly mounted:

   ```shell
   kubectl describe pod <pod-name>
   ```

4. Check resource limits and ensure sufficient memory/CPU

5. Verify Keycloak is accessible from the pods:

   ```shell
   kubectl exec <pod-name> -- curl http://keycloak:8080/health
   ```

### Performance Issues

#### Slow response times

**Symptoms:**

- Tools take a long time to execute
- Resource reads are slow
- MCP clients experience timeouts

**Solutions:**

1. Check router and service resource usage:

   ```shell
   top
   htop
   ```

2. Review logs for errors or warnings

3. Verify network latency between components:

   ```shell
   ping <service-host>
   ```

4. For Kubernetes deployments, ensure adequate resource limits:

   ```yaml
   resources:
     requests:
       memory: "512Mi"
       cpu: "500m"
     limits:
       memory: "1Gi"
       cpu: "1000m"
   ```

### Logging and Debugging

#### Enable debug logging

To get more detailed logs for troubleshooting:

**Router backend:**

```properties
quarkus.log.level=DEBUG
quarkus.log.category."ai.wanaku".level=DEBUG
quarkus.mcp.server.traffic-logging.enabled=true
```

**Downstream MCP servers:**

```properties
quarkus.log.level=DEBUG
quarkus.log.category."ai.wanaku".level=DEBUG
```

**CLI:**

```shell
wanaku --verbose tools list
```

To produce clean, parsable output without ANSI colors or escape sequences (useful for scripting and piping):

```shell
wanaku tools list --plain
```

#### Access logs

Check logs in these locations:

- **Router backend:** Look for `wanaku-barn-backend.log` or check container logs
- **Downstream MCP servers:** Check individual service log files
- **Kubernetes:** `kubectl logs <pod-name>`

### Getting Help

If you continue to experience issues:

1. Check the [GitHub Issues](https://github.com/wanaku-ai/wanaku/issues) for similar problems
2. Review the [documentation](https://github.com/wanaku-ai/wanaku/tree/main/docs)
3. Join the [community discussions](https://github.com/wanaku-ai/wanaku/discussions)
4. Open a new issue with:
   - Wanaku version
   - Deployment environment (local, OpenShift, etc.)
   - Steps to reproduce
   - Relevant log excerpts
   - Configuration (with secrets redacted)
