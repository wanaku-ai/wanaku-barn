# Test Plan: Wanaku MCP CLI Commands

## Overview

This test plan verifies the `wanaku mcp` CLI commands. These commands talk to an MCP endpoint directly. The plan points
them at the MCP endpoint of **Wanaku Server** (`${WANAKU_MCP_BASE_URL}/<namespace>/mcp`). The tools, resources and
prompts come from the MCP reference server, which is registered with Wanaku Server as a forward. Thus the
`list`, `call`, `read` and `get` operations return real data.

The plan has two parts:

- **Part 1 — Local:** help output, option validation, tool/resource/prompt operations, namespace isolation,
  governance, enable/disable and error handling against a local Wanaku Server.
- **Part 2 — OpenShift:** the operator deploys Wanaku Server with oauth2-proxy authentication. The plan registers an
  in-cluster MCP server as a forward and uses the `wanaku mcp` commands through the MCP Route with a bearer token.

**Components:**

| Component | Part 1 | Part 2 |
|-----------|--------|--------|
| Wanaku Server | Required (local) | Required (operator, `spec.server`) |
| Wanaku Barn | Not used | Not used (`spec.barn` is not enabled) |
| MCP reference server (`@modelcontextprotocol/server-everything`) | Required (local, forward `everything`) | Required (in-cluster Deployment, forward `everything`) |
| Keycloak and oauth2-proxy | Not used | Required (`spec.auth.enabled: true`) |

Every step in Part 1 is automatable. In Part 2, only the OpenShift login is manual.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `wanaku` CLI | build from source | `wanaku --version` |
| `java` | 21+ | `java -version` |
| `node` / `npx` | 18+ | `npx --version` |
| `curl` | any | `curl --version` |
| `jq` | 1.6+ | `jq --version` |

### CLI invocation

The plan calls the CLI as `wanaku`. To use the CLI jar from a local build, Step S.1 defines a shell function. A
function works in `sh`, `bash` and `zsh`.

Do not put the full command in a variable (for example `WANAKU_CLI="java -jar ..."`). `zsh` does not split it into
words.

### How to read MCP command output

- The `wanaku mcp` commands write results to stdout. Capture stdout and stderr separately. When the CLI talks to an
  upstream server that sends SSE frames, the MCP client library can write `WARN` log lines with Java stack traces to
  stderr. These lines are not errors of the command.
- `wanaku mcp ... list` prints one line per entry: `<name> <description>` for tools and prompts, and
  `<name> <uri> <mimeType>` for resources. An empty list prints `No tools found` (or `No resources found`,
  `No prompts found`) and exits with `0`.
- A failed call prints the error message and exits with `1`. Missing picocli-required options (for example `--uri`
  on `list` subcommands) exit with `2`.
- The CLI sends every `--param` and `--arg` value as a JSON **string**. A tool that requires a number or boolean
  argument (for example `get-sum`) rejects the call with `-32602`. Use string arguments for success paths.

### Known limitations

- Wanaku Server is fail-safe. It denies `tools/call`, `resources/read` and `prompts/get` in a namespace unless the
  governance configuration or an action policy allows them. The denial message is
  `No governance policy permits this action.` (action-policy filter, `-32003`) or `evaluator: no_match` (evaluator
  filter). Listing requests are not governed. Part 1 starts the server with the test governance configuration of
  [common/start-server.md](common/start-server.md) (step 2b).
- The operator has no field to supply the governance configuration. Part 2 therefore verifies the fail-safe denial
  for calls. The success path of calls on OpenShift is blocked until the operator supports it (see Test 11.2).
- The `wanaku mcp` commands accept `--insecure`, but they do not apply it. The MCP client always verifies the TLS
  certificate. Against an endpoint with an untrusted certificate, add the CA to the Java trust store (for example
  with `-Djavax.net.ssl.trustStore` in `JAVA_TOOL_OPTIONS`). The other CLI commands apply `--insecure`.
- When the MCP server is not reachable, the CLI prints `java.util.concurrent.ExecutionException:
  java.net.ConnectException` instead of the friendly `Connection refused` message. Test 8.3 reports this with a `WARN`
  line.
- Tool, resource and prompt names are unique in the whole Wanaku Server registry, not per namespace. Do not register
  the same upstream server twice in one server. The second discovery moves the entries to the namespace of the second
  forward.

---
---

## Part 1: Local

### Environment variables

The environment variables are set in Step S.2.

The reference server forward goes into `MCP_NAMESPACE`. `MCP_ISOLATED_NAMESPACE` is governed but has no forward.
`MCP_UNGOVERNED_NAMESPACE` has the fail-safe defaults.

## Phase S: Setup

### Step S.1: Define the CLI invocation

```bash
CLI_JAR="${CLI_JAR:-apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
wanaku() { java -jar "${CLI_JAR}" "$@"; }
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.2: Set local environment variables

```bash
export MCP_NAMESPACE="${MCP_NAMESPACE:-default}"
export MCP_ISOLATED_NAMESPACE="${MCP_ISOLATED_NAMESPACE:-mcp-isolated}"
export MCP_UNGOVERNED_NAMESPACE="${MCP_UNGOVERNED_NAMESPACE:-mcp-ungoverned}"
export MCP_UNREACHABLE_URI="${MCP_UNREACHABLE_URI:-http://localhost:59999/default/mcp}"
# Governance allows calls only in these namespaces (see common/start-server.md, step 2b)
export WANAKU_TEST_GOVERNED_NAMESPACES="${MCP_NAMESPACE},${MCP_ISOLATED_NAMESPACE}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

## Phase 0: Prerequisites

### Test 0.1: Verify the required tools

```bash
for CMD in java npx curl jq; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS [0.1]: ${CMD} available" || echo "FAIL [0.1]: ${CMD} not found"
done
wanaku --version > /dev/null 2>&1 && echo "PASS [0.1]: wanaku CLI available" || echo "FAIL [0.1]: wanaku CLI not available"
```

**Expected result:**

- The output includes `PASS [0.1]: java available`.
- The output includes `PASS [0.1]: npx available`.
- The output includes `PASS [0.1]: curl available`.
- The output includes `PASS [0.1]: jq available`.
- The output includes `PASS [0.1]: wanaku CLI available`.
- The output contains no line starting with `FAIL`.

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the test governance configuration

