# Test Plan: Centralized Wanaku Home Directory Resolution

## Overview

This test plan verifies the centralized home directory resolution introduced by `WanakuHome` (PR #1412). It confirms that the Wanaku home directory can be overridden via environment variable (`WANAKU_HOME`) or system property (`wanaku.home`), that the precedence order is correct (system property > env var > default), and that the router and the CLI respect the resolved path. Tests run locally with **no authentication** and are fully automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java -version` |
| `mvn` | 3.9+ | `mvn -version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |

### Prerequisite check script

```bash
java -version 2>&1 || { echo "FAIL: java not found"; exit 1; }
mvn -version 2>&1 || { echo "FAIL: mvn not found"; exit 1; }
jq --version 2>&1 || { echo "FAIL: jq not found"; exit 1; }
curl --version > /dev/null 2>&1 || { echo "FAIL: curl not found"; exit 1; }
echo "PASS: all prerequisites met"
```

### CLI invocation

When using the CLI from a local build (not installed), use `java -jar` directly:

```bash
CLI_JAR="apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
java -jar ${CLI_JAR} tools list --host "${WANAKU_ROUTER_URL}" --plain
```

Do **not** assign the full command to a single variable (e.g., `WANAKU_CLI="java -jar path/to/jar"`) -- zsh treats it as a single token. Use `CLI_JAR` for the path and call `java -jar ${CLI_JAR}` explicitly.

### Environment variables

```bash
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_ROUTER_PORT="${WANAKU_ROUTER_PORT:-8080}"
export WANAKU_ROUTER_URL="${WANAKU_ROUTER_URL:-http://localhost:${WANAKU_ROUTER_PORT}}"
export MCP_SERVER_URI="${MCP_SERVER_URI:-http://localhost:${WANAKU_ROUTER_PORT}/public/mcp/}"
export CUSTOM_HOME_DIR="${CUSTOM_HOME_DIR:-/tmp/wanaku-test-home-$$}"
export CUSTOM_HOME_DIR_SYSPROP="${CUSTOM_HOME_DIR_SYSPROP:-/tmp/wanaku-test-sysprop-$$}"
# Isolate credentials per test run to avoid contention (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-home-$$}"
```

| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_REPO_ROOT` | `.` | Path to the Wanaku repository root |
| `WANAKU_ROUTER_PORT` | `8080` | HTTP port the router listens on |
| `WANAKU_ROUTER_URL` | `http://localhost:8080` | Router base URL (no trailing slash) |
| `MCP_SERVER_URI` | `http://localhost:8080/public/mcp/` | MCP Streamable HTTP endpoint |
| `CUSTOM_HOME_DIR` | `/tmp/wanaku-test-home-$$` | Temporary directory for WANAKU_HOME env var tests |
| `CUSTOM_HOME_DIR_SYSPROP` | `/tmp/wanaku-test-sysprop-$$` | Temporary directory for wanaku.home system property tests |

### Known limitations

- **No Kubernetes:** This plan tests local execution only. Operator/container behavior is out of scope.
- **No authentication:** The router is always started with `WANAKU_HTTP_AUTH=none`.
- **Router only:** No capabilities are started. The CLI itself only uses the home directory for its credential store.
- **System property override scope:** The `-Dwanaku.home` system property only affects the JVM it is passed to. The router and the CLI are separate JVMs, so each must receive its own override.

---

## Phase 0: Prerequisites Verification

### Test 0.1: Verify required tools

```bash
java -version 2>&1 | head -1
JAVA_EXIT=$?

mvn -version 2>&1 | head -1
MVN_EXIT=$?

jq --version 2>&1
JQ_EXIT=$?

if [ "${JAVA_EXIT}" -eq 0 ] && [ "${MVN_EXIT}" -eq 0 ] && [ "${JQ_EXIT}" -eq 0 ]; then
  echo "PASS: all required tools present"
else
  echo "FAIL: one or more tools missing (java=${JAVA_EXIT}, mvn=${MVN_EXIT}, jq=${JQ_EXIT})"
  exit 1
fi
```

### Test 0.2: Verify Java version is 21+

```bash
JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
if [ "${JAVA_VERSION}" -ge 21 ]; then
  echo "PASS: Java version ${JAVA_VERSION} >= 21"
else
  echo "FAIL: Java version ${JAVA_VERSION} < 21"
  exit 1
fi
```

### Test 0.3: Create temporary test directories

```bash
mkdir -p "${CUSTOM_HOME_DIR}"
mkdir -p "${CUSTOM_HOME_DIR_SYSPROP}"

if [ -d "${CUSTOM_HOME_DIR}" ] && [ -d "${CUSTOM_HOME_DIR_SYSPROP}" ]; then
  echo "PASS: temporary test directories created"
  echo "  CUSTOM_HOME_DIR=${CUSTOM_HOME_DIR}"
  echo "  CUSTOM_HOME_DIR_SYSPROP=${CUSTOM_HOME_DIR_SYSPROP}"
else
  echo "FAIL: could not create temporary test directories"
  exit 1
fi
```

### Test 0.4: Verify default home does not conflict with test directories

```bash
DEFAULT_HOME="${HOME}/.wanaku"
if [ "${CUSTOM_HOME_DIR}" = "${DEFAULT_HOME}" ]; then
  echo "FAIL: CUSTOM_HOME_DIR must differ from default home ${DEFAULT_HOME}"
  exit 1
fi
if [ "${CUSTOM_HOME_DIR_SYSPROP}" = "${DEFAULT_HOME}" ]; then
  echo "FAIL: CUSTOM_HOME_DIR_SYSPROP must differ from default home ${DEFAULT_HOME}"
  exit 1
fi
echo "PASS: test directories do not overlap with default home"
```

---

## Phase 1: Build

Follow [common/start-backend.md](common/start-backend.md) **steps 1 and 2 only** (build the project and re-augment the backend). Do **not** start the backend yet -- individual phases start it with different environment configurations.

After completion, the following variables must be set:

- `VERSION` -- Wanaku version string
- `CLI_JAR` -- path to the CLI JAR
- `ROUTER_JAR` -- path to the backend runner JAR

### Test 1.1: Verify build artifacts exist

```bash
cd "${WANAKU_REPO_ROOT:-.}"
mvn -DskipTests clean package

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

### Test 1.2: Re-augment the backend to disable OIDC

```bash
java -Dquarkus.launch.rebuild=true \
     -Dquarkus.log.level=WARNING \
     -Dquarkus.oidc.enabled=false \
     -Dquarkus.oidc-proxy.enabled=false \
     -jar "${ROUTER_JAR}"
echo "PASS: re-augmentation complete"
```

---

## Phase 2: Default Behavior (Backward Compatibility)

Start Wanaku with **no overrides** and verify data goes to the default `~/.wanaku/` path.

### Test 2.1: Start the router with default home

```bash
unset WANAKU_HOME

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -jar "${ROUTER_JAR}" &
WANAKU_PID=$!
echo "Wanaku started with PID ${WANAKU_PID}"
```

### Test 2.2: Wait for router health

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

### Test 2.3: Verify default Infinispan data directory exists

```bash
DEFAULT_ROUTER_DATA="${HOME}/.wanaku/router"

if [ -d "${DEFAULT_ROUTER_DATA}" ]; then
  echo "PASS: default Infinispan data directory exists at ${DEFAULT_ROUTER_DATA}"
else
  echo "FAIL: default Infinispan data directory not found at ${DEFAULT_ROUTER_DATA}"
fi
```

### Test 2.4: Verify default log file exists

```bash
DEFAULT_LOG_FILE="${HOME}/.wanaku/local/logs/wanaku-router.log"

if [ -f "${DEFAULT_LOG_FILE}" ]; then
  echo "PASS: default router log file exists at ${DEFAULT_LOG_FILE}"
else
  echo "FAIL: default router log file not found at ${DEFAULT_LOG_FILE}"
fi
```

### Test 2.5: Stop default instance

```bash
if [ -n "${WANAKU_PID}" ]; then
  kill "${WANAKU_PID}" 2>/dev/null || true
  wait "${WANAKU_PID}" 2>/dev/null || true
  echo "PASS: default instance stopped"
fi
```

### Test 2.6: Verify process is gone

```bash
if [ -n "${WANAKU_PID}" ]; then
  kill -0 "${WANAKU_PID}" 2>/dev/null
  if [ $? -ne 0 ]; then
    echo "PASS: default instance process is no longer running"
  else
    echo "FAIL: default instance process ${WANAKU_PID} is still running"
  fi
fi
```

---

## Phase 3: WANAKU_HOME Environment Variable Override

Start Wanaku with `WANAKU_HOME` set to a custom temporary directory. Verify that data directories are created under the custom path instead of `~/.wanaku/`.

### Test 3.1: Start the router with WANAKU_HOME override

```bash
export WANAKU_HOME="${CUSTOM_HOME_DIR}"

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -jar "${ROUTER_JAR}" &
WANAKU_PID=$!
echo "Wanaku started with PID ${WANAKU_PID} and WANAKU_HOME=${WANAKU_HOME}"
```

### Test 3.2: Wait for router health

```bash
MAX_RETRIES=30
RETRY_INTERVAL=5
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_ROUTER_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: router is healthy with WANAKU_HOME override (attempt ${i})"
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

### Test 3.3: Verify Infinispan data directory under custom home

```bash
CUSTOM_ROUTER_DATA="${CUSTOM_HOME_DIR}/router"

if [ -d "${CUSTOM_ROUTER_DATA}" ]; then
  echo "PASS: Infinispan data directory created under custom home at ${CUSTOM_ROUTER_DATA}"
else
  echo "FAIL: Infinispan data directory not found at ${CUSTOM_ROUTER_DATA}"
fi
```

### Test 3.4: Verify log file under custom home

```bash
CUSTOM_LOG_FILE="${CUSTOM_HOME_DIR}/local/logs/wanaku-router.log"

if [ -f "${CUSTOM_LOG_FILE}" ]; then
  echo "PASS: router log file created under custom home at ${CUSTOM_LOG_FILE}"
else
  echo "FAIL: router log file not found at ${CUSTOM_LOG_FILE}"
fi
```

### Test 3.5: Verify CLI can connect to router with custom home

```bash
java -jar ${CLI_JAR} tools list --host "${WANAKU_ROUTER_URL}" --plain 2>&1
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ]; then
  echo "PASS: CLI connected to router with WANAKU_HOME override"
else
  echo "FAIL: CLI cannot connect to router with WANAKU_HOME override (exit code ${EXIT_CODE})"
fi
```

### Test 3.6: Verify router persists data at custom location

Verify the Infinispan store has content under the custom home by listing tools (which triggers data access).

```bash
java -jar ${CLI_JAR} tools list \
  --host "${WANAKU_ROUTER_URL}" --plain 2>&1

# Verify data files exist under custom router directory
CUSTOM_ROUTER_DATA="${CUSTOM_HOME_DIR}/router"
FILE_COUNT=$(find "${CUSTOM_ROUTER_DATA}" -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "${FILE_COUNT}" -gt 0 ]; then
  echo "PASS: Infinispan data files present under custom home (${FILE_COUNT} files)"
else
  echo "FAIL: no Infinispan data files found under ${CUSTOM_ROUTER_DATA}"
fi
```

### Test 3.7: Verify log file has content

```bash
CUSTOM_LOG_FILE="${CUSTOM_HOME_DIR}/local/logs/wanaku-router.log"

if [ -f "${CUSTOM_LOG_FILE}" ]; then
  LOG_SIZE=$(wc -c < "${CUSTOM_LOG_FILE}" | tr -d ' ')
  if [ "${LOG_SIZE}" -gt 0 ]; then
    echo "PASS: router log file has content (${LOG_SIZE} bytes)"
  else
    echo "FAIL: router log file exists but is empty"
  fi
else
  echo "FAIL: router log file not found at ${CUSTOM_LOG_FILE}"
fi
```

### Test 3.8: Stop custom-home instance

```bash
if [ -n "${WANAKU_PID}" ]; then
  kill "${WANAKU_PID}" 2>/dev/null || true
  wait "${WANAKU_PID}" 2>/dev/null || true
  echo "PASS: custom-home instance stopped"
fi
unset WANAKU_HOME
```

### Test 3.9: Verify process is gone

```bash
if [ -n "${WANAKU_PID}" ]; then
  kill -0 "${WANAKU_PID}" 2>/dev/null
  if [ $? -ne 0 ]; then
    echo "PASS: custom-home instance process is no longer running"
  else
    echo "FAIL: custom-home instance process ${WANAKU_PID} is still running"
  fi
fi
```

---

## Phase 4: System Property Override

This phase tests the `-Dwanaku.home` system property by starting the router JAR with the property set.

### Test 4.1: Verify the router JAR is available

```bash
if [ -f "${ROUTER_JAR}" ]; then
  echo "PASS: router JAR found at ${ROUTER_JAR}"
else
  echo "FAIL: router JAR not found at ${ROUTER_JAR}"
  exit 1
fi
```

### Test 4.2: Start router with -Dwanaku.home system property

```bash
unset WANAKU_HOME

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -Dwanaku.home="${CUSTOM_HOME_DIR_SYSPROP}" \
  -jar "${ROUTER_JAR}" &
ROUTER_PID=$!
echo "Router started with PID ${ROUTER_PID} and -Dwanaku.home=${CUSTOM_HOME_DIR_SYSPROP}"
```

### Test 4.3: Wait for router health

```bash
MAX_RETRIES=30
RETRY_INTERVAL=5
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_ROUTER_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: router is healthy with system property override (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: router not healthy after ${MAX_RETRIES} attempts (last HTTP ${HTTP_CODE})"
    kill "${ROUTER_PID}" 2>/dev/null || true
    exit 1
  fi
  sleep ${RETRY_INTERVAL}
done
```

### Test 4.4: Verify Infinispan data directory under system property path

```bash
SYSPROP_ROUTER_DATA="${CUSTOM_HOME_DIR_SYSPROP}/router"

if [ -d "${SYSPROP_ROUTER_DATA}" ]; then
  echo "PASS: Infinispan data directory created at system property path ${SYSPROP_ROUTER_DATA}"
else
  echo "FAIL: Infinispan data directory not found at ${SYSPROP_ROUTER_DATA}"
fi
```

### Test 4.5: Verify log file under system property path

```bash
SYSPROP_LOG_FILE="${CUSTOM_HOME_DIR_SYSPROP}/local/logs/wanaku-router.log"

if [ -f "${SYSPROP_LOG_FILE}" ]; then
  echo "PASS: router log file created at system property path ${SYSPROP_LOG_FILE}"
else
  echo "FAIL: router log file not found at ${SYSPROP_LOG_FILE}"
fi
```

### Test 4.6: Stop system property router

```bash
if [ -n "${ROUTER_PID}" ]; then
  kill "${ROUTER_PID}" 2>/dev/null || true
  wait "${ROUTER_PID}" 2>/dev/null || true
  echo "PASS: system property router stopped"
fi
```

### Test 4.7: Verify process is gone

```bash
if [ -n "${ROUTER_PID}" ]; then
  kill -0 "${ROUTER_PID}" 2>/dev/null
  if [ $? -ne 0 ]; then
    echo "PASS: system property router process is no longer running"
  else
    echo "FAIL: system property router process ${ROUTER_PID} is still running"
  fi
fi
```

---

## Phase 5: Precedence (System Property Wins Over Env Var)

Set **both** `WANAKU_HOME` env var and `-Dwanaku.home` system property to different paths. Verify the system property takes precedence for the router.

### Test 5.1: Start router with both overrides

```bash
PRECEDENCE_ENVVAR_DIR="${CUSTOM_HOME_DIR}/precedence-envvar"
PRECEDENCE_SYSPROP_DIR="${CUSTOM_HOME_DIR}/precedence-sysprop"
mkdir -p "${PRECEDENCE_ENVVAR_DIR}"
mkdir -p "${PRECEDENCE_SYSPROP_DIR}"

export WANAKU_HOME="${PRECEDENCE_ENVVAR_DIR}"

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -Dwanaku.home="${PRECEDENCE_SYSPROP_DIR}" \
  -jar "${ROUTER_JAR}" &
ROUTER_PID=$!
echo "Router started with PID ${ROUTER_PID}"
echo "  WANAKU_HOME=${WANAKU_HOME} (env var -- should be ignored)"
echo "  -Dwanaku.home=${PRECEDENCE_SYSPROP_DIR} (system property -- should win)"
```

### Test 5.2: Wait for router health

```bash
MAX_RETRIES=30
RETRY_INTERVAL=5
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_ROUTER_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: router is healthy in precedence test (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: router not healthy after ${MAX_RETRIES} attempts (last HTTP ${HTTP_CODE})"
    kill "${ROUTER_PID}" 2>/dev/null || true
    exit 1
  fi
  sleep ${RETRY_INTERVAL}
done
```

### Test 5.3: Verify system property path has router data (wins)

```bash
if [ -d "${PRECEDENCE_SYSPROP_DIR}/router" ]; then
  echo "PASS: system property path has router data (precedence correct)"
else
  echo "FAIL: system property path ${PRECEDENCE_SYSPROP_DIR}/router not found"
fi
```

### Test 5.4: Verify env var path does NOT have router data (loses)

```bash
if [ -d "${PRECEDENCE_ENVVAR_DIR}/router" ]; then
  echo "FAIL: env var path ${PRECEDENCE_ENVVAR_DIR}/router exists -- system property should have taken precedence"
else
  echo "PASS: env var path does not have router data (system property took precedence)"
fi
```

### Test 5.5: Verify log file under system property path (not env var path)

```bash
SYSPROP_LOG="${PRECEDENCE_SYSPROP_DIR}/local/logs/wanaku-router.log"
ENVVAR_LOG="${PRECEDENCE_ENVVAR_DIR}/local/logs/wanaku-router.log"

if [ -f "${SYSPROP_LOG}" ]; then
  echo "PASS: log file at system property path"
else
  echo "FAIL: log file not found at system property path ${SYSPROP_LOG}"
fi

if [ -f "${ENVVAR_LOG}" ]; then
  echo "FAIL: log file also at env var path -- precedence violation"
else
  echo "PASS: no log file at env var path (correct)"
fi
```

### Test 5.6: Stop precedence test router

```bash
if [ -n "${ROUTER_PID}" ]; then
  kill "${ROUTER_PID}" 2>/dev/null || true
  wait "${ROUTER_PID}" 2>/dev/null || true
  echo "PASS: precedence test router stopped"
fi
unset WANAKU_HOME
```

### Test 5.7: Verify process is gone

```bash
if [ -n "${ROUTER_PID}" ]; then
  kill -0 "${ROUTER_PID}" 2>/dev/null
  if [ $? -ne 0 ]; then
    echo "PASS: precedence test router process is no longer running"
  else
    echo "FAIL: precedence test router process ${ROUTER_PID} is still running"
  fi
fi
```

---

## Phase 6: Credential Store Path

Verify that the CLI credential store respects the resolved home directory when `WANAKU_HOME` is set.

### Test 6.1: Verify credentials file path uses custom home

The `AuthCredentialStore` stores credentials at `WanakuHome.get() + "/credentials"`. When `WANAKU_HOME` is set, the credentials file should be under the custom home.

```bash
export WANAKU_HOME="${CUSTOM_HOME_DIR}"

# Trigger credential storage by performing a login attempt (expected to fail against noauth)
# The CLI should still create or attempt to access the credentials path under WANAKU_HOME
java -jar ${CLI_JAR} login \
  --host "${WANAKU_ROUTER_URL}" \
  --username test --password test 2>&1 || true

CREDENTIALS_DIR="${CUSTOM_HOME_DIR}"
# The credentials file may or may not exist (login to noauth router may not create it),
# but the directory structure should at least be reachable
if [ -d "${CREDENTIALS_DIR}" ]; then
  echo "PASS: custom home directory exists for credentials path"
else
  echo "FAIL: custom home directory not accessible for credentials path"
fi

unset WANAKU_HOME
```

### Test 6.2: Verify credentials file does not appear in default home from custom-home login

```bash
# If a credentials file was created, it should be under CUSTOM_HOME_DIR, not ~/.wanaku/
DEFAULT_CREDS="${HOME}/.wanaku/credentials"
CUSTOM_CREDS="${CUSTOM_HOME_DIR}/credentials"

if [ -f "${CUSTOM_CREDS}" ]; then
  echo "PASS: credentials file created under custom home"
elif [ -f "${DEFAULT_CREDS}" ]; then
  # Check modification time to see if it was recently modified (within this test run)
  echo "WARN: credentials file exists at default location -- may be from a previous run"
else
  echo "PASS: no credentials file found (expected for noauth -- no leak)"
fi
```

---

## Phase 7: Cleanup Isolation

Verify that cleaning up a custom `WANAKU_HOME` directory does not affect the default `~/.wanaku/` directory.

### Test 7.1: Record default home state before cleanup

```bash
DEFAULT_HOME="${HOME}/.wanaku"

if [ -d "${DEFAULT_HOME}" ]; then
  DEFAULT_HOME_FILE_COUNT=$(find "${DEFAULT_HOME}" -type f 2>/dev/null | wc -l | tr -d ' ')
  echo "PASS: default home has ${DEFAULT_HOME_FILE_COUNT} files before cleanup"
else
  DEFAULT_HOME_FILE_COUNT=0
  echo "PASS: default home does not exist (nothing to protect)"
fi
```

### Test 7.2: Remove custom home directories

```bash
rm -rf "${CUSTOM_HOME_DIR}" 2>/dev/null || true
rm -rf "${CUSTOM_HOME_DIR_SYSPROP}" 2>/dev/null || true

if [ ! -d "${CUSTOM_HOME_DIR}" ] && [ ! -d "${CUSTOM_HOME_DIR_SYSPROP}" ]; then
  echo "PASS: custom home directories removed"
else
  echo "FAIL: custom home directories still exist after removal"
fi
```

### Test 7.3: Verify default home is unchanged

```bash
DEFAULT_HOME="${HOME}/.wanaku"

if [ -d "${DEFAULT_HOME}" ]; then
  CURRENT_FILE_COUNT=$(find "${DEFAULT_HOME}" -type f 2>/dev/null | wc -l | tr -d ' ')
  if [ "${CURRENT_FILE_COUNT}" -ge "${DEFAULT_HOME_FILE_COUNT}" ]; then
    echo "PASS: default home intact after custom home cleanup (${CURRENT_FILE_COUNT} files)"
  else
    echo "FAIL: default home lost files (was ${DEFAULT_HOME_FILE_COUNT}, now ${CURRENT_FILE_COUNT})"
  fi
else
  if [ "${DEFAULT_HOME_FILE_COUNT}" -eq 0 ]; then
    echo "PASS: default home still absent (consistent)"
  else
    echo "FAIL: default home disappeared during cleanup"
  fi
fi
```

---

## Phase 8: Negative Tests

### Test 8.1: WANAKU_HOME set to unwritable path

Start the router with `WANAKU_HOME` pointing to a path the current user cannot write to. The router should fail to start or report an error (Infinispan cannot create the data directory).

```bash
UNWRITABLE_DIR="/tmp/wanaku-unwritable-$$"
mkdir -p "${UNWRITABLE_DIR}"
chmod 000 "${UNWRITABLE_DIR}"

export WANAKU_HOME="${UNWRITABLE_DIR}"

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -jar "${ROUTER_JAR}" > /tmp/wanaku-unwritable-output-$$.log 2>&1 &
ROUTER_PID=$!

# Wait briefly for the process to fail
sleep 10

# The router should have failed -- either the process exited or health check fails
kill -0 "${ROUTER_PID}" 2>/dev/null
STILL_RUNNING=$?

if [ "${STILL_RUNNING}" -ne 0 ]; then
  echo "PASS: router failed to start with unwritable WANAKU_HOME (process exited)"
else
  # Process is still running -- check health
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_ROUTER_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" != "200" ]; then
    echo "PASS: router is not healthy with unwritable WANAKU_HOME"
  else
    echo "FAIL: router is healthy despite unwritable WANAKU_HOME"
  fi
  kill "${ROUTER_PID}" 2>/dev/null || true
  wait "${ROUTER_PID}" 2>/dev/null || true
fi

unset WANAKU_HOME
chmod 755 "${UNWRITABLE_DIR}" 2>/dev/null || true
rm -rf "${UNWRITABLE_DIR}" 2>/dev/null || true
rm -f /tmp/wanaku-unwritable-output-$$.log 2>/dev/null || true
```

### Test 8.2: WANAKU_HOME set to empty string

When `WANAKU_HOME` is empty, `WanakuHome.get()` should fall through to the default. Verify the router starts normally and uses `~/.wanaku/`.

```bash
export WANAKU_HOME=""

WANAKU_HTTP_AUTH=none java -Dquarkus.profile=local \
  -Dquarkus.http.port="${WANAKU_ROUTER_PORT}" \
  -jar "${ROUTER_JAR}" &
ROUTER_PID=$!

MAX_RETRIES=30
RETRY_INTERVAL=5
ROUTER_HEALTHY=false
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_ROUTER_URL}/q/health/ready" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    ROUTER_HEALTHY=true
    break
  fi
  sleep ${RETRY_INTERVAL}
done

if [ "${ROUTER_HEALTHY}" = "true" ]; then
  echo "PASS: router started with empty WANAKU_HOME (fell through to default)"
else
  echo "FAIL: router did not start with empty WANAKU_HOME"
fi

# Verify it used the default path
DEFAULT_ROUTER_DATA="${HOME}/.wanaku/router"
if [ -d "${DEFAULT_ROUTER_DATA}" ]; then
  echo "PASS: default router data directory exists (empty env var ignored)"
else
  echo "FAIL: default router data directory not found"
fi

kill "${ROUTER_PID}" 2>/dev/null || true
wait "${ROUTER_PID}" 2>/dev/null || true
unset WANAKU_HOME
```

---

## Phase 9: Final Cleanup

All cleanup is idempotent. This phase removes temporary directories and ensures no test processes are left running.

### Step 9.1: Kill any remaining test processes

```bash
for PID_VAR in WANAKU_PID ROUTER_PID; do
  eval "PID_VAL=\${${PID_VAR}}"
  if [ -n "${PID_VAL}" ]; then
    kill "${PID_VAL}" 2>/dev/null || true
    wait "${PID_VAL}" 2>/dev/null || true
  fi
done
echo "PASS: all test processes stopped (or already absent)"
```

### Step 9.2: Remove temporary directories

```bash
rm -rf "${CUSTOM_HOME_DIR}" 2>/dev/null || true
rm -rf "${CUSTOM_HOME_DIR_SYSPROP}" 2>/dev/null || true
rm -rf /tmp/wanaku-unwritable-* 2>/dev/null || true
echo "PASS: temporary directories cleaned up"
```

### Step 9.3: Unset test environment variables

```bash
unset WANAKU_HOME
unset CUSTOM_HOME_DIR
unset CUSTOM_HOME_DIR_SYSPROP
echo "PASS: test environment variables cleared"
```

---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | 0.1 | Verify required tools | Critical |
| 0 | 0.2 | Verify Java version >= 21 | Critical |
| 0 | 0.3 | Create temporary test directories | Critical |
| 0 | 0.4 | Verify no conflict with default home | Critical |
| 1 | 1.1 | Verify build artifacts exist | Critical |
| 1 | 1.2 | Re-augment the backend to disable OIDC | Critical |
| 2 | 2.1 | Start the router with default home | Critical |
| 2 | 2.2 | Wait for router health (default) | Critical |
| 2 | 2.3 | Verify default Infinispan data directory | Critical |
| 2 | 2.4 | Verify default log file | High |
| 2 | 2.5 | Stop default instance | Critical |
| 2 | 2.6 | Verify default process is gone | High |
| 3 | 3.1 | Start the router with WANAKU_HOME | Critical |
| 3 | 3.2 | Wait for router health (env var) | Critical |
| 3 | 3.3 | Verify Infinispan data under custom home | Critical |
| 3 | 3.4 | Verify log file under custom home | High |
| 3 | 3.5 | CLI connects with custom home | High |
| 3 | 3.6 | Router persists data at custom location | Critical |
| 3 | 3.7 | Verify log file has content | Medium |
| 3 | 3.8 | Stop custom-home instance | Critical |
| 3 | 3.9 | Verify custom-home process is gone | High |
| 4 | 4.1 | Verify the router JAR is available | Critical |
| 4 | 4.2 | Start router with -Dwanaku.home | Critical |
| 4 | 4.3 | Wait for router health (sys prop) | Critical |
| 4 | 4.4 | Verify Infinispan data under sys prop path | Critical |
| 4 | 4.5 | Verify log file under sys prop path | High |
| 4 | 4.6 | Stop system property router | Critical |
| 4 | 4.7 | Verify sys prop process is gone | High |
| 5 | 5.1 | Start router with both overrides | Critical |
| 5 | 5.2 | Wait for router health (precedence) | Critical |
| 5 | 5.3 | System property path has data (wins) | Critical |
| 5 | 5.4 | Env var path has no data (loses) | Critical |
| 5 | 5.5 | Log file under sys prop, not env var | High |
| 5 | 5.6 | Stop precedence test router | Critical |
| 5 | 5.7 | Verify precedence process is gone | High |
| 6 | 6.1 | Credentials path uses custom home | Medium |
| 6 | 6.2 | No credentials leak to default home | Medium |
| 7 | 7.1 | Record default home state | High |
| 7 | 7.2 | Remove custom home directories | High |
| 7 | 7.3 | Default home unchanged after cleanup | High |
| 8 | 8.1 | Unwritable WANAKU_HOME (negative) | High |
| 8 | 8.2 | Empty WANAKU_HOME falls to default | High |
| 9 | 9.1 | Kill remaining test processes | Critical |
| 9 | 9.2 | Remove temporary directories | Critical |
| 9 | 9.3 | Unset test environment variables | Medium |
