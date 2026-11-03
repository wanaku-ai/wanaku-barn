---
title: Tool URIs and reserved arguments
description: Build tool URIs with parameter expressions, send request bodies, and pass metadata and authentication tokens as headers with reserved argument names.
---

# Tool URIs and reserved arguments

Tool definitions use URIs and expressions to build the request that a downstream service receives. This page lists the expressions and the reserved argument names.

Universal Resource Identifiers (URI) are central to Wanaku.

They are used to define the location of resources, the tool invocation request that Wanaku will receive from the Agent/LLM and
the location of configuration and secret properties.

Understanding URIs is critical to leverage Wanaku and create flexible definitions of tools and resources.

## Flexible Input Data

Some services may require a more flexible definition of input data.

For instance, consider HTTP endpoints with dynamic parameters:

- `http://my-host/api/{someId}`
- `http://my-host/api/{someId}/create`
- `http://my-host/api/{someId}/link/to/{anotherId}`

In cases where the service cannot predetermine the actual tool addresses, users must define them when creating the tool.

## Creating URIs

Building the URIs is not always as simple as defining their address. Sometimes, optional parameters need to be filtered out or
query parameters need to be built. To help with that, Wanaku comes with a couple of expressions to build them.

To access the values, you can use the expression `{parameter.value('name')}`. For instance, to get the value of the parameter `id`
you would use the expression `{parameter.value('id')}`. You can also provide default values if none are provided, such as
`http://my-host/{parameter.valueOrElse('id', 1)}/data` (this would provide the value `1` if the parameter `id` is not set).

It is also possible to build the query part of URIs with the `query` method. For instance, to create a URI such as `http://my-host/data?id=456`
you could use `http://my-host/data{parameter.query('id')}`. If the `id` parameter is not provided, this would generate a URI such as
`http://my-host/data`. This can take multiple parameters, so it is possible to pass extra variables such as
`{parameter.query('id', 'name', 'location', ...)}`.

> [!IMPORTANT]
> Do not provide the `?` character.
> It is added automatically the parsing code if necessary.

Building the query part of URIs can be quite complex if there are too many. To avoid that, you can use `{parameter.query}` to build
a query composed of all query parameters.

The values for the queries will be automatically encoded, so a URI defined as `http://my-host/{parameter.query('id', 'name')}`
would generate `http://my-host/?id=456&name=My+Name+With+Spaces` if provided with a name value of `"My Name With Spaces"`.

## Dealing with Request Bodies

The `wanaku_body` property is a special argument used to indicate that the associated property or argument should be included in
the body of the data exchange, rather than as a parameter.

For instance, in an HTTP call, `wanaku_body` specifies that the property should be part of the HTTP body, not the HTTP URI.

The handling of such parameters may vary depending on the service being used.

## Passing Metadata as Headers

The `wanaku_meta_` prefix is a special argument prefix that allows AI services to inject headers into tool invocations
without requiring changes to the tool's configuration or route definition.

Arguments with this prefix are:

1. Extracted from the regular arguments (they are not passed to the tool as arguments)
2. Stripped of the `wanaku_meta_` prefix
3. Forwarded as headers in the tool invocation request

For example, an argument named `wanaku_meta_contextId` with value `ctx-123` becomes a header with key `contextId` and
value `ctx-123`.

This is useful for passing context information (such as user IDs, session IDs, or correlation IDs) from the AI service
through to the downstream MCP server.

### Example: LangChain4j AI Service

```java
@RegisterAiService
public interface MyService {
    @McpToolBox("toolbox")
    String callTool(
        @Header("wanaku_meta_contextId") String contextId,
        @Header("wanaku_meta_userId") String userId,
        @UserMessage String message
    );
}
```

In the tool implementation, these become accessible as headers in the `ToolInvokeRequest`:

```java
Map<String, String> headers = request.getHeadersMap();
String contextId = headers.get("contextId");  // prefix stripped
String userId = headers.get("userId");
```

> [!NOTE]
> If a metadata header has the same name as a tool-defined header (from the tool's schema), the tool-defined header
> takes precedence.

## Passing Authentication Tokens as Headers

The `wanaku_auth_` prefix is a special argument prefix that allows MCP clients to propagate access tokens or other
credentials to downstream MCP servers without exposing them to LLMs.

Arguments with this prefix are:

1. Extracted from the regular arguments (they are never passed to LLMs or to the tool as arguments)
2. Stripped of the `wanaku_auth_` prefix
3. Forwarded as headers in the tool invocation request
4. Always redacted in logs and observability events

For example, an argument named `wanaku_auth_Authorization` with value `Bearer token-123` becomes a header with key
`Authorization` and value `Bearer token-123`.

This is useful for propagating access tokens from MCP clients through to downstream MCP servers (HTTP, Camel, etc.)
when calling protected third-party APIs.

### Security Guarantees

Unlike `wanaku_meta_`, authentication arguments have stricter security handling:

- **Never exposed to LLMs** — filtered from tool arguments before any processing
- **Never appear in events** — filtered from observability event arguments
- **Always redacted in headers** — sensitive header names (e.g., `Authorization`) are redacted in event headers
- **Highest merge priority** — auth headers override both metadata and tool-defined headers on conflict

### Example: HTTP Service with Protected API

To call a protected third-party API (e.g., GitHub), the MCP client passes the access token via `wanaku_auth_`:

```text
Arguments from MCP client:
  wanaku_auth_Authorization = "Bearer ghp_xxxxxxxxxxxx"
  owner = "octocat"
  repo = "hello-world"
```

The `wanaku_auth_Authorization` argument is extracted and becomes an `Authorization` header on the outgoing HTTP
request, while `owner` and `repo` are passed as regular tool arguments.

### Example: Multiple Auth Tokens

Multiple auth tokens can be propagated simultaneously:

```text
Arguments from MCP client:
  wanaku_auth_Authorization = "Bearer internal-token"
  wanaku_auth_X-Third-Party-Token = "external-token"
```

Both are extracted and forwarded as separate headers to the downstream MCP server.

## Reserved Argument Names

Currently special arguments:

- `wanaku_body` - Indicates the argument should be included in the request body
- `wanaku_meta_` - Prefix for arguments that are converted to headers (e.g., `wanaku_meta_contextId`)
- `wanaku_auth_` - Prefix for sensitive authentication arguments that are converted to headers with redaction (e.g., `wanaku_auth_Authorization`)

## Next steps

- [Configuration](configuration.md): the properties of the Barn components.
- [Inspect tools, resources, and prompts](../cli/capabilities.md): see the URIs of the registered tools.
