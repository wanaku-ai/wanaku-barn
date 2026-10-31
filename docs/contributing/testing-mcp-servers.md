---
title: Testing MCP servers
description: "Test an MCP server before you add it to Wanaku: run it locally, write unit and integration tests, and validate service catalog deployments."
---

# Testing MCP servers

This guide shows how to test an MCP server before and after you add it to Wanaku. It covers manual tests with the MCP Inspector, unit tests for the tool code, the Wanaku integration test suite, and the checks for a service catalog deployment.

## Prerequisites

- A running Wanaku instance. See the [Wanaku quickstart](https://wanaku.ai/docs/next/get-started/quickstart).
- The [Wanaku CLI](../cli/install.md).
- Java 21 or later and Maven 3.9 or later, for the unit and integration tests.
- Docker or Podman, for tests that use Testcontainers.

## Test the server locally

Wanaku reaches an MCP server through an MCP forward. Test the server directly first, then test it through Wanaku.

1. Start the [MCP Inspector](https://github.com/modelcontextprotocol/inspector):

   ```shell
   npx @modelcontextprotocol/inspector
   ```

2. Connect the inspector to the MCP endpoint of your server, for example `http://localhost:9090/mcp`, with the Streamable HTTP transport. List the tools and call each one with test inputs.

3. Add the server to Wanaku as a forward:

   ```shell
   wanaku forwards add --name my-server --service http://localhost:9090/mcp --namespace default
   ```

4. Make sure that Wanaku lists the tools of the server:

   ```shell
   wanaku tools list
   ```

5. Connect the inspector to the Wanaku MCP endpoint of the namespace, `http://localhost:8081/default/mcp`, and call the tools again.

For more about forwards, see [Forward MCP servers](../cli/forwards.md).

## Write unit tests

Unit tests do not need Wanaku. Test the tool code of your server directly, and mock the services that it calls:

```java
@ExtendWith(MockitoExtension.class)
class MyToolTest {

    @Mock
    ExternalApiClient apiClient;

    @InjectMocks
    MyTool tool;

    @Test
    void shouldReturnApiResponse() {
        when(apiClient.fetchData("test")).thenReturn("api result");

        assertEquals("api result", tool.invoke("test"));
    }
}
```

For a Camel route, use the [Camel test support](https://camel.apache.org/manual/testing.html) to send messages to the route and check the result.

## Run the integration tests

The [wanaku-tests](https://github.com/wanaku-ai/wanaku-tests) repository has the integration test suite. It starts a Wanaku Server binary, the capabilities, and the MCP servers that each test needs, and calls them through the management API, the MCP protocol, and the CLI.

| Module | What it tests |
|--------|---------------|
| `mcp-forwarding-tests` | MCP forwards, header forwarding, and tool invocation through Wanaku |
| `camel-integration-capability-tests` | Camel tools and resources served by the Camel Integration Capability |
| `resources-tests` | Resources through MCP |
| `router-tests` | Forwards, namespaces, entries, evaluator revisions, and MCP error paths |
| `governance-tests` | Action policies, evaluators, and audit |
| `semantic-router-tests` | Semantic routers |
| `a2a-tests` | The agent-to-agent (A2A) proxy |

To run a module, point the tests at a Wanaku Server binary:

```shell
mvn verify -pl mcp-forwarding-tests -am \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

A test is skipped when a binary or artifact that it needs is missing. For the artifacts, the system properties, and the base classes for new tests, see the [wanaku-tests README](https://github.com/wanaku-ai/wanaku-tests#readme).

## Validate a service catalog deployment

1. Validate the catalog before you deploy it:

   ```shell
   wanaku service validate --path=my-catalog
   ```

2. Deploy the catalog, and make sure that Barn lists it:

   ```shell
   wanaku service deploy --path=my-catalog
   wanaku service catalog list
   ```

3. Start a Camel Integration Capability for each system, and add it as a forward. See [Build and deploy a service catalog](../manage/build-a-service-catalog.md#serve-the-catalog-to-wanaku).

4. Make sure that Wanaku lists the tools of the catalog:

   ```shell
   wanaku tools list
   ```

5. Call each tool with the MCP Inspector at `http://localhost:8081/default/mcp`.

## Troubleshooting

### The tools do not appear in Wanaku

- Make sure that the forward exists and is in the expected namespace: `wanaku forwards list`.
- Make sure that the MCP server is running and that Wanaku can reach the address of the forward.
- For a service catalog, make sure that the routes use `ai-tool:` endpoints.

### A Camel route fails with "Component Not Found"

- Add the Maven dependency of the component to the `*.dependencies.txt` file of the system.
- Make sure that the Maven coordinates are valid and that the versions exist.

### The MCP Inspector cannot connect

- Make sure that Wanaku is running: `curl http://localhost:8080/healthz`.
- Use the MCP endpoint of a namespace, for example `http://localhost:8081/default/mcp`, with the Streamable HTTP transport.

## Next steps

- [Build and deploy a service catalog](../manage/build-a-service-catalog.md): serve Camel routes as MCP tools.
- [Forward MCP servers](../cli/forwards.md): manage the forwards of Wanaku.
