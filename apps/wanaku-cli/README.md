# Wanaku MCP CLI

## Overview

Command-line interface tool for managing the Wanaku MCP Router via its management API.

## Purpose

The Wanaku CLI provides a user-friendly interface for:
- Managing tools and resources
- Configuring namespaces
- Monitoring downstream MCP servers
- Creating new MCP server projects
- Authenticating with the router

## Key Features

- **Tool Management**: Add, list, update, and remove MCP tools
- **Resource Management**: Manage MCP resources and providers
- **Namespace Support**: Organize tools and resources across namespaces
- **MCP Server Monitoring**: View registered downstream MCP servers and their health
- **Project Scaffolding**: Generate new tool and provider projects from templates
- **Authentication**: OAuth 2.0/OIDC authentication with the router
- **Label Filtering**: Advanced filtering using label expressions
- **Plain Output Mode**: `--plain` flag for clean, parsable output without ANSI colors (useful for scripting and piping)

## Installation

> **Note:** Java 21 or later is required to run Wanaku CLI.

### Via JBang (Recommended)

```shell
jbang app install wanaku@wanaku-ai/wanaku
```

### Via Binary Download

Download the latest release from [GitHub releases](https://github.com/wanaku-ai/wanaku/releases) and extract to your PATH.
### PATH Configuration

If you installed via `get-wanaku.sh`, the CLI is placed in `$HOME/bin` which may not be on your default `PATH`. See [PATH Configuration](../../docs/usage.md#path-configuration) in the usage guide for setup instructions.

>
> 
## Basic Usage

```shell
# Authenticate against Keycloak (local development). Wanaku sits behind oauth2-proxy,
# which only accepts tokens issued to the wanaku-mcp-router client, so its secret is required.
export WANAKU_CLIENT_SECRET=<wanaku-mcp-router secret>
wanaku auth login \
  --auth-server http://localhost:8543 \
  --realm wanaku \
  --username alice \
  --password

# Or store a token obtained elsewhere
wanaku auth login --api-token <token>

# Commands go through the management proxy (port 4181)
wanaku tools list --host http://localhost:4181

# List available tools
wanaku tools list

# Show tool details
wanaku tools show meow-facts

# List resources
wanaku resources list

# View capability services
wanaku capabilities list

# Manage namespaces
wanaku namespaces list
wanaku namespaces create --path ns-qa --name qa --label env=qa
wanaku namespaces show <namespace-id>
wanaku namespaces update <namespace-id> --name qa-updated
wanaku namespaces delete <namespace-id>
wanaku namespaces cleanup --max-age-days 7 -y

# Create a new tool project
wanaku services create tool --name my-tool
```

## Configuration

The CLI stores configuration in `~/.wanaku/`:
- `credentials` - Authentication tokens
- `cli.properties` - CLI configuration

## Related Documentation

- [Usage Guide](../../docs/usage.md) - Complete CLI reference
- [Label Expressions Guide](src/main/resources/docs/LABEL_EXPRESSIONS.md) - Advanced filtering
- [Architecture](../../docs/architecture.md)
