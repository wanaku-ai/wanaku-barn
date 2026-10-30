---
name: wanaku-mcp-basics
description: Basic MCP operations with the Wanaku MCP Router. Covers connecting coding agents to Wanaku, authenticating the CLI, discovering tools, resources and prompts, forwarding external MCP servers, and using data stores and namespaces. Use when an agent needs to interact with a running Wanaku instance.
---

# Wanaku MCP Basics

## Overview

Wanaku is an MCP router that connects MCP clients (such as coding agents) to downstream
capabilities: tools, resources, and prompts served by capability services or forwarded from
other MCP servers. Agents talk to the router, and the router routes and governs each request.

Key facts:

- Wanaku exposes MCP on port 8081 in the local Compose stack, separately from the
  barn-backend management API on port 8080. Use Streamable HTTP at
  `http://localhost:8081/default/mcp` for the `wanaku mcp` CLI commands.
- The CLI talks to the management API, which defaults to `http://localhost:8080` and can be
  overridden per command with `--host`.
- Namespaces group tools and resources under separate MCP endpoints, such as
  `http://localhost:8081/<namespace>/mcp/`.

## When to use this skill

Use it when you need to connect an agent to Wanaku, discover or inspect tools, resources and
prompts, forward external MCP servers, store or query agent knowledge in data stores, or
authenticate the CLI.

## Start a local router (no authentication)

The quickest way to run a router locally, without Keycloak:

```shell
docker compose -f deploy/docker-compose/docker-compose-noauth.yml up
```

Then open `http://localhost:8080` for the admin UI. Install the CLI from the
[Wanaku releases page](https://github.com/wanaku-ai/wanaku/releases) (or via JBang) and verify
with `wanaku --version`.

## Connect a coding agent

Point clients at the Wanaku Streamable HTTP endpoint (`http://localhost:8081/default/mcp`)
for the local Compose stack. For Claude Code:

```shell
claude mcp add wanaku --transport http http://localhost:8081/default/mcp
```

For other clients, configure the same URL with Streamable HTTP. The built-in
`wanaku configure` commands currently generate `/mcp` or legacy SSE paths, which do not
include the namespace required by Wanaku; use explicit client configuration instead.
For a cluster deployment, use the exposed Wanaku URL instead of localhost.

## Authenticate the CLI

When running with Keycloak authentication:

```shell
wanaku auth login --username alice --password
```

Useful follow-ups:

- `wanaku auth status` — show the current authentication state.
- `wanaku auth token --get --unmask --plain` — print the stored API token (for CI or scripts).
- `wanaku auth login --api-token <token>` — log in with a pre-issued API token.
- `wanaku auth logout` — clear stored credentials.

For a single command you can bypass stored credentials with `--token <token>`, or use
`--no-auth` when the router runs without authentication (as with the compose file above).

## Discover capabilities

```shell
wanaku tools list                     # all registered tools
wanaku tools list -e 'category=data'  # filter by label expression
wanaku tools show meow-facts          # details for one tool
wanaku resources list                 # all registered resources
wanaku resources show <resource-name>
wanaku prompts list                   # reusable MCP prompts
```

To query an MCP endpoint directly (bypassing the management API), use the `wanaku mcp`
commands and pass the complete Streamable HTTP endpoint with `--uri`:

```shell
wanaku mcp tool list --uri http://localhost:8081/default/mcp
wanaku mcp tool list --uri http://localhost:8081/team/mcp/   # namespaced endpoint
```

Wanaku requires `/<namespace>/mcp`, including `/default/mcp` for the default namespace.
A bare origin or `/mcp` is not expanded to a namespace endpoint.

## Forward external MCP servers

Bring an existing MCP server under the router's governance:

```shell
wanaku forwards add --service="http://your-mcp-server.com:8080/mcp/" --name my-mcp-server
wanaku forwards list
wanaku forwards remove --name my-mcp-server
```

## Data stores (agent knowledge)

The data store is a small knowledge base that agents and capabilities can share:

```shell
wanaku data-store add --read-from-file /path/to/file.yaml --name employee-routes
wanaku data-store list
wanaku data-store list -e 'category=routes'                # filter by labels
wanaku data-store label add --id <data-store-id> --label category=routes
wanaku data-store label remove --id <data-store-id> --label temporary
wanaku data-store remove --name employee-routes
```

## Namespaces

```shell
wanaku namespaces list                                # the default namespace shows as <default>
wanaku namespaces create production                   # create a namespace
wanaku namespaces list --label-expression 'env=production & tier=backend'
```

## Checklist for agents

1. Confirm the router is reachable (`wanaku tools list` against the right `--host`).
2. Authenticate once with `wanaku auth login`, or use `--no-auth` against an unauthenticated router.
3. Discover what exists before invoking: `wanaku tools list`, `wanaku resources list`, `wanaku prompts list`.
4. Inspect a tool with `wanaku tools show <name>` to learn its expected input schema.
5. Prefer registering external MCP servers as forwards instead of calling them directly, so they stay governed by the router.

## References

- [Usage guide](https://github.com/wanaku-ai/wanaku-barn/blob/main/docs/cli/usage.md) — installation, authentication, CLI reference
- [Service catalogs skill](../wanaku-service-catalogs/SKILL.md) — creating and deploying capabilities
- [Operator skill](../wanaku-operator/SKILL.md) — running Wanaku on Kubernetes or OpenShift
