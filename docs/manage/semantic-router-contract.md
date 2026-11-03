---
title: Semantic router contract
description: Learn the semantic router definition that Barn accepts and the Camel catalog that Barn generates from it for the Wanaku Semantic Router.
---

# Semantic router authoring and artifact contract

Barn turns a focused routing definition into an executable Camel catalog. The catalog contains a fixed MCP tool, native Camel semantic evaluation, and curated Kamelet branches. Wanaku authorizes the tool call. WSR loads the selected catalog before Camel starts. See the [wizard guide](semantic-routing-wizard.md) for the authoring workflow.

This contract implements Barn [#179](https://github.com/wanaku-ai/wanaku-barn/issues/179), [#180](https://github.com/wanaku-ai/wanaku-barn/issues/180), [#181](https://github.com/wanaku-ai/wanaku-barn/issues/181), and [#183](https://github.com/wanaku-ai/wanaku-barn/issues/183). Runtime loading is tracked by [#182](https://github.com/wanaku-ai/wanaku-barn/issues/182) in the separate [WSR repository](https://github.com/wanaku-ai/wanaku-semantic-router).

## Initial compatibility profile

The initial profile is `message-to-string/v1`. Its MCP input is an object with one required `message` string. The semantic input is the message body. A selected destination receives that string. An action returns its string result. A sink returns the fixed `Routed to <label>.` acknowledgement after delivery succeeds. This is one supported profile. It is not a universal contract for every Camel integration.

The caller supplies invocation data. The deployment supplies action configuration and expert configuration. For example, `prefix` is action configuration. A service address or a credential reference is also deployment configuration. These values are not MCP arguments.

An action with a different input or output schema requires a catalog-author adapter. Barn does not infer missing arguments or expose an arbitrary transformation editor.

| Outcome | Observable behavior |
|---|---|
| Selected action label | Execute the fixed Kamelet branch and return its string result |
| Selected sink label | Deliver the message and return `Routed to <label>.` after successful completion |
| `no_match` | Return `No matching action.` and do not execute an action |
| Provider failure | Return an MCP execution error; do not treat it as no-match |
| Malformed or unknown label | Return an evaluation error; do not execute an action |
| Action failure | Return an MCP execution error |

Router names and MCP tool names start with an ASCII letter. They contain only letters, digits, hyphens, and underscores. Router names have a maximum of 120 characters. Tool names have a maximum of 64 characters. Incomplete drafts can omit these fields. A nonempty field must use the valid format.

The generated tool name is the definition's `toolName`. Its description is the definition's `description`. The native `ai-tool` endpoint binds `parameter.message=string` and `parameter.message.required=true`. The fixed MCP tag is `wsr-semantic-router`. Internal classification and dispatch routes do not expose MCP tools.

## Curated native actions

Barn reads native Kamelet YAML from its [remote Kamelet catalog](kamelets.md). Upload reviewed `.kamelet.yaml` files through the Kamelets page or `POST /api/v1/kamelets`. Eligible uploads become available without a restart. The catalog also serves ordinary source, sink, and action Kamelets through raw YAML URLs. The bundled billing and technical actions return demonstration strings. They have no backend side effects. Existing `wanaku.semantic.actions-directory` configuration remains supported for startup-loaded files. Compatible native sinks are offered without Barn annotations. Native source Kamelets are not offered. Native action Kamelets require the annotations below.

| Native field | Meaning |
|---|---|
| `metadata.name` | Action identifier and Kamelet name |
| `spec.definition.title` and `description` | Display name and purpose |
| `spec.definition.properties`, `required`, and other JSON Schema fields | Deployment configuration form and validation |
| `spec.dependencies` | Native Camel dependency declarations |
| `spec.types.in.schema` | Invocation body schema |
| `spec.types.out.schema` | Result body schema |

Native action Kamelets require the `semantic-action` and `contract-profile` annotations. The `routing-description` annotation is optional:

| Annotation | Value |
|---|---|
| `barn.wanaku.ai/semantic-action` | `"true"` |
| `barn.wanaku.ai/routing-description` | Suggested selection criterion |
| `barn.wanaku.ai/contract-profile` | `message-to-string/v1` |

Saved action selections pin the SHA-256 digest of their native Kamelet. Validation, preview, and generation resolve that exact content and schema. Uploading another revision or removing a current catalog entry does not change the saved pin. The wizard supports an explicit update to the current revision. A publication cannot combine different revisions of the same Kamelet name. Semantic actions that reference other Kamelets are not eligible until dependency closure is supported. Native `kamelet:source` and `kamelet:sink` endpoints remain valid.

Native actions require string input and output schemas. Native sinks must consume from `kamelet:source`. A declared sink input schema must use `type: string`. A sink does not require an output schema. Barn derives string input and acknowledgement output schemas for the adapter. The API exposes the native `type` as `action` or `sink`. Sink criteria default to the native description. Explicit incompatible profiles or semantic opt-out annotations exclude the sink. Barn does not infer request headers or extra arguments.

The generated sink branch invokes the selected Kamelet before it sets the fixed acknowledgement body. An exception prevents that acknowledgement and remains an MCP execution error. Classification previews do not invoke the sink. Exact selected Kamelet bytes and pins remain unchanged.

Configuration forms support primitive string, boolean, integer, and number properties. The native JSON Schema validator checks required fields, enums, bounds, patterns, and other supported schema constraints. Catalog authors must expose compatible primitive parameters. They must keep adaptations inside their Kamelet.

Set `x-secret-reference: true` on a credential-reference property. Barn also recognizes native `format: password` and the exact `x-descriptors` password value `urn:alm:descriptor:com.tectonic.ui:password`. It adds the reference marker to the derived configuration schema. It does not modify the native YAML. Barn accepts only `env:VARIABLE_NAME` for these properties. The catalog contains the corresponding `{{env:VARIABLE_NAME}}` reference. It contains no credential value. The deployment must supply that environment variable. Other configuration values must not contain Camel expression or property delimiters or start with a bean reference marker.

## Expert instances

An expert GAV identifies its implementation dependency. A named Camel bean identifies its configured instance. Barn manages metadata for these reusable instances; WSR deployment configuration creates their beans and supplies credentials, model files, timeouts and other provider settings.

Use `GET /api/v1/semantic-routers/experts` to list entries; `POST /experts` creates an entry and `GET`, `PUT` and `DELETE /experts/{id}` read, replace and remove it. IDs and Camel bean names are unique. IDs are immutable. Duplicate identities or deletion while a saved draft references an expert return HTTP 409. Invalid metadata returns HTTP 422; missing entries return HTTP 404. Generic DataStore mutations cannot alter expert entries or the initialization marker.

Each entry retains `id`, `name`, `bean`, `dependency` and the legacy `supportsConfidence` field. Its `operations` describe the native name, `inputTypes`, `resultType`, `resultMeaning` and a Draft-07 `parameterSchema`. Administrators supply these descriptions from the deployed expert's Camel contract. This is metadata, not runtime discovery or a claim that a bean is enabled. WSR validates the native contract before use. Parameter schemas are bounded object schemas without references or external dialect declarations.

On first initialization, Barn imports `wanaku.semantic.experts-file`, or creates the default `support` / `supportExpert` entry for `org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT`. Legacy entries without operations acquire the existing text `choice` contract with instructions and named criteria. Initialization is recorded persistently; editing the file later does not overwrite administrator changes, and deleted seeds do not return after restart. Use the expert API for subsequent changes.

### Optional guard

A definition can add `guard: {expertId, operation, parameters, rejectWhen}`. The operation must accept text and return a Boolean. `rejectWhen` defaults to true; select false for operations whose negative verdict rejects the request. Parameters are literal JSON validated against the declared schema. They cannot contain Camel expressions or references. Provider credentials and model configuration remain deployment settings.

The guard reads the original request message before classification. A rejecting verdict raises the fixed tool error `Request rejected by semantic guard`. Neither the classifier nor any destination runs. Guard evaluation failures, unsupported operations, malformed results and uncertainty errors also stop processing. An accepted verdict permits the existing single choice evaluation and fixed-label dispatch. Barn never reapplies an expert's probability threshold.

Wolf Defender's `injection` operation is a guard example: true means injection detected. Configure its model directory and explicitly provision the pinned files in WSR; Barn stores no model or bean-property configuration. A negative verdict means this detector did not detect injection, not that the request has authorization.

## Native Camel build

Generated catalogs target Camel `4.23.0-SNAPSHOT`. The bundled schema and generated build hint use timestamp `20261009.103642`. WSR deployments record fixed timestamped coordinates and JAR checksums for all Camel components. Use the deployment dependency lock and checksum verification to reproduce its runtime build. A moving snapshot coordinate alone is not a deployment pin.

Barn's ordinary catalog validator remains on Camel `4.22.1`. Semantic catalogs use a separately bundled YAML DSL schema from `camel-yaml-dsl-4.23.0-20261009.103642-37.jar`. This avoids changing unrelated Barn Camel dependencies. The schema is Apache Camel material licensed under Apache License 2.0.

The schema build includes [Camel PR #27494](https://github.com/apache/camel/pull/27494), merged as `7c0fca7a09cb7cc2acf70715edd597a8adb334c1`. The schema artifact and SHA-256 are recorded in [`semanticCamelYamlDsl.provenance.properties`](https://github.com/wanaku-ai/wanaku-barn/blob/main/apps/wanaku-barn-backend/src/main/resources/schema/semanticCamelYamlDsl.provenance.properties).

Generated catalogs declare `semantic.evaluation.department` with `operation: choice`, `state: ${body}`, and `expert` naming a Camel bean. `parameters` contains `instructions` and the fixed label-to-criterion map `criteria`. Barn does not generate the legacy `semantic.question` or `type: choice` declaration. The native choice operation returns one criterion label; evaluation errors remain distinct from the explicit `no_match` criterion. Its language expression is `ref:department`. Selection occurs once in `direct:classify-router`. Camel EIPs map fixed labels to fixed Kamelet endpoints.

The exact build includes the expert contract changes associated with [CAMEL-25382](https://issues.apache.org/jira/browse/CAMEL-25382). This implementation uses the APIs in that build. It does not assume that every illustrative proposal in the ticket is available. See the current [semantic language documentation](https://camel.apache.org/components/next/languages/semantic-language.html), [evaluation design](https://camel.apache.org/blog/2026/09/semantic-evaluation-system-one/), and [routing discussion](https://camel.apache.org/blog/2026/10/semantic-agent-routing/).

## Artifact and publication

The ZIP retains Barn's existing `index.properties` structure:

```properties
catalog.name=semantic-<definition-id>-<revision>
catalog.description=<definition-description>
catalog.services=service
catalog.routes.service=service/router.camel.yaml
catalog.dependencies.service=service/dependencies.txt
catalog.properties.service=service/service.properties
```

The auxiliary manifest is `service/semantic-router.properties`:

```properties
contract.version=1
catalog.revision=<revision>
camel.version=4.23.0-SNAPSHOT
camel.build=20261009.103642
main=service/router.camel.yaml
preview.main=service/preview.camel.yaml
input.profile=message-to-string/v1
tool.name=<tool-name>
tool.tags=wsr-semantic-router
expert.bean=supportExpert
experts=service/experts.json
evaluation=department
kamelets=service/kamelets/wsr-billing-action.kamelet.yaml,service/kamelets/wsr-technical-action.kamelet.yaml
dependencies=service/dependencies.txt
configuration=service/service.properties
```

`contract.version` remains `1`. `camel.build` is an optional informational build hint for WSR; deployments own their dependency lock. It does not replace the revision and complete archive digest checks. New Barn publications include the schema build hint.

`service/experts.json` captures the classifier snapshot and optional guard snapshot/configuration. Guarded manifests also record `guard.expert.bean`, `guard.operation`, and `guard.rejectWhen`. Both expert dependencies appear in the dependency file. Snapshot changes participate in revision generation; historical resolution uses the archive, not current expert metadata. Existing archives without this resource retain legacy resolution behavior.

`kamelets` is a comma-separated list of exact relative file paths. It is not a directory. The catalog name and selected service have separate runtime settings: `wsr.catalog.name` and `wsr.catalog.service=service`.

Barn serializes YAML with a YAML serializer. It serializes properties with the Java properties serializer. Archive entry ordering and timestamps are fixed. Identical definitions, action resources, expert dependencies, and runtime pins produce identical ZIP bytes. A revision-specific catalog name prevents updates from replacing a prior publication. Generic catalog and data-store mutation endpoints reject immutable published artifacts.

The publication response provides a revision, complete ZIP SHA-256, main file, runtime pin, download URL, and deployment instructions. WSR must verify the external digest and expected revision before loading the catalog. Publishing does not start WSR or register a forward. The authoring API does not fabricate an active-runtime status. Read WSR readiness and loaded revision from its observed runtime endpoint.

Each new publication records the tool name and expert snapshot. Barn updates a separate current-publication record after the catalog and publication record are stored. Draft edits do not change that selection. Republishing an existing revision selects that revision again without rewriting its archive. Existing question-era revisions remain immutable. Publish the saved definition with the migrated generator to obtain a new evaluation-era revision, then restart WSR against that revision. The current-publication record contains JSON and uses the `semantic-current-publication` type. Generic mutation endpoints protect this record.

Removing a draft preserves its published catalogs. The existing catalog downloader returns `WanakuResponse<DataStore>` with a Base64 ZIP. No second download protocol is introduced.

## Authoring API

All responses use `WanakuResponse<T>`. The base path is `/api/v1/semantic-routers`.

| Method and path | Operation |
|---|---|
| `GET /actions` | List eligible actions and native schemas |
| `GET /experts` | List managed expert metadata |
| `POST /experts` | Create expert metadata |
| `GET /experts/{id}` | Read expert metadata |
| `PUT /experts/{id}` | Replace expert metadata |
| `DELETE /experts/{id}` | Remove unreferenced expert metadata |
| `GET /resolve?name=<name>` | Resolve the current published revision by exact saved name |
| `GET /resolve?name=<name>&revision=<revision>` | Resolve one stored fixed revision |
| `GET /` | List drafts |
| `POST /` | Save a new draft |
| `GET /{id}` | Read a draft |
| `PUT /{id}` | Update a draft |
| `DELETE /{id}` | Remove a draft |
| `POST /validate` | Validate a definition without inference |
| `POST /files` | Generate read-only file previews without storage or inference |
| `POST /{id}/preview` | Classify an example without action dispatch |
| `POST /{id}/publish` | Publish an immutable revision |
| `GET /{id}/revisions` | List that draft's published revisions |

Draft saves permit incomplete business fields. They still enforce storage bounds and credential-reference rules. Validation returns `{valid, errors: [{field, message}]}`. Preview and publication require a complete valid definition. Malformed names produce HTTP 400 on draft saves and file previews. Incomplete or otherwise invalid definitions produce HTTP 422. A missing definition produces HTTP 404.

## Resolve a publication for WSR

The resolver requires one exact saved definition name. It returns HTTP 409 when several definitions have that name. It returns HTTP 404 when the definition or a published revision is missing. An optional `revision` query parameter selects a stored revision. Without it, the resolver uses the current-publication record. A legacy definition with one publication can use that publication. A legacy definition with several publications and no current-publication record returns HTTP 409. Publish the saved draft again or specify a revision to select a publication.

The response uses `WanakuResponse<SemanticResolvedPublication>`. It contains `name`, `toolName`, `catalogName`, `service`, `revision`, `sha256`, `mainFile`, `camelVersion`, `camelBuild`, `downloadUrl`, and `expert`. Barn verifies these values against the persisted archive. The expert contains its published identifier, name, bean, dependency, and confidence support. It contains no implementation class or credential. The optional `guard` contains its published expert snapshot, operation, literal parameters and rejecting verdict. The resolver uses the published expert snapshot instead of the edited draft. Legacy expert recovery uses the verified archive and the configured expert catalog. If that recovery cannot identify one expert, `expert` is `null`. WSR then requires explicit expert configuration.

Start the default expert runtime with `runtime --semantic-route support-route`. Supply `TYPESAFE_API_KEY` in the process environment. Kubernetes can supply the route and service addresses with `WSR_*` environment settings. See [Run Wanaku Semantic Router](https://wanaku.ai/docs/semantic-router/next/operate/). WSR resolves one publication at startup. It verifies the fixed archive pins before startup. It keeps that publication until restart.

## Classification-only preview

Set `wanaku.semantic.preview-url` to the dedicated WSR preview endpoint. Set `wanaku.semantic.preview-token` if that deployment requires a bearer token. The URL and token are administrator configuration. They are not supplied by the caller or saved in a definition.

When enabled, Barn first sends the guard bean, operation, parameters and example state through the same preview API, requiring a native Boolean result. A rejecting guard returns `blocked: true`, `guard: {value, diagnostics}`, no label and no classification error. Guard failures return a sanitized error and skip classification. Accepted guards retain their verdict while classification continues. One Barn concurrency permit and deadline cover both evaluations. Saved examples can use `expectedBlocked: true` instead of an expected label.

For classification, Barn sends only `expertBean`, `operation: "choice"`, `parameters` containing `instructions` and `criteria`, and `state` containing the example message. It does not send action endpoints, Kamelet resources, action configuration, or executable YAML. WSR creates an isolated Camel context with only an evaluation declaration and classification route using the same choice operation and parameters as production. It does not insert routes into a production context.

WSR must return `resultType: "choice"` and a textual `value` equal to a configured action label or `no_match`. Barn rejects other result types, unknown labels, and malformed values.

Preview returns `label`, `noMatch`, `durationMillis`, `error`, and `diagnostics`. Provider failure and malformed labels remain errors. Confidence is never invented. Diagnostics include only available finite confidence or per-label probabilities in the range zero through one. Provider text, credentials, and unknown metadata are excluded.

Barn defaults to a 15-second deadline and four concurrent evaluations. Configure `wanaku.semantic.preview-timeout-seconds` in the range 1 through 120. Configure `wanaku.semantic.preview-max-concurrency` in the range 1 through 32. The complete response has a 64 KiB limit. WSR also bounds its context count and provider evaluation duration.

## Evaluation and CI

Routine tests use deterministic expert or local provider fixtures. They prove routing, MCP, packaging, and error behavior. They do not prove model accuracy. CI requires no paid provider and invokes no external business action.

For a separate model evaluation, select representative saved examples. Configure a reviewed provider and model in the deployment. Run classification preview for each example. Compare `expectedLabel` with the returned label. Record the provider/model revision, labels, errors, and durations. Do not execute actions during this evaluation. Do not interpret absent confidence as zero confidence.