Follow [common/start-server.md](common/start-server.md).

Run the optional step 2b with the
`WANAKU_TEST_GOVERNED_NAMESPACES` value from the environment variables above. After completion,
`WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID` and `WANAKU_SERVER_DATA_DIR` must be set.

```bash
grep -q "${MCP_ISOLATED_NAMESPACE}:" "${WANAKU_SERVER_DATA_DIR}/wanaku.yaml" \
  && echo "PASS [1.1]: governance configuration allows ${WANAKU_TEST_GOVERNED_NAMESPACES}" \
  || echo "FAIL [1.1]: governance configuration does not contain ${MCP_ISOLATED_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS [1.1]: governance configuration allows <WANAKU_TEST_GOVERNED_NAMESPACES>`.
- The output contains no line starting with `FAIL`.

### Step 1.2: Start the MCP reference server and register it

Use `TEST_MCP_NAMESPACE="${MCP_NAMESPACE}"` for the procedure.

Follow [common/start-test-mcp-server.md](common/start-test-mcp-server.md).
After completion, `TEST_MCP_URL`, `TEST_MCP_PID` and the forward `everything` must exist.

**Expected result:**

- `TEST_MCP_URL` and `TEST_MCP_PID` are set, and the forward `everything` exists.

### Step 1.3: Create the test namespaces

```bash
for NS in "${MCP_ISOLATED_NAMESPACE}" "${MCP_UNGOVERNED_NAMESPACE}"; do
  wanaku namespaces create "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null
  [ $? -eq 0 ] && echo "PASS [1.3]: namespace ${NS} created" || echo "FAIL [1.3]: namespace ${NS} not created"
done
```

**Expected result:**

- The output includes `PASS [1.3]: namespace <MCP_ISOLATED_NAMESPACE> created`.
- The output includes `PASS [1.3]: namespace <MCP_UNGOVERNED_NAMESPACE> created`.
- The output contains no line starting with `FAIL`.

### Step 1.4: Set the MCP endpoint

```bash
export MCP_URI="${WANAKU_MCP_BASE_URL}/${MCP_NAMESPACE}/mcp"
echo "MCP endpoint: ${MCP_URI}"
```

**Expected result:**

- The output includes `MCP endpoint: <MCP_URI>` (inferred).

---

## Phase 2: Help and Usage

### Test 2.1: `wanaku mcp` shows the subcommands

```bash
OUTPUT=$(wanaku mcp 2>&1)
for SUB in tool resource prompt; do
  echo "${OUTPUT}" | grep -q "^  ${SUB} " && echo "PASS [2.1]: '${SUB}' listed" || echo "FAIL [2.1]: '${SUB}' not listed"
done
```

**Expected result:**

- The output includes `PASS [2.1]: 'tool' listed`.
- The output includes `PASS [2.1]: 'resource' listed`.
- The output includes `PASS [2.1]: 'prompt' listed`.
- The output contains no line starting with `FAIL`.

### Test 2.2: Subcommand help shows the options

```bash
check_help() {
  HELP=$(wanaku mcp "$1" --help 2>&1)
  shift
  for OPT in "$@"; do
    echo "${HELP}" | grep -q -- "${OPT}" && echo "PASS [2.2]: ${OPT} listed" || echo "FAIL [2.2]: ${OPT} missing"
  done
  echo "${HELP}" | grep -q "^  list " && echo "PASS [2.2]: 'list' listed" || echo "FAIL [2.2]: 'list' missing"
}
check_help tool --uri --name --param
check_help resource --uri --resource-uri
check_help prompt --uri --name --arg
```

**Expected result:**

- The output includes `PASS [2.2]: --uri listed`.
- The output includes `PASS [2.2]: --name listed`.
- The output includes `PASS [2.2]: --param listed`.
- The output includes `PASS [2.2]: --resource-uri listed`.
- The output includes `PASS [2.2]: --arg listed`.
- The output includes `PASS [2.2]: 'list' listed`.
- The output contains no line starting with `FAIL`.

---

## Phase 3: Required Option Validation

The call commands check `--uri`, `--name` and `--resource-uri` themselves and exit with `1`. The `list` subcommands
declare `--uri` as a required picocli option and exit with `2`.

### Test 3.1: Missing options are rejected

```bash
expect_exit() {
  EXPECTED="$1"; MESSAGE="$2"; shift 2
  OUTPUT=$("$@" 2>&1)
  EXIT_CODE=$?
  if [ "${EXIT_CODE}" -eq "${EXPECTED}" ] && echo "${OUTPUT}" | grep -q -- "${MESSAGE}"; then
    echo "PASS [3.1]: '$*' exits ${EXIT_CODE} with '${MESSAGE}'"
  else
    echo "FAIL [3.1]: '$*' exit ${EXIT_CODE}, expected ${EXPECTED} with '${MESSAGE}'"
    echo "${OUTPUT}"
  fi
}
expect_exit 1 "Missing required option: --uri"          wanaku mcp tool --no-auth --name echo
expect_exit 1 "Missing required option: --name"         wanaku mcp tool --no-auth --uri "${MCP_URI}"
expect_exit 1 "Missing required option: --resource-uri" wanaku mcp resource --no-auth --uri "${MCP_URI}"
expect_exit 1 "Missing required option: --name"         wanaku mcp prompt --no-auth --uri "${MCP_URI}"
expect_exit 2 "Missing required option"                 wanaku mcp tool list --no-auth
expect_exit 2 "Missing required option"                 wanaku mcp resource list --no-auth
expect_exit 2 "Missing required option"                 wanaku mcp prompt list --no-auth
```

**Expected result:**

