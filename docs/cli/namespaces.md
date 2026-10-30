---
title: Namespaces
description: Use namespaces to group the tools and resources in Wanaku, and use the Wanaku CLI to list namespaces and filter them with label expressions.
---

# Namespaces

A namespace groups related tools and resources, so that each agent sees only the tools that it needs.

Wanaku introduces the concept of namespaces to help users organize and isolate tools and resources, effectively managing the
Large Language Model (LLM) context. This prevents context bloat and improves the efficiency of your Wanaku deployments.

## What Are Namespaces

Namespaces provide a mechanism to group related tools and resources.

Each namespace acts as a separate logical container, ensuring that the LLM context for tools within one namespace does not
interfere with tools in another.
This is particularly useful when you have a large number of tools or when different sets of tools are used for distinct purposes.

Each namespace has a name and its own MCP endpoint, `<mcp-url>/<namespace>/mcp`.
A name uses only lowercase letters, numbers, and hyphens, with 1 to 63 characters.
Wanaku also provides a `default` namespace.

## Using Namespaces

Tools and resources are associated with namespaces through the MCP servers that provide them.
Each forward belongs to one namespace.

Create a namespace, then add a forward to it:

```shell
wanaku namespaces create finance --label team=finance
wanaku forwards add --service="http://market-data-mcp:8080/mcp" --name market-data --namespace finance
```

Use `wanaku namespaces show <name>` to see one namespace, and `wanaku namespaces delete <name>` to delete it.

All commands that accept `--namespace` (`-N`) also support `--namespace-id` for passing the namespace UUID directly. These options are mutually exclusive. Use `--namespace-id` when scripting with known UUIDs.

## Checking Namespace Assignments

You can verify which namespace a tool or resource has been assigned to by using the `wanaku namespaces list` command.

This command displays each namespace with its MCP endpoint address (`<mcp-url>/<namespace>/mcp`) and labels.

The output will look similar to this:

```shell
name    address                              labels
default http://localhost:8081/default/mcp
finance http://localhost:8081/finance/mcp   team=finance
```

The address uses the Wanaku Server MCP listener, which is `http://localhost:8081` by default. The management API
(`--host`) runs on a different port, so if the MCP listener is exposed elsewhere (for example, behind an ingress), pass
its base URL with `--mcp-url`:

```shell
wanaku namespaces list --mcp-url https://mcp.example.com
```

## The Default Namespace

If you do not specify a namespace when adding a tool or resource, it will automatically be added to the default namespace.

The default namespace acts as a general container for tools that don't require specific isolation.

You can identify the default namespace in the wanaku namespaces list output by its `default` name.

## Filtering Namespaces by Labels

You can filter namespaces by their labels when listing them:

```shell
# List all namespaces with a specific label
wanaku namespaces list --label-filter 'env=production'

# List namespaces matching complex expressions
wanaku namespaces list --label-filter 'env=production & tier=backend'
```

See the [Label Expressions Guide](../reference/label-expressions.md) for detailed information on the label expression syntax and advanced filtering options.

## Next steps

- [Forward MCP servers](forwards.md): add MCP servers to a namespace.
- [Evaluators](evaluators.md): govern the calls in each namespace.
