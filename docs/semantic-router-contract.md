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

An expert GAV identifies its implementation dependency. A named Camel bean identifies its configured instance. These identifiers have different purposes.

The default authoring entry has ID `support`, bean `supportExpert`, and dependency `org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT`. WSR must configure that bean before startup. The deployment owns the provider endpoint, credentials, model, timeout, and concurrency settings. Barn does not store or return those values.

Administrators can set `wanaku.semantic.experts-file` to a JSON array of expert entries:

```json
[
  {
    "id": "support",
    "name": "Support expert",
    "bean": "supportExpert",
    "dependency": "org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT",
    "supportsConfidence": false
  }
]
```

Unknown expert fields are rejected. The initial choice contract does not expose confidence thresholds. Preview can return confidence or per-label probabilities only when the native expert supplies them.

## Native Camel build

The tested runtime uses Camel `4.23.0-SNAPSHOT`, timestamp `20261006.103638`. WSR records fixed timestamped coordinates and JAR checksums for all Camel components. Use its dependency lock and checksum verification to reproduce that build. A moving snapshot coordinate alone is not a deployment pin.

Barn's ordinary catalog validator remains on Camel `4.22.1`. Semantic catalogs use a separately bundled YAML DSL schema from `camel-yaml-dsl-4.23.0-20261006.103638-33.jar`. This avoids changing unrelated Barn Camel dependencies. The schema is Apache Camel material licensed under Apache License 2.0.

The pinned build supports native `semantic.question`, `type: choice`, `state`, `instructions`, `criteria`, and `expert`. The expert value names a Camel bean. Its language expression is `ref:department`. Selection occurs once in `direct:classify-router`. Camel EIPs map fixed labels to fixed Kamelet endpoints.

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
camel.build=20261006.103638
main=service/router.camel.yaml
preview.main=service/preview.camel.yaml
input.profile=message-to-string/v1
tool.name=<tool-name>
tool.tags=wsr-semantic-router
expert.bean=supportExpert
question=department
kamelets=service/kamelets/wsr-billing-action.kamelet.yaml,service/kamelets/wsr-technical-action.kamelet.yaml
dependencies=service/dependencies.txt
configuration=service/service.properties
```

`kamelets` is a comma-separated list of exact relative file paths. It is not a directory. The catalog name and selected service have separate runtime settings: `wsr.catalog.name` and `wsr.catalog.service=service`.

Barn serializes YAML with a YAML serializer. It serializes properties with the Java properties serializer. Archive entry ordering and timestamps are fixed. Identical definitions, action resources, expert dependencies, and runtime pins produce identical ZIP bytes. A revision-specific catalog name prevents updates from replacing a prior publication. Generic catalog and data-store mutation endpoints reject immutable published artifacts.

The publication response provides a revision, complete ZIP SHA-256, main file, runtime pin, download URL, and deployment instructions. WSR must verify the external digest and expected revision before loading the catalog. Publishing does not start WSR or register a forward. The authoring API does not fabricate an active-runtime status. Read WSR readiness and loaded revision from its observed runtime endpoint.

Each new publication records the tool name and expert snapshot. Barn updates a separate current-publication record after the catalog and publication record are stored. Draft edits do not change that selection. Republishing an existing revision selects that revision again. The current-publication record contains JSON and uses the `semantic-current-publication` type. Generic mutation endpoints protect this record.

Removing a draft preserves its published catalogs. The existing catalog downloader returns `WanakuResponse<DataStore>` with a Base64 ZIP. No second download protocol is introduced.

## Authoring API

All responses use `WanakuResponse<T>`. The base path is `/api/v1/semantic-routers`.

| Method and path | Operation |
|---|---|
| `GET /actions` | List eligible actions and native schemas |
| `GET /experts` | List configured expert identifiers |
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

The response uses `WanakuResponse<SemanticResolvedPublication>`. It contains `name`, `toolName`, `catalogName`, `service`, `revision`, `sha256`, `mainFile`, `camelVersion`, `camelBuild`, `downloadUrl`, and `expert`. Barn verifies these values against the persisted archive. The expert contains its published identifier, name, bean, dependency, and confidence support. It contains no implementation class or credential. The resolver uses the published expert snapshot instead of the edited draft. Legacy expert recovery uses the verified archive and the configured expert catalog. If that recovery cannot identify one expert, `expert` is `null`. WSR then requires explicit expert configuration.

Start the default expert runtime with `runtime --semantic-route support-route`. Supply `TYPESAFE_API_KEY` in the process environment. Kubernetes can supply the route and service addresses with `WSR_*` environment settings. See the [WSR deployment guide](https://github.com/wanaku-ai/wanaku-semantic-router/blob/ci-issue-182/docs/deployment.md). WSR resolves one publication at startup. It verifies the fixed archive pins before startup. It keeps that publication until restart.

## Classification-only preview

Set `wanaku.semantic.preview-url` to the dedicated WSR preview endpoint. Set `wanaku.semantic.preview-token` if that deployment requires a bearer token. The URL and token are administrator configuration. They are not supplied by the caller or saved in a definition.

Barn sends only `input`, `instructions`, `criteria`, `expertBean`, and `message`. It does not send action endpoints, Kamelet resources, action configuration, or executable YAML. WSR creates an isolated Camel context with only a semantic declaration and classification route. It does not insert routes into a production context.

Preview returns `label`, `noMatch`, `durationMillis`, `error`, and `diagnostics`. Provider failure and malformed labels remain errors. Confidence is never invented. Diagnostics include only available finite confidence or per-label probabilities in the range zero through one. Provider text, credentials, and unknown metadata are excluded.

Barn defaults to a 15-second deadline and four concurrent evaluations. Configure `wanaku.semantic.preview-timeout-seconds` in the range 1 through 120. Configure `wanaku.semantic.preview-max-concurrency` in the range 1 through 32. The complete response has a 64 KiB limit. WSR also bounds its context count and provider evaluation duration.

## Evaluation and CI

Routine tests use deterministic expert or local provider fixtures. They prove routing, MCP, packaging, and error behavior. They do not prove model accuracy. CI requires no paid provider and invokes no external business action.

For a separate model evaluation, select representative saved examples. Configure a reviewed provider and model in the deployment. Run classification preview for each example. Compare `expectedLabel` with the returned label. Record the provider/model revision, labels, errors, and durations. Do not execute actions during this evaluation. Do not interpret absent confidence as zero confidence.