- The output includes `PASS [3.1]: 'wanaku mcp tool --no-auth --name echo' exits 1 with 'Missing required option: --uri'`.
- The output includes `PASS [3.1]: 'wanaku mcp tool --no-auth --uri <MCP_URI>' exits 1 with 'Missing required option: --name'`.
- The output includes `PASS [3.1]: 'wanaku mcp resource --no-auth --uri <MCP_URI>' exits 1 with 'Missing required option: --resource-uri'`.
- The output includes `PASS [3.1]: 'wanaku mcp prompt --no-auth --uri <MCP_URI>' exits 1 with 'Missing required option: --name'`.
- The output includes `PASS [3.1]: 'wanaku mcp tool list --no-auth' exits 2 with 'Missing required option'`.
- The output includes `PASS [3.1]: 'wanaku mcp resource list --no-auth' exits 2 with 'Missing required option'`.
- The output includes `PASS [3.1]: 'wanaku mcp prompt list --no-auth' exits 2 with 'Missing required option'`.
- The output contains no line starting with `FAIL`.

---

## Phase 4: Tool Operations

### Test 4.1: List the tools of the namespace

```bash
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS [4.1]: tool list exit 0" || echo "FAIL [4.1]: tool list exit ${EXIT_CODE}"
for TOOL in echo get-sum; do
  echo "${OUTPUT}" | grep -q "^${TOOL} " && echo "PASS [4.1]: '${TOOL}' listed" || echo "FAIL [4.1]: '${TOOL}' not listed"
done
```

**Expected result:**

- The output includes `PASS [4.1]: tool list exit 0`.
- The output includes `PASS [4.1]: 'echo' listed`.
- The output includes `PASS [4.1]: 'get-sum' listed`.
- The output contains no line starting with `FAIL`.

### Test 4.2: Call a tool with one parameter

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo --param message=hello --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && [ "${OUTPUT}" = "Echo: hello" ]; then
  echo "PASS [4.2]: echo returned 'Echo: hello'"
else
  echo "FAIL [4.2]: exit ${EXIT_CODE}, output '${OUTPUT}'"
fi
```

**Expected result:**

- The output includes `PASS [4.2]: echo returned 'Echo: hello'`.
- The output contains no line starting with `FAIL`.

### Test 4.3: Call a tool with repeated and comma-separated parameters

The CLI accepts `--param` more than once, and it splits one value at commas. The reference server ignores the extra
key.

```bash
for ARGS in "--param message=hello --param extra=x" "--param message=hello,extra=x"; do
  # shellcheck disable=SC2086 # word splitting of ARGS is intended
  OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo ${ARGS} --plain 2>/dev/null)
  [ "${OUTPUT}" = "Echo: hello" ] && echo "PASS [4.3]: ${ARGS}" || echo "FAIL [4.3]: ${ARGS} returned '${OUTPUT}'"
done
```

**Expected result:**

- The output includes `PASS [4.3]: --param message=hello --param extra=x`.
- The output includes `PASS [4.3]: --param message=hello,extra=x`.
- The output contains no line starting with `FAIL`.

### Test 4.4: Non-string tool arguments are rejected

The CLI sends `a=2` and `b=3` as strings. `get-sum` requires numbers.

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name get-sum --param a=2 --param b=3 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "expected number, received string"; then
  echo "PASS [4.4]: string arguments rejected by the upstream schema"
else
  echo "FAIL [4.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.4]: string arguments rejected by the upstream schema`.
- The output contains no line starting with `FAIL`.

### Test 4.5: Call a tool without its required argument

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q -- "-32602"; then
  echo "PASS [4.5]: missing 'message' rejected with -32602"
else
  echo "FAIL [4.5]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.5]: missing 'message' rejected with -32602`.
- The output contains no line starting with `FAIL`.

### Test 4.6: Call an unknown tool

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name non-existent-tool-12345 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "tool not found: non-existent-tool-12345"; then
  echo "PASS [4.6]: unknown tool rejected"
else
  echo "FAIL [4.6]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.6]: unknown tool rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 5: Resource Operations

### Test 5.1: List the resources of the namespace

```bash
OUTPUT=$(wanaku mcp resource list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^architecture.md .*demo://resource/static/document/architecture.md"; then
  echo "PASS [5.1]: architecture.md listed with its URI"
else
  echo "FAIL [5.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.1]: architecture.md listed with its URI`.
- The output contains no line starting with `FAIL`.

### Test 5.2: Read a resource

```bash
RESOURCE_URI="${TEST_RESOURCE_URI:-demo://resource/static/document/architecture.md}"
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "${RESOURCE_URI}" --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "McpTextResourceContents\[uri=${RESOURCE_URI}"; then
  echo "PASS [5.2]: resource content returned"
else
  echo "FAIL [5.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.2]: resource content returned`.
- The output contains no line starting with `FAIL`.

### Test 5.3: Read an unknown resource

Wanaku Server looks up the URI in its registry before it forwards the request.

