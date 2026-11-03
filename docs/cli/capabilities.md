---
title: Inspect tools, resources, and prompts
description: Use the Wanaku CLI to list and show the tools, resources, and prompts that MCP servers expose through Wanaku, and call a tool with wanaku mcp.
---

# Inspect tools, resources, and prompts

Tools, resources, and prompts come from the MCP servers that Wanaku forwards. The CLI lists and shows them, and it can call a tool through an MCP endpoint.

## Call a tool with `wanaku mcp`

For `wanaku mcp` commands, pass the complete MCP endpoint with `--uri`. The CLI sends this URI to the MCP server without adding or changing a path. For example, use `--uri http://localhost:4180/team/mcp` for the `team` namespace. A bare server origin, such as `--uri http://localhost:4180`, is not expanded to a namespace endpoint; use it only when the server accepts MCP requests at its root.

These commands use MCP protocol version `2025-11-25`. They start with the `initialize` request and skip protocol discovery.

To call a tool, pass its arguments with `--param key=value` (repeatable) or as a JSON object with `--arguments`:

```shell
wanaku mcp tool --uri http://localhost:4180/team/mcp --name get-sum --param a=1 --param b=2
wanaku mcp tool --uri http://localhost:4180/team/mcp --name get-sum --arguments '{"a": 1, "b": 2}'
```

The CLI converts `--param` values to the type declared in the tool input schema. Values for `number`, `integer`,
`boolean`, `array` and `object` properties are parsed as JSON (for example, `--param tags='["a","b"]'`). All other
values are sent as strings. Values are not split at commas. When you use both options, `--param` values override
the ones in `--arguments`.

An MCP (Model Context Protocol) tool enables Large Language Models (LLMs) to execute tasks beyond their inherent capabilities by
using external functions.

## Tools

Each tool is uniquely identified by a name and defined with an input schema that outlines the expected parameters.
Essentially, MCP tools act as a standardized interface through which an AI agent can request information or execute specific
tasks from external systems, like APIs or databases.

Tools and resources are managed through MCP servers added to the router. When you register an MCP server,
its tools become available automatically. The CLI provides read-only commands for inspecting tools.

### Listing Tools

Any available tool is listed by default when you access the UI.

When using the CLI, the `wanaku tools list` command allows you to view all available tools on your Wanaku MCP Router instance.

Running this command will display a comprehensive list of tools, including their names and descriptions.

```shell
wanaku tools list
```

For example, you should receive an output similar to this.

```shell
Name               Type               URI
meow-facts      => http            => https://meowfacts.herokuapp.com?count={parameter.valueOrElse('count', 1)}
dog-facts       => http            => https://dogapi.dog/api/v2/facts?limit={parameter.valueOrElse('count', 1)}
```

### Showing Tool Details

The `wanaku tools show` command displays detailed information about a specific tool registered in the Wanaku MCP Router.

```shell
wanaku tools show <tool-name>
```

This command retrieves comprehensive details including the tool's name, namespace, type, description, URI, labels, and input schema properties.

**Example:**

```shell
wanaku tools show meow-facts
```

**Sample Output:**

```text
Tool Details:
name        meow-facts
namespace   default
type        http
description Retrieve random facts about cats
uri         https://meowfacts.herokuapp.com?count={parameter.valueOrElse('count', 1)}
labels      category=animals

Input Schema Properties:
name   type   description                    required
count  int    The count of facts to retrieve yes
```

**Options:**

- `--host <url>`: The API host URL (default: `http://localhost:8080`)

**Example with remote host:**

```shell
wanaku tools show --host http://api.example.com:8080 meow-facts
```

### Generating Tools from an OpenAPI Specification

Wanaku does not generate tools from OpenAPI specifications. Tools come from the MCP servers forwarded to the router.

To expose an API described by an OpenAPI specification:

1. Use Apache Camel to create routes from the specification. The [REST OpenApi component](https://camel.apache.org/components/next/rest-openapi-component.html) calls the operations defined in the specification. Camel JBang can also [generate REST DSL code from it](https://camel.apache.org/manual/camel-jbang.html).
2. Expose the routes as tools through a capability. See the [Wanaku Capabilities Java SDK](https://github.com/wanaku-ai/wanaku-capabilities-java-sdk).
3. Add the capability's MCP endpoint to Wanaku with `wanaku forwards add`.

## Managing MCP Resources

Resources are managed through MCP servers added to the router. The CLI provides read-only commands for inspecting resources.

### Listing Resources

The wanaku resources list command allows you to view all resources currently exposed by your Wanaku MCP Router instance.

Executing this command will display a list of available resources, including their names and descriptions.

```shell
wanaku resources list
```

### Showing Resource Details

The `wanaku resources show` command displays detailed information about a specific resource registered in the Wanaku MCP Router.

```shell
wanaku resources show <resource-name>
```

This command retrieves comprehensive details including the resource's name, type, description, location, MIME type, namespace, labels, and parameters.

**Example:**

```shell
wanaku resources show q4-report
```

**Sample Output:**

```text
Resource Details:
name        q4-report
type        file
description Q4 Financial Report
location    /home/user/documents/report.pdf
mimeType    application/pdf
namespace   default
labels      category=finance, year=2024

Parameters:
name   value
key1   value1
key2   value2
```

**Options:**

- `--host <url>`: The API host URL (default: `http://localhost:8080`)

**Example with remote host:**

```shell
wanaku resources show --host http://api.example.com:8080 q4-report
```

## Managing MCP Prompts

Prompts are reusable templates that can leverage multiple tools and provide example interactions for LLMs. They are part of the MCP (Model Context Protocol) specification and enable:

- Creating standardized message templates with variable substitution
- Defining argument schemas for dynamic prompt generation
- Referencing tools that the prompt can utilize
- Supporting multiple content types (text, images, audio, embedded resources)
- Providing example interactions to guide LLM behavior

Prompts are managed through MCP servers added to the router. The CLI provides read-only commands for inspecting prompts.

### Listing Prompts

View all prompts currently available in your Wanaku MCP Router instance:

```shell
wanaku prompts list
```

This displays all prompts with their names, descriptions, and namespaces.

## Next steps

- [Forward MCP servers](forwards.md): add the MCP servers that provide the tools.
- [Namespaces](namespaces.md): group tools and resources for each agent.
