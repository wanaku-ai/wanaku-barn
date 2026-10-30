---
title: Troubleshoot Barn and the Wanaku CLI
description: Find the cause and the fix for common problems with the Wanaku CLI, Keycloak sign-in, the Barn backend, containers, and builds from source.
---

# Troubleshoot Barn and the Wanaku CLI

Each heading on this page is a symptom or an error message. Find your problem, then read the cause and the fix.

> [!NOTE]
> For problems with the Wanaku server itself, such as MCP endpoints, forwards, or tool calls, see the
> [Wanaku troubleshooting guide](https://wanaku.ai/docs/next/operate/troubleshooting.html).

## Wanaku CLI

### CLI commands return 401 Unauthorized

**Symptoms:**

- All `wanaku` CLI commands fail with authentication errors
- No clear indication that login is required

**Why this happens:**

When oauth2-proxy protects Wanaku, the CLI requires an active session. The CLI does not prompt for login automatically.

**Fix:**

```shell
# Log in against Keycloak with the client oauth2-proxy accepts (wanaku-mcp-router)
export WANAKU_CLIENT_SECRET=<wanaku-mcp-router secret>
wanaku auth login \
  --auth-server http://localhost:8543 \
  --realm wanaku \
  --username alice \
  --password

# Then target the management proxy, not the backend port
wanaku tools list --host http://localhost:4181

# For servers running without authentication, use --no-auth
wanaku tools list --no-auth
```

### Token expired errors

**Symptoms:**

- Commands work initially but fail after some time
- Error messages about expired tokens

**Solutions:**

1. The CLI refreshes access tokens automatically. When the refresh token has also expired, log in again:

   ```shell
   WANAKU_CLIENT_SECRET=<secret> wanaku auth login \
     --username alice \
     --password
   ```

2. Check token lifetime settings in Keycloak if tokens expire too quickly

### CLI commands fail with: Failed to communicate with Keycloak

**Symptoms:**

- `wanaku` CLI commands fail to communicate with Keycloak

Example:

```shell
An error occurred: Failed to communicate with Keycloak at https://127.0.0.1:8543

Run with --verbose flag for more details

```

If you set `--verbose` and then shows the cause as:

```shell
sun.security.validator.ValidatorException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

**Why this happens:**

The security code (JSSE) in wanaku-cli doesn't recognize the certificate that serves the Keycloak HTTPS endpoint.

**Fix:**

You have two options:

- You can skip the certificate checks in wanaku-cli by using the `--insecure` parameter. In this case, it will show a warning `WARNING: TLS certificate verification is disabled. This is insecure and should only be used for development.`.

- You can use the CA that signed the Keycloak HTTPS endpoint, by either importing it into the default Java truststore `$JAVA_HOME/lib/security/cacerts` or have a particular keystore file and use the `-Djavax.net.ssl.trustStore` and `-Djavax.net.ssl.trustStorePassword` parameters to refer to this custom truststore.

You can pass these `-D` options to the CLI, as in this example:

```shell
java "-Djavax.net.ssl.trustStore=my-truststore.p12" "-Djavax.net.ssl.trustStorePassword=changeit" -jar quarkus-run.jar  admin credentials show --admin-username=admin --admin-password=admin --keycloak-url=https://keycloak.192.168.49.2.nip.io --show-secret --client-id wanaku-service
```

### `wanaku mcp` commands report a redirect

**Symptoms:**

- `wanaku mcp tool list` (or any other `wanaku mcp` command) fails with `The MCP server responded with a redirect (HTTP 302) to ...`

The `wanaku mcp` commands do not follow redirects, because MCP requests are `POST` requests and following them can send the
bearer token to a different host. When the server redirects, the CLI shows the redirect target, when it can read it, and a hint:

- `The server redirects to HTTPS. Use --uri https://...`: the endpoint forces HTTPS. This is the case for OpenShift Routes
  created by the operator, and for most Ingress controllers and load balancers. Run the command again with the `https://` URI
  that the message shows.
- `The server asks for a browser login, which the CLI cannot do`: the redirect goes to a login page (for example, the
  oauth2-proxy `/oauth2/start` page or the Keycloak login page). Check that you pass a token with `--token`, that the token is
  not expired, and that the server accepts its audience.
- `The CLI does not follow redirects for MCP requests. Try --uri ...`: the server redirects to another URL. Check that the URL
  is the MCP endpoint you want and use it with `--uri`.
- `The CLI does not follow redirects for MCP requests. Possible causes:`: the CLI could not read the redirect target. Try the
  `https://` URI, then check the token.

When you pass `--token` with an `http://` URI, the `wanaku mcp` commands also show a warning that the token is sent over plain
HTTP. The warning is not shown for `localhost` and other loopback addresses.

### Module access errors on Java 25

**Symptoms:**

- `wanaku --version` or any `wanaku` command fails immediately with `IllegalAccessError`, `InaccessibleObjectException`, or a stack trace mentioning `java.base/java.lang` module access
- Running with `JAVA_TOOL_OPTIONS='--add-opens=java.base/java.lang=ALL-UNNAMED'` resolves the error

**Why this happens:**

Java 25 tightened strong encapsulation of internal JDK classes. Some dependencies used by Quarkus and the CLI attempt reflective access to `java.lang` internals that are no longer automatically open.

**Fix:**

The Wanaku CLI launcher scripts already pass `--add-opens=java.base/java.lang=ALL-UNNAMED` by default on Java 25. If you run the JAR directly (e.g., `java -jar quarkus-run.jar`), add the option manually:

```shell
java --add-opens=java.base/java.lang=ALL-UNNAMED -jar quarkus-run.jar --version
```

If you still encounter module access errors for other packages, add the corresponding `--add-opens` or `--add-exports` flag. Report the full stack trace as a new issue so we can extend the launcher defaults.

### Get more output from the CLI

Add `--verbose` to a command to show more logging:

```shell
wanaku --verbose tools list
```

To produce clean, parsable output without ANSI colors or escape sequences (useful for scripting and piping), add `--plain`:

```shell
wanaku tools list --plain
```

## Keycloak and sign-in

### Login fails or the realm is not found

**Symptoms:**

- CLI commands fail with authentication errors
- The Admin UI redirects to Keycloak but login fails

**Solutions:**

1. Verify Keycloak is running and accessible:

   ```shell
   curl http://localhost:8543/health
   ```

2. Check that the Keycloak realm is properly configured:
   - Ensure the `wanaku` realm exists
   - Verify the `wanaku-mcp-router` client is configured
   - Confirm user accounts have been created

3. Clear stored credentials and re-authenticate against Keycloak with the `wanaku-mcp-router` client:

   ```shell
   wanaku auth logout
   WANAKU_CLIENT_SECRET=<secret> wanaku auth login \
     --auth-server http://localhost:8543 \
     --username alice \
     --password
   ```

4. Verify oauth2-proxy can reach Keycloak:
   - Check the `OAUTH2_PROXY_OIDC_ISSUER_URL` (and, when discovery is skipped, the redeem and JWKS URLs)
   - Ensure network connectivity between components

### No human user account exists in Keycloak

**Symptoms:**

- Keycloak login page appears but you have no username/password
- Admin UI and CLI authentication fail

**Why this happens:**

The realm import (`deploy/auth/wanaku-config.json`) creates the realm structure and a service account, but no human user. However, self-registration is enabled in the realm configuration.

**Fix:**

Either register a new account or create one manually:

1. **Self-register:** Navigate to the Keycloak login page and click "Register" to create a new account
2. **Manual creation:** Go to the Keycloak admin console at `http://localhost:8543/admin` (login: `admin`/`admin`), select the `wanaku` realm, navigate to Users, and create a new user with a password

### Token issuer mismatch causes 401 on OpenShift or Kubernetes

**Symptoms:**

- External clients obtain tokens from Keycloak and send them to Wanaku
- Wanaku rejects all tokens with HTTP 401
- Keycloak and Wanaku are both running, and the realm is configured correctly

**Why this happens:**

When Keycloak is accessed via an external route (e.g. `http://keycloak-wanaku-test.<cluster>/realms/wanaku`), tokens are stamped with that route URL as the issuer. Wanaku, however, validates tokens against the internal service URL (`http://keycloak:8080/realms/wanaku`). Because the issuers don't match, every token is rejected.

**Fix:**

Set `KC_HOSTNAME` on the Keycloak deployment to match the external route or ingress host. This forces Keycloak to stamp the same issuer in all tokens regardless of how it is accessed:

```shell
# Kubernetes (use the Ingress host instead)
KEYCLOAK_HOST=$(kubectl get ingress keycloak -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.rules[0].host}')
kubectl set env deployment/keycloak \
  KC_HOSTNAME="${KEYCLOAK_HOST}" \
  -n "${WANAKU_NAMESPACE}"
```

The `deploy/auth/keycloak.yaml` manifest includes a `KEYCLOAK_HOST` placeholder for `KC_HOSTNAME` — replace it with your actual hostname before applying. See also the Keycloak setup procedure (`plans/common/keycloak-setup.md`) in [wanaku-tests](https://github.com/wanaku-ai/wanaku-tests) for the full automated procedure.

### Browser shows "502 Bad Gateway" after sign in from Keycloak

**Symptoms:**

When you access Wanaku web console with authentication enabled, it redirects to Keycloak for authentication, then after you input the username and password, it redirects back to Wanaku with the `502 Bad Gateway` error in the browser page.
If you are using nginx as loadbalancer you can verify if it logs this message `upstream sent too big header while reading response header from upstream`.

```shell
kubectl -n ingress-nginx logs deploy/ingress-nginx-controller|grep upstream
```

**Why this happens:**

Nginx `proxy-buffer-size` [defaults to 4k or 8k](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffer_size), so you have to increase it.

**Fix:**

You have to change the nginx configuration to a larger buffer size.
There is this patch command to change the configuration value. In this example it increases the value to 16k.

```shell
kubectl -n ingress-nginx patch cm/ingress-nginx-controller --type=merge -p '{"data": {"proxy-buffer-size": "16k"}}'
```

## Barn backend

### API returns "Generic error"

**Symptoms:**

- API responses contain only `{"error": "Generic error"}` with HTTP 500
- No indication of what went wrong

**Why this happens:**

The `BaseExceptionMapper` of the Barn backend catches all unhandled exceptions and returns a sanitized error message. The actual exception details are logged server-side but not exposed in the API response.

**Fix:**

Check the Barn backend logs for the real stack trace:

- **Dev mode:** `target/logs/wanaku.log`
- **Docker:** `docker logs <container-name>`
- **Kubernetes:** `kubectl logs <pod-name>`

Enable debug logging for more detail:

```properties
quarkus.log.category."ai.wanaku".level=DEBUG
```

### Enable debug logging

To get more detailed logs for troubleshooting:

**Barn backend:**

```properties
quarkus.log.level=DEBUG
quarkus.log.category."ai.wanaku".level=DEBUG
quarkus.mcp.server.traffic-logging.enabled=true
```

Find the logs in `wanaku-barn-backend.log`, in the container logs, or with `kubectl logs <pod-name>` on Kubernetes.

### Data directory permission errors in containers

**Symptoms:**

- The Barn backend fails at startup with file permission errors
- Persistence data is silently lost between restarts

**Why this happens:**

The Infinispan data directory defaults to `${wanaku.home}/barn/`. In container images, this resolves to `/home/jboss/.wanaku/barn/`. Running the container with a different UID or mounting a host directory with incompatible permissions causes Infinispan's `SoftIndexFileStore` to fail.

**Fix:**

Use named Docker volumes (which handle permissions automatically) rather than host-mounted directories. If you must use host mounts, ensure the directory is owned by UID 185 (the `jboss` user in the container image).

### Changes on one replica are not visible on another

**Symptoms:**

- Changes made on one Barn backend instance (tool registrations, resource additions) are not visible on other instances
- Inconsistent behavior depending on which replica handles the request

**Why this happens:**

Infinispan is configured in `LOCAL` cache mode. Each Barn backend instance maintains its own isolated data store. There is no replication or synchronization between instances.

**Fix:**

Currently, Wanaku supports single-instance deployments only. Running multiple replicas requires architectural changes to enable distributed Infinispan cache modes. For high availability, use a single replica with persistent storage.

### CORS errors when the frontend runs on a non-default port

**Symptoms:**

- Browser console shows CORS preflight failures
- API calls from the frontend are blocked

**Why this happens:**

The CORS allowlist of the Barn backend is configured for specific origins: `localhost:8180`, `127.0.0.1:8180`, `localhost:5173`, `localhost:6274`, and `localhost:3000`. If Vite's dev server runs on a different port (which happens automatically when 5173 is already in use), requests are blocked.

**Fix:**

Add the origin of your dev server to the Barn backend configuration:

```properties
# In apps/wanaku-barn-backend/src/main/resources/application.properties
quarkus.http.cors.origins=http://localhost:8180,http://127.0.0.1:8180,http://localhost:5173,http://localhost:6274,http://localhost:3000,http://localhost:<your-port>
```

## Containers and Kubernetes

### Docker Compose does not work on macOS with Podman

**Symptoms:**

- Services fail to start or cannot communicate with each other

**Why this happens:**

All services in the provided `docker-compose.yml` use `network_mode: host`, which is not supported on macOS with Podman (see [podman#15664](https://github.com/containers/podman/issues/15664)).

**Fix:**

Use Docker Desktop on macOS instead of Podman, or modify the compose file to use bridge networking with explicit port mappings.

### Insecure default credentials in Docker Compose

**Symptoms:**

No error, which is the problem. The provided docker-compose ships with well-known credentials.

**Why this happens:**

The development docker-compose uses default credentials for convenience:

- Keycloak admin: `admin` / `admin`
- OIDC client secret for `wanaku-service`: `mypasswd`

**Fix:**

Before any non-local deployment, rotate these credentials:

1. Change `KEYCLOAK_ADMIN_PASSWORD` in the compose file
2. Update the `wanaku-service` client secret in Keycloak
3. Update `QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET` on all downstream MCP servers to match

### Pods fail to start

**Symptoms:**

- Pods in CrashLoopBackOff state
- ImagePullBackOff errors

**Solutions:**

1. Verify the container image exists and is accessible:

   ```shell
   podman pull quay.io/wanaku/wanaku-barn-backend:latest
   ```

2. Check pod logs for startup errors:

   ```shell
   kubectl logs <pod-name>
   ```

3. Verify ConfigMaps and Secrets are properly mounted:

   ```shell
   kubectl describe pod <pod-name>
   ```

4. Check resource limits and ensure sufficient memory/CPU

5. Verify Keycloak is accessible from the pods:

   ```shell
   kubectl exec <pod-name> -- curl http://keycloak:8080/health
   ```

## Build from source

### Build fails with missing dependencies

**Symptoms:**

- Maven build errors
- Missing artifact errors

**Solutions:**

1. Ensure you're using the correct Maven version:

   ```shell
   mvn --version  # Should be 3.x
   ```

2. Clear Maven cache and rebuild:

   ```shell
   rm -rf ~/.m2/repository/ai/wanaku
   mvn clean install
   ```

3. Verify internet connectivity for downloading dependencies

### Native build fails

**Symptoms:**

- Native compilation errors
- GraalVM-related failures

**Solutions:**

1. Verify GraalVM is properly installed:

   ```shell
   java -version  # Should show GraalVM
   native-image --version
   ```

2. Check the [Quarkus native build guide](https://quarkus.io/guides/building-native-image) for system requirements

3. Try building without native mode first to isolate the issue:

   ```shell
   mvn clean package
   ```

## Get help

If your problem is not on this page:

1. Search the [Wanaku Barn issues](https://github.com/wanaku-ai/wanaku-barn/issues) for similar problems.
2. Ask in the [Wanaku discussions](https://github.com/wanaku-ai/wanaku/discussions).
3. Open a new issue with the Barn version, the deployment environment, the steps to reproduce, relevant log excerpts, and the configuration with secrets redacted.

## Next steps

- [Set up Keycloak for Wanaku](keycloak.md): deploy Keycloak and import the realm.
- [Install the Barn backend](install.md): run the backend locally or on Kubernetes.