```bash
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "demo://non-existent-12345" --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "resource not found: demo://non-existent-12345"; then
  echo "PASS [5.3]: unknown resource rejected"
else
  echo "FAIL [5.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.3]: unknown resource rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 6: Prompt Operations

### Test 6.1: List the prompts of the namespace

```bash
OUTPUT=$(wanaku mcp prompt list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
for PROMPT in simple-prompt args-prompt; do
  echo "${OUTPUT}" | grep -q "^${PROMPT} " && echo "PASS [6.1]: '${PROMPT}' listed" || echo "FAIL [6.1]: '${PROMPT}' not listed"
done
```

**Expected result:**

- The output includes `PASS [6.1]: 'simple-prompt' listed`.
- The output includes `PASS [6.1]: 'args-prompt' listed`.
- The output contains no line starting with `FAIL`.

### Test 6.2: Get a prompt without arguments

```bash
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name simple-prompt --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "This is a simple prompt without arguments." \
  && echo "PASS [6.2]: simple-prompt rendered" || echo "FAIL [6.2]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [6.2]: simple-prompt rendered`.
- The output contains no line starting with `FAIL`.

### Test 6.3: Get a prompt with arguments

```bash
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name args-prompt --arg city=Paris --arg state=Texas --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "Paris, Texas" \
  && echo "PASS [6.3]: args-prompt rendered with both arguments" || echo "FAIL [6.3]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [6.3]: args-prompt rendered with both arguments`.
- The output contains no line starting with `FAIL`.

### Test 6.4: Get a prompt without its required argument

```bash
wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name args-prompt --plain > /dev/null 2>&1
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 1 ] && echo "PASS [6.4]: missing 'city' rejected" || echo "FAIL [6.4]: exit ${EXIT_CODE}"
```

**Expected result:**

- The output includes `PASS [6.4]: missing 'city' rejected`.
- The output contains no line starting with `FAIL`.

### Test 6.5: Get an unknown prompt

```bash
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name non-existent-prompt-12345 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "prompt not found: non-existent-prompt-12345"; then
  echo "PASS [6.5]: unknown prompt rejected"
else
  echo "FAIL [6.5]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [6.5]: unknown prompt rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 7: Namespaces and Governance

### Test 7.1: A namespace shows only its own entries

`MCP_ISOLATED_NAMESPACE` is governed, but it has no forward.

```bash
ISO_URI="${WANAKU_MCP_BASE_URL}/${MCP_ISOLATED_NAMESPACE}/mcp"
for KIND in tool resource prompt; do
  OUTPUT=$(wanaku mcp "${KIND}" list --uri "${ISO_URI}" --no-auth --plain 2>/dev/null)
  echo "${OUTPUT}" | grep -q "^No ${KIND}s found" \
    && echo "PASS [7.1]: no ${KIND}s in ${MCP_ISOLATED_NAMESPACE}" || echo "FAIL [7.1]: ${KIND}s leaked: ${OUTPUT}"
done
```

**Expected result:**

- The output includes `PASS [7.1]: no tools in <MCP_ISOLATED_NAMESPACE>`.
- The output includes `PASS [7.1]: no resources in <MCP_ISOLATED_NAMESPACE>`.
- The output includes `PASS [7.1]: no prompts in <MCP_ISOLATED_NAMESPACE>`.
- The output contains no line starting with `FAIL`.

### Test 7.2: A tool of another namespace cannot be called

```bash
OUTPUT=$(wanaku mcp tool --uri "${ISO_URI}" --no-auth --name echo --param message=hello --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "tool not found: echo"; then
  echo "PASS [7.2]: 'echo' is not callable from ${MCP_ISOLATED_NAMESPACE}"
else
  echo "FAIL [7.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [7.2]: 'echo' is not callable from <MCP_ISOLATED_NAMESPACE>`.
- The output contains no line starting with `FAIL`.

### Test 7.3: The fail-safe default denies calls in a namespace that is not governed

The governance check runs before the tool lookup. Listing is not governed.

```bash
UNGOV_URI="${WANAKU_MCP_BASE_URL}/${MCP_UNGOVERNED_NAMESPACE}/mcp"
wanaku mcp tool list --uri "${UNGOV_URI}" --no-auth --plain > /dev/null 2>&1 \
  && echo "PASS [7.3]: listing is allowed in ${MCP_UNGOVERNED_NAMESPACE}" || echo "FAIL [7.3]: listing failed"

OUTPUT=$(wanaku mcp tool --uri "${UNGOV_URI}" --no-auth --name echo --param message=hello --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "No governance policy permits this action."; then
  echo "PASS [7.3]: tools/call denied by the fail-safe default"
else
  echo "FAIL [7.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [7.3]: listing is allowed in <MCP_UNGOVERNED_NAMESPACE>`.
- The output includes `PASS [7.3]: tools/call denied by the fail-safe default`.
- The output contains no line starting with `FAIL`.

### Test 7.4: A disabled tool is hidden from MCP clients

The enable and disable routes have no CLI command, so this test uses `curl`.

```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/echo/disable")
[ "${HTTP_CODE}" = "200" ] && echo "PASS [7.4]: echo disabled" || echo "FAIL [7.4]: disable returned HTTP ${HTTP_CODE}"

wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null | grep -q "^echo " \
  && echo "FAIL [7.4]: disabled tool still listed" || echo "PASS [7.4]: disabled tool not listed"

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo --param message=hello --plain 2>&1)
echo "${OUTPUT}" | grep -q "tool not found: echo" \
  && echo "PASS [7.4]: disabled tool cannot be called" || echo "FAIL [7.4]: output: ${OUTPUT}"

# The management API still lists the entry
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^echo" \
  && echo "PASS [7.4]: management API still lists echo" || echo "FAIL [7.4]: echo missing from management API"
```

**Expected result:**

- The output includes `PASS [7.4]: echo disabled`.
- The output includes `PASS [7.4]: disabled tool not listed`.
- The output includes `PASS [7.4]: disabled tool cannot be called`.
- The output includes `PASS [7.4]: management API still lists echo`.
- The output contains no line starting with `FAIL`.

### Test 7.5: An enabled tool is visible again

```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/echo/enable")
[ "${HTTP_CODE}" = "200" ] && echo "PASS [7.5]: echo enabled" || echo "FAIL [7.5]: enable returned HTTP ${HTTP_CODE}"

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name echo --param message=again --plain 2>/dev/null)
[ "${OUTPUT}" = "Echo: again" ] && echo "PASS [7.5]: echo callable again" || echo "FAIL [7.5]: output '${OUTPUT}'"
```

**Expected result:**

- The output includes `PASS [7.5]: echo enabled`.
- The output includes `PASS [7.5]: echo callable again`.
- The output contains no line starting with `FAIL`.

---

## Phase 8: Endpoint and Connection Errors

### Test 8.1: The bare `/mcp` path is rejected

Wanaku Server returns the JSON-RPC error `-32600` for an MCP request without a namespace.

```bash
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/mcp" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "invalid MCP endpoint path: use /{namespace}/mcp"; then
  echo "PASS [8.1]: bare /mcp rejected"
else
  echo "FAIL [8.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [8.1]: bare /mcp rejected`.
- The output contains no line starting with `FAIL`.

### Test 8.2: A legacy SSE endpoint is rejected by the CLI

```bash
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}/sse" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Legacy SSE MCP endpoints are no longer supported"; then
  echo "PASS [8.2]: SSE endpoint rejected with a migration hint"
else
  echo "FAIL [8.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [8.2]: SSE endpoint rejected with a migration hint`.
- The output contains no line starting with `FAIL`.

### Test 8.3: An unreachable server fails without a stack trace

The current CLI prints `java.util.concurrent.ExecutionException: java.net.ConnectException`. The `WARN` line is
therefore expected until the CLI shows the friendly message.

```bash
OUTPUT=$(wanaku mcp tool list --uri "${MCP_UNREACHABLE_URI}" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && ! echo "${OUTPUT}" | grep -q "at .*(.*\.java:"; then
  echo "PASS [8.3]: unreachable server fails gracefully"
else
  echo "FAIL [8.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
echo "${OUTPUT}" | grep -q "Connection refused" \
  || echo "WARN [8.3]: message is not the friendly 'Connection refused' text: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [8.3]: unreachable server fails gracefully`.
- The output contains no line starting with `FAIL`.
- The output contains `Connection refused`, or includes `WARN [8.3]: message is not the friendly`.

### Test 8.4: An invalid URI fails gracefully

```bash
OUTPUT=$(wanaku mcp tool list --uri "not-a-valid-uri" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Invalid URI: 'not-a-valid-uri'"; then
  echo "PASS [8.4]: invalid URI rejected"
else
  echo "FAIL [8.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [8.4]: invalid URI rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 9: Cleanup (Part 1)

### Step 9.1: Cleanup (Part 1)

```bash
for NS in "${MCP_ISOLATED_NAMESPACE}" "${MCP_UNGOVERNED_NAMESPACE}"; do
  wanaku namespaces delete "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1 || true
done
curl -s -o /dev/null -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/echo/enable" 2>/dev/null || true
echo "PASS [9.1]: test namespaces removed"
```

Then follow the **Shutdown** sections of [common/start-test-mcp-server.md](common/start-test-mcp-server.md) and
[common/start-server.md](common/start-server.md), in this order.

```bash
java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${TEST_MCP_FORWARD:-everything}" 2>/dev/null || true
# TEST_MCP_PID is the npx wrapper. Stop the node process that listens on the port too.
if [ -n "${TEST_MCP_PID}" ]; then
  kill "${TEST_MCP_PID}" 2>/dev/null || true
  wait "${TEST_MCP_PID}" 2>/dev/null || true
fi
lsof -ti "tcp:${TEST_MCP_PORT:-3001}" -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null || true
echo "PASS: reference MCP server stopped"
```

```bash
if [ -n "${WANAKU_SERVER_PID}" ]; then
  kill "${WANAKU_SERVER_PID}" 2>/dev/null || true
  wait "${WANAKU_SERVER_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Server stopped"
fi
rm -rf "${WANAKU_SERVER_DATA_DIR}"
```

**Expected result:**

- The output includes `PASS [9.1]: test namespaces removed`.
- The output includes `PASS: reference MCP server stopped`.
- The output includes `PASS: Wanaku Server stopped`.

## Part 1 Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| S | S.1-S.2 | CLI invocation and local environment | High |
| 0 | 0.1 | Prerequisites | High |
| 1 | 1.1-1.4 | Server with test governance, reference forward, namespaces | Critical |
| 2 | 2.1-2.2 | Help and usage output | Medium |
| 3 | 3.1 | Required option validation (exit codes 1 and 2) | High |
| 4 | 4.1-4.6 | Tool list, call, multi-param, string-only params, missing arg, unknown tool | Critical |
| 5 | 5.1-5.3 | Resource list, read, unknown resource | Critical |
| 6 | 6.1-6.5 | Prompt list, get, get with args, missing arg, unknown prompt | Critical |
| 7 | 7.1-7.5 | Namespace isolation, fail-safe governance, disable/enable | Critical |
| 8 | 8.1-8.4 | Bare `/mcp`, legacy SSE, unreachable server, invalid URI | High |
| 9 | 9.1 | Cleanup | Critical |

---
---

## Part 2: OpenShift with the Operator and oauth2-proxy

## Part 2 overview

The operator deploys Wanaku Server from a `WanakuRouter` resource (`spec.server`) with authentication enabled
(`spec.auth`). The operator creates:

- the `<router>-server` Deployment and the `server-<router>` Service (ports 8080 management, 8081 MCP, 8083, 8084)
- the `<router>-oauth2-proxy` Deployment (4180 MCP proxy, 4181 management proxy)
- the Route `<router>` for the management API and the Route `<router>-mcp` for the MCP listener (edge TLS)

The plan deploys the MCP reference server in the cluster and registers it as a forward with the CLI. This replaces
the removed `WanakuCapability` resource. The operator also offers `WanakuIntegration` for the Camel Integration
Capability; this plan does not need it. Barn is not deployed.

## Part 2 prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `oc` | 4.12+ | `oc version --client` |
| `helm` | 3.x | `helm version --short` |
| `wanaku` CLI and `wanaku-keycloak-admin` | build from source | `wanaku --version` |
| `openssl` | any | `openssl version` |
| `curl`, `jq` | any / 1.6+ | `jq --version` |

### Environment variables

Step 11.1 sets a unique `WANAKU_NAMESPACE` using the namespace setup procedure.

The environment variables are set in Step S.3 after Step 11.1.

The reference server pod downloads `TEST_MCP_PACKAGE` from the npm registry. The cluster needs egress to
`registry.npmjs.org`, or a mirror set with `npm_config_registry` in the Deployment.

Step S.4 defines `wait_for_deletion` from [common/wait-for-deletion.md](common/wait-for-deletion.md).

---

## Phase 10: OpenShift Login (MANUAL)

### Step 10.1: Log in to OpenShift (MANUAL)

Follow [common/openshift-login.md](common/openshift-login.md).

**Expected result:**

- All procedure steps passed, and `oc whoami` shows the service account.

---

## Phase 11: Deploy the Wanaku Stack

### Step 11.1: Create the namespace

Follow [common/namespace-setup.md](common/namespace-setup.md).

**Expected result:**

- The current namespace is the value of `WANAKU_NAMESPACE`.

## Phase T: Setup for OpenShift

### Step S.3: Set OpenShift environment variables

```bash
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-mcp-test}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_OAUTH2_PROXY_IMAGE="${WANAKU_OAUTH2_PROXY_IMAGE:-}"   # empty: operator default
export TEST_MCP_NODE_IMAGE="${TEST_MCP_NODE_IMAGE:-registry.access.redhat.com/ubi9/nodejs-22:latest}"
export TEST_MCP_PACKAGE="${TEST_MCP_PACKAGE:-@modelcontextprotocol/server-everything@1.0.0}"
export MCP_E2E_NAMESPACE="${MCP_E2E_NAMESPACE:-mcp-e2e}"
# Set to "--insecure" when the cluster Route certificates are not trusted.
# The wanaku mcp commands ignore this flag (see Known limitations): trust the Route CA in Java instead.
export WANAKU_TLS_FLAG="${WANAKU_TLS_FLAG:-}"
# Isolate CLI credentials per test run (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-${WANAKU_NAMESPACE}}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.4: Define the deletion helper

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the function without errors (inferred).

## Phase 11b: Deploy the Wanaku Stack (continued)

### Step 11.2: Deploy Keycloak

Follow [common/keycloak-setup.md](common/keycloak-setup.md).

After completion, verify the variables:

Run this block as a separate `bash` script, not in the shared session.

```bash
for VAR_NAME in KEYCLOAK_URL WANAKU_CLIENT_SECRET WANAKU_TEST_USER WANAKU_TEST_PASS; do
  eval "VAL=\${${VAR_NAME}}"
  [ -n "${VAL}" ] && echo "PASS [11.2]: ${VAR_NAME} is set" || { echo "FAIL [11.2]: ${VAR_NAME} is not set"; exit 1; }
done
```

**Expected result:**

- The output includes `PASS [11.2]: KEYCLOAK_URL is set`.
- The output includes `PASS [11.2]: WANAKU_CLIENT_SECRET is set`.
- The output includes `PASS [11.2]: WANAKU_TEST_USER is set`.
- The output includes `PASS [11.2]: WANAKU_TEST_PASS is set`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.3: Install the operator

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is available and the procedure checks pass.

### Step 11.4: Create the oauth2-proxy Secret

The proxies use the `wanaku-mcp-router` client. The Secret needs the keys `client-secret` and `cookie-secret`.

Run this block as a separate `bash` script, not in the shared session.

```bash
oc create secret generic "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="$(openssl rand -hex 16)" 2>/dev/null \
  || oc get secret "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" > /dev/null
[ $? -eq 0 ] && echo "PASS [11.4]: oauth2-proxy Secret exists" || { echo "FAIL [11.4]: Secret not created"; exit 1; }
```

**Expected result:**

- The output includes `PASS [11.4]: oauth2-proxy Secret exists`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.5: Create the WanakuRouter

Run this block as a separate `bash` script, not in the shared session.

```bash
{
cat <<EOF
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  server:
    image: ${WANAKU_SERVER_IMAGE}
    imagePullPolicy: Always
  auth:
    enabled: true
    issuerUrl: ${KEYCLOAK_URL}/realms/wanaku
    internalIssuerUrl: http://keycloak:8080/realms/wanaku
    clientId: wanaku-mcp-router
    secretName: ${WANAKU_ROUTER_NAME}-oidc
EOF
[ -n "${WANAKU_OAUTH2_PROXY_IMAGE}" ] && echo "    image: ${WANAKU_OAUTH2_PROXY_IMAGE}"
} | oc apply -n "${WANAKU_NAMESPACE}" -f -

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=300s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS [11.5]: WanakuRouter is Ready" || { echo "FAIL [11.5]: WanakuRouter not Ready"; exit 1; }
```

**Expected result:**

- The output includes `PASS [11.5]: WanakuRouter is Ready`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.6: Verify the generated resources

```bash
R="${WANAKU_ROUTER_NAME}"
for DEP in "${R}-server" "${R}-oauth2-proxy"; do
  oc rollout status "deployment/${DEP}" --timeout=180s -n "${WANAKU_NAMESPACE}" > /dev/null \
    && echo "PASS [11.6]: deployment ${DEP} available" || echo "FAIL [11.6]: deployment ${DEP} not available"
done

PORTS=$(oc get service "server-${R}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[*].port}')
for PORT in 8080 8081; do
  echo " ${PORTS} " | grep -q " ${PORT} " && echo "PASS [11.6]: server-${R} exposes ${PORT}" \
    || echo "FAIL [11.6]: server-${R} ports are '${PORTS}'"
done

for ROUTE in "${R}" "${R}-mcp"; do
  oc get route "${ROUTE}" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
    && echo "PASS [11.6]: route ${ROUTE} exists" || echo "FAIL [11.6]: route ${ROUTE} missing"
done

oc get deployment "${R}-barn" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
  && echo "FAIL [11.6]: Barn deployed although spec.barn is not enabled" || echo "PASS [11.6]: Barn not deployed"

REASON=$(oc get wanakurouter "${R}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="AuthConfigured")].reason}')
[ "${REASON}" = "Configured" ] && echo "PASS [11.6]: AuthConfigured=Configured" \
  || echo "FAIL [11.6]: AuthConfigured reason is '${REASON}'"
```

**Expected result:**

- The output includes `PASS [11.6]: deployment <R>-server available`.
- The output includes `PASS [11.6]: deployment <R>-oauth2-proxy available`.
- The output includes `PASS [11.6]: server-<R> exposes 8080`.
- The output includes `PASS [11.6]: server-<R> exposes 8081`.
- The output includes `PASS [11.6]: route <R> exists`.
- The output includes `PASS [11.6]: route <R>-mcp exists`.
- The output includes `PASS [11.6]: Barn not deployed`.
- The output includes `PASS [11.6]: AuthConfigured=Configured`.
- The output contains no line starting with `FAIL`.

### Step 11.7: Set the URL variables

The operator publishes `status.endpoints.mcp` as `<MCP Route>/mcp`, without a namespace. Wanaku Server rejects this
path (see Test 13.4), so the plan builds `MCP_URI` with the `default` namespace.

```bash
export WANAKU_SERVER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_MCP_BASE_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
# common/oidc-login-verification.md uses WANAKU_ROUTER_URL
export WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}"
export MCP_URI="${WANAKU_MCP_BASE_URL}/default/mcp"

STATUS_MCP=$(oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.endpoints.mcp}')
[ "${STATUS_MCP}" = "${WANAKU_MCP_BASE_URL}/mcp" ] && echo "PASS [11.7]: status.endpoints.mcp is ${STATUS_MCP}" \
  || echo "FAIL [11.7]: status.endpoints.mcp is '${STATUS_MCP}', expected ${WANAKU_MCP_BASE_URL}/mcp"
```

**Expected result:**

- The output includes `PASS [11.7]: status.endpoints.mcp is <STATUS_MCP>`.
- The output contains no line starting with `FAIL`.

### Step 11.8: Wait for health

`/health` does not require a token. `curl` is used to test HTTP-level readiness. Add `-k` to the `curl` commands of
this part when `WANAKU_TLS_FLAG` is `--insecure`.

Run this block as a separate `bash` script, not in the shared session.

```bash
for i in $(seq 1 30); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health" 2>/dev/null || echo "000")
  [ "${HTTP_CODE}" = "200" ] && { echo "PASS [11.8]: Wanaku Server healthy (attempt ${i})"; break; }
  [ "${i}" -eq 30 ] && { echo "FAIL [11.8]: health returned HTTP ${HTTP_CODE}"; exit 1; }
  sleep 5
done
```

**Expected result:**

- The output includes `PASS [11.8]: Wanaku Server healthy`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.9: Verify that the proxies require a token

```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/tools")
[ "${HTTP_CODE}" = "401" ] && echo "PASS [11.9]: management API returns 401 without a token" \
  || echo "FAIL [11.9]: management API returned HTTP ${HTTP_CODE}"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${MCP_URI}" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
[ "${HTTP_CODE}" = "401" ] && echo "PASS [11.9]: MCP endpoint returns 401 without a token" \
  || echo "FAIL [11.9]: MCP endpoint returned HTTP ${HTTP_CODE}"

# The OAuth protected-resource metadata is public. The operator sets WANAKU_AUTH_ISSUER.
META=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/oauth-protected-resource/default/mcp")
echo "${META}" | grep -q "${KEYCLOAK_URL}/realms/wanaku" \
  && echo "PASS [11.9]: protected-resource metadata names the issuer" || echo "FAIL [11.9]: metadata: ${META}"
```

**Expected result:**

- The output includes `PASS [11.9]: management API returns 401 without a token`.
- The output includes `PASS [11.9]: MCP endpoint returns 401 without a token`.
- The output includes `PASS [11.9]: protected-resource metadata names the issuer`.
- The output contains no line starting with `FAIL`.

### Step 11.10: Log in

Follow [common/oidc-login-verification.md](common/oidc-login-verification.md).

Then read the access token for the
`wanaku mcp` commands. These commands do not read the stored credentials, so they need `--token`.

Run this block as a separate `bash` script, not in the shared session.

```bash
MCP_TOKEN=$(wanaku auth token --get --unmask 2>/dev/null)
[ -n "${MCP_TOKEN}" ] && echo "PASS [11.10]: access token available" || { echo "FAIL [11.10]: no access token"; exit 1; }
```

**Expected result:**

- The output includes `PASS [11.10]: access token available`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

---

## Phase 12: Register an MCP Server as a Forward

### Step 12.1: Deploy the reference MCP server

Run this block as a separate `bash` script, not in the shared session.

```bash
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-mcp
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-mcp
  template:
    metadata:
      labels:
        app: test-mcp
    spec:
      containers:
        - name: everything
          image: ${TEST_MCP_NODE_IMAGE}
          command: ["sh", "-c", "npx -y ${TEST_MCP_PACKAGE} streamableHttp"]
          env:
            - name: PORT
              value: "3001"
            - name: npm_config_cache
              value: /tmp/.npm
          ports:
            - containerPort: 3001
          readinessProbe:
            tcpSocket:
              port: 3001
            periodSeconds: 5
            failureThreshold: 60
---
apiVersion: v1
kind: Service
metadata:
  name: test-mcp
spec:
  selector:
    app: test-mcp
  ports:
    - port: 3001
      targetPort: 3001
EOF
oc rollout status deployment/test-mcp --timeout=300s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS [12.1]: reference MCP server running" || { echo "FAIL [12.1]: reference MCP server not ready"; exit 1; }
```

**Expected result:**

- The output includes `PASS [12.1]: reference MCP server running`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 12.2: Register the forward

The CLI uses the stored token from step 11.10. Do not pass `--no-auth`.

```bash
wanaku forwards add --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} \
  --name everything --service "http://test-mcp:3001/mcp" --namespace default --plain
[ $? -eq 0 ] && echo "PASS [12.2]: forward 'everything' registered" || echo "FAIL [12.2]: forward not registered"
```

**Expected result:**

- The output includes `PASS [12.2]: forward 'everything' registered`.
- The output contains no line starting with `FAIL`.

### Step 12.3: Verify the discovery

```bash
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain 2>&1)
echo "${OUTPUT}" | grep -q "^echo" && echo "PASS [12.3]: tool 'echo' discovered" \
  || echo "FAIL [12.3]: 'echo' not discovered: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [12.3]: tool 'echo' discovered`.
- The output contains no line starting with `FAIL`.

### Step 12.4: Create a second namespace

```bash
wanaku namespaces create "${MCP_E2E_NAMESPACE}" --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain > /dev/null \
  && echo "PASS [12.4]: namespace ${MCP_E2E_NAMESPACE} created" || echo "FAIL [12.4]: namespace not created"
```

**Expected result:**

- The output includes `PASS [12.4]: namespace <MCP_E2E_NAMESPACE> created`.
- The output contains no line starting with `FAIL`.

---

## Phase 13: MCP CLI Through the MCP Route

### Test 13.1: List tools, resources and prompts with a token

```bash
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^echo " && echo "PASS [13.1]: echo listed through the Route" || echo "FAIL [13.1]: ${OUTPUT}"

OUTPUT=$(wanaku mcp resource list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^architecture.md " && echo "PASS [13.1]: architecture.md listed" || echo "FAIL [13.1]: ${OUTPUT}"

OUTPUT=$(wanaku mcp prompt list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^simple-prompt " && echo "PASS [13.1]: simple-prompt listed" || echo "FAIL [13.1]: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.1]: echo listed through the Route`.
- The output includes `PASS [13.1]: architecture.md listed`.
- The output includes `PASS [13.1]: simple-prompt listed`.
- The output contains no line starting with `FAIL`.

### Test 13.2: The MCP Route rejects a request without a token

```bash
wanaku mcp tool list --uri "${MCP_URI}" ${WANAKU_TLS_FLAG} --plain > /dev/null 2>&1
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "PASS [13.2]: request without token rejected (exit ${EXIT_CODE})" \
  || echo "FAIL [13.2]: request without token succeeded"
```

**Expected result:**

- The output includes `PASS [13.2]: request without token rejected`.
- The output contains no line starting with `FAIL`.

### Test 13.3: Namespace isolation through the Route

```bash
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${MCP_E2E_NAMESPACE}/mcp" \
  --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^No tools found" && echo "PASS [13.3]: ${MCP_E2E_NAMESPACE} has no tools" \
  || echo "FAIL [13.3]: tools leaked: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.3]: <MCP_E2E_NAMESPACE> has no tools`.
- The output contains no line starting with `FAIL`.

### Test 13.4: The bare `/mcp` path is rejected

The MCP Route sends all paths to the MCP proxy. Wanaku Server rejects `/mcp` without a namespace.

```bash
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/mcp" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>&1)
echo "${OUTPUT}" | grep -q "invalid MCP endpoint path" && echo "PASS [13.4]: bare /mcp rejected" \
  || echo "FAIL [13.4]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.4]: bare /mcp rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 14: Governed Calls on OpenShift

### Test 14.1: Calls get the fail-safe denial

The operator starts Wanaku Server without a governance configuration. The default posture denies `tools/call`,
`resources/read` and `prompts/get`.

```bash
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --name echo --param message=hello --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match"; then
  echo "PASS [14.1]: tools/call denied by the fail-safe default"
else
  echo "FAIL [14.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi

OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --resource-uri demo://resource/static/document/architecture.md --plain 2>&1)
echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match" \
  && echo "PASS [14.1]: resources/read denied" || echo "FAIL [14.1]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [14.1]: tools/call denied by the fail-safe default`.
- The output includes `PASS [14.1]: resources/read denied`.
- The output contains no line starting with `FAIL`.

### Test 14.2: Calls succeed when governance allows them (BLOCKED)

This test is blocked. The `WanakuRouter` resource has no field for the Wanaku Server governance configuration, and
the operator owns the generated `wanaku.yaml`. Wanaku Server has no environment variable for the governance posture,
so `spec.server.env` does not help. An allow rule from the action-policy API (`PUT /api/v1/action-policies`) does not
help either: the evaluator baseline still denies the call with `evaluator: no_match`. Run the test only when the
deployment allows calls in the `default` namespace. Set `WANAKU_E2E_GOVERNANCE_ALLOWS_DEFAULT=true` in that case.

```bash
if [ "${WANAKU_E2E_GOVERNANCE_ALLOWS_DEFAULT:-false}" = "true" ]; then
  OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
    --name echo --param message=hello --plain 2>/dev/null)
  [ "${OUTPUT}" = "Echo: hello" ] && echo "PASS [14.2]: echo called through the Route" || echo "FAIL [14.2]: '${OUTPUT}'"
  OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
    --name args-prompt --arg city=Paris --plain 2>/dev/null)
  echo "${OUTPUT}" | grep -q "Paris" && echo "PASS [14.2]: args-prompt rendered" || echo "FAIL [14.2]: '${OUTPUT}'"
