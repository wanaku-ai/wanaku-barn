# Kamelet catalog

Barn stores native Kamelet definitions and serves them to Camel applications. The Kamelets page provides upload, search, download, and removal. The semantic routing wizard uses eligible actions and sinks from this catalog.

## Upload a Kamelet

1. Open **Kamelets**.
2. Select **Upload Kamelet**.
3. Select one `.kamelet.yaml` file.
4. Select **Upload**.

The file must use UTF-8. The file must not exceed 1 MiB. Barn reads the name from `metadata.name`. Barn validates the Kamelet before it stores the content. Validation does not start routes or create uploaded beans. Invalid uploads remain in the dialog with an error message.

The catalog accepts native source, sink, and action Kamelets. It preserves the uploaded YAML. The file must contain one Kamelet document with `apiVersion: camel.apache.org/v1`, `kind: Kamelet`, metadata, a parameter definition, and a route template. Duplicate YAML keys and multiple documents are not accepted. Use Camel's canonical processor names, such as `setBody` and `setHeader`.

Names must start with a lowercase letter. They must end with a lowercase letter or digit. Names can contain lowercase letters, digits, and hyphens. The maximum name length is 64 characters.

For string parameters, Barn validates numeric and boolean defaults as their string values. This accepts native Kamelets that declare `type: string` with an unquoted default such as `22`. String constraints still apply, and the uploaded YAML remains unchanged.

## Use the HTTP API

Upload YAML through JSON. The JSON transport supports the standalone UI and the Wanaku plugin host.

```bash
jq -Rs '{yaml: .}' new-action.kamelet.yaml | \
  curl --fail-with-body http://localhost:8180/api/v1/kamelets \
    -H 'Content-Type: application/json' \
    --data-binary @-
```

The upload response contains a `WanakuResponse` envelope. The `data` member contains the name, title, type, SHA-256 digest, semantic eligibility, source, and download URL. Names come from the YAML. No separate name parameter is required.

| Method and path | Result |
|---|---|
| `GET /api/v1/kamelets` | Current catalog entries |
| `POST /api/v1/kamelets` | Upload a JSON object with a `yaml` string |
| `GET /api/v1/kamelets/{name}` | Metadata and YAML in a JSON response |
| `GET /api/v1/kamelets/{name}.kamelet.yaml` | Raw YAML for Camel |
| `DELETE /api/v1/kamelets/{name}` | Remove the current uploaded entry |

Add `?sha256=<digest>` to a detail or raw download URL to select exact stored content. Raw downloads return YAML directly. They do not use the JSON response envelope. Missing entries return HTTP 404.

## Use Barn as a Camel repository

Set the Kamelet component location to the Barn catalog URL. Include the trailing slash.

```properties
camel.component.kamelet.location=http://localhost:8180/api/v1/kamelets/
```

A route that uses `kamelet:new-action` requests `new-action.kamelet.yaml` from that location. This follows [Camel's native Kamelet lookup](https://camel.apache.org/components/next/others/kamelet-custom.html). Supply the required Kamelet parameters in the route. Configure the required Camel component dependencies in the application.

Use the Barn API access configuration for the deployment. Camel's native resource loader does not use the Wanaku SDK HTTP client or its authentication headers. It must be able to read the raw YAML URL. Uploading or selecting a Kamelet does not start an application.

## Select semantic actions

Eligible uploads become available when a semantic router wizard opens. Barn does not require a restart. Other Kamelets remain available in the remote catalog. Their cards explain why they are not eligible for semantic routing.

Native sink Kamelets can receive the classified message without Barn annotations. A sink must consume from `kamelet:source`. If it declares an input schema, that schema must use `type: string`. A sink does not need an output schema. Barn returns `Routed to <label>.` after the sink completes successfully. A delivery failure returns an execution error. The sink description supplies the suggested selection criterion.

Native action Kamelets must declare string input and output schemas. Their `semantic-action` and `contract-profile` annotations are required. The optional `routing-description` annotation supplies a suggested selection criterion:

```yaml
barn.wanaku.ai/semantic-action: "true"
barn.wanaku.ai/routing-description: Requests this action handles.
barn.wanaku.ai/contract-profile: message-to-string/v1
```

An explicit `semantic-action: "false"` annotation excludes a sink. An explicit incompatible `contract-profile` also excludes it. Source Kamelets create new messages and cannot receive a semantic routing decision.

Configuration fields must use supported primitive types. Credential properties must use external environment references. Barn treats native `format: password`, the exact password descriptor `urn:alm:descriptor:com.tectonic.ui:password`, and `x-secret-reference: true` as credential fields. Enter `env:VARIABLE_NAME` for these fields. The deployment must supply that environment variable. Barn preserves the original Kamelet YAML.

Semantic destinations must be self-contained. References to other Kamelets are not supported for semantic publication. Native `kamelet:source` and `kamelet:sink` endpoints remain valid. Barn does not derive per-request headers or other arguments from a sink definition.

## Revisions and removal

Each upload has a SHA-256 digest. Identical YAML reuses the same stored revision. Different YAML with the same name creates another revision and changes the current catalog selection.

A saved semantic action selection contains its digest. The wizard displays the selected revision and its configuration schema. When a newer revision exists, select **Use current revision** to change it. This operation resets deployment configuration to the new defaults. The business label and criteria remain available.

Removal hides an uploaded entry from the current catalog. Retained revisions remain available for saved selections and exact downloads. Bundled and directory-configured entries are read-only. An upload cannot replace one of their names.

Published semantic catalogs contain their selected Kamelet files. Replacing or removing a remote entry does not change a published ZIP or a running WSR instance. A new publication requires an explicit publish operation. WSR continues to load Kamelets from its verified local catalog archive.

## Directory configuration

Existing `wanaku.semantic.actions-directory` configuration remains supported. Barn loads these files during startup. Use the upload API to add remote catalog entries without a restart. Review the [semantic router contract](semantic-router-contract.md) for the complete action schema and publication rules.
