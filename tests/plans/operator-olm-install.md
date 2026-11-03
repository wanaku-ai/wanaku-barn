# Test Plan: Wanaku Operator Installation through OLM

## Overview

This test plan verifies that the OLM bundle of the Wanaku Operator is valid and installs through the Operator
Lifecycle Manager on a local cluster (minikube or OpenShift Local/CRC). It covers bundle generation, static
validation, installation with `operator-sdk run bundle`, the scorecard, a `WanakuRouter` smoke test, and cleanup.
See [docs/operate/operator-olm.md](../../docs/operate/operator-olm.md) for the background.

Every step is fully automatable.

**Hard timeout rule:** Any single operation that takes longer than **3 minutes** MUST be considered broken. Mark the
test as FAIL and abort, except for the image build in Phase 2 and the OLM installation in Phase 3, which may take up to
**5 minutes** on a cold cache.

## Prerequisites

### Environment variables

```bash
export VERSION="${VERSION:-0.0.1-test}"       # must be valid semver: it becomes the CSV version
export NAMESPACE="${NAMESPACE:-olm-test-$(date +%s)}"
export CLUSTER="${CLUSTER:-minikube}"         # minikube | crc
```

### Required tools

| Tool | Verify command |
|------|----------------|
| `mvn`, `podman` (`docker` for minikube) | `mvn -v && podman --version && docker --version` |
| `operator-sdk` 1.42+ | `operator-sdk version` |
| `kubectl` + `minikube`, or `oc` + `crc` | `minikube status` or `crc status` |

## Phase 1: Static validation

### Step 1.1: Generate the bundle

```bash
make olm-bundle VERSION="${VERSION}"
BUNDLE=apps/wanaku-operator/target/bundle/wanaku-operator
test -f "${BUNDLE}/manifests/wanaku-operator.clusterserviceversion.yaml" && echo PASS || echo FAIL
```

**Expected:** PASS.

### Step 1.2: The bundle contains all four CRDs

```bash
test "$(ls "${BUNDLE}"/manifests/*.crd.yml | wc -l)" -eq 4 && echo PASS || echo FAIL
```

**Expected:** PASS (`WanakuRouter`, `WanakuServiceCatalog`, `WanakuSemanticRouter`, `WanakuIntegration`).

### Step 1.3: Validate

```bash
make olm-validate && echo PASS || echo FAIL
```

**Expected:** PASS. Warnings about empty CRD descriptions are accepted.

### Step 1.4: Required OperatorHub fields

```bash
CSV="${BUNDLE}/manifests/wanaku-operator.clusterserviceversion.yaml"
FAIL=0
# annotations are indented by 4 spaces, spec fields by 2
for KEY in capabilities categories containerImage createdAt support alm-examples repository; do
  grep -q "^    ${KEY}: " "${CSV}" || { echo "FAIL: missing annotation ${KEY}"; FAIL=1; }
done
for KEY in displayName description icon version maintainers provider links keywords relatedImages; do
  grep -q "^  ${KEY}:" "${CSV}" || { echo "FAIL: missing spec.${KEY}"; FAIL=1; }
done
grep -q "^    capabilities: Basic Install$" "${CSV}" || { echo "FAIL: capability level"; FAIL=1; }
CSV_VERSION="$(sed -n 's/^  version: //p' "${CSV}")"
grep -q "^  name: wanaku-operator.v${CSV_VERSION}$" "${CSV}" || { echo "FAIL: CSV name"; FAIL=1; }
grep -A1 "^  - name: wanaku-operator$" "${CSV}" | grep -q ":latest$" && { echo "FAIL: latest tag"; FAIL=1; }
[ "${FAIL}" -eq 0 ] && echo PASS
```

**Expected:** `PASS`.

### Step 1.5: Install modes

```bash
FAIL=0
for MODE in OwnNamespace SingleNamespace; do
  grep -B1 "^    type: ${MODE}$" "${CSV}" | grep -q "supported: true" || { echo "FAIL: ${MODE} must be supported"; FAIL=1; }
done
for MODE in MultiNamespace AllNamespaces; do
  grep -B1 "^    type: ${MODE}$" "${CSV}" | grep -q "supported: false" || { echo "FAIL: ${MODE} must not be supported"; FAIL=1; }
done
[ "${FAIL}" -eq 0 ] && echo PASS
```

**Expected:** `PASS`.

## Phase 2: Install on the cluster

### Step 2.1: Install the bundle

```bash
if [ "${CLUSTER}" = "crc" ]; then
  make olm-crc-up NAMESPACE="${NAMESPACE}"
else
  make olm-minikube-up NAMESPACE="${NAMESPACE}"
fi
```

**Expected:** the command ends with the CSV in phase `Succeeded`.

### Step 2.2: The operator is running

```bash
CLI=$([ "${CLUSTER}" = "crc" ] && echo oc || echo kubectl)
${CLI} -n "${NAMESPACE}" rollout status deployment/wanaku-operator --timeout=180s
${CLI} -n "${NAMESPACE}" get csv -o jsonpath='{.items[0].status.phase}'
```

**Expected:** rollout completes and the phase is `Succeeded`.

### Step 2.3: The operator got its configuration from OLM

```bash
${CLI} -n "${NAMESPACE}" get deployment wanaku-operator \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_ROUTER_NAMESPACES")].valueFrom.fieldRef.fieldPath}'
```

**Expected:** `metadata.annotations['olm.targetNamespaces']`.

## Phase 3: Functional checks

### Step 3.1: Smoke test

```bash
make olm-$([ "${CLUSTER}" = "crc" ] && echo crc || echo minikube)-smoke NAMESPACE="${NAMESPACE}"
```

**Expected:** `Smoke test passed`. A `WanakuRouter` reaches `Ready` and its `smoke-server` Deployment and
`server-smoke` Service exist.

### Step 3.2: Scorecard

```bash
make olm-scorecard NAMESPACE="${NAMESPACE}" 2>&1 | grep -E "State:"
```

**Expected:** every line is `State: pass`.

## Phase 4: Cleanup

```bash
if [ "${CLUSTER}" = "crc" ]; then
  make olm-crc-down NAMESPACE="${NAMESPACE}"
else
  make olm-minikube-down NAMESPACE="${NAMESPACE}"
fi
${CLI} get namespace "${NAMESPACE}" 2>&1 | grep -q NotFound && echo PASS || echo "FAIL (namespace still terminating)"
```

**Expected:** PASS. The CSV, the CatalogSource and the Subscription created by `operator-sdk run bundle` are gone
(`${CLI} get catalogsource,subscription -A | grep wanaku` prints nothing).
