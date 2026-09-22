# Testing MCP Servers

This guide covers how to test your Wanaku MCP servers locally before deploying, how to mock the Wanaku router for unit tests, integration test patterns, and how to validate service catalog deployments.

## What You Will Learn

- How to test MCP servers locally using the Wanaku CLI and local router
- How to mock the Wanaku router for unit testing your tools
- Integration test patterns for verifying MCP servers end-to-end
- How to validate service catalog deployments

## What You Will Need

- **Wanaku CLI** installed (download from [releases page](https://github.com/wanaku-ai/wanaku/releases/tag/v0.2.0))
- **Java 21 or later**
- **Maven 3.9+** (for running integration tests)
- **Docker** or **Podman** (for Testcontainers-based tests)
- Completed [Getting Started with Wanaku](../1.01-your-first-tool/README.md) (demo 1.01)
- Completed [Introduction to MCP Servers](../2.01-introduction-to-capabilities/README.md) (demo 2.01)

## Part 1: Testing Tools Locally Before Deploying

Before deploying an MCP server to the Wanaku router, you can test it locally using the Wanaku CLI and a local router instance.

### Starting a Local Wanaku Instance

```shell
docker compose -f deploy/docker-compose/docker-compose-noauth.yml up
```

This starts the router with authentication disabled. The router is available at `http://localhost:8080`.
See [Running Without Authentication](usage.md#running-without-authentication) for other ways to start the router
without Keycloak.

### Testing with the MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is an interactive tool for testing MCP servers:

```shell
npx @modelcontextprotocol/inspector
```

1. In the inspector, enter `http://localhost:8080/mcp/sse` as the server URL
2. Click **Connect**
3. You can now browse available tools and invoke them with test inputs

### Testing HTTP Tools Locally

For HTTP-based tools, you can also test the underlying endpoint directly with `curl`:

```shell
curl -X GET "https://api.example.com/endpoint?param=value"
```

This lets you verify the external API works before wiring it through Wanaku.

### Verifying Tool Registration

After importing or deploying tools, verify they are registered:

```shell
wanaku tools list
```

Expected output:

```text
name namespace type uri labels
free-currency-conversion-tool default http https://economia.awesomeapi.com.br/last/{parameter.value('fromCurrency')}-{parameter.value('toCurrency')} {}
```

## Part 2: Mocking the Wanaku Router for Unit Tests

When writing unit tests for your MCP server code, you don't need a running Wanaku router. Instead, use the exchange stubs directly and test your tool logic in isolation.

### Setting Up Test Dependencies

Projects using the Wanaku Capabilities Java SDK already have the exchange classes (`ToolInvokeRequest`, `ToolInvokeReply`, and the other classes under `ai.wanaku.core.exchange.v1`) on the classpath. You only need JUnit 5 with test scope, if your project does not already declare it:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

### Mocking Tool Invocations

Create a test that invokes your tool implementation directly. With Camel 4.22+, MCP servers use the native MCP server support, so tests use standard MCP types:

```java
package ai.test;

import io.quarkiverse.mcp.server.ToolArg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppToolTest {

    @Test
    void shouldInvokeToolAndReturnResponse() {
        AppTool tool = new AppTool();

        // Build arguments matching your tool's @ToolArg parameters
        String result = tool.invoke("test input");

        assertNotNull(result);
        assertEquals("expected output", result);
    }
}
```

> [!NOTE]
> The exact test pattern depends on how your tool is implemented. Tools using the Quarkus MCP Server extension
> are plain CDI beans annotated with `@Tool`, making them straightforward to unit test.

### Testing with Mocked Dependencies

If your tool calls external services, mock those dependencies:

```java
@ExtendWith(MockitoExtension.class)
class MyToolTest {

    @Mock
    ExternalApiClient apiClient;

    @InjectMocks
    MyTool tool;

    @Test
    void shouldReturnApiResponse() throws InterruptedException {
        when(apiClient.fetchData("test")).thenReturn("api result");

        // invoke tool and verify
    }
}
```

## Part 3: Integration Test Patterns

For end-to-end testing, the [wanaku-tests](https://github.com/wanaku-ai/wanaku-tests) project provides a comprehensive integration test framework.

### Running the Integration Test Suite

The integration tests require Wanaku artifacts (router, HTTP MCP server, CLI). Download them from the [`wanaku-tests`](https://github.com/wanaku-ai/wanaku-tests) repository:

```shell
cd <WANAKU_TESTS_REPO_PATH>
./artifacts/download.sh
```

Run all tests, replacing placeholders with values for your environment (CLI version should match your Wanaku release, e.g. `v0.2.0`):

```shell
mvn clean install -Dwanaku.test.cli.path=<wanaku-tests-repo-path>/artifacts/wanaku-cli-<wanaku-cli-version>/quarkus-run.jar
```

### Test Categories

| Module | Description |
|--------|-------------|
| `http-capability-tests` | HTTP tool registration, invocation via REST API and CLI |
| `resources-tests` | File resource management via REST API, MCP, and CLI |
| `camel-integration-capability-tests` | Camel tools, file resources, PostgreSQL, multi-instance |
| `cross-capability-tests` | Router restart and mixed-server scenarios |

### Writing Custom Integration Tests

Extend `BaseIntegrationTest` and use the provided clients:

```java
public class MyCustomITCase extends BaseIntegrationTest {

    @Test
    void shouldTestMyMcpServer() {
        // Use routerClient to interact with router REST API
        // Use mcpClient to invoke tools via MCP protocol
        // Use cliExecutor to run wanaku CLI commands
    }
}
```

Key test infrastructure components:

- **RouterClient** — REST API calls to the Wanaku router
- **McpTestClient** — MCP protocol communication for tool invocation
- **CLIExecutor** — Programmatic CLI command execution
- **CamelCapabilityManager** — Lifecycle management for the [Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability)
- **KeycloakManager** — Authentication setup (when needed)

### Test Fixtures

Test configurations are defined in YAML fixtures under `src/test/resources/fixtures/`:

```text
fixtures/
├── simple-tool/
│   ├── routes.camel.yaml      # Camel route definitions
│   └── rules.yaml             # MCP tool rules
├── file-resource/
│   ├── routes.camel.yaml
│   └── rules.yaml
└── postgres-tool/
    ├── routes.camel.yaml
    ├── rules.yaml
    ├── dependencies.txt
    └── seed.sql               # Database initialization
```

Use `TestFixtures.load()` to load and substitute variables:

```java
String config = TestFixtures.load("simple-tool/routes.camel.yaml")
    .replace("${ROUTE_ID}", "my-route")
    .toString();
```

## Part 4: Validating Service Catalog Deployment

After deploying a service catalog, validate that it works correctly.

### Step 1: Verify Deployment via CLI

```shell
wanaku service catalog list
```

You should see your catalog listed:

```text
Available service catalogs:
name         description
demo-catalog Demo catalog with book tools
```

### Step 2: Verify Tools Are Registered

```shell
wanaku tools list
```

Tools from your catalog should appear:

```text
name namespace type uri labels
get-book-by-isbn default books books://get-book-by-isbn {}
search-books default books books://search-books {}
```

### Step 3: Verify via Admin UI

Open the Service Catalog page at `http://localhost:8080/admin/#/service-catalog`. Your catalog should show the correct number of services and tools.

### Step 4: Test Tool Invocation

Use the MCP Inspector or an MCP client to invoke each tool:

```json
{
  "wanaku_body": "9781935182368"
}
```

Verify the response contains expected data.

### Step 5: Check MCP Server Logs

If tools don't work, check the MCP server logs:

```shell
# If running via Docker Compose, check the container logs
# For standalone MCP servers:
java -jar camel-integration-capability-*.jar --help
```

Look for:

- Registration success messages
- Server startup on the configured port
- Tool invocation requests and responses

### Step 6: Validate Rules File

Inspect the generated `.wanaku-rules.yaml` to ensure tools are correctly mapped.

> [!NOTE]
> The `mcp.tools` section uses a **list of single-key maps**, where each list item's key is the tool's name. This is the format produced by the Wanaku CLI and consumed by the router.

```yaml
mcp:
  tools:
    - get-book-by-isbn:
        route:
          id: "get-book-by-isbn"
        description: "Invoke route get-book-by-isbn in books"
        properties:
          - name: wanaku_body
            type: string
            description: The ISBN to look up
            required: true
```

Verify:

- Route IDs match your Camel route definitions
- Input schemas match expected parameters
- Descriptions are meaningful

## Common Testing Scenarios

The examples below are pseudocode sketches, not compilable tests. Helper methods such as `assertErrorResponse`, `invokeTool`, and `executor` stand in for your own test utilities — adapt them to the invocation pattern shown in Part 2.

### Testing Tool Input Validation

```java
@Test
void shouldRejectInvalidInput() throws InterruptedException {
    ToolInvokeRequest request = ToolInvokeRequest.newBuilder()
        .putArguments("wanaku_body", "")  // Empty input
        .build();

    // Invoke and verify error response
    assertErrorResponse(response);
}
```

### Testing Error Handling

```java
@Test
void shouldHandleExternalApiFailure() throws InterruptedException {
    // Mock external API to return 500
    // Invoke tool
    // Verify graceful error handling
}
```

### Testing Multiple Tool Invocations

```java
@Test
void shouldHandleConcurrentInvocations() throws InterruptedException {
    int concurrent = 10;
    CountDownLatch latch = new CountDownLatch(concurrent);

    for (int i = 0; i < concurrent; i++) {
        executor.submit(() -> {
            invokeTool("input-" + i);
            latch.countDown();
        });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
}
```

## Troubleshooting

### Tools Not Appearing After Deployment

- Refresh the Admin UI (hard refresh: Ctrl+Shift+R)
- Check the Wanaku router logs
- Verify the service catalog was deployed: `wanaku service catalog list`

### Camel Route Fails with "Component Not Found"

- Verify dependencies are correctly listed in `dependencies.txt`
- Check that Maven coordinates are valid and versions exist
- Ensure the [Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability) downloaded dependencies

### Integration Tests Fail to Start

- Ensure Docker is running (for Keycloak Testcontainers)
- Verify artifacts are in the correct location: `artifacts/wanaku-barn-backend-*/quarkus-run.jar`
- Kill orphan processes: `pkill -f quarkus-run.jar`

### MCP Inspector Cannot Connect

- Verify router is running: `curl http://localhost:8080/health`
- Check the correct MCP endpoint: `http://localhost:8080/mcp/sse` (SSE) or `http://localhost:8080/mcp/` (Streamable HTTP)
- Ensure no firewall blocks the connection

If you find a bug, please [report it](https://github.com/wanaku-ai/wanaku/issues).
To get in touch with the community, visit the [Wanaku project](https://github.com/wanaku-ai/wanaku).
