# Common: Start the Wanaku Backend Locally

Reusable steps for building and starting the Wanaku backend locally (no authentication, no Kubernetes).

Only the backend is started here. Plans that need a capability (an MCP server such as the
[Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability)) must start it separately
and register it against the running backend.

## Prerequisites

- Java 21+
- Maven 3.9+
- The Wanaku repository checked out and at the repo root

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `WANAKU_REPO_ROOT` | Path to the Wanaku repository root | `.` |
| `WANAKU_ROUTER_PORT` | HTTP port the backend listens on | `8080` |

## Output variables

| Variable | Description |
|----------|-------------|
| `VERSION` | Wanaku version string from the build |
| `CLI_JAR` | Path to the CLI JAR |
| `ROUTER_JAR` | Path to the backend runner JAR |
| `WANAKU_ROUTER_URL` | Router base URL (`http://localhost:8080`) |
| `WANAKU_PID` | PID of the background backend process |

## Steps

### 1. Build the project

```bash
cd "${WANAKU_REPO_ROOT:-.}"
mvn -DskipTests clean package
```

**Verification:**

```bash
VERSION=$(cat core/core-util/target/classes/version.txt)
CLI_JAR="apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
ROUTER_JAR="apps/wanaku-barn-backend/target/quarkus-app/quarkus-run.jar"

for FILE in "${CLI_JAR}" "${ROUTER_JAR}"; do
  if [ ! -f "${FILE}" ]; then
    echo "FAIL: ${FILE} not found"
    exit 1
  fi
  echo "PASS: ${FILE} exists"
done
```

### 2. Re-augment the backend to disable OIDC

The backend ships with OIDC enabled and needs a one-time re-augmentation to run without Keycloak
(see [contributing-test-plans.md](../../../docs/contributing-test-plans.md#re-augmentation-for-local-test-plans)).

```bash
java -Dquarkus.launch.rebuild=true \
     -Dquarkus.log.level=WARNING \
     -Dquarkus.oidc.enabled=false \
     -Dquarkus.oidc-proxy.enabled=false \
     -jar "${ROUTER_JAR}"
echo "PASS: re-augmentation complete"
```

### 3. Start the backend

```bash
export WANAKU_ROUTER_PORT="${WANAKU_ROUTER_PORT:-8080}"
export WANAKU_ROUTER_URL="http://localhost:${WANAKU_ROUTER_PORT}"

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -jar "${ROUTER_JAR}" &
WANAKU_PID=$!
echo "Wanaku backend started with PID ${WANAKU_PID}"
```

### 4. Wait for health

```bash
MAX_RETRIES=30
RETRY_INTERVAL=5
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_ROUTER_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: router is healthy (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: router not healthy after ${MAX_RETRIES} attempts (last HTTP ${HTTP_CODE})"
    kill "${WANAKU_PID}" 2>/dev/null || true
    exit 1
  fi
  sleep ${RETRY_INTERVAL}
done
```

### 5. Verify the CLI can connect

```bash
java -jar "${CLI_JAR}" tools list --host "${WANAKU_ROUTER_URL}" --plain
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: CLI can connect to router"
else
  echo "FAIL: CLI cannot connect to router (exit code ${EXIT_CODE})"
  exit 1
fi
```

## Shutdown

Stop the backend with the PID captured at startup:

```bash
if [ -n "${WANAKU_PID}" ]; then
  kill "${WANAKU_PID}" 2>/dev/null || true
  wait "${WANAKU_PID}" 2>/dev/null || true
  echo "PASS: Wanaku backend stopped"
fi
```

Do **not** use `kill -9` — it bypasses shutdown hooks and can leave the Infinispan store in an inconsistent state.
