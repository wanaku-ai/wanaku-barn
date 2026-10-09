# Two-action support reference

This reference selects billing support, technical support, or `no_match`. The bundled native Kamelets return strings and perform no backend operations.

Build Barn:

```shell
mvn verify -DskipUI
```

Start Barn with an isolated local data directory:

```shell
java -Dwanaku.home=/tmp/barn-semantic-reference \
  -Dquarkus.http.port=8180 \
  -jar apps/wanaku-barn-backend/target/quarkus-app/quarkus-run.jar
```

Create a definition:

```shell
curl --fail-with-body -H 'Content-Type: application/json' \
  --data-binary @examples/semantic-router/reference-definition.json \
  http://127.0.0.1:8180/api/v1/semantic-routers
```

Read the returned `data.id`. Publish that definition with `POST /api/v1/semantic-routers/{id}/publish`. Download the returned catalog name through `GET /api/v1/service-catalog/download?name={catalogName}`. Decode `data.data` as a Base64 ZIP.

Set `TYPESAFE_API_KEY` in the WSR process environment. Start WSR from its checkout with the saved route name:

```shell
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --semantic-route support-route
```

Replace `support-route` with the name in your saved definition. The local defaults use Barn at `http://localhost:8180` and Wanaku management at `http://localhost:8080`. WSR resolves the current published catalog and its expert snapshot. It verifies the fixed revision and SHA-256 before startup. It checks MCP readiness before registration with Wanaku. It keeps the publication until restart. Set `TYPESAFE_MODEL` to override the component's default model. Kubernetes can supply the route and service addresses through `WSR_*` environment settings. Use the [WSR deployment guide](https://github.com/wanaku-ai/wanaku-semantic-router/blob/ci-issue-182/docs/deployment.md) for the manifest and explicit catalog options. Use the WSR dependency lock for the exact tested Camel build.

Use the dedicated WSR preview service for classification tests. Set Barn's `wanaku.semantic.preview-url` to its `/api/v1/preview` endpoint. Send `{"message":"I have a question about my invoice."}` to Barn's `POST /api/v1/semantic-routers/{id}/preview`. The preview must return `billing` without executing a Kamelet.

The reference covers both branches, explicit no-match, provider failure, and malformed output through deterministic native Camel fixtures in WSR. The cross-repository acceptance test publishes through Barn, downloads and verifies the ZIP in WSR, initializes MCP, invokes the tool through Wanaku, and checks policy denial before dispatch.

See [the contract](../../docs/semantic-router-contract.md) and [the wizard guide](../../docs/semantic-routing-wizard.md).
