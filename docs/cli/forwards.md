---
title: Forward MCP servers
description: Use the Wanaku CLI to add, list, refresh, and remove MCP forwards, so that Wanaku exposes the tools and resources of other MCP servers.
---

# Forward MCP servers

A forward connects Wanaku to another MCP server that uses HTTP. Wanaku then exposes the tools and resources of that server from one endpoint.

The MCP bridge in Wanaku allows it to act as a central gateway or proxy to other MCP servers that use HTTP as the transport mechanism.

This feature enables a centralized endpoint for aggregating tools and resources provided by other MCP servers.

## Listing Forwards

To view a list of currently configured forwards, use the `wanaku forwards list` command:

```bash
wanaku forwards list
```

This command displays information about each forward, including its name, service URL, and any other relevant details.

This can be useful for managing and troubleshooting MCP server integrations.

## Adding Forwards

To add an external MCP server to the Wanaku instance, use the `wanaku forwards add` command:

```bash
wanaku forwards add --service="http://your-mcp-server.com:8080/mcp" --name my-mcp-server --namespace default
```

- `--service`: The URL of the MCP endpoint of the external MCP server (Streamable HTTP).
- `--name`: A unique human-readable name for the forward, used for identification and management purposes.
- `--namespace` / `-N` (required): The namespace name to associate the forward with (e.g., `default`). The CLI resolves the name to its UUID automatically.
- `--namespace-id` (alternative to `--namespace`): The namespace UUID to use directly, for scripting or when the UUID is already known.

Use `--namespace` for the common case (human-readable name), or `--namespace-id` when scripting with known UUIDs. These options are mutually exclusive.

When the forward is added, the CLI prints what the router discovered on the external MCP server:

```text
Successfully added forward 'my-mcp-server': discovered 13 tools, 7 resources and 2 prompts
```

If the router cannot reach the external MCP server, it still stores the forward but marks it as unavailable. The CLI
prints a warning with the reason and exits with code 3:

```text
Forward 'my-mcp-server' was added, but it is unavailable: connection refused
```

Scripts can use the exit code to tell the two failure cases apart:

| Exit code | Meaning                                                              |
|-----------|----------------------------------------------------------------------|
| 0         | The forward was added and the router reached the MCP server          |
| 1         | The forward was not added (for example, the router rejected it)      |
| 3         | The forward was added, but the router could not reach the MCP server |

Once a forward is added, all tools and resources provided by the external MCP server will be mapped in the Wanaku instance.

These tools and resources can then be accessed as if they were local to the server.

## Removing Forwards

To remove a specific external MCP server from the Wanaku instance, use the `wanaku forwards remove` command:

```bash
wanaku forwards remove --name my-mcp-server
```

- `--name`: The human-readable name for the forward to be removed.

> [!WARNING]
> Forward removal operations cannot be undone. Once removed, the tools and resources from those MCP servers will no longer be accessible.

<!-- -->

> [!NOTE]
> Attempting to remove a non-existent forward will result in an error message.

## Example Use Case

Suppose you have two MCP servers: `http://mcp-server1.com:8080/mcp` and `http://mcp-server2.com:8080/mcp`.

To integrate these external MCP servers into your Wanaku instance, follow these steps:

1. Add the first forward using the `wanaku forwards add` command:

```shell
wanaku forwards add --service="http://mcp-server1.com:8080/mcp" --name mcp-server-1 --namespace default
```

1. Use the `wanaku forwards list` command to confirm that the forward has been successfully added:

```bash
wanaku forwards list
```

1. Verify that all tools and resources from `mcp-server1` are now accessible within your Wanaku instance using `wanaku tools list`

```shell
Name               Type               URI
tavily-search-local => tavily          => tavily://search?maxResults={parameter.value('maxResults')}
meow-facts      => mcp-remote-tool => <remote>
dog-facts       => mcp-remote-tool => <remote>
camel-rider-quote-generator => mcp-remote-tool => <remote>
tavily-search   => mcp-remote-tool => <remote>
laptop-order    => mcp-remote-tool => <remote>
```

1. Add the second forward using the same command:

```bash
wanaku forwards add --service="http://mcp-server2.com:8080/mcp" --name mcp-server-2 --namespace default
```

1. Confirm that tools and resources from both external MCP servers are now integrated into your Wanaku instance (use `wanaku tools list`)
2. Use the `wanaku forwards list` command to view the updated list of forwards:

```bash
wanaku forwards list
```

By leveraging the MCP bridge feature, you can create a centralized endpoint for aggregating tools and resources from multiple
external MCP servers, simplifying management and increasing the overall functionality of your Wanaku instance.

## Add your own MCP server

Use the [Wanaku Capabilities Java SDK](https://github.com/wanaku-ai/wanaku-capabilities-java-sdk) to build standalone MCP servers, or implement MCP in another language. Consult the SDK documentation for build and authentication configuration. Deploy the server and add its reachable MCP endpoint to Wanaku with `wanaku forwards add`.

## Next steps

- [Namespaces](namespaces.md): put the forwarded tools in a namespace.
- [Inspect tools, resources, and prompts](capabilities.md): check what a forward exposes.
