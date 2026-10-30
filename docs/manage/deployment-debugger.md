# Deployment Debugger

The Deployment Debugger is an independent Wanaku UI plugin for inspecting a deployment. It connects directly from your browser to MCP, A2A, and inference listeners and uses the same-origin Wanaku management API for registry and audit data. It does not require the Wanaku Barn backend.

## Install

Download `wanaku-debugger-plugin-<version>.zip` from the Wanaku Barn release assets. Extract it into a directory under Wanaku's `--plugins-path` and restart Wanaku, or install the zip URL through `POST /api/v1/plugins/install`. The archive contains `plugin.json`, `plugin.js`, and `plugin.css` at its root. React and Carbon are bundled in the plugin. No service mapping or Barn backend is needed.

The directory or symlink under `--plugins-path` must be named `wanaku-debugger`, matching the manifest ID. For example, with `--plugins-path plugins`, use `plugins/wanaku-debugger/plugin.json`. A different name can appear in plugin discovery while its JavaScript and CSS return 404, leaving the navigation entry absent.

Open **Debugger** in the **Developer** navigation section. In **Connection settings**, choose endpoint base URLs, plus a bearer token and additional JSON headers when required. Choose the namespace directly in the MCP or A2A panel; the list loads from the management API. Registered A2A agents load automatically for the selected namespace. Defaults use the browser's current hostname and scheme with ports `8081` (MCP), `8083` (inference), and `8084` (A2A). Registered agents use their management API `proxyUrl`. The MCP panel discovers tools, resources, and prompts, validates tool inputs, and invokes entries. The A2A panel discovers agent cards and sends `SendMessage`, `GetTask`, and `CancelTask` JSON-RPC operations. It reads task IDs from the `SendMessage` response’s `result.task` envelope. It leaves `A2A-Version` unset by default so Wanaku can forward the request without conflicting gateway and agent version requirements; deployments that require it can set it in A2A headers JSON. Curl exports use the same methods and headers. The inference panel lists models and submits chat completion JSON; it does not execute returned tool calls.

Protocol operations open a centered inspector dialog with formatted, highlighted JSON. Catalog discovery updates the panel without opening the dialog. Select a run in the history to inspect its request, response, status, duration, and policy audit events. Close the dialog with Escape or its close button. Edit and replay a run, copy curl, or export a redacted JSON record. Audit links match namespace, target, and a time window; concurrent traffic may also appear. MCP uses plain JSON-RPC POSTs to Wanaku's stateless listener. The default action-policy posture is `no_match: deny`; register an explicit allow policy before expecting an invocation to succeed.

## LLM Chat

Open the **LLM Chat** tab to converse with an OpenAI-compatible inference provider, including local Ollama endpoints. Set the inference and MCP endpoint bases in Connection settings. Open **LLM settings** for a separate inference API key, system prompt, and extra parameters JSON. An empty API key supports providers without authentication and does not reuse the MCP bearer token. Click **Load models and tools**, choose or enter a model, and select the MCP tools the model may call in the current namespace.

Send a message to start the conversation. The model can request several tools in one response and continue through multiple rounds; every tool result is fed back to the model. Unselected tools are rejected. **Stop generation** cancels pending requests; **Clear conversation** starts fresh. Changing endpoints or namespace clears the conversation and tool selection to prevent tools from another namespace being reused. Streaming is unsupported; use `stream: false`. Conversations stop after 32 tool rounds.

Chat exchanges appear in the same redacted run history and inspector as protocol operations, including policy audit data. The model receives original tool results in memory while history and exports redact credentials. Chat settings stay in memory by default. **Remember LLM settings in this browser** saves configuration; the API key is saved only when **Also remember the inference API key** is enabled separately. Disabling either relevant preference removes the saved key. Conversations are not saved as chat transcripts.

## Browser access and authentication

Set endpoint base URLs; the plugin adds namespace and protocol paths to them. A deployment exposed only inside a cluster must first be made reachable from your browser. When the UI runs over HTTPS, all listener endpoints must also support HTTPS to avoid mixed-content blocking.

For listeners on a different origin, set `WANAKU_CORS_ORIGIN` to allow the UI origin. Allow `POST`, `GET`, and browser `OPTIONS` preflight requests, and request headers `Authorization`, `Content-Type`, `A2A-Version`, `x-request-id`, and any custom forwarding headers. Configure `WANAKU_FORWARD_HEADERS` or `wanaku.forward_headers` when testing forwarding. For Ollama, also set `OLLAMA_ORIGINS`. Browser CORS errors can prevent JavaScript from reading an otherwise valid server response; inspect the browser network panel to verify the deployment configuration.

Paste a bearer token issued for the MCP server when authentication is required. Tokens are held in memory by default. Enabling **Remember settings and token in this browser** stores the settings, headers, and token in local storage; disabling it removes the stored settings. Clear the token after debugging. Do not embed tokens in endpoint URLs.

## Build

From the repository root, build and package only the plugin:

```shell
mvn -pl apps/ui/debugger -Pdist package
```

The archive is written to `apps/ui/debugger/target/distributions/`. The normal distribution build and JReleaser release configuration include this archive alongside the Barn plugin. The module shares the repository's Node and Yarn versions but has no Java backend dependency.

For local development:

```shell
cd apps/ui/debugger
yarn install --frozen-lockfile
yarn dev
```

Run `yarn build:plugin` for the bundled production assets, `yarn test` for protocol tests, and `yarn lint` for source checks.

To load a local build, create a symlink named `wanaku-debugger` inside the configured plugins directory, pointing to the absolute path of `apps/ui/debugger/plugin-dist`. Restart Wanaku for discovery and reload the browser page for activation.

The browser regression test loads the built plugin without a Barn backend. After building the plugin, use the existing UI test tooling:

```shell
cd tests/e2e/ui
npx playwright test --config ../debugger/playwright.config.ts
```
