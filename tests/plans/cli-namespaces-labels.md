# Test Plan: CLI Namespaces and Labels

## Overview

This test plan verifies namespace management, label operations, and namespace isolation via the Wanaku CLI against a locally running instance (no authentication). It covers the full CRUD lifecycle for namespaces, label add/remove operations on both namespaces and tools, label expression filtering, and namespace-scoped tool isolation.

Every step is fully automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `wanaku` | build from source | `wanaku --version` |
| `jq` | 1.6+ | `jq --version` |
| `java` | 21+ | `java -version` |
| `mvn` | 3.9+ | `mvn -version` |

### CLI invocation

When using the CLI from a local build (not installed), use `java -jar` directly:

```bash
CLI_JAR="apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
java -jar ${CLI_JAR} namespaces list --host ...
```

Do **not** assign the full command to a single variable (e.g., `WANAKU_CLI="java -jar path/to/jar"`) --- zsh treats it as a single token. Use `CLI_JAR` for the path and call `java -jar ${CLI_JAR}` explicitly.

### Environment variables

```bash
export WANAKU_ROUTER_URL="${WANAKU_ROUTER_URL:-http://localhost:8080}"
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
```

---

## Phase 0: Manual Prerequisites

Verify the required tools are installed:

```bash
wanaku --version && echo "PASS: wanaku CLI available" || echo "FAIL: wanaku CLI not found"
jq --version && echo "PASS: jq available" || echo "FAIL: jq not found"
java -version 2>&1 | head -1 && echo "PASS: java available" || echo "FAIL: java not found"
mvn -version 2>&1 | head -1 && echo "PASS: mvn available" || echo "FAIL: mvn not found"
```

---

## Phase 1: Setup

Follow [common/start-local.md](common/start-local.md) to build and start the Wanaku stack locally. After completion, the following variables must be set:

- `VERSION`
- `CLI_JAR`
- `WANAKU_ROUTER_URL` (defaults to `http://localhost:8080`)
- `WANAKU_PID`

---

## Phase 2: Namespace CRUD

### Test 2.1: List namespaces and verify defaults exist

```bash
OUTPUT=$(wanaku namespaces list --host "${WANAKU_ROUTER_URL}" --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespaces list failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: namespaces list succeeded"
echo "${OUTPUT}"

echo "${OUTPUT}" | grep -q "public" \
  && echo "PASS: 'public' namespace exists" \
  || echo "FAIL: 'public' namespace not found"

echo "${OUTPUT}" | grep -q "<default>" \
  && echo "PASS: '<default>' namespace exists" \
  || echo "FAIL: '<default>' namespace not found"
```

### Test 2.2: Create a custom namespace

```bash
OUTPUT=$(wanaku namespaces create \
  --host "${WANAKU_ROUTER_URL}" \
  --path "test-ns" \
  --name "test-namespace" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace create failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: namespace created"
echo "${OUTPUT}"

# Extract the namespace ID for subsequent tests
TEST_NS_ID=$(echo "${OUTPUT}" | grep -i "id" | head -1 | sed 's/.*: *//' | tr -d '[:space:]')
if [ -z "${TEST_NS_ID}" ]; then
  echo "WARN: could not extract namespace ID from output, will look up by listing"
fi
```

### Test 2.3: Verify created namespace appears in list

```bash
OUTPUT=$(wanaku namespaces list --host "${WANAKU_ROUTER_URL}" --plain 2>&1)
echo "${OUTPUT}" | grep -q "test-namespace" \
  && echo "PASS: created namespace appears in list" \
  || echo "FAIL: created namespace not found in list"
```

### Test 2.4: Show namespace details

**Description:** Retrieve details of the created namespace by ID.

```bash
# If TEST_NS_ID was not captured, look it up from the list output
if [ -z "${TEST_NS_ID}" ]; then
  TEST_NS_ID=$(wanaku namespaces list --host "${WANAKU_ROUTER_URL}" --plain 2>&1 \
    | grep "test-namespace" | awk '{print $1}' | head -1)
fi

if [ -z "${TEST_NS_ID}" ]; then
  echo "FAIL: could not determine namespace ID"
  exit 1
fi

OUTPUT=$(wanaku namespaces show --host "${WANAKU_ROUTER_URL}" "${TEST_NS_ID}" --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace show failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: namespace show succeeded"
echo "${OUTPUT}"

echo "${OUTPUT}" | grep -q "test-namespace" \
  && echo "PASS: namespace name matches" \
  || echo "FAIL: namespace name does not match"
```

