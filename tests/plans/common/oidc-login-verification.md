# Common: OIDC Login Verification

Reusable steps for verifying OIDC authentication end to end: log in against Keycloak, then call the router through
the oauth2-proxy that protects its management API.

The `wanaku auth login` command authenticates directly against Keycloak (`<auth-server>/realms/<realm>`, realm
`wanaku` by default) using the confidential `wanaku-mcp-router` client. oauth2-proxy accepts only tokens carrying that
client's audience, so the client secret must be available in `WANAKU_CLIENT_SECRET` (set by
[keycloak-setup.md](keycloak-setup.md), step 8b).

## Prerequisites

- Keycloak deployed and configured (see [keycloak-setup.md](keycloak-setup.md))
- WanakuRouter CR created and Ready
- `WANAKU_ROUTER_URL` environment variable set (e.g. `http://<router-route-host>`)
- `WANAKU_TEST_USER` and `WANAKU_TEST_PASS` environment variables set
- `KEYCLOAK_URL` and `WANAKU_CLIENT_SECRET` environment variables set
- `WANAKU_CREDENTIALS` exported to a per-run path (see [#1697](https://github.com/wanaku-ai/wanaku/issues/1697)) to isolate credential storage across concurrent test runs

## Steps

### 1. Verify the OIDC login works

Log in as the test user against Keycloak with the `wanaku-mcp-router` client.

Note: `--password` is a boolean flag that prompts for input. Pipe the password via stdin.

```bash
echo "${WANAKU_TEST_PASS}" | ${WANAKU_CLI:-wanaku} auth login \
  --auth-server "${KEYCLOAK_URL}" \
  --realm wanaku \
  --client-secret "${WANAKU_CLIENT_SECRET}" \
  --username "${WANAKU_TEST_USER}" \
  --password \
  --plain 2>&1

LOGIN_EXIT=$?

if [ "${LOGIN_EXIT}" -ne 0 ]; then
  echo "FAIL: OIDC login failed (exit code ${LOGIN_EXIT})"
  LOGIN_FAILED=true
else
  echo "PASS: OIDC login works for user '${WANAKU_TEST_USER}'"
  LOGIN_FAILED=false
fi
```

### 2. Workaround: regenerate the router client secret if login fails

If the stored `wanaku-mcp-router` secret does not match Keycloak (for example after the realm was re-imported),
regenerate it, re-read it and log in again:

```bash
if [ "${LOGIN_FAILED}" = "true" ]; then
  echo "WARN: wanaku-mcp-router secret may be stale — regenerating via CLI"

  ${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin} credentials regenerate \
    --keycloak-url "${KEYCLOAK_URL}" \
    --admin-username "${KEYCLOAK_ADMIN_USER}" \
    --admin-password "${KEYCLOAK_ADMIN_PASS}" \
    --client-id wanaku-mcp-router \
    --plain 2>&1

  # With --show-secret --plain only the secret is written to stdout
  export WANAKU_CLIENT_SECRET=$(${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin} credentials show \
    --keycloak-url "${KEYCLOAK_URL}" \
    --admin-username "${KEYCLOAK_ADMIN_USER}" \
    --admin-password "${KEYCLOAK_ADMIN_PASS}" \
    --client-id wanaku-mcp-router \
    --show-secret \
    --plain 2>/dev/null)

  echo "${WANAKU_TEST_PASS}" | ${WANAKU_CLI:-wanaku} auth login \
    --auth-server "${KEYCLOAK_URL}" \
    --realm wanaku \
    --client-secret "${WANAKU_CLIENT_SECRET}" \
    --username "${WANAKU_TEST_USER}" \
    --password \
    --plain 2>&1

  if [ $? -ne 0 ]; then
    echo "FAIL: OIDC login still failing after secret regeneration"
    exit 1
  fi
  echo "PASS: OIDC login works after secret regeneration"
fi
```

> Note: the oauth2-proxy instances in front of the router use the same client secret. After regenerating it,
> update the router's OIDC secret (for example the `wanaku-oidc` Kubernetes Secret) and restart the proxies.

### 3. Verify the token is accepted by the router's management proxy

```bash
OUTPUT=$(${WANAKU_CLI:-wanaku} tools list --host "${WANAKU_ROUTER_URL}" --plain 2>&1)

if [ $? -ne 0 ]; then
  echo "FAIL: management proxy rejected the token issued to wanaku-mcp-router"
  echo "${OUTPUT}"
  exit 1
fi
echo "PASS: token accepted by the management proxy at ${WANAKU_ROUTER_URL}"
```
