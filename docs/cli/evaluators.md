---
title: Manage evaluators with the CLI
description: Use the Wanaku CLI to update the evaluator configuration at runtime, list and show its revisions, and roll back to a prior revision.
---

# Manage evaluators with the CLI

The `wanaku evaluators` commands change the evaluators of a running Wanaku server through its management API.

Evaluators are WASM- and LLM-backed governance processors that the router runs on MCP traffic. They can pass, block,
warn, filter tools, or set metadata. The router loads them from `wanaku.yaml` at startup. The `wanaku evaluators`
commands change them at runtime through the router's management API. See the router's
[evaluator engine documentation](https://github.com/wanaku-ai/wanaku/blob/main/docs/govern/evaluators.md) for the
evaluator definition format.

Every change creates an immutable revision. The router keeps the revision history, and you can activate the
configuration of a prior revision to roll back.

## Updating the Evaluator Configuration

The `wanaku evaluators update` command replaces the active configuration with the evaluators in a YAML or JSON file.
The file holds either a list of evaluator definitions or an object with an `evaluators` list, so you can pass a
`wanaku.yaml` file directly. The command ignores other top-level keys.

```shell
wanaku evaluators update evaluators.yaml
```

```yaml
evaluators:
  - name: "safety-gate"
    trigger:
      method: "tools/call"
      namespace: "production"
    engine:
      type: passthrough
    processor:
      path: "/wasm/safety-gate.wasm"
```

To avoid overwriting a change that someone else made, pass the revision that you expect to be active. The router
rejects the update with `409 Conflict` if the active revision is different:

```shell
wanaku evaluators update evaluators.yaml --expected-revision 4
```

The router rejects a configuration that fails validation with `422 Unprocessable Entity` and keeps the active revision.

## Inspecting Revisions

```shell
# List the revision history, newest first
wanaku evaluators revisions list

# Show the active revision and its evaluators
wanaku evaluators revisions active

# Show a specific revision
wanaku evaluators revisions get 3
```

Each revision shows its ID, status (`active`, `superseded` or `rejected`), creation and activation times, origin
(`startup` or `api`) and checksum. For a `rejected` revision, `revisions get` also shows the failure reason, which
explains why the router rejected the configuration.

## Rolling Back

The `wanaku evaluators revisions activate` command activates the configuration of a prior revision. The router records
it as a new revision, so the history stays intact:

```shell
wanaku evaluators revisions activate 3

# Fail with 409 Conflict if revision 5 is no longer the active one
wanaku evaluators revisions activate 3 --expected-revision 5
```

All `wanaku evaluators` commands accept `--host` to select the router (default: `http://localhost:8080`).

## Next steps

- [Evaluators in Wanaku](https://wanaku.ai/docs/next/govern/evaluators.html): the evaluator definition format.
- [Namespaces](namespaces.md): scope evaluators to namespaces.