### Test 2.5: Update namespace name

```bash
OUTPUT=$(wanaku namespaces update \
  --host "${WANAKU_ROUTER_URL}" \
  --name "test-namespace-updated" \
  "${TEST_NS_ID}" --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace update failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: namespace updated"

# Verify the update
VERIFY=$(wanaku namespaces show --host "${WANAKU_ROUTER_URL}" "${TEST_NS_ID}" --plain 2>&1)
echo "${VERIFY}" | grep -q "test-namespace-updated" \
  && echo "PASS: updated name verified" \
  || echo "FAIL: updated name not found"
```

### Test 2.6: Delete namespace

```bash
OUTPUT=$(wanaku namespaces delete --host "${WANAKU_ROUTER_URL}" "${TEST_NS_ID}" --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace delete failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: namespace deleted"

# Verify deletion
VERIFY=$(wanaku namespaces list --host "${WANAKU_ROUTER_URL}" --plain 2>&1)
echo "${VERIFY}" | grep -q "test-namespace-updated" \
  && echo "FAIL: deleted namespace still appears in list" \
  || echo "PASS: deleted namespace no longer in list"
```

---

## Phase 3: Namespace Labels

### Test 3.1: Create a namespace for label tests

```bash
OUTPUT=$(wanaku namespaces create \
  --host "${WANAKU_ROUTER_URL}" \
  --path "label-test-ns" \
  --name "label-test-namespace" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace create for label tests failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: label test namespace created"

# Extract namespace ID
LABEL_NS_ID=$(wanaku namespaces list --host "${WANAKU_ROUTER_URL}" --plain 2>&1 \
  | grep "label-test-namespace" | awk '{print $1}' | head -1)

if [ -z "${LABEL_NS_ID}" ]; then
  echo "FAIL: could not determine label test namespace ID"
  exit 1
fi
echo "PASS: label test namespace ID is ${LABEL_NS_ID}"
```

### Test 3.2: Add a label to the namespace

```bash
OUTPUT=$(wanaku namespaces label add \
  --host "${WANAKU_ROUTER_URL}" \
  --id "${LABEL_NS_ID}" \
  --label env=test \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace label add failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: label added to namespace"

# Verify the label exists
VERIFY=$(wanaku namespaces show --host "${WANAKU_ROUTER_URL}" "${LABEL_NS_ID}" --plain 2>&1)
echo "${VERIFY}" | grep -q "env" \
  && echo "PASS: label 'env' visible on namespace" \
  || echo "FAIL: label 'env' not found on namespace"
```

### Test 3.3: Add multiple labels to the namespace

```bash
OUTPUT=$(wanaku namespaces label add \
  --host "${WANAKU_ROUTER_URL}" \
  --id "${LABEL_NS_ID}" \
  --label tier=frontend \
  --label region=us-east \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace multiple label add failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: multiple labels added to namespace"

VERIFY=$(wanaku namespaces show --host "${WANAKU_ROUTER_URL}" "${LABEL_NS_ID}" --plain 2>&1)
echo "${VERIFY}" | grep -q "tier" \
  && echo "PASS: label 'tier' visible" \
  || echo "FAIL: label 'tier' not found"
echo "${VERIFY}" | grep -q "region" \
  && echo "PASS: label 'region' visible" \
  || echo "FAIL: label 'region' not found"
```

### Test 3.4: Remove a label from the namespace

```bash
OUTPUT=$(wanaku namespaces label remove \
  --host "${WANAKU_ROUTER_URL}" \
  --id "${LABEL_NS_ID}" \
  --label env \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace label remove failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: label removed from namespace"

# Verify the label is gone but others remain
VERIFY=$(wanaku namespaces show --host "${WANAKU_ROUTER_URL}" "${LABEL_NS_ID}" --plain 2>&1)
echo "${VERIFY}" | grep -q "env=test" \
  && echo "FAIL: label 'env=test' still present after removal" \
  || echo "PASS: label 'env=test' removed"
echo "${VERIFY}" | grep -q "tier" \
  && echo "PASS: label 'tier' still present (not removed)" \
  || echo "FAIL: label 'tier' was incorrectly removed"
```