else
  echo "SKIP [14.2]: governance configuration is not available through the operator"
fi
```

**Expected result:**

- Either `PASS [14.2]: echo called through the Route` and `PASS [14.2]: args-prompt rendered` appear, or `SKIP [14.2]: governance configuration is not available through the operator` appears.
- The output contains no line starting with `FAIL`.

---

## Phase 15: Cleanup (Part 2)

### Step 15.1: Remove the test data and workloads

```bash
wanaku forwards remove --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --name everything --plain > /dev/null 2>&1 || true
wanaku namespaces delete "${MCP_E2E_NAMESPACE}" --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain > /dev/null 2>&1 || true
oc delete deployment test-mcp -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete service test-mcp -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-server" "${WANAKU_NAMESPACE}" 120 || true
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" "${WANAKU_NAMESPACE}" 120 || true
oc delete secret "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
wanaku auth logout > /dev/null 2>&1 || true
rm -rf "${WANAKU_CREDENTIALS}" 2>/dev/null || true
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 15.2: Full cleanup

Clean up Keycloak, the operator and the namespace.

Follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- All cleanup procedure steps satisfy their expected results.

---

## Full Test Summary Matrix

| Phase | Test ID | Test Name | Priority | Part |
|-------|---------|-----------|----------|------|
| S | S.1-S.2 | CLI invocation and local environment | High | 1 |
| 0 | 0.1 | Prerequisites | High | 1 |
| 1 | 1.1-1.4 | Server with test governance, reference forward, namespaces | Critical | 1 |
| 2 | 2.1-2.2 | Help and usage output | Medium | 1 |
| 3 | 3.1 | Required option validation | High | 1 |
| 4 | 4.1-4.6 | Tool operations | Critical | 1 |
| 5 | 5.1-5.3 | Resource operations | Critical | 1 |
| 6 | 6.1-6.5 | Prompt operations | Critical | 1 |
| 7 | 7.1-7.5 | Namespace isolation, governance, disable/enable | Critical | 1 |
| 8 | 8.1-8.4 | Endpoint and connection errors | High | 1 |
| 9 | 9.1 | Cleanup | Critical | 1 |
| 10 | 10.1 | OpenShift login (MANUAL) | Critical | 2 |
| T | S.3-S.4 | OpenShift environment and deletion helper | High | 2 |
| 11 | 11.1-11.10 | Operator, Keycloak, WanakuRouter with oauth2-proxy, Routes, 401 checks, login | Critical | 2 |
| 12 | 12.1-12.4 | In-cluster MCP server registered as a forward | Critical | 2 |
| 13 | 13.1-13.4 | MCP list through the Route, token required, isolation, bare `/mcp` | Critical | 2 |
| 14 | 14.1-14.2 | Fail-safe denial; governed success path (BLOCKED) | High | 2 |
| 15 | 15.1-15.2 | Cleanup | Critical | 2 |
