---
title: Authenticate the Wanaku CLI
description: Log in to Wanaku from the CLI with Keycloak, manage tokens and stored credentials, use tokens in CI/CD, and fix common authentication errors.
---

# Authenticate the Wanaku CLI

When oauth2-proxy and Keycloak protect Wanaku, the CLI must send a token with each request. This page shows how to get, store, and use that token.

Wanaku is protected by [oauth2-proxy](https://github.com/oauth2-proxy/oauth2-proxy): one proxy instance sits in
front of the management API (port `4181` by default) and another in front of the MCP endpoint (port `4180`). Both
validate bearer tokens issued by Keycloak. The CLI talks to the management proxy, so every request must carry a
token that the proxy accepts. The CLI stores that token locally and adds it to each request automatically.

## Which Keycloak client to use

The bundled realm defines several clients. They have distinct roles, and only some of them produce tokens that the
oauth2-proxy instances accept:

| Client | Used by | Token accepted by oauth2-proxy? |
|--------|---------|---------------------------------|
| `wanaku-mcp-router` | oauth2-proxy itself, and the `wanaku` CLI password login | Yes. Confidential client: needs its secret. Direct access grants enabled for local development. |
| `mcp-client` | MCP clients such as MCP Inspector (browser OAuth flow) | Yes. Public client. The `wanaku-mcp-client` scope is a default scope, so its tokens carry the router audience. Direct access grants enabled for local development. |
| `admin-cli` | `wanaku-keycloak-admin` against the Keycloak `master` realm | No. Its tokens lack the router audience. |
| `wanaku-service` | Downstream capabilities and services (client credentials) | Yes. Not meant for interactive users. |

> [!NOTE]
> The CLI used to default to `admin-cli`. Tokens issued to that client are rejected by oauth2-proxy with
> `401`/`403`, so the default is now `wanaku-mcp-router`. If you still have credentials from an older login,
> run `wanaku auth logout` and log in again.

## Authentication Commands

### Login

The password grant is intended for local development only. Create the user in the `wanaku` realm first (for
example with `wanaku-keycloak-admin users add`), and obtain the `wanaku-mcp-router` client secret from Keycloak
(or with `wanaku-keycloak-admin credentials show --client-id wanaku-mcp-router --show-secret --plain`).

```shell
export WANAKU_CLIENT_SECRET=<wanaku-mcp-router secret>

wanaku auth login --username alice --password
```

This authenticates against Keycloak at `http://localhost:8543`, realm `wanaku`, client `wanaku-mcp-router`.
All of these can be overridden:

```shell
wanaku auth login \
  --auth-server https://keycloak.example.com \
  --realm wanaku \
  --client-id wanaku-mcp-router \
  --client-secret <secret> \
  --username alice \
  --password
```

To log in without a client secret, use the public `mcp-client` client. The bundled realm enables the password grant for it:

```shell
wanaku auth login --client-id mcp-client --username alice --password
```

If you already have a token (for example one obtained with `curl` or from another tool), store it directly:

```shell
wanaku auth login --api-token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**Options:**

- `--username <username>` / `--password`: Password-based login. `--password` prompts interactively (pipe the value through stdin in scripts).
- `--api-token <token>`: Store an existing bearer token instead of logging in.
- `--auth-server <url>`: Keycloak base URL, or a full OIDC issuer URL such as `https://keycloak.example.com/realms/wanaku` (default: `http://localhost:8543`)
- `--realm <realm>`: Keycloak realm (default: `wanaku`). Ignored when `--auth-server` already points at a realm.
- `--client-id <client-id>`: OAuth2 client ID (default: `wanaku-mcp-router`). Must be a client whose tokens the oauth2-proxy accepts.
- `--client-secret <secret>`: Client secret for confidential clients. Also read from the `WANAKU_CLIENT_SECRET` environment variable.

The CLI resolves the token endpoint through OIDC discovery at `<auth-server>/realms/<realm>/.well-known/openid-configuration`.
Refresh tokens are used automatically when the access token is about to expire.

Once logged in, point the commands at the management proxy:

```shell
wanaku tools list --host http://localhost:4181
```

### Status

Check the current authentication status and view stored credentials:

```shell
wanaku auth status
```

This command displays:

- Current authentication mode
- Masked API token (showing first and last 4 characters)
- Authentication server URL (if configured)
- Masked refresh token (if available)
- Credentials file location
- Whether credentials are currently stored

**Example output:**

```text
Authentication Status:
=====================
Mode: token
API Token: eyJh***VCJ9
Auth Server: http://localhost:8543
Realm: wanaku
Client ID: wanaku-mcp-router (confidential)
Refresh Token: eyJh***abcd
Credentials File: /Users/username/.wanaku/credentials
Has Credentials: true
```

### Logout

Clear all stored authentication credentials:

```shell
wanaku auth logout
```

This command removes all authentication data from the local credentials file.

### Token Management

Manage the stored authentication token:

```shell
wanaku auth token --get
```

By default, `--get` masks the token value. Use `--unmask` to print the full token:

```shell
wanaku auth token --get --unmask
```

To extract the token programmatically (for example, in a script or test helper), combine `--unmask` with `--plain` and capture stdout:

```shell
TOKEN=$(wanaku auth token --get --unmask --plain) || wanaku auth login --username alice --password
curl -H "Authorization: Bearer $TOKEN" http://localhost:4181/api/v1/tools
```

On success, stdout contains only the token and a final newline. If no valid token is available, the command returns exit code 1 and leaves stdout empty. Error messages and application logs go to stderr. Check the exit code before you use the token.

You can also set or clear the stored token directly:

```shell
wanaku auth token --set <token>
wanaku auth token --clear
```

## Using Authentication with Commands

Once authenticated via `wanaku auth login`, all subsequent CLI commands will automatically include the authentication token in their requests.

### Per-Command Token Override

You can override the stored authentication token for a single command:

```shell
wanaku tools list --token <temporary-token>
```

### Disabling Authentication

To explicitly disable authentication for a command:

```shell
wanaku tools list --no-auth
```

## Credential Storage

By default, credentials are stored in:

```text
~/.wanaku/credentials
```

You can override this path with the `WANAKU_CREDENTIALS` environment variable to isolate credentials across contexts or concurrent sessions:

```shell
export WANAKU_CREDENTIALS=/tmp/my-isolated-credentials
wanaku auth login --api-token <token>
```

This is useful when running multiple test plans or sessions in parallel, as it prevents token overwrites that would otherwise occur in the shared global file.

The credentials file is a Java properties file containing:

- `api.token`: The API bearer token
- `refresh.token`: OAuth2 refresh token (when applicable)
- `token.expiry`: Access token expiry (epoch seconds), used to decide when to refresh
- `auth.mode`: The authentication mode (`token`)
- `auth.server.url`: The Keycloak (or issuer) URL used for login and refresh
- `auth.realm`: The Keycloak realm (absent when `auth.server.url` is a full issuer URL)
- `client.id`: The OAuth2 client used for login and refresh
- `client.secret`: The client secret, only for confidential clients

> [!CAUTION]
> The credentials file contains sensitive authentication tokens. Ensure proper file permissions are set to prevent unauthorized access.
> On Unix-like systems, you should restrict access: `chmod 600 ~/.wanaku/credentials`

## Authentication Flow

The CLI authentication process works as follows:

1. **Login**: The user authenticates with `wanaku auth login` (password grant against Keycloak, or `--api-token`)
2. **Storage**: Access token, refresh token, expiry and client details are stored in `~/.wanaku/credentials`
3. **Auto-Injection**: The CLI adds the token as a Bearer token to the `Authorization` header of every API request, refreshing it first when it is about to expire
4. **Validation**: oauth2-proxy validates the token (signature, expiry and audience) before forwarding the request to Wanaku
5. **Logout**: The user clears credentials via `wanaku auth logout`

## Troubleshooting Authentication

### Token Not Working

If you receive `401`/`403` errors from the management proxy:

1. Check what is stored:

   ```shell
   wanaku auth status
   ```

2. Verify the token was issued to a client the proxy accepts (`Client ID` should be `wanaku-mcp-router`, not `admin-cli`)
3. Verify `--host` points at the management proxy (`http://localhost:4181`), not at the unprotected backend port
4. If the management proxy restricts access by role, verify the user has that role (for example `admin`)
5. Log in again with fresh credentials, or use `--no-auth` when Wanaku runs without oauth2-proxy

### Clear and Reset

To completely reset authentication:

```shell
wanaku auth logout
wanaku auth login --api-token <new-token>
```

### Manual Credential Management

You can manually edit or remove the credentials file if needed:

```shell
# View credentials
cat ~/.wanaku/credentials

# Remove credentials manually
rm ~/.wanaku/credentials
```

## Security Best Practices

1. **Token Protection**: Never share your API tokens or commit them to version control
2. **Regular Rotation**: Rotate tokens periodically for enhanced security
3. **Use Environment Variables**: For CI/CD, consider using `--token` flag with environment variables instead of storing tokens
4. **File Permissions**: Ensure credentials file has restricted permissions (600)
5. **Logout When Done**: Use `wanaku auth logout` when finished working on shared systems

## Example Workflows

### Basic Authentication Workflow

```shell
# Login with API token
wanaku auth login --api-token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# Verify authentication
wanaku auth status

# Use authenticated commands
wanaku tools list
wanaku resources list
wanaku data-store list

# Logout when done
wanaku auth logout
```

### CI/CD Usage

For automated scripts, use token override instead of storing credentials:

```shell
# Use token from environment variable
wanaku tools list --token $WANAKU_API_TOKEN

# Or disable authentication for public endpoints
wanaku tools list --no-auth
```

## Next steps

- [Set up Keycloak for Wanaku](../operate/keycloak.md): create the realm and the clients that the CLI uses.
- [Inspect tools, resources, and prompts](capabilities.md): use the CLI after you log in.