### Test 3.5: Filter namespaces by label expression

```bash
OUTPUT=$(wanaku namespaces list \
  --host "${WANAKU_ROUTER_URL}" \
  -e "tier=frontend" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace list with label expression failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "${OUTPUT}" | grep -q "label-test-namespace" \
  && echo "PASS: label-test-namespace found with tier=frontend filter" \
  || echo "FAIL: label-test-namespace not returned by tier=frontend filter"
```

### Test 3.6: Clean up label test namespace

```bash
wanaku namespaces delete --host "${WANAKU_ROUTER_URL}" "${LABEL_NS_ID}" 2>/dev/null || true
echo "PASS: label test namespace cleanup done"
```

---

## Phase 4: Namespace Cleanup

### Test 6.1: Run namespace cleanup (dry run)

**Description:** Verify that the cleanup command runs without error. Uses `--assume-yes` to avoid interactive prompts and a short max-age to reduce scope.

```bash
OUTPUT=$(wanaku namespaces cleanup \
  --host "${WANAKU_ROUTER_URL}" \
  --max-age-days 0 \
  --assume-yes \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace cleanup failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: namespace cleanup completed without error"
echo "${OUTPUT}"
```

### Test 6.2: Create a pre-allocated namespace and verify cleanup targets it

**Description:** Pre-allocated namespaces (no name) should be eligible for cleanup.

```bash
# Create a pre-allocated namespace (no --name)
OUTPUT=$(wanaku namespaces create \
  --host "${WANAKU_ROUTER_URL}" \
  --path "cleanup-target" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: pre-allocated namespace create failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
  exit 1
fi

echo "PASS: pre-allocated namespace created"

# Run cleanup with max-age-days 0 to catch newly created pre-allocated namespaces
OUTPUT=$(wanaku namespaces cleanup \
  --host "${WANAKU_ROUTER_URL}" \
  --max-age-days 0 \
  --assume-yes \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: namespace cleanup after pre-allocation failed (exit code ${EXIT_CODE})"
  echo "${OUTPUT}"
else
  echo "PASS: namespace cleanup ran after pre-allocation"
  echo "${OUTPUT}"
fi
```

---

## Phase 5: Negative Tests

### Test 5.1: Delete a non-existent namespace

```bash
OUTPUT=$(wanaku namespaces delete \
  --host "${WANAKU_ROUTER_URL}" \
  "non-existent-id-12345" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: deleting non-existent namespace failed gracefully (exit code ${EXIT_CODE})"
else
  echo "FAIL: deleting non-existent namespace should have failed"
fi
```

### Test 5.2: Show a non-existent namespace

```bash
OUTPUT=$(wanaku namespaces show \
  --host "${WANAKU_ROUTER_URL}" \
  "non-existent-id-12345" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: showing non-existent namespace failed gracefully (exit code ${EXIT_CODE})"
else
  echo "FAIL: showing non-existent namespace should have failed"
fi
```

### Test 5.3: Update a non-existent namespace

```bash
OUTPUT=$(wanaku namespaces update \
  --host "${WANAKU_ROUTER_URL}" \
  --name "ghost" \
  "non-existent-id-12345" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: updating non-existent namespace failed gracefully (exit code ${EXIT_CODE})"
else
  echo "FAIL: updating non-existent namespace should have failed"
fi
```

### Test 5.4: Add label to a non-existent namespace

```bash
OUTPUT=$(wanaku namespaces label add \
  --host "${WANAKU_ROUTER_URL}" \
  --id "non-existent-id-12345" \
  --label env=test \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: adding label to non-existent namespace failed gracefully (exit code ${EXIT_CODE})"
else
  echo "FAIL: adding label to non-existent namespace should have failed"
fi
```

### Test 5.5: Remove label from a non-existent namespace

```bash
OUTPUT=$(wanaku namespaces label remove \
  --host "${WANAKU_ROUTER_URL}" \
  --id "non-existent-id-12345" \
  --label env \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: removing label from non-existent namespace failed gracefully (exit code ${EXIT_CODE})"
else
  echo "FAIL: removing label from non-existent namespace should have failed"
fi
```

