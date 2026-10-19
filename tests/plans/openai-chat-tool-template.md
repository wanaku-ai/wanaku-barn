# Test Plan: OpenAI Chat Tool Service Template

## Overview

This test plan verifies the built-in `openai-chat-tool` service template (PR #1429, closes #1184). It covers the
template source, the listing and the instantiation in Wanaku Barn, the tool registration in Wanaku Server through a
Camel Integration Capability (CIC) runtime, and an end-to-end MCP tool call against the OpenAI API.

The template turns into an MCP tool in this order:

```text
openai-chat-tool template (Barn) --instantiate--> catalog (Barn) --CIC downloads--> CIC runtime :9190/mcp
    --forward--> Wanaku Server --MCP--> ${WANAKU_MCP_BASE_URL}/default/mcp
```

### Components under test

| Component | Needed for | Common procedure |
|-----------|------------|------------------|
| Wanaku Barn | Phases 2 to 4 (templates and catalogs) | [common/start-barn.md](common/start-barn.md) |
| Wanaku Server | Phases 5 and 6 (tool registration and MCP call) | [common/start-server.md](common/start-server.md) |
| CIC runtime (container) | Phases 5 and 6 | [common/start-cic.md](common/start-cic.md) |
| OpenAI API key | Phase 6 only | — |

Phases 1 to 5 run without an OpenAI API key. Phase 6 is skipped when `OPENAI_API_KEY` is not set.

The shared template checks are in [common/service-template-checks.md](common/service-template-checks.md). This plan
adds the checks that are specific to the OpenAI template.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java --version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |
| `unzip` | any | `unzip -v` |
| `docker` or `podman` | 20+ | `docker --version` or `podman --version` (Phases 5 and 6) |

Run all commands from the root of this repository. Call the CLI as `java -jar "${CLI_JAR}"`. Do not put the full
command in one variable, because zsh treats it as one token.

### Environment variables

```bash
export TEMPLATE_NAME="openai-chat-tool"
export TEMPLATE_SYSTEM="openai-chat"
export TEMPLATE_TOOL="openai-chat-completion"
export TEMPLATE_TOOL_PARAMS="prompt systemMessage model temperature maxTokens"
export TEMPLATE_SEARCH="openai"
export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-openai-chat-test}"
export TEMPLATE_PROPS_FILE="${TEMPLATE_PROPS_FILE:-$(mktemp)}"

export OPENAI_API_KEY="${OPENAI_API_KEY:-}"
export OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-mini}"
export OPENAI_ORG="${OPENAI_ORG:-}"

export CIC_NAME="${CIC_NAME:-cic-openai}"
export CIC_NAMESPACE="${CIC_NAMESPACE:-default}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${WANAKU_TEST_GOVERNED_NAMESPACES:-${CIC_NAMESPACE}}"
```

| Variable | Default | Description |
|----------|---------|-------------|
| `TEMPLATE_CATALOG` | `openai-chat-test` | Name of the catalog that the instantiation creates |
| `TEMPLATE_PROPS_FILE` | `$(mktemp)` | Properties file for the instantiation (written in Phase 4) |
| `OPENAI_API_KEY` | _(empty)_ | OpenAI API key. Empty skips Phase 6. |
| `OPENAI_MODEL` | `gpt-4o-mini` | Model for the chat completion |
| `OPENAI_ORG` | _(empty)_ | OpenAI organization ID (optional) |
| `CIC_NAME` | `cic-openai` | CIC container name and forward name |
| `CIC_NAMESPACE` | `default` | Wanaku namespace of the forward |
| `WANAKU_TEST_GOVERNED_NAMESPACES` | `${CIC_NAMESPACE}` | Namespaces where the test governance allows tool calls |

---

## Phase 1: Environment Setup

### Step 1.1: Start Wanaku Server with the test governance configuration

Wanaku Server denies tool calls unless governance permits them. Follow [common/start-server.md](common/start-server.md)
and run step 2b. After completion, `WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL`, `WANAKU_SERVER_PID` and
`WANAKU_SERVER_CONFIG` must be set.

### Step 1.2: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md). After completion, `WANAKU_BARN_URL`, `CLI_JAR` and
`WANAKU_BARN_PID` must be set.

### Step 1.3: Detect the container runtime

Follow [common/container-runtime.md](common/container-runtime.md). After completion, `CONTAINER_RUNTIME` must be set.
If no container runtime is available, Phases 5 and 6 are skipped.

---

## Phase 2: Template Source

### Test 2.1: Shared source checks

Run the Setup and section A of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. Barn reports `Service template 'openai-chat-tool' is valid`.

### Test 2.2: The route calls the OpenAI component

```bash
ROUTE_FILE="${TEMPLATE_ROUTE_FILE}"

grep -q 'uri: "openai:chat-completion"' "${ROUTE_FILE}" && echo "PASS: route sends to openai:chat-completion" \
  || echo "FAIL: openai endpoint missing"
grep -q 'apiKey: "{{openai.api.key}}"' "${ROUTE_FILE}" && echo "PASS: API key comes from a property" \
  || echo "FAIL: API key is not read from openai.api.key"
for OPTION in "model: \"{{openai.model}}\"" "temperature: \"{{openai.temperature}}\"" \
  "maxTokens: \"{{openai.max.tokens}}\"" "systemMessage: \"{{openai.system.message}}\""; do
  grep -q "${OPTION}" "${ROUTE_FILE}" && echo "PASS: endpoint option ${OPTION}" || echo "FAIL: ${OPTION} missing"
done
```

### Test 2.3: The route maps the tool parameters to the OpenAI headers

The CIC passes each tool argument as a message header. The route sends the `prompt` header as the body (the user
message), and copies the optional parameters to the `CamelOpenAI*` headers. The OpenAI component uses a header when it
is set, and the endpoint option from the catalog properties otherwise.

```bash
grep -q 'simple: "${header.prompt}"' "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: user message comes from the prompt header" || echo "FAIL: user message does not use header.prompt"
for PAIR in CamelOpenAISystemMessage:systemMessage CamelOpenAIModel:model CamelOpenAITemperature:temperature \
  CamelOpenAIMaxTokens:maxTokens; do
  HEADER="${PAIR%%:*}"; PARAM="${PAIR##*:}"
  grep -A1 "name: ${HEADER}$" "${TEMPLATE_ROUTE_FILE}" | grep -q "simple: \"\${header.${PARAM}}\"" \
    && echo "PASS: ${HEADER} comes from ${PARAM}" || echo "FAIL: ${HEADER} does not use ${PARAM}"
done
```

### Test 2.4: Dependencies and properties

```bash
DIR="${TEMPLATE_DIR}/${TEMPLATE_SYSTEM}"

grep -q "^org.apache.camel:camel-openai" "${DIR}/${TEMPLATE_SYSTEM}.dependencies.txt" \
  && echo "PASS: camel-openai dependency" || echo "FAIL: camel-openai dependency missing"

PROPS="${TEMPLATE_DIR}/${TEMPLATE_PROPS_PATH}"
for KEY in openai.api.key openai.model openai.organization; do
  grep -q "^${KEY}={{${KEY}}}$" "${PROPS}" && echo "PASS: ${KEY} is a placeholder" \
    || echo "FAIL: ${KEY} is not a placeholder"
done
grep -q "^openai.temperature=0.7$" "${PROPS}" && echo "PASS: temperature default" || echo "FAIL: temperature default"
grep -q "^openai.max.tokens=1024$" "${PROPS}" && echo "PASS: max.tokens default" || echo "FAIL: max.tokens default"
```

---

## Phase 3: Template Listing

### Test 3.1: Shared listing checks

Run section B of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. The template detail lists the system `openai-chat`. The properties endpoint
returns the `openai.*` keys.

---

## Phase 4: Template Instantiation

### Step 4.1: Write the instantiation properties

Phase 6 needs a real key. Phases 4 and 5 work with a placeholder key, because the CIC does not call OpenAI until a
tool call arrives.

```bash
cat > "${TEMPLATE_PROPS_FILE}" <<EOF
openai.api.key=${OPENAI_API_KEY:-sk-test-placeholder}
openai.model=${OPENAI_MODEL}
openai.organization=${OPENAI_ORG}
EOF
[ -s "${TEMPLATE_PROPS_FILE}" ] && echo "PASS: properties written to ${TEMPLATE_PROPS_FILE}" \
  || echo "FAIL: properties file is empty"
```

### Test 4.2: Shared instantiation checks

Run section C of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** every line is `PASS`. The catalog `${TEMPLATE_CATALOG}` has versions 1 and 2. Its `service.properties`
contains the supplied `openai.api.key`, `openai.model` and `openai.organization`.

### Test 4.3: Unset placeholders stay in the catalog

An instantiation without properties succeeds. Barn keeps the `{{placeholder}}` values, so the CIC cannot resolve them
at runtime.

```bash
EMPTY_CATALOG="${TEMPLATE_CATALOG}-empty"
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --service-name "${EMPTY_CATALOG}" > /dev/null 2>&1 \
  && echo "PASS: instantiation without properties succeeded" || echo "FAIL: instantiation without properties failed"

java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${EMPTY_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/empty.zip" > /dev/null 2>&1
unzip -p "${TEMPLATE_WORK_DIR}/empty.zip" "${TEMPLATE_PROPS_PATH}" | grep -q '^openai.api.key={{openai.api.key}}$' \
  && echo "PASS: unset placeholder kept" || echo "FAIL: unset placeholder not kept"
```

---

## Phase 5: Serve the Catalog through the CIC

### Test 5.0: Pre-flight check

```bash
if [ -z "${CONTAINER_RUNTIME}" ]; then
  echo "SKIP: no container runtime -- skip Phases 5 and 6"
else
  echo "PASS: container runtime ${CONTAINER_RUNTIME} is available"
fi
```

### Step 5.1: Start the CIC runtime

Follow [common/start-cic.md](common/start-cic.md) with these values:

```bash
export CIC_CATALOG="${TEMPLATE_CATALOG}"
export CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"
```

After completion, the forward `${CIC_NAME}` is registered and `CIC_MCP_URL` is set. The runtime downloads
`camel-openai` at the first start. This can take some minutes.

### Test 5.2: Shared tool registration checks

Run section D of [common/service-template-checks.md](common/service-template-checks.md).

**Expected:** `openai-chat-completion` is in `wanaku tools list`, its type is `mcp-forward`, its input schema has
`prompt`, `systemMessage`, `model`, `temperature` and `maxTokens`, and it is listed at
`${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp`.

---

## Phase 6: End-to-End MCP Tool Call (requires `OPENAI_API_KEY`)

> [!NOTE]
> `wanaku mcp tool --param` splits values at commas. Do not use commas in the prompt values.

### Test 6.0: Pre-flight check

```bash
PHASE6_SKIP=0
[ -z "${OPENAI_API_KEY}" ] && { echo "SKIP: OPENAI_API_KEY is not set"; PHASE6_SKIP=1; }
[ -z "${CONTAINER_RUNTIME}" ] && { echo "SKIP: no CIC runtime"; PHASE6_SKIP=1; }
[ "${PHASE6_SKIP}" -eq 0 ] && echo "PASS: Phase 6 pre-flight checks passed"
```

### Test 6.1: Call the tool through Wanaku Server

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "prompt=Say hello in exactly three words." 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && [ -n "${OUTPUT}" ] && echo "PASS: tool call returned a completion" \
  || echo "FAIL: tool call failed (exit ${EXIT_CODE})"
```

### Test 6.2: Call the tool with the optional parameters

The optional parameters override the catalog properties for this call. The system message forces a fixed answer, so
the test shows that the route uses it.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "prompt=What is 2+2?" \
  --param "systemMessage=Ignore the question and answer only with the word PINEAPPLE." \
  --param "temperature=0.1" --param "maxTokens=10" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -qi "pineapple" \
  && echo "PASS: the system message parameter changed the answer" \
  || echo "FAIL: the system message parameter had no effect (exit ${EXIT_CODE})"
```

### Test 6.3: The audit trail records the tool call

Wanaku Server has no CLI command for the audit trail. `curl` is acceptable here.

```bash
curl -s "${WANAKU_SERVER_URL}/api/v1/audit/events" \
  | jq -e --arg t "${TEMPLATE_TOOL}" \
    '[.data.events[] | select(.operation == "tools/call" and .target == $t and .decision != "block")] | length > 0' \
    > /dev/null \
  && echo "PASS: audit trail has an allowed tools/call event for ${TEMPLATE_TOOL}" \
  || echo "FAIL: no allowed tools/call audit event for ${TEMPLATE_TOOL}"
```

---

## Phase 7: Negative Tests

### Test 7.1: A call to an unknown tool fails

```bash
java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "does-not-exist-99999" --param "prompt=hello" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: unknown tool rejected" || echo "FAIL: unknown tool accepted"
```

### Test 7.2: A CIC for an unknown catalog does not become ready

This test needs a container runtime. It uses port `9191` so that it does not conflict with the runtime of Phase 5.

```bash
${CONTAINER_RUNTIME} run -d --name "${CIC_NAME}-bad" --network host \
  -e REGISTRATION_URL="${WANAKU_BARN_URL}" -e SERVICE_NAME="${CIC_NAME}-bad" \
  -e SERVICE_CATALOG="does-not-exist-99999" -e SERVICE_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}" \
  -e MCP_PORT=9191 -e DATA_DIR=/tmp/cic-data "${CIC_IMAGE:-quay.io/wanaku/camel-integration-capability:latest}" \
  > /dev/null
READY=0
for i in $(seq 1 20); do
  [ "$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9191/observe/health/ready)" = "200" ] && { READY=1; break; }
  sleep 3
done
[ "${READY}" -eq 0 ] && echo "PASS: CIC with an unknown catalog is not ready" || echo "FAIL: CIC became ready"
${CONTAINER_RUNTIME} rm -f "${CIC_NAME}-bad" > /dev/null 2>&1 || true
```

---

## Phase 8: Cleanup

### Step 8.1: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md). It removes the forward `${CIC_NAME}` and the
container.

### Step 8.2: Remove the catalogs

Run section E of [common/service-template-checks.md](common/service-template-checks.md), then remove the extra
catalog of Test 4.3:

```bash
java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}-empty" > /dev/null 2>&1 || true
rm -f "${TEMPLATE_PROPS_FILE}"
echo "PASS: catalogs removed"
```

### Step 8.3: Stop Barn and Wanaku Server

Run the Shutdown sections of [common/start-barn.md](common/start-barn.md) and
[common/start-server.md](common/start-server.md).

---

## Test Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 1 | 1.1-1.3 | Start Wanaku Server (with test governance), Barn, detect container runtime | P0 |
| 2 | 2.1 | Shared source checks (files, index, ai-tool route, credentials, Barn validation) | P0 |
| 2 | 2.2 | Route calls the OpenAI component with the property-based endpoint options | P0 |
| 2 | 2.3 | Tool parameters are mapped to the OpenAI headers | P1 |
| 2 | 2.4 | camel-openai dependency, placeholders and defaults | P0 |
| 3 | 3.1 | Shared listing checks (CLI list, search, negative search, detail, properties, download) | P1 |
| 4 | 4.1 | Write instantiation properties | P0 |
| 4 | 4.2 | Shared instantiation checks (substitution, versions, negative cases) | P0 |
| 4 | 4.3 | Unset placeholders stay in the catalog | P2 |
| 5 | 5.0-5.1 | Serve the catalog through the CIC and register the forward | P0 |
| 5 | 5.2 | Tool registered in Wanaku Server with the declared schema | P0 |
| 6 | 6.0-6.2 | End-to-end MCP tool call (live OpenAI API) | P1 |
| 6 | 6.3 | Audit trail records the call | P2 |
| 7 | 7.1 | Unknown tool is rejected | P1 |
| 7 | 7.2 | CIC with an unknown catalog does not become ready | P2 |
| 8 | 8.1-8.3 | Cleanup (CIC, forward, catalogs, processes) | P1 |