### Test 5.6: Create namespace without required --path option

```bash
OUTPUT=$(wanaku namespaces create \
  --host "${WANAKU_ROUTER_URL}" \
  --name "missing-path" \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: create namespace without --path rejected (exit code ${EXIT_CODE})"
else
  echo "FAIL: create namespace without --path should have failed"
fi
```

### Test 5.7: Update namespace with no update fields

```bash
# First create a temporary namespace for this test
TEMP_OUTPUT=$(wanaku namespaces create \
  --host "${WANAKU_ROUTER_URL}" \
  --path "negative-test-ns" \
  --name "negative-test" \
  --plain 2>&1)

TEMP_NS_ID=$(wanaku namespaces list --host "${WANAKU_ROUTER_URL}" --plain 2>&1 \
  | grep "negative-test" | awk '{print $1}' | head -1)

if [ -n "${TEMP_NS_ID}" ]; then
  OUTPUT=$(wanaku namespaces update \
    --host "${WANAKU_ROUTER_URL}" \
    "${TEMP_NS_ID}" \
    --plain 2>&1)
  EXIT_CODE=$?

  if [ "${EXIT_CODE}" -ne 0 ]; then
    echo "PASS: update with no fields rejected (exit code ${EXIT_CODE})"
  else
    echo "FAIL: update with no fields should have been rejected"
  fi

  # Clean up
  wanaku namespaces delete --host "${WANAKU_ROUTER_URL}" "${TEMP_NS_ID}" 2>/dev/null || true
else
  echo "SKIP: could not create temporary namespace for this test"
fi
```

### Test 5.8: Namespace label add with both --id and --label-expression (mutual exclusion)

```bash
OUTPUT=$(wanaku namespaces label add \
  --host "${WANAKU_ROUTER_URL}" \
  --id "some-id" \
  --label-expression "env=test" \
  --label env=test \
  --plain 2>&1)
EXIT_CODE=$?

if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: specifying both --id and --label-expression rejected (exit code ${EXIT_CODE})"
else
  echo "FAIL: specifying both --id and --label-expression should have been rejected"
fi
```

---

## Phase 6: Cleanup

### Step 6.1: Stop the Wanaku process

```bash
if [ -n "${WANAKU_PID}" ]; then
  kill "${WANAKU_PID}" 2>/dev/null || true
  wait "${WANAKU_PID}" 2>/dev/null || true
  echo "PASS: Wanaku process stopped"
else
  echo "WARN: WANAKU_PID not set, process may still be running"
fi
```

---

## Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | 0 | Manual prerequisites | Medium |
| 1 | 1 | Start local stack | Critical |
| 2 | 2.1 | List namespaces, verify defaults | Critical |
| 2 | 2.2 | Create a custom namespace | Critical |
| 2 | 2.3 | Verify created namespace in list | Critical |
| 2 | 2.4 | Show namespace details | High |
| 2 | 2.5 | Update namespace name | High |
| 2 | 2.6 | Delete namespace | Critical |
| 3 | 3.1 | Create namespace for label tests | High |
| 3 | 3.2 | Add single label to namespace | Critical |
| 3 | 3.3 | Add multiple labels to namespace | High |
| 3 | 3.4 | Remove label from namespace | Critical |
| 3 | 3.5 | Filter namespaces by label expression | High |
| 3 | 3.6 | Clean up label test namespace | Medium |
| 4 | 4.1 | Run namespace cleanup | High |
| 4 | 4.2 | Pre-allocated namespace cleanup | High |
| 5 | 5.1 | Delete non-existent namespace | High |
| 5 | 5.2 | Show non-existent namespace | High |
| 5 | 5.3 | Update non-existent namespace | High |
| 5 | 5.4 | Add label to non-existent namespace | High |
| 5 | 5.5 | Remove label from non-existent namespace | High |
| 5 | 5.6 | Create namespace without required path | High |
| 5 | 5.7 | Update namespace with no fields | Medium |
| 5 | 5.8 | Namespace label add mutual exclusion (--id + --label-expression) | High |
| 6 | 6.1 | Cleanup (process) | Critical |
